package com.cedrickgd.morgenbriefing.ui.settings

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import com.cedrickgd.morgenbriefing.ai.AiServices
import com.cedrickgd.morgenbriefing.ai.KeyCheckOutcome
import com.cedrickgd.morgenbriefing.ai.KeyExtras
import com.cedrickgd.morgenbriefing.ai.compat.CustomServer
import com.cedrickgd.morgenbriefing.ai.provider.ProviderCatalog
import com.cedrickgd.morgenbriefing.data.model.AiProviderId
import com.cedrickgd.morgenbriefing.data.model.AiSettings
import com.cedrickgd.morgenbriefing.data.model.GeminiTier
import com.cedrickgd.morgenbriefing.data.model.OpenRouterTier
import com.cedrickgd.morgenbriefing.data.model.cleanApiKey
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
 * What the key dialog hands over: the typed key ([key] blank = keep the stored one, or none for
 * the own server) and what is stored with it. Never logged: [toString] leaves the key out.
 *
 * @property skipTest "Ohne Test speichern" (offline): stored, marked as untested.
 * @property extras the Anthropic workspace id and the own server's address as typed.
 * @property geminiTier the chosen "Tarif" of a Gemini key; null for every other provider.
 * @property openRouterTier the chosen "Tarif" of an OpenRouter key (the key is tested for it); null
 *   for every other provider.
 * @property selectProvider make the provider the chat provider once its key is stored (OpenRouter
 *   set up as the way out of a refused Gemini key).
 */
@Immutable
internal class KeySubmission(
    val key: String,
    val skipTest: Boolean = false,
    val extras: KeyExtras = KeyExtras(),
    val geminiTier: GeminiTier? = null,
    val openRouterTier: OpenRouterTier? = null,
    val selectProvider: Boolean = false,
) {
    override fun toString(): String =
        "KeySubmission(skipTest=$skipTest, tier=${geminiTier ?: openRouterTier}, select=$selectProvider)"
}

/**
 * "Speichern & testen" of an AI key, for every provider: one key or connection check through the
 * AI facade with the candidate configuration, and only what works is stored (offline, "Ohne Test
 * speichern" stores it marked as untested). Shared by the settings page and the in-place key
 * dialog that "Frag nach" and "⚡ Live" open where the user is ([InlineAiKeyDialog]).
 *
 * @param onServerModels receives the model ids the own server listed in a passed connection test.
 */
