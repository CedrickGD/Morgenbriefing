package com.cedrickgd.morgenbriefing.ui.settings

import android.app.Application
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cedrickgd.morgenbriefing.AppContainer
import com.cedrickgd.morgenbriefing.BuildConfig
import com.cedrickgd.morgenbriefing.MorgenbriefingApp
import com.cedrickgd.morgenbriefing.update.ApkInstaller
import com.cedrickgd.morgenbriefing.update.ApkVerifier
import com.cedrickgd.morgenbriefing.update.ReleaseInfo
import com.cedrickgd.morgenbriefing.update.UpdateCheckResult
import com.cedrickgd.morgenbriefing.update.UpdateError
import com.cedrickgd.morgenbriefing.update.UpdateException
import com.cedrickgd.morgenbriefing.update.UpdateNotifications
import com.cedrickgd.morgenbriefing.update.UpdateServices
import com.cedrickgd.morgenbriefing.util.attempt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What the "App-Update" card shows. */
@Immutable
sealed interface UpdatePhase {
    /** Nothing known beyond the last check time. */
    data object Idle : UpdatePhase

    data object Checking : UpdatePhase

    data object UpToDate : UpdatePhase

    data class Available(val release: ReleaseInfo) : UpdatePhase

    /** [progress] 0..1, or null while the size is unknown. */
    data class Downloading(val release: ReleaseInfo, val progress: Float?, val received: Long, val total: Long) :
        UpdatePhase

    data class Verifying(val release: ReleaseInfo) : UpdatePhase

    /** Verified; Android first needs the one-time "install unknown apps" permission. */
    data class NeedsPermission(val release: ReleaseInfo, val apk: File) : UpdatePhase

    /** Verified and handed to (or ready for) the system installer. */
    data class ReadyToInstall(val release: ReleaseInfo, val apk: File, val installerOpened: Boolean) : UpdatePhase

    /** [release] set: the download/installation failed (retry downloads); null: the check failed. */
    data class Failed(val error: UpdateError, val release: ReleaseInfo?) : UpdatePhase
}

@Immutable
data class UpdatePanelState(
    val installedVersion: String,
    /** Epoch millis of the last successful check. */
    val lastCheckAt: Long? = null,
    val phase: UpdatePhase = UpdatePhase.Idle,
) {
    /** Download/verification running: no second action may start. */
    val busy: Boolean
        get() = phase is UpdatePhase.Checking || phase is UpdatePhase.Downloading || phase is UpdatePhase.Verifying
}

/**
 * Drives the in-app updater on the settings screen: check → download (with progress) →
 * verify package + signature + version → system installer. Survives configuration changes, so
 * a running download keeps going when the screen rotates.
 */
