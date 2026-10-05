package com.cedrickgd.morgenbriefing.ui.settings.email

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cedrickgd.morgenbriefing.MorgenbriefingApp
import com.cedrickgd.morgenbriefing.data.EmailSettingsRepository
import com.cedrickgd.morgenbriefing.data.EmailSettingsState
import com.cedrickgd.morgenbriefing.data.RemoveRecipientResult
import com.cedrickgd.morgenbriefing.data.model.RecipientCheck
import com.cedrickgd.morgenbriefing.util.AppSignals
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * "E-Mail-Briefing" in the settings. The state lives in the process-wide
 * [EmailSettingsRepository] (so a pending save survives leaving the page); this ViewModel loads
 * it, reloads after the GitHub token changed and forwards the user's actions.
 */
class EmailSettingsViewModel(private val repository: EmailSettingsRepository) : ViewModel() {

    val state: StateFlow<EmailSettingsState> = repository.state

    init {
        repository.load()
        viewModelScope.launch {
            // A new or removed token changes what we may read and write.
            AppSignals.briefSourceChanged.collect { repository.load(force = true) }
        }
    }

    /** The section became visible again: refresh unless the last read is very recent. */
    fun onResume() = repository.load()

    fun reload() = repository.load(force = true)

    fun setEnabled(enabled: Boolean) = repository.setEnabled(enabled)

    fun addRecipient(raw: String): RecipientCheck = repository.addRecipient(raw)

    fun removeRecipient(address: String): RemoveRecipientResult = repository.removeRecipient(address)

    fun dismissError() = repository.dismissError()

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as MorgenbriefingApp
                EmailSettingsViewModel(app.container.emailSettings)
            }
        }
    }
}
