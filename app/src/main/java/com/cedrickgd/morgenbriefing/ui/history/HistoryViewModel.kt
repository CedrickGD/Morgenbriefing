package com.cedrickgd.morgenbriefing.ui.history

import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cedrickgd.morgenbriefing.AppContainer
import com.cedrickgd.morgenbriefing.MorgenbriefingApp
import com.cedrickgd.morgenbriefing.data.BERLIN
import com.cedrickgd.morgenbriefing.util.AppSignals
import com.cedrickgd.morgenbriefing.util.attempt
import com.cedrickgd.morgenbriefing.util.isWithin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime

@Immutable
data class HistoryUiState(
    /** Null until the first load finished. */
    val groups: List<MonthGroup>? = null,
    val refreshing: Boolean = false,
    /** The last load threw; [groups] keeps what was shown before. */
    val failed: Boolean = false,
    /** For the "Heute" pill. */
    val today: LocalDate,
)

/** History tab: local days merged with the remote index (see BriefRepository.history). */
class HistoryViewModel(
    private val container: AppContainer,
    private val clock: () -> ZonedDateTime = { ZonedDateTime.now(BERLIN) },
) : ViewModel() {

    private val _state = MutableStateFlow(HistoryUiState(today = clock().toLocalDate()))
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var lastLoad: Instant? = null

    init {
        load(userInitiated = false)
        // A new token may unlock the remote index (or removing it hides remote-only days).
        viewModelScope.launch {
            AppSignals.briefSourceChanged.collect { load(userInitiated = false, force = true) }
        }
    }

    /** Pull-to-refresh. */
    fun refresh() = load(userInitiated = true)

    /** Reload when the list may be outdated (new morning, or a while since the last load). */
    fun onResume() {
        val now = clock()
        val dayChanged = now.toLocalDate() != _state.value.today
        if (dayChanged || !isWithin(lastLoad, now.toInstant(), RELOAD_AFTER)) load(userInitiated = false)
    }

    /**
     * Loads the history. A running load is reused (it clears `refreshing` when done) unless
     * [force] — then it is replaced, because its result may already be outdated.
     */
    private fun load(userInitiated: Boolean, force: Boolean = false) {
        if (userInitiated) _state.update { it.copy(refreshing = true) }
        loadJob?.let { running ->
            if (running.isActive) {
                if (!force) return
                running.cancel()
            }
        }
        lastLoad = clock().toInstant()
        loadJob = viewModelScope.launch {
            val result = attempt { container.briefs.history() }
            val groups = result.getOrNull()?.let { withContext(Dispatchers.Default) { groupByMonth(it) } }
            ensureActive()
            if (groups == null) Log.w(TAG, "History could not be loaded", result.exceptionOrNull())
            _state.update { current ->
                current.copy(
                    groups = groups ?: current.groups ?: emptyList(),
                    refreshing = false,
                    failed = groups == null,
                    today = clock().toLocalDate(),
                )
            }
        }
    }

    companion object {
        private const val TAG = "HistoryViewModel"
        private val RELOAD_AFTER: Duration = Duration.ofMinutes(10)

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as MorgenbriefingApp
                HistoryViewModel(app.container)
            }
        }
    }
}
