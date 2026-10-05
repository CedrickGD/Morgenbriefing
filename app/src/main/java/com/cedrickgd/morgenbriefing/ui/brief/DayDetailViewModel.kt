package com.cedrickgd.morgenbriefing.ui.brief

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
import com.cedrickgd.morgenbriefing.data.model.Brief
import com.cedrickgd.morgenbriefing.util.attempt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZonedDateTime

/** State of one stored morning. */
@Immutable
data class DayDetailUiState(
    val loading: Boolean = true,
    val brief: Brief? = null,
    val weather: WeatherUi? = null,
    /** Loading threw (e.g. storage error) — offer a retry. */
    val failed: Boolean = false,
    /** Used to render relative publish times ("vor 3 Std.") as seen from now. */
    val now: ZonedDateTime = ZonedDateTime.now(BERLIN),
) {
    val isEmpty: Boolean get() = !loading && brief == null && weather == null
}

/**
 * Loads one day: cached brief + morning weather snapshot, fetching the brief from GitHub when
 * it is not cached yet (see [com.cedrickgd.morgenbriefing.data.BriefRepository.day]).
 *
 * @param date null when the route carried a malformed date; the screen then shows a hint.
 */
class DayDetailViewModel(
    private val container: AppContainer,
    val date: LocalDate?,
) : ViewModel() {

    private val _state = MutableStateFlow(DayDetailUiState(loading = date != null))
    val state: StateFlow<DayDetailUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        reload()
    }

    fun reload() {
        val day = date ?: return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, failed = false) }
            val bundle = attempt { container.briefs.day(day) }
                .onFailure { Log.w(TAG, "Could not load $day", it) }
                .getOrNull()
            val weather = bundle?.weather?.let { withContext(Dispatchers.Default) { it.toWeatherUi() } }
            ensureActive()
            _state.value = DayDetailUiState(
                loading = false,
                brief = bundle?.brief,
                weather = weather,
                failed = bundle == null,
                now = ZonedDateTime.now(BERLIN),
            )
        }
    }

    companion object {
        private const val TAG = "DayDetailViewModel"

        fun factory(date: LocalDate?): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as MorgenbriefingApp
                DayDetailViewModel(app.container, date)
            }
        }
    }
}