internal class AiKeyEditor(
    private val scope: CoroutineScope,
    private val container: AppContainer,
    private val ai: () -> AiServices,
    private val onServerModels: (List<String>) -> Unit = {},
) {
    private val _state = MutableStateFlow<AiKeyState>(AiKeyState.Idle)
    val state: StateFlow<AiKeyState> = _state.asStateFlow()

    private var job: Job? = null

    /**
     * Tests [submission] for [provider] and stores the key, the workspace id, the server address
     * and the Gemini or OpenRouter tariff when it works. A blank key re-tests the stored one (a
     * changed workspace id or tariff); the own server may have no key at all. A key that works but
     * has no credit is stored with a warning. A freshly connected own server without a chosen
     * model gets the first model it lists, so the chat can start right away. With
     * [KeySubmission.selectProvider] the provider answers the chat from then on.
     */
    fun saveAndTest(provider: AiProviderId, submission: KeySubmission) {
        // Spaces, quotes and invisible characters a copy brought along are not part of the key.
        val key = cleanApiKey(submission.key)
        job?.cancel()
        job = scope.launch {
            val stored = attempt { container.settings.current().ai }.getOrNull() ?: AiSettings()
            if (key.isEmpty() && !provider.keyOptional && !stored.of(provider).hasKey) return@launch
            _state.value = AiKeyState.Checking
            var outcome: KeyCheckOutcome? = null
            if (!submission.skipTest) {
                outcome = try {
                    val extras = submission.openRouterTier?.let { submission.extras.copy(openRouterTier = it) } ?: submission.extras
                    ai().testKey(provider, key.ifEmpty { null }, extras)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // Anything unexpected from the facade. Only its type is logged: a message
                    // could quote the key (OkHttp names a header value it refuses).
                    Log.w(TAG, "Key test failed: ${e.javaClass.name}")
                    null
                }
                ensureActive()
                val problem = outcome?.check?.toProblem() ?: if (outcome == null) AiKeyProblem.UNKNOWN else null
                if (problem != null) {
                    _state.value = AiKeyState.Failed(problem, outcome?.diagnostic)
                    return@launch
                }
            }
            val models = outcome?.serverModels.orEmpty()
            val saved = attempt { store(provider, key, submission, stored, models, outcome) }
            // Also after a partial write: a new tariff or key must not keep what the old one taught.
            ai().providerSetupSaved(provider)
            if (saved.isFailure) {
                Log.w(TAG, "Key could not be stored", saved.exceptionOrNull())
                _state.value = AiKeyState.Failed(AiKeyProblem.SAVE_FAILED)
                return@launch
            }
            if (provider == AiProviderId.CUSTOM && outcome != null) onServerModels(models)
            _state.value = when {
                provider == AiProviderId.CUSTOM && outcome != null -> AiKeyState.ServerConnected(models.size)
                else -> savedKeyState(outcome)
            }
        }
    }

    private suspend fun store(
        provider: AiProviderId,
        key: String,
        submission: KeySubmission,
        stored: AiSettings,
        models: List<String>,
        outcome: KeyCheckOutcome?,
    ) {
        val settings = container.settings
        if (provider == AiProviderId.ANTHROPIC) submission.extras.workspaceId?.let { settings.setAnthropicWorkspaceId(it) }
        if (provider == AiProviderId.CUSTOM) {
            submission.extras.baseUrl?.let { typed -> settings.setCustomBaseUrl(CustomServer.normalize(typed) ?: typed) }
        }
        if (provider == AiProviderId.GEMINI) submission.geminiTier?.let { settings.setGeminiTier(it) }
        if (provider == AiProviderId.OPENROUTER) submission.openRouterTier?.let { settings.setOpenRouterTier(it) }
        when {
            key.isNotEmpty() -> settings.setAiKey(provider, key, tested = !submission.skipTest)
            // The stored key passed again (with a new workspace id or tariff).
            !submission.skipTest && stored.of(provider).hasKey -> settings.markAiKeyWorks(provider)
        }
        // A passed Gemini test decides the model for this key: the chosen one, or the fallback
        // that answered instead (GE §12.5). Saving the key above dropped the previous one.
        if (provider == AiProviderId.GEMINI && outcome != null && outcome.check.toProblem() == null) {
            settings.setModelFallback(provider, outcome.fallback)
        }
        val first = models.firstOrNull()
        if (provider == AiProviderId.CUSTOM && first != null && stored.of(provider).model.isNullOrBlank()) {
            settings.update { it.copy(ai = it.ai.withModel(AiProviderId.CUSTOM, first)) }
        }
        if (submission.selectProvider) settings.update { it.copy(ai = it.ai.copy(provider = provider)) }
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

/** Backs [InlineAiKeyDialog]: the key editor plus the stored AI settings. */
internal class AiKeyViewModel(container: AppContainer, ai: () -> AiServices) : ViewModel() {
    private val editor = AiKeyEditor(viewModelScope, container, ai)
    val state: StateFlow<AiKeyState> = editor.state

    /** Null until the DataStore emitted. */
    val settings: StateFlow<AiSettings?> = container.settings.settings
        .map { it.ai }
        .distinctUntilChanged()
        .catch { e -> Log.w("AiKeyViewModel", "Settings unavailable", e) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun saveAndTest(provider: AiProviderId, submission: KeySubmission) = editor.saveAndTest(provider, submission)

    fun reset() = editor.reset()

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as MorgenbriefingApp
                AiKeyViewModel(app.container) { app.ai }
            }
        }
    }
}

/**
 * The key dialog of [provider] right where the user needs it (blocked chat, "⚡ Live"), so
 * entering a key never sends them hunting through the settings — and closing it returns them
 * exactly where they were. Removing a stored key stays in the settings. [openRouterTier]
 * preselects OpenRouter's tariff; [selectOnSave] makes [provider] the chat provider once its key
 * is stored. [onSetUpOpenRouter] is offered when Google refuses a Gemini key: the caller shows
 * OpenRouter's dialog instead (free tariff, chosen as chat provider once its key works).
 */
@Composable
fun InlineAiKeyDialog(
    provider: AiProviderId,
    onDismiss: () -> Unit,
    openRouterTier: OpenRouterTier? = null,
    selectOnSave: Boolean = false,
    onSetUpOpenRouter: (() -> Unit)? = null,
) {
    val viewModel: AiKeyViewModel = viewModel(factory = AiKeyViewModel.Factory)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val links = rememberLinkActions()
    // A frame later instead of a dialog that guesses what is stored.
    val ai = settings ?: return
    // Another provider is another dialog: nothing typed for one may show up in the other.
    key(provider) {
        AiKeyDialog(
            spec = ProviderCatalog.spec(provider),
            stored = ai.of(provider),
            state = state,
            onSave = { viewModel.saveAndTest(provider, it) },
            onOpenLink = links::open,
            onRemove = null,
            onDismiss = {
                viewModel.reset()
                onDismiss()
            },
            initialOpenRouterTier = openRouterTier,
            selectOnSave = selectOnSave,
            onSetUpOpenRouter = onSetUpOpenRouter?.let { setUp ->
                {
                    viewModel.reset()
                    setUp()
                }
            },
        )
    }
}