class UpdateViewModel(
    private val app: Application,
    private val container: AppContainer,
    private val services: UpdateServices,
    installedVersionName: String,
) : ViewModel() {

    private val _state = MutableStateFlow(UpdatePanelState(installedVersion = installedVersionName))
    val state: StateFlow<UpdatePanelState> = _state.asStateFlow()

    private var job: Job? = null

    /** Whether the settings screen is in the foreground (only then the installer opens itself). */
    private var resumed = false

    init {
        refreshFromStore()
    }

    /** Screen resumed: pick up what the background check found and continue a pending install. */
    fun onResume() {
        resumed = true
        val phase = _state.value.phase
        if (phase is UpdatePhase.NeedsPermission && ApkInstaller.canRequestInstalls(app)) {
            install(phase.release, phase.apk)
        } else {
            refreshFromStore()
        }
    }

    fun onPause() {
        resumed = false
    }

    /** "Nach Updates suchen" */
    fun check() {
        if (_state.value.busy) return
        job = viewModelScope.launch {
            setPhase(UpdatePhase.Checking)
            val token = readToken() ?: return@launch setPhase(UpdatePhase.Failed(UpdateError.NO_TOKEN, null))
            val result = services.checker.check(token)
            attempt { withContext(Dispatchers.IO) { services.store.record(result) } }
                .onFailure { Log.w(TAG, "Could not remember the check result", it) }
            val lastCheck = readLastCheck()
            _state.update {
                it.copy(
                    lastCheckAt = lastCheck ?: it.lastCheckAt,
                    phase = when (result) {
                        is UpdateCheckResult.Available -> UpdatePhase.Available(result.release)
                        is UpdateCheckResult.UpToDate -> UpdatePhase.UpToDate
                        is UpdateCheckResult.Failed -> UpdatePhase.Failed(result.error, null)
                    },
                )
            }
        }
    }

    /** "Herunterladen & installieren" */
    fun downloadAndInstall(release: ReleaseInfo) {
        if (_state.value.busy) return
        job = viewModelScope.launch {
            setPhase(UpdatePhase.Downloading(release, progress = null, received = 0, total = release.apk.size))
            val token = readToken() ?: return@launch setPhase(UpdatePhase.Failed(UpdateError.NO_TOKEN, release))
            val download = attempt {
                services.downloader.download(release, token) { progress ->
                    _state.update { current ->
                        if (current.phase is UpdatePhase.Downloading) {
                            current.copy(
                                phase = UpdatePhase.Downloading(release, progress.fraction, progress.received, progress.total),
                            )
                        } else {
                            current
                        }
                    }
                }
            }
            val apk = download.getOrElse { e ->
                val error = (e as? UpdateException)?.error ?: UpdateError.INCOMPLETE_DOWNLOAD
                Log.w(TAG, "Download failed: $error", e)
                return@launch setPhase(UpdatePhase.Failed(error, release))
            }

            setPhase(UpdatePhase.Verifying(release))
            val problem = attempt { withContext(Dispatchers.IO) { ApkVerifier.verify(app, apk) } }
                .getOrElse { e ->
                    Log.w(TAG, "Verification crashed", e)
                    UpdateError.APK_UNREADABLE
                }
            if (problem != null) {
                Log.w(TAG, "Refusing ${release.tag}: $problem")
                withContext(Dispatchers.IO) { apk.delete() }
                return@launch setPhase(UpdatePhase.Failed(problem, release))
            }
            UpdateNotifications.cancel(app)
            if (resumed) {
                install(release, apk)
            } else {
                setPhase(UpdatePhase.ReadyToInstall(release, apk, installerOpened = false))
            }
        }
    }

    /** Stops a running check or download. */
    fun cancel() {
        val phase = _state.value.phase
        job?.cancel()
        job = null
        setPhase(
            when (phase) {
                is UpdatePhase.Downloading -> UpdatePhase.Available(phase.release)
                is UpdatePhase.Verifying -> UpdatePhase.Available(phase.release)
                else -> UpdatePhase.Idle
            },
        )
        if (phase !is UpdatePhase.Downloading && phase !is UpdatePhase.Verifying) refreshFromStore()
    }

    /** Opens the system installer for an already verified APK (or asks for the permission). */
    fun install(release: ReleaseInfo, apk: File) {
        if (!apk.isFile) {
            // The cache was cleared in the meantime: fetch it again.
            setPhase(UpdatePhase.Available(release))
            downloadAndInstall(release)
            return
        }
        when (val launch = ApkInstaller.install(app, apk)) {
            ApkInstaller.Launch.Started -> setPhase(UpdatePhase.ReadyToInstall(release, apk, installerOpened = true))
            ApkInstaller.Launch.NeedsPermission -> setPhase(UpdatePhase.NeedsPermission(release, apk))
            is ApkInstaller.Launch.Failed -> setPhase(UpdatePhase.Failed(launch.error, release))
        }
    }

    /** "Erlauben": the system screen for "install unknown apps"; [onResume] continues. */
    fun openInstallPermission(): Boolean = ApkInstaller.openPermissionSettings(app)

    /** Back to the plain state after an error. */
    fun dismissError() {
        val phase = _state.value.phase
        if (phase is UpdatePhase.Failed) {
            setPhase(phase.release?.let { UpdatePhase.Available(it) } ?: UpdatePhase.Idle)
            refreshFromStore()
        }
    }

    /** Shows a release the background worker found; never overrides a running action. */
    private fun refreshFromStore() {
        viewModelScope.launch {
            val (lastCheck, pending) = attempt {
                withContext(Dispatchers.IO) {
                    services.store.lastCheckAt() to services.store.pendingRelease(services.installed)
                }
            }.getOrElse {
                Log.w(TAG, "Update store unreadable", it)
                null to null
            }
            _state.update { current ->
                val phase = when (current.phase) {
                    UpdatePhase.Idle, UpdatePhase.UpToDate -> pending?.let { UpdatePhase.Available(it) } ?: current.phase
                    else -> current.phase
                }
                current.copy(lastCheckAt = lastCheck ?: current.lastCheckAt, phase = phase)
            }
        }
    }

    private suspend fun readToken(): String? =
        attempt { container.settings.token() }
            .onFailure { Log.w(TAG, "Token unreadable", it) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

    private suspend fun readLastCheck(): Long? =
        attempt { withContext(Dispatchers.IO) { services.store.lastCheckAt() } }.getOrNull()

    private fun setPhase(phase: UpdatePhase) {
        _state.update { it.copy(phase = phase) }
    }

    companion object {
        private const val TAG = "UpdateViewModel"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as MorgenbriefingApp
                UpdateViewModel(
                    app = app,
                    container = app.container,
                    services = app.updates,
                    installedVersionName = BuildConfig.VERSION_NAME,
                )
            }
        }
    }
}
