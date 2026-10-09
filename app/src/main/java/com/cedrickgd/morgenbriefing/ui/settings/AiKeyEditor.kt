package com.cedrickgd.morgenbriefing.ui.settings

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cedrickgd.morgenbriefing.AppContainer
import com.cedrickgd.morgenbriefing.MorgenbriefingApp
import com.cedrickgd.morgenbriefing.ai.AiKeyCheck
import com.cedrickgd.morgenbriefing.ai.AiServices
import com.cedrickgd.morgenbriefing.ai.AiSupport
import com.cedrickgd.morgenbriefing.ai.KeyExtras
import com.cedrickgd.morgenbriefing.data.model.AiProviderId
import com.cedrickgd.morgenbriefing.util.attempt
import com.cedrickgd.morgenbriefing.util.rememberLinkActions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * "Speichern & testen" of an AI key: one key check through the AI facade, and only a key that
 * works is stored (offline, "Ohne Test speichern" stores it marked as untested). Shared by the
 * settings page and the in-place key dialog that "Frag nach" and "⚡ Live" open where the user
 * is ([InlineAiKeyDialog]).
 *
 * @param ai the AI facade, or null where AI cannot run (Android 7) — never touched then.
 */
internal class AiKeyEditor(
    private val scope: CoroutineScope,
    private val container: AppContainer,
    private val ai: (() -> AiServices)?,
) {
    private val _state = MutableStateFlow<AiKeyState>(AiKeyState.Idle)
    val state: StateFlow<AiKeyState> = _state.asStateFlow()

    private var job: Job? = null

    /**
     * Tests [raw] for [provider] (null = the active chat provider) together with [extras] (the
     * Anthropic workspace id, the own server's address) and stores all of it when it works; with
     * [skipTest] (offline) it is stored untested and the dialog and the settings say so instead
     * of claiming it works. A key that works but has no credit is stored with a warning.
     */
    fun saveAndTest(raw: String, skipTest: Boolean = false, provider: AiProviderId? = null, extras: KeyExtras = KeyExtras()) {
        val key = raw.trim()
        if (key.isEmpty()) return
        val services = ai
        if (services == null) {
            _state.value = AiKeyState.Failed(AiKeyProblem.UNSUPPORTED)
            return
        }
        job?.cancel()
        job = scope.launch {
            _state.value = AiKeyState.Checking
            val target = provider ?: attempt { container.settings.current().ai.provider }.getOrNull() ?: AiProviderId.ANTHROPIC
            var noCredit = false
            if (!skipTest) {
                val outcome = try {
                    services().testKey(target, key, extras).check
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // Anything unexpected from the facade (incl. a missing implementation).
                    Log.w(TAG, "Key test failed", e)
                    null
                }
                ensureActive()
                val problem = outcome?.toProblem() ?: if (outcome == null) AiKeyProblem.UNKNOWN else null
                if (problem != null) {
                    _state.value = AiKeyState.Failed(problem)
                    return@launch
                }
                noCredit = outcome == AiKeyCheck.OK_NO_CREDIT
            }
            val saved = attempt {
                extras.workspaceId?.let { container.settings.setAnthropicWorkspaceId(it) }
                extras.baseUrl?.let { container.settings.setCustomBaseUrl(it) }
                container.settings.setAiKey(target, key, tested = !skipTest)
            }
            _state.value = if (saved.isSuccess) {
                savedKeyState(tested = !skipTest, noCredit = noCredit)
            } else {
                Log.w(TAG, "Key could not be stored", saved.exceptionOrNull())
                AiKeyState.Failed(AiKeyProblem.SAVE_FAILED)
            }
        }
    }

    /** Back to idle (dialog closed or reopened); a running check is abandoned. */
    fun reset() {
        job?.cancel()
        _state.value = AiKeyState.Idle
    }

    private companion object {
        const val TAG = "AiKeyEditor"
    }
}

/** Backs [InlineAiKeyDialog]: the key editor plus whether a key is stored already. */
internal class AiKeyViewModel(container: AppContainer, ai: (() -> AiServices)?) : ViewModel() {
    private val editor = AiKeyEditor(viewModelScope, container, ai)
    val state: StateFlow<AiKeyState> = editor.state

    val hasKey: StateFlow<Boolean> = container.settings.settings
        .map { it.ai.of(it.ai.provider).hasKey }
        .distinctUntilChanged()
        .catch { e -> Log.w("AiKeyViewModel", "Settings unavailable", e) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun saveAndTest(raw: String, skipTest: Boolean) = editor.saveAndTest(raw, skipTest)

    fun reset() = editor.reset()

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as MorgenbriefingApp
                AiKeyViewModel(app.container, if (AiSupport.isSupported) ({ app.ai }) else null)
            }
        }
    }
}

/**
 * The Claude key dialog right where the user needs it (blocked chat, "⚡ Live"), so entering a
 * key never sends them hunting through the settings — and closing it returns them exactly where
 * they were. Removing a stored key stays in the settings.
 */
@Composable
fun InlineAiKeyDialog(onDismiss: () -> Unit) {
    val viewModel: AiKeyViewModel = viewModel(factory = AiKeyViewModel.Factory)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val hasKey by viewModel.hasKey.collectAsStateWithLifecycle()
    val links = rememberLinkActions()
    AiKeyDialog(
        hasKey = hasKey,
        state = state,
        onSaveAndTest = viewModel::saveAndTest,
        onOpenConsole = { links.open(ANTHROPIC_KEYS_URL) },
        onRemove = null,
        onDismiss = {
            viewModel.reset()
            onDismiss()
        },
    )
}
