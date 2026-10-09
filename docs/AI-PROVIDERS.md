# KI-Anbieter (v1.3.0): Design

Status: implemented on branch `ai-providers` (T1–T11); manual QA with real keys is still open (§11, §14). Target
release: **v1.3.0**. Written 2026-10-09.
Inputs: the current AI layer (`ai/**`, settings, `docs/AI.md`, `docs/DESIGN.md`) and the research specs
[`providers/openai.md`](providers/openai.md), [`providers/gemini.md`](providers/gemini.md),
[`providers/openai-compatible.md`](providers/openai-compatible.md) (all AS_OF 2026-10-09). Section
references like "OA §5" point into those specs (OA = openai.md, GE = gemini.md, OC = openai-compatible.md).

The code language is English (as in the code base); every user-facing string below is German and
final unless marked *(Vorschlag)*.

---

## 0. Kurzfassung (Deutsch)

**Was kommt:** Statt nur Claude kannst du in *Einstellungen → KI* einen von neun Anbietern wählen:
Claude (Anthropic), OpenAI, Google Gemini, xAI Grok, Perplexity, OpenRouter, Mistral, DeepSeek und
„Eigener Server“ (Ollama/LM Studio auf dem NAS). Jeder Anbieter hat einen eigenen, verschlüsselten
Key, eine eigene Modellwahl und eine eigene Key-Prüfung. Das Monatslimit gilt für alle zusammen.
Bestehende Nutzer merken nichts: der gespeicherte Claude-Key, das Modell und das Limit werden beim
ersten Start still übernommen.

| Anbieter | Chat „Frag nach“ | Websuche im Chat | ⚡ Live | Android 7 |
|---|---|---|---|---|
| Claude (Anthropic) | ✓ | ✓ | ✓ | ✗ (braucht Android 8) |
| OpenAI | ✓ | ✓ | ✓ | ✓ |
| Google Gemini | ✓ | ✓ (Google-Suche) | ✗ (Googles Nutzungsbedingungen) | ✓ |
| xAI Grok | ✓ | ✓ | ✓ | ✓ |
| Perplexity | ✓ | ✓ | ✓ | ✓ |
| OpenRouter | ✓ | ✓ | ✓ | ✓ |
| Mistral | ✓ | ✗ (in 1.3 ohne) | ✗ | ✓ |
| DeepSeek | ✓ | ✗ | ✗ | ✓ |
| Eigener Server | ✓ | ✗ | ✗ | ✓ |

Für ⚡ Live kann ein eigener Anbieter gewählt werden („Live über OpenAI“, Chat z. B. kostenlos über den
eigenen Server). Auf Android 7 laufen die KI-Funktionen damit zum ersten Mal – mit jedem Anbieter
außer Claude.

### Die „zweite Option“ bei Anthropic: Keys mit Identität (identity-linked)

Anthropic kennt seit Kurzem drei Arten von API-Keys (Quelle: platform.claude.com/docs/en/manage-claude/authentication, abgerufen 2026-10-09):

1. **Persönlicher Key** („Linked account“ = du): Der Key handelt *als du*, mit deinen Rechten in der
   Organisation. Verlierst du den Zugang zur Organisation, hört der Key auf zu funktionieren.
2. **Service-Account-Key** („Linked account“ = ein Service-Account): wie oben, nur für eine
   Maschinen-Identität statt einer Person – gedacht für geteilte oder automatische Dienste.
3. **Workspace-Key** (alt, „legacy“): gehört einem Workspace, nicht einer Person.

Persönliche und Service-Account-Keys heißen „identity-linked“ (an eine Identität gebunden). Beim
Erstellen kann man sie **auf einen Workspace beschränken** – oder offen lassen, dann gelten sie für alle
Workspaces, in denen die Identität arbeiten darf. **Ein offener Key muss bei jeder Anfrage sagen, in
welchem Workspace er handelt**: Header `anthropic-workspace-id: wrkspc_…`. Fehlt der Header, antwortet
die API mit HTTP 400: *„anthropic-workspace-id is required when authenticating with an
identity-linked API key; send the id of the workspace this request acts in.“* Eine ungültige ID gibt
400 („must be a valid workspace ID“), ein unbekannter oder nicht erlaubter Workspace 404 („Workspace
`…` not found“).

**Was die App daraus macht:**
* Die Anleitung im Key-Dialog sagt: *Beim Erstellen: „Linked account“ = du, „Workspace“ = „Default
  Workspace“ wählen.* Ein so erstellter Key braucht keinen Header – das ist der einfache Weg.
* Wer schon einen offenen Key hat, trägt unter „Erweitert“ die **Workspace-ID** ein (Konsole →
  Settings → Workspaces, Spalte „ID“, Form `wrkspc_01…`). Die App schickt sie dann bei jeder Anfrage mit.
* Der 400er wird erkannt und klar erklärt (statt „Claude hat die Anfrage abgelehnt (HTTP 400)“), und die
  Key-Prüfung meldet „Workspace-ID fehlt“, statt den Key fälschlich als funktionierend zu speichern
  (heute wertet `ClaudeKeyChecker` jeden 400 als „Key ok“ – das ist ein echter Bug).

---

## 1. Decisions

| # | Decision | Why / alternatives |
|---|---|---|
| D1 | **One active chat provider + optional separate Live provider** (`aiLiveProvider`, null = same as chat). | Lets a user chat for free on their own server or DeepSeek and still run Live on a search-capable provider. Cheap to build: Live already resolves its own settings. |
| D2 | **Four engine families**: Claude SDK (existing, API 26+), OpenAI **Responses** (OpenAI, xAI, Perplexity Agent API), **Gemini generateContent**, OpenAI-compatible **Chat Completions** (OpenRouter, Mistral, DeepSeek, custom). | OC §1 architecture recommendation. Interactions API (GE §10) and Mistral Conversations (OC §3.5) are deferred. |
| D3 | Non-SDK engines: plain OkHttp 5 + `kotlinx.serialization.json` **element trees** (`buildJsonObject`, `JsonObject` parsing), no `@Serializable` wire DTOs. | Union types (string \| array content, error object \| array), unknown events, no R8 risk, runs on API 24. |
| D4 | **Gemini: chat only (with Google Search), no Live in v1.3.0.** | Google's grounding terms (GE §11) require showing the Search Suggestions with every grounded result and restrict storing grounded results. Live articles are stored and shown in the feed/reader without a suggestion widget. Chat history storage is explicitly allowed; suggestions are rendered under each grounded answer (WebView). |
| D5 | **Mistral, DeepSeek, custom server: chat without web search, no Live.** | Mistral search needs the Conversations SSE grammar (OC §3.5, deferred to a later release); DeepSeek/self-hosted have no search (OC §2.6). No preset is dropped: all can stream chat. |
| D6 | **Live correction loop without an "ok" round trip** for the new engines: when every `publish_articles` call is accepted, the run ends. | Saves one long-context request per run. Claude keeps its current loop (unchanged behavior). |
| D7 | Global monthly USD limit, **per-provider price table with AS_OF/validFrom**, provider-reported cost wins (OpenRouter `usage.cost`, xAI `cost_in_usd_ticks`, Perplexity `cost.total_cost`), custom server = 0 $, unknown model = conservative fallback rates + "Preis geschätzt". | Requirement. |
| D8 | **Silent DataStore migration** (`DataMigration<Preferences>`, schema 2): legacy `ai_key_enc`/`ai_key_untested`/`ai_key_rejected`/`ai_model` → Anthropic slot; active provider = Anthropic. | Atomic, runs before the first read, unit-testable on the JVM. The ciphertext does not depend on the preference name (`TokenCipher`, one keystore alias), so it is moved as-is. |
| D9 | `AiServices` and all non-SDK engines are **constructible on every API level**; only the Claude backend stays behind `AiSupport.sdkAvailable`. UI gating becomes per provider (`AiSupport.supports(provider)`). | Android 7 gets AI with every provider except Claude. |
| D10 | **Cleartext (`http://`) only for local names**: NSC `domain-config` allowlist (`localhost`, `10.0.2.2`, `*.local`, `*.lan`, `*.home.arpa`, `*.fritz.box`, `*.internal`). A raw LAN IP needs `https://`. | Keeps `cleartextTrafficPermitted="false"` for everything else. Fritz!Box gives every device `<name>.fritz.box`. Alternative if raw IPs are a must: base-config cleartext + an HTTPS-only interceptor on `container.http` (see Risks). |
| D11 | Provider-neutral prompts: chat prompt has a search / no-search variant; Live prompt has a "search + fetch" (Claude, byte-identical to v1.2) and a "search only" variant; `publish_articles` keeps its name everywhere; the schema gets a `PORTABLE` dialect (no `format: "uri"`, no `image`). | OA §7.4, GE §7.1, OC §5.4. |
| D12 | Chat history is replayed to any provider as **plain text** (as today). No provider-specific blobs (Gemini thought signatures, OpenAI reasoning items) are stored in chat history. | Provider switches mid-conversation keep working. GE §4: signatures in plain chat are not enforced. |
| D13 | Anthropic: optional **Workspace-ID** (advanced), sent as `anthropic-workspace-id` via `AnthropicOkHttpClient.builder().putHeader(...)`; workspace errors get their own `AiError`s and key-check results. Console links move to `platform.claude.com`. | Anthropic auth docs (fetched 2026-10-09). |
| D14 | Add **Claude Haiku 5.5** to the Claude models (cheap). It uses the basic server tools `web_search_20250305` / `web_fetch_20250910` and no `fallbacks`. | The `_20260209` tools need Opus 4.6+/Sonnet 4.6+ (claude-api skill reference). Both classes exist in SDK 2.68.0. |

---

## 2. Provider catalog

`ai/provider/ProviderCatalog.kt` is the one place with per-provider facts. All strings German.

### 2.1 Types

```kotlin
// data/model/AiSettingsModels.kt  (settings layer needs the id; no ai.* dependency)
enum class AiProviderId(val key: String) {
    ANTHROPIC("anthropic"), OPENAI("openai"), GEMINI("gemini"), XAI("xai"), PERPLEXITY("perplexity"),
    OPENROUTER("openrouter"), MISTRAL("mistral"), DEEPSEEK("deepseek"), CUSTOM("custom");

    /** Only the custom server works without a key. */
    val keyOptional: Boolean get() = this == CUSTOM

    companion object { fun fromKey(key: String?): AiProviderId? = entries.firstOrNull { it.key == key } }
}

// ai/provider/ProviderSpec.kt
enum class EngineKind { CLAUDE_SDK, RESPONSES, GEMINI, CHAT_COMPLETIONS }
enum class LiveSupport { YES, NO_WEB_SEARCH, NOT_ALLOWED }
enum class LiveToolNote { SEARCH_AND_FETCH, SEARCH_ONLY }   // AiPrompts.liveSystem variant

data class ModelOption(val id: String, val label: String, val subtitle: String, val badge: String? = null)

data class ProviderSpec(
    val id: AiProviderId,
    val name: String,              // "OpenAI" — titles, pickers
    val brand: String,             // "OpenAI", "Claude", "Gemini", "Grok" — sentences ("Grok ist überlastet")
    val tagline: String,           // one line in the picker
    val engine: EngineKind,
    val baseUrl: String?,          // null: SDK default (Anthropic) or user-supplied (custom)
    val chatWebSearch: Boolean,
    val live: LiveSupport,
    val liveToolNote: LiveToolNote,
    val needsSdk: Boolean,         // true only for Anthropic (API 26+)
    val consoleUrl: String,        // where keys are created
    val consoleLabel: String,      // pill text: "platform.openai.com öffnen"
    val keySteps: List<String>,    // "So bekommst du den Key"
    val keyTip: String?,           // lamina tip under the steps
    val keyPlaceholder: String,    // field placeholder
    val keyPrefixes: List<String>, // soft format check; empty = only the whitespace check
    val invalidKeyText: String?,   // override for AiError.InvalidKey (Perplexity: also no credit)
    val models: List<ModelOption>, // curated; first entry with badge "Standard" = default
    val defaultModel: String,
) {
    val liveUnavailableText: String? get() = when (live) {
        LiveSupport.YES -> null
        LiveSupport.NO_WEB_SEARCH -> "Live braucht Websuche – mit $name nicht verfügbar."
        LiveSupport.NOT_ALLOWED -> "Live ist mit $name nicht verfügbar: Googles Bedingungen für die Websuche erlauben keine gespeicherten Artikel."
    }
}
```

### 2.2 Catalog content

Console URLs marked † must be link-checked before release (T12); the spec files do not name them.

| id | name / brand | engine | base URL | chat search | live | tool note | needsSdk |
|---|---|---|---|---|---|---|---|
| ANTHROPIC | Claude (Anthropic) / Claude | CLAUDE_SDK | SDK default | ✓ | YES | SEARCH_AND_FETCH | ✓ |
| OPENAI | OpenAI / OpenAI | RESPONSES | `https://api.openai.com/v1` | ✓ | YES | SEARCH_ONLY | – |
| GEMINI | Google Gemini / Gemini | GEMINI | `https://generativelanguage.googleapis.com/v1beta` | ✓ | NOT_ALLOWED | – | – |
| XAI | xAI Grok / Grok | RESPONSES | `https://api.x.ai/v1` | ✓ | YES | SEARCH_ONLY | – |
| PERPLEXITY | Perplexity / Perplexity | RESPONSES | `https://api.perplexity.ai/v1` | ✓ | YES | SEARCH_ONLY | – |
| OPENROUTER | OpenRouter / OpenRouter | CHAT_COMPLETIONS | `https://openrouter.ai/api/v1` | ✓ | YES | SEARCH_ONLY | – |
| MISTRAL | Mistral / Mistral | CHAT_COMPLETIONS | `https://api.mistral.ai/v1` | ✗ | NO_WEB_SEARCH | – | – |
| DEEPSEEK | DeepSeek / DeepSeek | CHAT_COMPLETIONS | `https://api.deepseek.com` | ✗ | NO_WEB_SEARCH | – | – |
| CUSTOM | Eigener Server / dein Server | CHAT_COMPLETIONS | user | ✗ | NO_WEB_SEARCH | – | – |

Taglines:
* Claude: „Gründlich und quellenstark – liest Seiten selbst nach.“
* OpenAI: „GPT-Modelle mit Websuche – vielseitig und schnell.“
* Gemini: „Schnell und günstig, im Chat mit Google-Suche.“
* Grok: „Grok mit Websuche, günstig pro Suche.“
* Perplexity: „Such-Spezialist: Antworten mit vielen Quellen.“
* OpenRouter: „Ein Key für viele Modelle – Claude, GPT, Gemini und mehr.“
* Mistral: „Europäischer Anbieter – Chat ohne Websuche.“
* DeepSeek: „Sehr günstig – Chat ohne Websuche.“
* Eigener Server: „Ollama, LM Studio & Co. auf NAS oder PC – kostenlos, ohne Websuche.“

Key guides (`keySteps`, `keyTip`, `consoleUrl`, `keyPlaceholder`, `keyPrefixes`):

| id | consoleUrl | steps | tip | placeholder / prefixes |
|---|---|---|---|---|
| ANTHROPIC | `https://platform.claude.com/settings/keys` | 1. „platform.claude.com öffnen und anmelden“ 2. „Settings → API keys → „Create key““ 3. „Beim Erstellen: „Linked account“ = du, „Workspace“ = „Default Workspace“ wählen“ 4. „Key kopieren und hier einfügen“ | „Tipp: Setz dir unter „Limits“ ein Ausgabenlimit – so wird nie mehr abgebucht, als du willst.“ | `sk-ant-…` / `sk-ant-` |
| OPENAI | `https://platform.openai.com/api-keys` † | 1. „platform.openai.com öffnen und anmelden“ 2. „„API keys“ → „Create new secret key““ 3. „Unter „Billing“ Guthaben aufladen (mind. 5 $)“ 4. „Key kopieren und hier einfügen“ | „Ohne Guthaben antwortet OpenAI mit „Guthaben aufgebraucht“. Ein Limit setzt du im Projekt unter „Limits“.“ | `sk-…` / `sk-` |
| GEMINI | `https://aistudio.google.com/apikey` † | 1. „aistudio.google.com öffnen und mit Google anmelden“ 2. „„Get API key“ → „Create API key““ 3. „Für die Websuche: im Projekt die Abrechnung aktivieren (Prepay, mind. 5 $)“ 4. „Key kopieren und hier einfügen“ | „Mit einem Gratis-Key antwortet Gemini ohne Websuche.“ | `AIza… oder AQ.…` / none (GE §13: format undocumented) |
| XAI | `https://console.x.ai` † | 1. „console.x.ai öffnen und anmelden“ 2. „„API Keys“ → „Create API Key““ 3. „Unter „Billing“ Guthaben aufladen“ 4. „Key kopieren und hier einfügen“ | null | `xai-…` / none |
| PERPLEXITY | `https://www.perplexity.ai/account/api/keys` † | 1. „perplexity.ai öffnen und anmelden“ 2. „Einstellungen → „API“ → „API-Key erstellen““ 3. „Guthaben aufladen“ 4. „Key kopieren und hier einfügen“ | „Ist das Guthaben leer, meldet Perplexity den Key als ungültig.“ | `pplx-…` / none |
| OPENROUTER | `https://openrouter.ai/settings/keys` † | 1. „openrouter.ai öffnen und anmelden“ 2. „„Keys“ → „Create Key““ 3. „Unter „Credits“ Guthaben kaufen“ 4. „Key kopieren und hier einfügen“ | „Ein Key für viele Modelle. Am Key kannst du ein Ausgabenlimit setzen.“ | `sk-or-v1-…` / `sk-or-` |
| MISTRAL | `https://console.mistral.ai/api-keys` † | 1. „console.mistral.ai öffnen und anmelden“ 2. „Eine Zahlungsmethode hinterlegen“ 3. „„API Keys“ → „Create new key““ 4. „Key kopieren (er wird nur einmal angezeigt)“ | null | `…` / none |
| DEEPSEEK | `https://platform.deepseek.com/api_keys` † | 1. „platform.deepseek.com öffnen und anmelden“ 2. „Guthaben aufladen“ 3. „„API keys“ → „Create new API key““ 4. „Key kopieren und hier einfügen“ | null | `sk-…` / none |
| CUSTOM | – | 1. „Ollama oder LM Studio im Heimnetz starten (Server für das Netzwerk freigeben)“ 2. „Adresse eintragen, z. B. http://nas.fritz.box:11434/v1“ 3. „Key nur eintragen, wenn dein Server einen verlangt“ | „Unverschlüsseltes http:// geht nur mit Namen wie nas.fritz.box, nas.local oder *.home.arpa. Für eine IP-Adresse brauchst du https://.“ | key field placeholder „optional“ |

`invalidKeyText`: Perplexity → „Perplexity lehnt den Key ab – er ist ungültig oder dein Guthaben ist leer.“ (OC §6.8: 401 for both). Others null.

Curated models (first = default, badge „Standard“):

| id | models (id – label – subtitle) |
|---|---|
| ANTHROPIC | `claude-sonnet-5-5` – Claude Sonnet 5.5 – „Schnell und gründlich“ · `claude-opus-5-5` – Claude Opus 5.5 – „Maximale Qualität, etwa doppelter Preis“ · `claude-haiku-5-5` – Claude Haiku 5.5 – „Sehr günstig und flott“ |
| OPENAI | `gpt-6.1-sol` – GPT-6.1 Sol – „Ausgewogen“ · `gpt-6-luna` – GPT-6 Luna – „Sehr günstig“ · `gpt-6-astra` – GPT-6 Astra – „Spitzenmodell, deutlich teurer“ (OA §3.1) |
| GEMINI | `gemini-3.8-flash` – Gemini 3.8 Flash – „Schnell und günstig“ (with billing: „(ab 2027 doppelter Preis)“ after it) · `gemini-3.5-flash-lite` – Gemini 3.5 Flash-Lite – „Am günstigsten“ · `gemini-3.1-pro-preview` – Gemini 3.1 Pro (Preview) – „Stärkstes Gemini, nur mit Abrechnung“ (GE §3.1) |
| XAI | `grok-4.3` – Grok 4.3 – „Günstig“ · `grok-4.7` – Grok 4.7 – „Spitzenmodell“ (OC §4.2) |
| PERPLEXITY | `google/gemini-3.8-flash` – Gemini 3.8 Flash – „Mit Perplexity-Suche“ · `perplexity/sonar` – Sonar – „Perplexitys eigenes Modell, günstig“ · `anthropic/claude-sonnet-5-5` – Claude Sonnet 5.5 – „Claude mit Perplexity-Suche“ (OC §6.2; dashes!) |
| OPENROUTER | `google/gemini-3.8-flash` – Gemini 3.8 Flash – „Schnell und günstig“ · `openai/gpt-6-luna` – GPT-6 Luna – „Sehr günstig“ · `anthropic/claude-sonnet-5.5` – Claude Sonnet 5.5 – „Gründlich“ (OC §7.2; dots!) |
| MISTRAL | `mistral-medium-3-5` – Mistral Medium 3.5 – „Ausgewogen“ · `mistral-small-2603` – Mistral Small 4 – „Sehr günstig“ · `mistral-large-2512` – Mistral Large 3 – „Stark und günstig“ (OC §3.2, pinned names) |
| DEEPSEEK | `deepseek-flash` – DeepSeek V4.1 Flash – „Sehr günstig“ · `deepseek-v4-pro` – DeepSeek V4 Pro – „Stärker“ (OC §5.1) |
| CUSTOM | none (free text; suggestions loaded from `GET {base}/models`); `defaultModel = ""` |

Every provider also offers „Eigenes Modell …“ (free text).

---

## 3. Settings, keys and migration

### 3.1 Model

```kotlin
// data/model/AiSettingsModels.kt
data class ProviderSettings(
    val hasKey: Boolean = false,
    val keyUntested: Boolean = false,  // only meaningful with hasKey
    val keyRejected: Boolean = false,  // only meaningful with hasKey
    val model: String? = null,         // null = catalog default
    val baseUrl: String? = null,       // CUSTOM only, normalized (§7.6)
    val workspaceId: String? = null,   // ANTHROPIC only
)

data class AiSettings(
    val provider: AiProviderId = AiProviderId.ANTHROPIC,
    /** Live research provider; null = the chat provider. */
    val liveProvider: AiProviderId? = null,
    val providers: Map<AiProviderId, ProviderSettings> = emptyMap(),
) {
    fun of(id: AiProviderId): ProviderSettings = providers[id] ?: ProviderSettings()
    val effectiveLiveProvider: AiProviderId get() = liveProvider ?: provider
    /** Key present (or optional) and, for the custom server, a base URL. */
    fun isConfigured(id: AiProviderId): Boolean {
        val p = of(id)
        return (p.hasKey || id.keyOptional) && (id != AiProviderId.CUSTOM || !p.baseUrl.isNullOrBlank())
    }
}
```

`AppSettings` loses `hasAiKey`, `aiKeyUntested`, `aiKeyRejected`, `aiModel` and the `AiModel` enum is
deleted; it gains `val ai: AiSettings = AiSettings()`. `aiMonthlyLimitUsd` and `liveAutoRefresh` stay
(global). Add a test helper `AppSettings.withAiKey(id = ANTHROPIC)` in `test/` for the many tests that
used `AppSettings(hasAiKey = true)`.

### 3.2 DataStore keys (`settings`)

| Key | Type | Content |
|---|---|---|
| `ai_schema` | int | `2` after migration |
| `ai_provider` | string | `AiProviderId.key` of the chat provider |
| `ai_live_provider` | string | absent = same as chat |
| `ai_<p>_key_enc` | string | `TokenCipher` ciphertext (never logged, never in `AppSettings`) |
| `ai_<p>_key_untested` | bool | |
| `ai_<p>_key_rejected` | bool | |
| `ai_<p>_model` | string | model id (curated or free text, trimmed, ≤ 200 chars) |
| `ai_custom_base_url` | string | normalized base URL |
| `ai_anthropic_workspace_id` | string | plain text (not a secret) |
| `ai_monthly_limit_usd`, `live_auto_refresh` | unchanged | global |
| ~~`ai_key_enc`, `ai_key_untested`, `ai_key_rejected`, `ai_model`~~ | removed by the migration | |

`<p>` = `AiProviderId.key`. Key names live in one object `AiPrefKeys` (`data/AiSettingsPrefs.kt`) with
`keyEnc(p)`, `untested(p)`, `rejected(p)`, `model(p)`.

The settings file is already excluded from backups (`backup_rules.xml`), so no restored-but-undecryptable
keys; `forgetUndecryptable` still runs per provider key.

### 3.3 Migration

```kotlin
// data/AiSettingsPrefs.kt
internal object AiProvidersMigration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences) = (currentData[AI_SCHEMA] ?: 0) < 2
    override suspend fun migrate(currentData: Preferences): Preferences = migrateAiPrefs(currentData)
    override suspend fun cleanUp() = Unit
}

/** Pure, idempotent; unit-tested on the JVM with mutablePreferencesOf(). */
internal fun migrateAiPrefs(old: Preferences): Preferences = old.toMutablePreferences().apply {
    val legacyKey = this[LEGACY_KEY]
    if (!legacyKey.isNullOrBlank() && this[keyEnc(ANTHROPIC)] == null) {
        this[keyEnc(ANTHROPIC)] = legacyKey
        if (this[LEGACY_UNTESTED] == true) this[untested(ANTHROPIC)] = true
        if (this[LEGACY_REJECTED] == true) this[rejected(ANTHROPIC)] = true
    }
    if (this[model(ANTHROPIC)] == null) {
        when (this[LEGACY_MODEL]) {                 // old AiModel enum names
            "SONNET" -> this[model(ANTHROPIC)] = "claude-sonnet-5-5"
            "OPUS" -> this[model(ANTHROPIC)] = "claude-opus-5-5"
            else -> Unit                            // unknown / absent → catalog default
        }
    }
    if (this[AI_PROVIDER] == null) this[AI_PROVIDER] = AiProviderId.ANTHROPIC.key
    remove(LEGACY_KEY); remove(LEGACY_UNTESTED); remove(LEGACY_REJECTED); remove(LEGACY_MODEL)
    this[AI_SCHEMA] = 2
}.toPreferences()
```

Register it: `preferencesDataStore(name = "settings", produceMigrations = { listOf(AiProvidersMigration) })`.
Fresh installs run the same path (provider = Anthropic, schema 2). On Android 7 the default provider
Anthropic is blocked with `UNSUPPORTED_DEVICE` and the settings invite a switch (no automatic switch).

### 3.4 `SettingsRepository` API

```kotlin
suspend fun setAiKey(provider: AiProviderId, key: String?, tested: Boolean = true)   // null/blank clears key + flags
suspend fun aiKey(provider: AiProviderId): String?
suspend fun markAiKeyWorks(provider: AiProviderId)
suspend fun markAiKeyRejected(provider: AiProviderId, key: String?)  // same guard as today (stored == key); CUSTOM without key: no-op
suspend fun setCustomBaseUrl(url: String?)       // normalized via CustomServer.normalize, null clears
suspend fun setAnthropicWorkspaceId(id: String?) // trimmed, null/blank clears
```

`update { }` / `write()` keep writing every *plain* field. For AI that is `ai_provider`,
`ai_live_provider` (remove when null) and `ai_<p>_model` for each provider in `ai.providers` (remove when
null). Key flags, base URL and workspace id are only written by the dedicated setters. `toSettings()`
builds `AiSettings` with one entry per `AiProviderId` (hasKey = ciphertext not blank; flags only with a
key). Extract the AI part of `toSettings()` into `readAiSettings(prefs: Preferences): AiSettings` so it
is unit-testable.

---

## 4. Engine seam (changed types)

`ai/AiEngines.kt`:

```kotlin
/** Credentials and endpoint of one request. Never logged, never in toString(). */
internal class ProviderAuth(
    val provider: AiProviderId,
    val apiKey: String?,        // null only for CUSTOM without key
    val baseUrl: String?,       // custom server; null = catalog default
    val workspaceId: String? = null,
) { override fun toString() = "ProviderAuth(${provider.key})" }

internal interface UsageSink {
    suspend fun beforeRequest()
    suspend fun record(provider: AiProviderId, modelId: String, usage: TokenUsage)
}

internal interface ToolActivityListener {
    fun onSearch(query: String) {}         // "" = query unknown ("Suche im Netz …")
    fun onFetch(url: String) {}
    fun onToolCall(name: String) {}
}

internal interface ChatTurnListener : ToolActivityListener {
    fun onText(delta: String) {}
    fun onSources(sources: List<SourceRef>) {}
    /** Gemini only: HTML of the Search Suggestions that must be shown with the answer. */
    fun onSearchSuggestions(html: String) {}
}

internal data class ChatTurnRequest(val auth: ProviderAuth, val model: String, val systemPrompt: String,
                                    val context: String, val history: List<ApiMessage>)
internal data class ChatTurnResult(
    val text: String, val sources: List<SourceRef>, val end: TurnEnd,
    val refusalCategory: String? = null,
    val searchSuggestionsHtml: String? = null,
    /** Gemini free-tier fallback: answered without web search. */
    val withoutWebSearch: Boolean = false,
)
internal data class LiveResearchRequest(val auth: ProviderAuth, val model: String, val systemPrompt: String, val userPrompt: String)

internal data class KeyCheckOutcome(val check: AiKeyCheck, val serverModels: List<String> = emptyList())
internal interface KeyChecker {
    suspend fun check(auth: ProviderAuth, model: String, usage: UsageSink): KeyCheckOutcome
}

internal interface AiBackend {
    val chat: ChatEngine
    val live: LiveEngine?      // null: the family/provider has no Live
    val keys: KeyChecker
}
```

`TokenUsage` (`ai/AiPricing.kt`) gains `val reportedCostUsd: Double? = null`. **Convention for every
engine:** `inputTokens` = *uncached* input, `cacheReadTokens` and `cacheWriteTokens` separately,
`outputTokens` includes reasoning/thinking tokens, `webSearches` = billable searches. `plus` sums
`reportedCostUsd` when either side is non-null (null counts as 0 then).

### 4.1 Resolution and blocks

`ai/AiResolver.kt` (pure parts unit-tested):

```kotlin
enum class AiPurpose { CHAT, LIVE }

/** Why AI can't run for [purpose] right now; null = go. Order: device, Live support, configuration, limit. */
internal fun aiBlock(ai: AiSettings, purpose: AiPurpose, sdkAvailable: Boolean, usage: UsageSnapshot): AiBlock? {
    val id = if (purpose == AiPurpose.LIVE) ai.effectiveLiveProvider else ai.provider
    val spec = ProviderCatalog.spec(id)
    return when {
        spec.needsSdk && !sdkAvailable -> AiBlock.UNSUPPORTED_DEVICE
        purpose == AiPurpose.LIVE && spec.live != LiveSupport.YES -> AiBlock.LIVE_UNAVAILABLE
        !ai.isConfigured(id) -> AiBlock.NO_KEY
        usage.limitUsd > 0.0 && usage.spentUsd >= usage.limitUsd -> AiBlock.LIMIT_REACHED
        else -> null
    }
}

internal sealed interface Resolution {
    data class Ready(val spec: ProviderSpec, val auth: ProviderAuth, val model: String, val backend: AiBackend) : Resolution
    data class Blocked(val spec: ProviderSpec, val block: AiBlock) : Resolution
}

/** What ChatServiceImpl / LiveNewsServiceImpl need from the facade (faked in tests). */
internal interface AiProviderAccess {
    suspend fun resolve(purpose: AiPurpose): Resolution          // reads settings + decrypts the key
    fun blocks(purpose: AiPurpose): Flow<Pair<ProviderSpec, AiBlock?>>
    suspend fun keyRejected(provider: AiProviderId, key: String?) // must not throw
}
```

`AiContracts.kt` changes:

```kotlin
object AiSupport {
    /** The Anthropic Java SDK needs Android 8.0+. Check before touching ai.claude. */
    val sdkAvailable: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
    fun supports(provider: AiProviderId): Boolean = !ProviderCatalog.spec(provider).needsSdk || sdkAvailable
}
enum class AiBlock { UNSUPPORTED_DEVICE, NO_KEY, LIMIT_REACHED, LIVE_UNAVAILABLE }

sealed interface AiError {
    // existing: InvalidKey, RateLimited, Overloaded, Network, Refusal, Truncated, Unknown
    data object NoCredit : AiError
    data object WorkspaceRequired : AiError
    data object WorkspaceNotFound : AiError
    data class ModelUnavailable(val model: String?) : AiError
    data object ServerUnreachable : AiError
}
enum class AiKeyCheck { OK, OK_NO_CREDIT, INVALID, NETWORK, UNSUPPORTED, WORKSPACE_REQUIRED, WORKSPACE_INVALID,
                        SERVER_UNREACHABLE, SERVER_CERTIFICATE, CLEARTEXT_BLOCKED, NOT_AN_AI_SERVER }

data class ChatMessage(/* existing */ ..., val searchSuggestionsHtml: String? = null, val note: String? = null,
                       val provider: AiProviderId? = null)   // the answer's author (null before v1.3.0)
data class ChatUiState(/* existing */ ..., val providerName: String = "", val provider: AiProviderId? = null)
data class LiveState(/* existing */ ..., val provider: AiProviderId? = null, val providerName: String = "")
data class UsageSnapshot(/* existing */ ..., val estimatedRequests: Int = 0)
```

`StoredAiError` gains kinds `no_credit`, `workspace_required`, `workspace_not_found`,
`model_unavailable` (detail = model), `server_unreachable`; unknown kinds keep mapping to `Unknown`.
`StoredChatMessage` gains `searchSuggestionsHtml: String? = null` and `note: String? = null`.
`StoredLiveRun` gains `provider: String = ""`.

### 4.2 `AiServices` (facade)

```kotlin
class AiServices(context: Context, container: AppContainer) {
    private val http by lazy { AiHttp(container.http) }
    private val claude: AiBackend? by lazy { if (!AiSupport.sdkAvailable) null else try { ClaudeBackend() } catch (e: LinkageError) { null } }
    private val backends = ConcurrentHashMap<AiProviderId, AiBackend>()
    internal fun backendFor(id: AiProviderId): AiBackend? = when (ProviderCatalog.spec(id).engine) {
        EngineKind.CLAUDE_SDK -> claude
        EngineKind.RESPONSES -> backends.getOrPut(id) { ResponsesBackend(http, ResponsesDialect.of(id)) }
        EngineKind.GEMINI -> backends.getOrPut(id) { GeminiBackend(http) }
        EngineKind.CHAT_COMPLETIONS -> backends.getOrPut(id) { CompatBackend(http, CompatDialect.of(id)) }
    }
    // AiProviderAccess implementation: settings → spec, key (container.settings.aiKey(id)), base URL, workspace,
    // model = providers[id].model ?: spec.defaultModel; Blocked(UNSUPPORTED_DEVICE) also when backendFor() is null.
    suspend fun testKey(provider: AiProviderId, key: String?, extras: KeyExtras): KeyCheckOutcome
    suspend fun listServerModels(baseUrl: String, key: String?): List<String>   // custom server
}
data class KeyExtras(val workspaceId: String? = null, val baseUrl: String? = null)
```

`requestUsage` wrapper: `record(provider, …)` → `usageStore.record(...)` then
`container.settings.markAiKeyWorks(provider)`.

`ChatServiceImpl` / `LiveNewsServiceImpl` take `access: AiProviderAccess` instead of
`apiKey` + `engine` + `keyRejected`; their `blocked` state comes from `access.blocks(purpose)`;
`providerName` from the resolved spec. The chat system prompt is
`AiPrompts.chatSystem(webSearch = spec.chatWebSearch)`, the Live system prompt
`AiPrompts.liveSystem(spec.liveToolNote)`. `LiveStore.append` records `provider.key` and the model.

---

## 5. Prompts and `publish_articles`

### 5.1 `AiPrompts`

* `CHAT_SYSTEM` → `fun chatSystem(webSearch: Boolean): String` (two precomputed constants, cache-stable).
  * `true`: today's text unchanged.
  * `false`: the bullet „Der Kontext hat einen Stand …“ is replaced by:
    „Der Kontext hat einen Stand (Datum am Anfang). Du hast hier keinen Internetzugang: Geht es um etwas, das danach passiert sein kann, sag offen, dass du dazu nichts Aktuelles weißt, und nenn den Stand des Kontexts. Erfinde keine Quellen.“
* `LIVE_SYSTEM` → `fun liveSystem(note: LiveToolNote): String`.
  * `SEARCH_AND_FETCH` (Claude): **byte-identical to v1.2 `LIVE_SYSTEM`** (golden test).
  * `SEARCH_ONLY`: same text with the tool names removed:
    * rule 3: „… Öffne die Quellen mit web_fetch und lies sie.“ → „… Lies die Quellen, die dir die Websuche liefert, und verlass dich nicht auf Überschriften allein.“
    * Ablauf 1: „Recherchiere mit web_search (deutsch und englisch) …“ → „Recherchiere mit der Websuche (deutsch und englisch) …“
    * Ablauf 2: „Prüfe jede Geschichte mit web_fetch an ihren Quellen.“ → „Prüfe jede Geschichte an mindestens zwei Quellen aus der Websuche.“
    * add to Ablauf 3: „Schreib vor dem Aufruf keinen Text.“ (OA §8 instruction; GE §7.3 MALFORMED gotcha)
  * `publish_articles` keeps its name in every variant (same function name for all engines).
* `LIVE_NUDGE`, `liveRequest`, contexts: unchanged (already neutral).

### 5.2 `LivePublish` schema dialects

```kotlin
enum class SchemaDialect { FULL, PORTABLE }
fun articleSchema(topicIds: List<String>, dialect: SchemaDialect = SchemaDialect.FULL): Map<String, Any>
fun articlesProperty(topicIds: List<String>, dialect: SchemaDialect = SchemaDialect.FULL): Map<String, Any>
/** Top-level parameters object: {type:object, properties:{articles}, required:[articles], additionalProperties:false}. */
fun parametersSchema(topicIds: List<String>, dialect: SchemaDialect): Map<String, Any>
```

* `FULL` (Claude): unchanged (golden test) → the Claude cached prefix does not change.
* `PORTABLE` (Responses, OpenRouter): no `"format": "uri"` on `url` / `sources[].url`, no `image`
  property (and not in `required`); `date-time` stays (OpenAI strict supports it, OA §7.4).
  `validate()` is unchanged (it never reads `image`; URLs stay checked client-side).
* Strict flag: OpenAI, xAI, Perplexity `strict: true`; OpenRouter omits `strict` (upstreams vary).
* Helper `ai/http/JsonMaps.kt`: `fun Any?.toJsonElement(): JsonElement` for maps/lists/strings/numbers/booleans/null.

---

## 6. HTTP / SSE layer (`ai/http`)

```kotlin
internal class AiHttp(base: OkHttpClient) {
    val client: OkHttpClient = base.newBuilder()
        .connectTimeout(15, SECONDS).readTimeout(150, SECONDS)   // thinking/search can be quiet for long
        .writeTimeout(30, SECONDS).callTimeout(10, MINUTES)
        .build()
}

internal class HttpFailure(val code: Int, val body: String, val retryAfter: Duration?) : IOException("HTTP $code")

internal data class SseEvent(val event: String?, val data: String)

/**
 * Executes [request] and streams its SSE body. Non-2xx → HttpFailure (body ≤ 64 KiB, read as text even when the
 * Content-Type says text/event-stream, GE §5.1). Cancelling the coroutine cancels the OkHttp call.
 * [onEvent] returns false to stop reading.
 */
internal suspend fun AiHttp.streamSse(request: Request, onEvent: suspend (SseEvent) -> Boolean)

/** Plain request (key checks, model lists): code + body text. */
internal suspend fun AiHttp.fetch(request: Request): HttpText
```

SSE parsing rules (one reader for all families): lines via `BufferedSource.readUtf8Line()` (handles
`\r\n`); `:` comment lines ignored (`: OPENROUTER PROCESSING`, `: keep-alive`); `event:` sets the name;
`data:` lines are joined with `\n`; a blank line dispatches; `id:`/`retry:` ignored; EOF dispatches a
pending event; a `data` of exactly `[DONE]` ends the stream. Blocking reads run on `Dispatchers.IO`;
`invokeOnCancellation { call.cancel() }`; an `IOException` after cancellation is rethrown as
`CancellationException`.

Retry (`ai/http/Retry.kt`): only **before the first event was delivered**, at most 2 retries, when the
family's error mapper says `retryable` (429 rate limits, 408, 5xx/529, connection reset). Delay =
`Retry-After` capped at 20 s, else 1 s then 3 s (± 20 % jitter). Never retry billing/quota/auth errors,
never replay after output was streamed (OA §11, OC §9). `usage.beforeRequest()` runs before each try.

Headers: `User-Agent: Morgenbriefing-Android/<versionName>`, `Accept: text/event-stream` for streams.
Keys only in headers (Gemini: `x-goog-api-key`, never `?key=`). Logging: status code and provider
only — **never request bodies, response bodies or headers** (error bodies can echo key fragments).

Error mapping: `ai/http/ProviderErrors.kt` with one `ErrorMapper` per family (§8). Each returns
`MappedError(error: AiError, retryable: Boolean)` from an `HttpFailure`, an `IOException` or a
mid-stream error object.

---

## 7. Engines: request/response mapping

Shared engine behavior (all new families):
* `SourceCollector` + `AnswerText` (reuse from `claude/ClaudeStream.kt`: move `AnswerText` to `ai/`).
* Usage is recorded once per HTTP request (also on failure/cancel after the stream started: estimated
  from streamed chars ÷ 3 when no usage arrived, like Claude).
* Live loop (D6), `MAX_REQUESTS = 6`:
  ```
  input = [user(userPrompt)]; published = false; reminded = false
  repeat(6) {
    turn = stream(request(input))                       // usage recorded inside
    if (turn.refused) return Result(published, REFUSED, category)
    if (turn.truncated) return Result(published, TRUNCATED)
    val calls = turn.functionCalls.filter { it.name == "publish_articles" }   // other names → ignored
    if (calls.isEmpty()) {
        if (published) return Result(true, COMPLETE)
        if (reminded) return Result(false, INCOMPLETE)
        reminded = true; input += turn.replayItems + user(LIVE_NUDGE); continue
    }
    val replies = calls.map { listener.onPublish(it.arguments) }
    if (replies.any { it.accepted }) published = true
    if (replies.all { it.accepted }) return Result(true, COMPLETE)
    input += turn.replayItems + calls.zip(replies).map { (c, r) -> toolResult(c, r.message) }
  }
  return Result(published, INCOMPLETE)
  ```
* Effort / budget per purpose:

  | | Chat | Live |
  |---|---|---|
  | OpenAI | `reasoning.effort:"low"`, `max_output_tokens: 12000`, `max_tool_calls: 4` | `"medium"`, `32000`, `10` |
  | xAI | `reasoning.effort:"low"`, `max_output_tokens: 8000`, `max_turns: 3` | `"medium"`, `32000`, `10` |
  | Perplexity | `max_steps: 3`, `max_output_tokens: 4000`, search `search_type:"fast"` | `max_steps: 10`, `16000`, `search_type:"web"`, `filters.search_recency_filter:"day"` |
  | Gemini | `thinkingLevel:"low"`, `maxOutputTokens: 8192` | – |
  | OpenRouter | `reasoning.effort:"low"`, `max_tokens: 8000`, `max_tool_calls: 3` | `"medium"`, `32000`, `10` |
  | Mistral | `temperature: 0.3`, `max_tokens: 4000` | – |
  | DeepSeek | `thinking:{type:"disabled"}`, `max_tokens: 4000`, `stream_options.include_usage` | – |
  | Custom | `temperature: 0.3`, `max_tokens: 4000` | – |

* Web search location: OpenAI `user_location {type:"approximate", country:"DE", city:"Leipzig", region:"Sachsen", timezone:"Europe/Berlin"}` (default would be US, OA §6.1); Perplexity `user_location {country:"DE", city:"Leipzig"}`; OpenRouter `parameters.user_location {type:"approximate", country:"DE", city:"Leipzig", timezone:"Europe/Berlin"}`; xAI none documented.

### 7.1 Responses family (`ai/openai/`)

Files: `ResponsesDialect.kt`, `ResponsesRequests.kt`, `ResponsesStream.kt`, `ResponsesEngines.kt` (chat, live, key checker, backend).

```kotlin
internal enum class ResponsesDialect(
    val path: String,                  // "/responses" | "/agent"
    val contextAsDeveloperMessage: Boolean, // OpenAI: developer message in input (cache breakpoint, OA §4); others: appended to instructions
    val sendPhase: Boolean,            // OpenAI: assistant replay with "phase":"final_answer" (OA §4)
    val include: List<String>,         // OpenAI ["web_search_call.action.sources"], xAI [+"no_inline_citations","reasoning.encrypted_content"], Perplexity []
    val obfuscationOff: Boolean,       // OpenAI stream_options.include_obfuscation=false
    val liveCacheOff: Boolean,         // OpenAI Live: prompt_cache_options {mode:"explicit"} w/o breakpoints (OA §9)
    val functionCallsOnlyInItemDone: Boolean, // xAI, Perplexity (OC §4.4, §6.4)
    val stripCitationMarkers: Boolean, // Perplexity: remove "[1]" / "[web:1]" from text
) { OPENAI(...), XAI(...), PERPLEXITY(...) }
```

Chat request (OpenAI shape, OA §4; xAI OC §4.3; Perplexity OC §6.3):

```json
{ "model": "<model>", "stream": true, "store": false,
  "reasoning": {"effort": "low"}, "max_output_tokens": 12000,
  "instructions": "<chatSystem(true)>",
  "input": [
    {"role": "developer", "content": [{"type": "input_text", "text": "<context>"}]},
    {"role": "user", "content": "<q1>"},
    {"role": "assistant", "content": "<a1>", "phase": "final_answer"},
    {"role": "user", "content": "<q2 + day note>"}],
  "tools": [{"type": "web_search", "search_context_size": "medium", "user_location": {…}}],
  "tool_choice": "auto", "max_tool_calls": 4,
  "include": ["web_search_call.action.sources"],
  "stream_options": {"include_obfuscation": false} }
```
* xAI: no `developer` message (context appended to `instructions` after `\n\n`), no `phase`, no
  `stream_options`, `max_turns` instead of `max_tool_calls`, `tools:[{"type":"web_search"}]`.
* Perplexity: `POST /v1/agent`, `input` items `{"type":"message","role":…,"content":…}`, tool
  `{"type":"web_search","search_type":"fast","max_results":5,"user_location":{…}}`, `max_steps`,
  `language_preference:"de"`, `max_output_tokens` always set (required for `anthropic/*`).

Live request: same skeleton with `instructions = liveSystem(SEARCH_ONLY)`, `input=[user(liveRequest)]`,
tools `[web_search …, {"type":"function","name":"publish_articles","description":TOOL_DESCRIPTION,"strict":true,"parameters":parametersSchema(ALL topics, PORTABLE)}]`,
`tool_choice:"auto"` (never `"required"`, OA §8.5). Correction request: `input += every output item of
the previous response as received (reasoning incl. encrypted_content, web_search_call, function_call,
message)` + `{"type":"function_call_output","call_id":…,"output":replyText}` (OA §7.3, §8.4; Perplexity
OC §6.7 re-sends the `function_call` item next to its output).

Stream mapping (OA §5.2; parse `data`, switch on its `type`, ignore unknown):

| Event | Action |
|---|---|
| `response.created` | remember `response.model` (for usage) |
| `response.output_item.added` | register item by `item.id`: `message` (+`phase`), `function_call` (`name`, `call_id`), `web_search_call`, `reasoning`, `search_results` (Perplexity), other; `function_call` → `onToolCall(name)` |
| `response.output_text.delta` | if the item is a message with `phase != "commentary"` → text (Perplexity: through `CitationMarkerStripper`) |
| `response.output_text.annotation.added` | `url_citation` → `sources.addCited(url, title)`; xAI title is a number → pass `null` (host shown) |
| `response.function_call_arguments.delta` / `.done` | buffer args by `item_id` |
| `response.output_item.done` | keep the raw item JSON for replay; `function_call` → final call (authoritative); `web_search_call` → `action.type=="search"` (or no action) counts a search, `onSearch(action.query ?: action.queries?.first() ?: "")`, `action.sources[].url` → `addSearched`; `open_page` → `onFetch(url)`; `message` → re-read `content[].annotations` (authoritative); `search_results` → `results[] {title,url}` → `addFetched` |
| `response.refusal.delta/.done` | refused turn (category null) |
| `response.completed` | usage, end COMPLETE (or tool call) |
| `response.incomplete` | `incomplete_details.reason`: `max_output_tokens` → TRUNCATED, `content_filter` → REFUSED, else INCOMPLETE |
| `response.failed` / `error` | map `code` (§8.2) → throw `AiException` |
| `[DONE]` | end |

Usage: OpenAI `usage.input_tokens/output_tokens/input_tokens_details.{cached_tokens,cache_write_tokens}`
→ `inputTokens = input − cached − cacheWrite`; searches counted from items (OA §9). xAI: additionally
`cost_in_usd_ticks / 1e10` → `reportedCostUsd`, `server_side_tool_usage_details.web_search_calls`.
Perplexity: `cost.total_cost` → `reportedCostUsd`, `tool_calls_details.web_search.invocation`,
`input_tokens_details.{cache_read_input_tokens,cache_creation_input_tokens}`.

`CitationMarkerStripper` (Perplexity): removes `\[(web:)?\d+\]` from streamed text, holding back at most
12 chars after an unmatched `[` until it can decide; unit-tested across chunk boundaries.

Key checks (no tokens where possible):
* OpenAI: `GET /v1/models` → 200 OK, 401/403 INVALID, 429 OK, 5xx/IO NETWORK (OA §10).
* xAI: `GET /v1/api-key` → 200: `api_key_blocked || api_key_disabled` → INVALID, `team_blocked` →
  OK_NO_CREDIT, else OK; 401 INVALID; a wrong key is a **400** "Incorrect API key provided" (observed,
  OC §4.8) → INVALID, like every other 4xx of `/api-key`. All three: a 400 naming the API key →
  INVALID, another 4xx → OK (the key got through), 5xx/IO → NETWORK.
* Perplexity: `POST /v1/agent {"model":"openai/gpt-6-luna","input":"ping","max_output_tokens":16}` (no
  tools, not streamed) → 200 OK (record usage), 401 INVALID (OC §6.8).

### 7.2 Gemini (`ai/gemini/`)

Files: `GeminiRequests.kt`, `GeminiStream.kt`, `GeminiEngines.kt`. Chat only; `GeminiBackend.live = null`.

Request: `POST {base}/models/{model}:streamGenerateContent?alt=sse`, header `x-goog-api-key` (GE §4):

```json
{ "systemInstruction": {"parts": [{"text": "<chatSystem(true)>"}]},
  "contents": [
    {"role": "user", "parts": [{"text": "<context>"}, {"text": "<q1>"}]},
    {"role": "model", "parts": [{"text": "<a1>"}]},
    {"role": "user", "parts": [{"text": "<q2 + day note>"}]}],
  "tools": [{"googleSearch": {}}],
  "generationConfig": {"thinkingConfig": {"thinkingLevel": "low"}, "maxOutputTokens": 8192} }
```
No `temperature`/`topP`/`topK`/`candidateCount`, never `thinkingBudget`, last turn always `user`
(GE §15). Context first and byte-stable → implicit caching (GE §3.4).

Stream (GE §5): each `data:` = one `GenerateContentResponse`.
* `candidates[0].content.parts[]`: `thought == true` → skip; `text` → text delta.
* `groundingMetadata`: `groundingChunks[].web {uri,title}` **appended** in arrival order →
  `addCited(uri, title)` (title is a domain, uri a redirect; open as-is, never resolve, GE §11);
  `webSearchQueries[]` → `onSearch` per new distinct non-empty query (also the search count);
  `searchEntryPoint.renderedContent` → keep latest, `onSearchSuggestions(html)`.
* `finishReason`: `STOP` → COMPLETE; `MAX_TOKENS` → TRUNCATED; `SAFETY`, `PROHIBITED_CONTENT`,
  `BLOCKLIST`, `SPII`, `RECITATION` → REFUSED (category = lowercased reason); others → `AiError.Unknown`
  („Gemini hat die Antwort abgebrochen.“). `promptFeedback.blockReason` → REFUSED.
* Top-level `error` in a chunk → map (§8.3). EOF without `finishReason` → `AiError.Network`.
* Usage = last `usageMetadata`: `inputTokens = promptTokenCount − cachedContentTokenCount + toolUsePromptTokenCount`,
  `cacheReadTokens = cachedContentTokenCount`, `outputTokens = candidatesTokenCount + thoughtsTokenCount`,
  `webSearches = distinct queries` (GE §9.2).

Free-tier fallback (GE §3.3, Open question 1): if the request **with** `googleSearch` fails **before the
first event** with 400 `FAILED_PRECONDITION`, or 400/403/429 whose message mentions „search“,
„grounding“ or „Google Search“ (case-insensitive), and the error is not `API_KEY_INVALID`/402: retry
once without `tools` and with `chatSystem(false)` (no prompt that asks to search and cite); result
`withoutWebSearch = true`; remember per key hash in memory (skip search next time) until the tariff or a
Gemini key is saved (`GeminiSearchMemo.forget`, then the search is tried again). The chat shows the note „Ohne Websuche beantwortet – für die Websuche braucht
Gemini ein Projekt mit Abrechnung.“

Key check (v1.3.1, GE §13): (1) `GET {base}/models?pageSize=1000` without tokens — a refusal of the key or
project ends the test with its cause: 400 `API_KEY_INVALID`, other 401/403 → INVALID; `ACCESS_TOKEN_TYPE_UNSUPPORTED`
→ AUTH_KEY_REJECTED; "reported as leaked" → KEY_LEAKED; `API_KEY_SERVICE_BLOCKED`/`SERVICE_DISABLED` → API_BLOCKED;
`API_KEY_*_BLOCKED` → KEY_RESTRICTED; 402 → OK_NO_CREDIT; 5xx → NETWORK. (2) `POST {base}/models/{model}:generateContent`
„Antworte nur: OK“ (thinking `low`, `maxOutputTokens` 32, no tools): 200 → OK; a refused model → the next listed model
of the fallback chain (GE §12.5), reported as `KeyCheckOutcome.fallback` and stored for the key; no model → MODEL_UNAVAILABLE;
400 `FAILED_PRECONDITION` → FREE_TIER_UNAVAILABLE; 429 → OK_QUOTA_EXHAUSTED (stored with a note); any other 4xx → OK
(the tiny request was the problem, not the key). Every failed verdict carries the key-free diagnostic (GE §12.6).

Model fallback in the chat (GE §12.5): a model refused before the stream (404, a 400 in Google's words about the model id, a
403 denying access to the model, a quota of 0; never another 400 that merely mentions the model)
→ `gemini-3.8-flash` → `gemini-3.7-flash` → `gemini-3.5-flash-lite` → `gemini-3.1-flash-lite`, each once;
`ChatTurnResult.fallbackModel` → `AiProviderAccess.modelFallback` stores it for the key as chosen model
(`Resolution.Ready.selectedModel`) → working one, also when the request already ran with a fallback (dropped when a key, the
tariff or another model is saved), and the answer gets the note „<chosen> ist für deinen Key nicht freigeschaltet – die App
nutzt jetzt …“. A `ModelUnavailable` of the fallback that ran names the chosen model.

### 7.3 Chat Completions family (`ai/compat/`)

Files: `CompatDialect.kt`, `CompatRequests.kt`, `CompatStream.kt`, `CompatEngines.kt`, `CustomServer.kt`.

```kotlin
internal enum class CompatDialect(
    val webSearch: Boolean,              // OPENROUTER
    val live: Boolean,                   // OPENROUTER
    val streamOptionsUsage: Boolean,     // DEEPSEEK (Mistral must NOT get it, OC §2.5)
    val temperature: Double?,            // MISTRAL 0.3, CUSTOM 0.3
    val extraHeaders: Map<String, String>, // OPENROUTER: HTTP-Referer https://github.com/CedrickGD/Morgenbriefing, X-OpenRouter-Title Morgenbriefing
    val keyCheck: KeyCheckKind,          // MODELS (Mistral, custom), OPENROUTER_KEY, DEEPSEEK_BALANCE
) { OPENROUTER(...), MISTRAL(...), DEEPSEEK(...), CUSTOM(...) }
```

Chat request (OC §2.1): `POST {base}/chat/completions`

```json
{ "model": "<model>", "stream": true,
  "messages": [
    {"role": "system", "content": "<chatSystem(webSearch)>\n\n<context>"},
    {"role": "user", "content": "<q1>"}, {"role": "assistant", "content": "<a1>"}, {"role": "user", "content": "<q2>"}],
  "max_tokens": 4000 }
```
plus per dialect: OpenRouter `tools:[{"type":"openrouter:web_search","parameters":{"engine":"auto","max_results":5,"search_context_size":"medium","user_location":{…}}}]`,
`max_tool_calls: 3` (if a 400 names `max_tool_calls`, retry once without it and remember, OC §7.3),
`reasoning:{"effort":"low"}`, `max_tokens: 8000`; DeepSeek `thinking:{"type":"disabled"}`,
`stream_options:{"include_usage":true}`; Mistral/custom `temperature: 0.3`. Authorization header only
when a key exists (custom).

Stream (OC §2.2–2.7): `[DONE]` ends; comments skipped; chunk with top-level `error` → mid-stream error;
`choices[0].delta.content` string → text, array → `type:"text"` parts appended, `thinking` parts
ignored (Mistral); `reasoning_content` / `reasoning` ignored; `tool_calls` accumulated by `index`
(id/name first, argument fragments appended; whole-call variant also works; Mistral id `"null"`
ignored); `delta.annotations` / `message.annotations` `url_citation` → `addCited` (defensive, OC §7.5);
`finish_reason` remembered, reading continues (OpenRouter sends a final usage chunk repeating it);
`usage` = last non-null.
* `finish_reason`: `stop` → COMPLETE; `length` → TRUNCATED; `content_filter` → REFUSED; `tool_calls` →
  Live handling; `error`, `insufficient_system_resource`, `aborted` → `AiError.Overloaded`.
* OpenRouter without annotations: at the end, Markdown links `[label](https://…)` in the answer become
  sources (`addCited(url, label)`) and are replaced by `label` in `ChatTurnResult.text` (the final text
  replaces the streamed one, as today).
* Usage: `prompt_tokens`, `completion_tokens`, `prompt_tokens_details.{cached_tokens,cache_write_tokens}`
  (OpenRouter) or `prompt_cache_hit_tokens` (DeepSeek) → `inputTokens = prompt − cached − cacheWrite`;
  OpenRouter `usage.cost` → `reportedCostUsd`; `server_tool_use_details.web_search_requests` **or**
  `server_tool_use.web_search_requests` → `webSearches` (OC §7.7).

Live (OpenRouter only, OC §7.8): tools = web search server tool + `{"type":"function","function":{"name":"publish_articles","description":…,"parameters":parametersSchema(…, PORTABLE)}}`,
sent on every request (OC §7.3); `reasoning:{"effort":"medium"}`, `max_tokens: 32000`. Correction:
`messages += {"role":"assistant","content":null,"reasoning_details":[…as streamed…],"tool_calls":[…as received…]}` +
`{"role":"tool","tool_call_id":…,"content":replyText}` per call. `reasoning_details` (merged per `index`,
OC §2.4) carry Gemini 3's thought signatures and Claude's thinking blocks, without which those upstreams
answer the replayed call with 400; only OpenRouter gets the field (Mistral rejects unknown fields).
UNVERIFIED against a live key.

Key checks: Mistral `GET /v1/models`; OpenRouter `GET /api/v1/key` (200 OK, 401 INVALID); DeepSeek
`GET /user/balance` (`is_available:false` → OK_NO_CREDIT); custom §7.6.

### 7.4 Claude (existing, `ai/claude/`) changes

* `ClaudeClients` caches by `(apiKey, workspaceId)`; `create()` adds
  `.putHeader("anthropic-workspace-id", id)` when `workspaceId` is not blank (Anthropic auth docs, Java sample).
* Requests take `ProviderAuth`; `apiKey!!` is guaranteed by the resolver for Anthropic.
* Model-dependent tools: `claude-haiku-*` → `WebSearchTool20250305` / `WebFetchTool20250910`, no
  `fallbacks` (only Sonnet 5.5 / Opus 5.5 already get it); everything else unchanged.
* `ClaudeErrors` (checked **before** the generic branches):
  * `BadRequestException` message contains `anthropic-workspace-id is required` → `WorkspaceRequired`;
    contains `must be a valid workspace ID` → `WorkspaceNotFound`.
  * `NotFoundException` message matches `Workspace .* not found` → `WorkspaceNotFound`; otherwise
    `ModelUnavailable(model)` (replaces the current `Unknown` text).
  * billing / credit balance → `NoCredit` (replaces `Unknown(BILLING)`).
  * Strings: „Claude“ in texts now comes from `AiTexts` with the brand.
* `ClaudeKeyChecker`: the same workspace checks first → `WORKSPACE_REQUIRED` / `WORKSPACE_INVALID`
  (**bug fix**: today a workspace 400 is reported as „OK“); billing/credit 400/402 → `OK_NO_CREDIT`;
  other 400 → OK (as today). Uses the provider's chosen model.
* `AiPricing.SONNET_5_5.cacheRead` is wrong today (0.20): Sonnet 5.5 cache hits cost **0.10 $/MTok**
  (0.05× input, platform.claude.com pricing, fetched 2026-10-09). Fixed in the new price table.

### 7.5 Engine summary matrix

| Provider | Endpoint | Auth header | Search tool | Function tool (Live) | Cost source |
|---|---|---|---|---|---|
| Anthropic | SDK `messages` | `x-api-key` (SDK) + optional `anthropic-workspace-id` | `web_search_20260209` / `web_fetch_20260209` (Haiku: 20250305/20250910) | `publish_articles` strict, FULL | price table |
| OpenAI | `POST /v1/responses` | `Authorization: Bearer` | `web_search` | flat function, strict, PORTABLE | price table |
| xAI | `POST /v1/responses` | Bearer | `web_search` | flat function, strict | `cost_in_usd_ticks` |
| Perplexity | `POST /v1/agent` | Bearer | `web_search` | flat function, strict | `cost.total_cost` |
| Gemini | `POST /v1beta/models/{m}:streamGenerateContent?alt=sse` | `x-goog-api-key` | `googleSearch` | – | price table |
| OpenRouter | `POST /api/v1/chat/completions` | Bearer + attribution headers | `openrouter:web_search` | nested function, non-strict | `usage.cost` |
| Mistral | `POST /v1/chat/completions` | Bearer | – | – | price table |
| DeepSeek | `POST /chat/completions` | Bearer | – | – | price table (peak) |
| Custom | `POST {base}/chat/completions` | Bearer if key | – | – | 0 $ |

### 7.6 Custom server

* `CustomServer.normalize(raw)`: trim, drop trailing `/`; no scheme → error; empty path → append `/v1`
  (`http://nas.fritz.box:11434` → `http://nas.fritz.box:11434/v1`); other paths kept.
* `CustomServer.issue(url): CustomUrlIssue?` — `NO_SCHEME`, `NOT_HTTP`, `CLEARTEXT_HOST` (http with a
  host outside `CLEARTEXT_HOSTS`), `BLANK`. `CLEARTEXT_HOSTS` mirrors the NSC allowlist; a unit test
  parses `src/main/res/xml/network_security_config.xml` and asserts both lists are equal.
* `network_security_config.xml` gets:
  ```xml
  <!-- "Eigener Server" (docs/AI-PROVIDERS.md D10): plain http only for local names. -->
  <domain-config cleartextTrafficPermitted="true">
      <domain includeSubdomains="false">localhost</domain>
      <domain includeSubdomains="false">10.0.2.2</domain>
      <domain includeSubdomains="true">local</domain>
      <domain includeSubdomains="true">lan</domain>
      <domain includeSubdomains="true">home.arpa</domain>
      <domain includeSubdomains="true">fritz.box</domain>
      <domain includeSubdomains="true">internal</domain>
  </domain-config>
  ```
  (domain-configs inherit the base trust anchors incl. ISRG roots.)
* Key check = connection test: `GET {base}/models` (Bearer if key) → 200 with `data[]` → OK +
  `serverModels = data[].id`; 401/403 → INVALID; `ConnectException`/`UnknownHostException`/connect
  timeout → SERVER_UNREACHABLE; `UnknownServiceException` (cleartext refused) → CLEARTEXT_BLOCKED;
  any `SSLException` (a self-signed certificate, or plain http behind an https:// address) →
  SERVER_CERTIFICATE with the hint about certificates (the app trusts only system CAs + ISRG; „Ohne
  Test speichern“ is not offered, every request would fail the same way); 404 / non-JSON →
  NOT_AN_AI_SERVER.
* Runtime: IO errors on connect → `AiError.ServerUnreachable`; a failed TLS setup
  (`SSLHandshakeException`, `SSLPeerUnverifiedException`, `SSLProtocolException`) →
  `AiError.Unknown(ProviderErrors.CERTIFICATE_TEXT)` with the same hint; cost always 0 $.

---

## 8. Error mapping

UI texts (`AiTexts.error(error, spec)`; `brand` = `spec.brand`):

| AiError | Text | Retry button |
|---|---|---|
| InvalidKey | `spec.invalidKeyText` ?: „$brand hat deinen API-Key abgelehnt. Prüf ihn bitte oder trag einen neuen ein.“ | no |
| NoCredit | „Dein Guthaben bei $brand ist aufgebraucht. Lade es in der Konsole auf, dann geht es weiter.“ | no |
| RateLimited | „Gerade kommen zu viele Anfragen auf einmal an. Versuch es in einer Minute noch mal.“ | yes |
| Overloaded | „$brand ist im Moment überlastet. Versuch es gleich noch einmal.“ | yes |
| Network | „Keine Verbindung zu $brand. Prüf dein Internet und versuch es noch mal.“ | yes |
| ServerUnreachable | „Dein Server antwortet nicht. Läuft er, und ist dein Handy im selben Netz?“ | yes |
| WorkspaceRequired | „Dein Claude-Key gehört zu keinem Workspace. Trag in den Einstellungen unter „Erweitert“ die Workspace-ID ein – oder erstell einen Key für den „Default Workspace“.“ | no |
| WorkspaceNotFound | „Diesen Workspace findet Anthropic nicht. Prüf die Workspace-ID in den Einstellungen.“ | no |
| ModelUnavailable(m) | „Das Modell „$m“ gibt es bei $brand nicht (mehr), oder dein Key darf es nicht nutzen. Wähl in den Einstellungen ein anderes.“ | no |
| GeminiAccess(problem) | `AiTexts.gemini(problem)` (GE §12.4): AUTH_KEY_REJECTED, KEY_LEAKED („Key ändern“), API_BLOCKED, KEY_RESTRICTED, FREE_TIER_UNAVAILABLE — each with its link (`AiTexts.errorLink`: AI Studio keys/projects, Cloud Console library/credentials) | no |
| Refusal / Truncated / Unknown | as today, provider-neutral | as today |

Every failed answer keeps `ChatMessage.errorDetail` (stored with the history): the key-free diagnostic line of
`ErrorDiagnostics` (GE §12.6), shown collapsed under „Details“ in the error card, selectable and with „Kopieren“. The key
dialog shows the same for a failed test (`AiKeyState.Failed.diagnostic`) plus the link that fixes it (`aiKeyFixLink`).

`liveErrorText` in `ui/today/LiveModels.kt` follows the same table (Live wording, brand instead of „Claude“).
`AiError.InvalidKey` from any request — and a Gemini auth key Google does not take or blocked as leaked
(`AiError.rejectsKey`) — → `keyRejected(provider, key)` (as today, now per provider). A key
test („Speichern & testen“) that ends in `INVALID` (or AUTH_KEY_REJECTED / KEY_LEAKED) for the stored setup marks the stored key the same way
(`keyTestRejectsStoredKey`; `markAiKeyRejected` only acts when the tested key is the stored one).

### 8.1 HTTP status → AiError

| Status / signal | Anthropic | OpenAI | xAI | Perplexity | Gemini | OpenRouter | Mistral | DeepSeek | Custom |
|---|---|---|---|---|---|---|---|---|---|
| 400 | workspace msgs → Workspace*; credit → NoCredit; else Unknown | `model` + „does not exist“ → ModelUnavailable; else Unknown | message names the API key („Incorrect API key provided“, OC §4.8) → InvalidKey; else Unknown | Unknown | `API_KEY_INVALID` → InvalidKey; `FAILED_PRECONDITION` → GeminiAccess(FREE_TIER_UNAVAILABLE); „is not found for API version“ / „is not supported for generateContent“ / „unexpected model name format“ → ModelUnavailable (fallback, GE §12.5); else Unknown (also a request error that mentions the model) | Unknown | Unknown | Unknown (`reasoning_content` 400 can't happen: thinking off) | Unknown |
| 401 | InvalidKey | InvalidKey | InvalidKey | InvalidKey (text: or no credit) | `ACCESS_TOKEN_TYPE_UNSUPPORTED` → GeminiAccess(AUTH_KEY_REJECTED); `API_KEY_SERVICE_BLOCKED` → GeminiAccess(API_BLOCKED); else InvalidKey | InvalidKey | InvalidKey | InvalidKey | InvalidKey |
| 402 | NoCredit | – | – | – | NoCredit | with `Retry-After` → RateLimited (retry); else NoCredit | NoCredit (no payment method, OC §3.8) | NoCredit | – |
| 403 | Unknown(„kein Zugriff auf dieses Modell“) | Unknown(„OpenAI ist in deiner Region nicht verfügbar.“) | Unknown(„Kein Zugriff – Team gesperrt oder Guthaben leer? Schau in console.x.ai nach.“) | Unknown | „reported as leaked“ → GeminiAccess(KEY_LEAKED); `API_KEY_SERVICE_BLOCKED`/`SERVICE_DISABLED` → GeminiAccess(API_BLOCKED); `API_KEY_*_BLOCKED` → GeminiAccess(KEY_RESTRICTED); message denying access to the model („does not have access to model …“) → ModelUnavailable (fallback); else Unknown(„Der Key darf die Gemini API nicht nutzen.“) | Unknown(„OpenRouter hat die Anfrage abgelehnt (Moderation oder Rechte).“) | Unknown | – | InvalidKey |
| 404 | `Workspace … not found` → WorkspaceNotFound; else ModelUnavailable | ModelUnavailable | ModelUnavailable | ModelUnavailable | ModelUnavailable (fallback first, GE §12.5) | ModelUnavailable | ModelUnavailable | ModelUnavailable | ModelUnavailable |
| 408 | Overloaded (r) | Overloaded (r) | Overloaded (r) | Overloaded (r) | Overloaded (r) | Overloaded (r) | Overloaded (r) | Overloaded (r) | Overloaded (r) |
| 422 | – | – | Unknown | – | – | – | Unknown | Unknown | Unknown |
| 429 | RateLimited (r) | code `credit_balance_exhausted`/`insufficient_quota` → NoCredit; `*_spend_limit_exceeded`/`organization_usage_limit_exceeded` → Unknown(„Das Ausgabenlimit deines OpenAI-Projekts ist erreicht.“); else RateLimited (r) | RateLimited (r) | RateLimited (r) | quota of 0 → ModelUnavailable (fallback, GE §12.5); free tier → QuotaExhausted (r only for RetryInfo ≤ 20 s); else RateLimited (r) | RateLimited (r) | RateLimited (r) | RateLimited (r) | RateLimited (r) |
| 5xx, 529 | Overloaded (r) | Overloaded (r) | Overloaded (r) | Overloaded (r) | Overloaded (r) | Overloaded (r) | Overloaded (r) | Overloaded (r) | Overloaded (r) |
| IO before response | Network (r) | Network (r) | Network (r) | Network (r) | Network (r) | Network (r) | Network (r) | Network (r) | ServerUnreachable (r) |

(r) = retryable before the first event. Error bodies: OpenAI/xAI/OpenRouter/Perplexity/Mistral/DeepSeek
`{"error":{"message","type","code","param"}}` (Mistral also flat `{"object":"error",…}`); Gemini
google.rpc `{"error":{"code","status","message","details":[{"@type":"…ErrorInfo","reason"}]}}`, object
**or array** (GE §12.1). Parse leniently; never show raw messages except through the table.

### 8.2 Mid-stream errors (HTTP 200)

| Family | Signal | Mapping |
|---|---|---|
| Responses | `response.failed.response.error.code` / `error.code` | `server_error` → Overloaded; `rate_limit_exceeded` → RateLimited; `invalid_prompt`, `bio_policy`, `misalignment_policy_violation` → Refusal(code); others → Unknown |
| Responses | `response.incomplete` | §7.1 |
| Gemini | chunk `error` | as 8.1 by `error.code`/`status` |
| Gemini | `finishReason` / `promptFeedback.blockReason` | §7.2 |
| Compat | chunk `error` (OpenRouter) | by `error.code` like 8.1 (`metadata.error_type` ignored) |
| Compat | `finish_reason` `error` / `insufficient_system_resource` / `aborted` | Overloaded |

A mid-stream error after text was shown keeps the text (as Claude does today: FAILED with partial text).
This does **not** hold for Gemini text grounded in Google Search (`ChatTurnListener.onGrounded`: the
first query or grounding source arrived) whose Search Suggestions have not come yet — they come with
the last chunk, and grounded results may only be shown with them (GE §11): on an error or the monthly
limit the answer keeps neither text nor sources (only the error and „Nochmal“), and Stop removes it
like an empty one.

---

## 9. Pricing

`ai/AiPricing.kt` becomes a table (keep the object name `AiPricing`; `TokenUsage` stays there):

```kotlin
internal object AiPricing {
    const val AS_OF = "2026-10-09"
    data class Rates(val input: Double, val output: Double, val cacheWrite: Double, val cacheRead: Double,
                     val perSearchUsd: Double = 0.0, val longContext: LongContext? = null)
    /** Above [thresholdInputTokens] (uncached + cache read + cache write) the whole request uses [rates]. */
    data class LongContext(val thresholdInputTokens: Long, val rates: Rates)
    data class Entry(val provider: AiProviderId, val modelPrefix: String, val rates: Rates,
                     val validFrom: LocalDate? = null, val source: String)
    data class PricedCost(val usd: Double, val estimated: Boolean)

    /** Longest matching prefix of [provider], latest validFrom ≤ [on]; unknown → provider fallback, estimated. */
    fun ratesFor(provider: AiProviderId, modelId: String, on: LocalDate): Pair<Rates, Boolean>
    fun costUsd(provider: AiProviderId, modelId: String, usage: TokenUsage, on: LocalDate): PricedCost
}
```

Rules: `reportedCostUsd != null` → that value, `estimated = false`. CUSTOM → 0 $, not estimated. Model
ids are matched lowercased; for OpenRouter/Perplexity ids including the `vendor/` prefix. Cost =
`(input·in + cacheWrite·cw + cacheRead·cr + output·out) / 1e6 + webSearches·perSearchUsd`.

Price table (USD per 1M tokens; cw = cache write, cr = cache read; search = per call/query):

| Provider | Prefix | in | out | cw | cr | search | Notes / source |
|---|---|---|---|---|---|---|---|
| Anthropic | `claude-sonnet-5-5` | 2.00 | 10.00 | 2.50 | **0.10** | 0.01 | platform.claude.com pricing 2026-10-09 |
| | `claude-opus-5-5` | 4.00 | 20.00 | 5.00 | 0.20 | 0.01 | |
| | `claude-haiku-5-5` | 0.10 | 0.50 | 0.125 | 0.01 | 0.01 | long context > 100,000: 0.50 / 2.50 / 0.625 / 0.05 |
| | `claude-sonnet-5` | 2.00 | 10.00 | 2.50 | 0.20 | 0.01 | fallback target |
| | `claude-opus-5` | 5.00 | 25.00 | 6.25 | 0.50 | 0.01 | fallback target; **provider fallback** |
| OpenAI | `gpt-6.1-sol` | 2.00 | 10.00 | 2.50 | 0.10 | 0.01 | OA §3; long context > 272,000: input/cw/cr ×2, out ×1.5 (all OpenAI rows) |
| | `gpt-6-luna` | 0.10 | 0.50 | 0.125 | 0.01 | 0.01 | |
| | `gpt-6-astra` | 10.00 | 50.00 | 12.50 | 1.00 | 0.01 | **provider fallback** |
| | `gpt-6-sol` | 2.00 | 10.00 | 2.50 | 0.20 | 0.01 | |
| | `gpt-5.6-sol` / `gpt-5.6` | 4.00 | 20.00 | 5.00 | 0.40 | 0.01 | promo price, conservative |
| | `gpt-5.6-terra` | 2.00 | 12.00 | 2.50 | 0.20 | 0.01 | |
| | `gpt-5.6-luna` | 0.20 | 1.20 | 0.25 | 0.02 | 0.01 | |
| | `gpt-5.5` | 5.00 | 30.00 | 5.00 | 0.50 | 0.01 | cw not listed → input |
| Gemini | `gemini-3.8-flash`, `gemini-3.7-flash`, `gemini-3.6-flash` | 0.75 | 3.75 | 0.75 | 0.075 | 0.014 | validFrom –; **validFrom 2027-01-01: 1.50 / 7.50 / 1.50 / 0.15** (GE §3) |
| | `gemini-3.5-flash` | 1.50 | 9.00 | 1.50 | 0.15 | 0.014 | |
| | `gemini-3.5-flash-lite` | 0.30 | 2.50 | 0.30 | 0.03 | 0.014 | |
| | `gemini-3.1-flash-lite` | 0.25 | 1.50 | 0.25 | 0.025 | 0.014 | |
| | `gemini-3.1-pro` | 2.00 | 12.00 | 2.00 | 0.20 | 0.014 | long context > 200,000: 4.00 / 18.00 / 4.00 / 0.40; **provider fallback** |
| | `gemini-3-flash` | 0.50 | 3.00 | 0.50 | 0.05 | 0.014 | |
| xAI | `grok-4.7`, `grok-4.6`, `grok-4.5` | 2.00 | 6.00 | 2.00 | 0.50 | 0.005 | long context ≥ 200,000: ×2 (OC §4.2); **provider fallback** |
| | `grok-4.3`, `grok-4.20` | 1.25 | 2.50 | 1.25 | 0.20 | 0.005 | ≥ 200,000: ×2 |
| Perplexity | `google/gemini-3.8-flash` | 0.75 | 3.75 | 0.75 | 0.075 | 0.0025 | only if `cost` is missing (OC §6.2) |
| | `perplexity/sonar` | 0.25 | 2.50 | 0.25 | 0.0625 | 0.0025 | |
| | `anthropic/claude-sonnet-5-5` | 2.00 | 10.00 | 2.50 | 0.20 | 0.0025 | |
| | `anthropic/claude-opus-5-5` | 4.00 | 20.00 | 5.00 | 0.20 | 0.0025 | **provider fallback** |
| | `openai/gpt-6-luna` | 0.10 | 0.50 | 0.10 | 0.01 | 0.0025 | |
| OpenRouter | `google/gemini-3.8-flash` | 0.75 | 3.75 | 0.75 | 0.075 | 0.014 | only if `usage.cost` is missing (OC §7.2) |
| | `openai/gpt-6-luna` | 0.10 | 0.50 | 0.10 | 0.01 | 0.01 | |
| | `openai/gpt-6.1-sol` | 2.00 | 10.00 | 2.00 | 0.10 | 0.01 | |
| | `anthropic/claude-sonnet-5.5` | 2.00 | 10.00 | 2.50 | 0.10 | 0.01 | |
| | `anthropic/claude-opus-5.5` | 4.00 | 20.00 | 5.00 | 0.20 | 0.01 | **provider fallback** |
| | `anthropic/claude-haiku-5.5` | 0.10 | 0.50 | 0.125 | 0.01 | 0.01 | |
| | `x-ai/grok-4.7` | 2.00 | 6.00 | 2.00 | 0.50 | 0.005 | |
| | `deepseek/deepseek-v4.1-flash` | 0.30 | 1.20 | 0.30 | 0.006 | 0.007 | Exa |
| | `mistralai/mistral-small-2603` | 0.15 | 0.60 | 0.15 | 0.015 | 0.007 | Exa |
| Mistral | `mistral-medium-3-5`, `mistral-medium-3`, `mistral-medium-latest` | 1.50 | 7.50 | 1.50 | 0.15 | – | OC §3.2 |
| | `mistral-small-2603`, `mistral-small-latest` | 0.15 | 0.60 | 0.15 | 0.015 | – | |
| | `mistral-large-2512`, `mistral-large-latest` | 0.50 | 1.50 | 0.50 | 0.05 | – | |
| | `mistral-large-4` | 1.36 | 4.18 | 1.36 | 0.14 | – | list price (sale ignored); **provider fallback** |
| | `ministral-8b` | 0.15 | 0.15 | 0.15 | 0.015 | – | |
| DeepSeek | `deepseek-flash`, `deepseek-v4-flash` | 0.30 | 1.20 | 0.30 | 0.006 | – | peak prices always (conservative, OC §5.1) |
| | `deepseek-v4-pro` | 1.32 | 3.96 | 1.32 | 0.044 | – | **provider fallback** |

OpenRouter/Perplexity unknown models without reported cost: global fallback **10 / 50 / 12.50 / 1.00,
search 0.014**, estimated.

`UsageStore.record(provider, model, usage)`: price with `AiPricing.costUsd(..., LocalDate.now(clock))`;
add key `estimated_requests` (int, reset per month like the others) incremented when `estimated`;
`snapshot()` fills `UsageSnapshot.estimatedRequests`. Searches count `usage.webSearches` for every provider.

---

## 10. UI

Morgenglas rules (DESIGN.md) apply: GlassCard, `lamina()`, serif titles, accent ring for selection,
≥ 48 dp targets, content descriptions, reduce-motion.

### 10.1 Settings → KI (`AiSection.kt`)

```
SectionHeading("Dein Erklärer", "KI")                      (was "Claude", "KI")
┌ GlassCard ───────────────────────────────────────────────┐
│ ✨  KI · DEIN ERKLÄRER                                    │
│     Frag nach & ⚡ Live  (serif)                           │
│ Frag zu jedem Artikel nach und hol dir brandaktuelle       │
│ Meldungen – mit deinem eigenen Key bei einem Anbieter      │
│ deiner Wahl.                                               │
│                                                            │
│ ANBIETER                                                   │
│ ┌ lamina(strong) ─────────────────────────────────────┐   │
│ │ OpenAI (serif)                         [Wechseln]   │   │
│ │ GPT-Modelle mit Websuche – vielseitig und schnell.  │   │
│ │ (Chat ✓) (Websuche ✓) (Live ✓) (Android 7 ✓)       │   │
│ └─────────────────────────────────────────────────────┘   │
│ ┌ lamina ─────────────────────────────────────────────┐   │
│ │ 🔑 API-Key                                 [Ändern]  │   │
│ │    Gespeichert ✓ · verschlüsselt                     │   │
│ └─────────────────────────────────────────────────────┘   │
│   (custom: 🖥 Server · nas.fritz.box:11434  [Ändern])      │
│                                                            │
│ MODELL                                                     │
│ (•) GPT-6.1 Sol  [Standard]   Ausgewogen · 2 $ / 10 $     │
│ ( ) GPT-6 Luna                Sehr günstig · 0,10 $ / …   │
│ ( ) GPT-6 Astra               Spitzenmodell …             │
│ ( ) Eigenes Modell …          z. B. gpt-5.6-terra          │
│                                                            │
│ ⚡ LIVE                                                     │
│ Live über OpenAI (wie Chat)                 [Ändern]       │
│   – or, chat provider without Live (amber text):           │
│ Live braucht Websuche – mit Mistral nicht verfügbar.       │
│ [Anbieter für Live wählen]                                 │
│                                                            │
│ MONATSLIMIT  5 $ im Monat   ───●──────── (slider)          │
│ Gilt für alle Anbieter zusammen. Ist es erreicht, …        │
│ ┌ Diesen Monat 0,84 $ von 5 $ ▬▬▬───── 23 Anfragen · 7 … │
│ │ Preis teils geschätzt (unbekanntes Modell)   (if any)   │
│ Live automatisch aktualisieren            [switch]         │
└────────────────────────────────────────────────────────────┘
```

* Capability chips: tiny pills (labelSmall), ✓ in `StatusPalette.ok()`, ✗ in `onSurfaceVariant`;
  semantics „Chat verfügbar, Live nicht verfügbar, läuft auf Android 7“.
* Price line in model subtitles from `AiPricing.ratesFor(...)`: „2 $ / 10 $ je 1 Mio. Tokens“; free
  text and CUSTOM: „Preis geschätzt“ / „kostenlos“.
* The old Android-7-only explanation block is removed; on Android 7 the Claude card shows the chip
  „ab Android 8“ and the summary row says „Claude braucht Android 8 – wähl einen anderen Anbieter.“

### 10.2 Provider picker (`AiProviderPicker.kt`, new)

`GlassDialog(title = "KI-Anbieter wählen")`, scrollable `selectableGroup` of `ProviderCard`s in catalog
order: radio dot, name (serif titleMedium), tagline, capability chips, status line („Key gespeichert ✓“,
„Kein Key“, „Server eingerichtet“). Not supported on the device → alpha 0.5, not selectable, chip
„ab Android 8“. Selecting a configured provider sets it active and closes; selecting one without
key/server sets it active and opens its key dialog right away. A `liveOnly` mode (for „Anbieter für Live
wählen“) lists only `live == YES` providers that run on the device, plus „Wie Chat“ when the chat
provider supports Live; selecting one without key opens its key dialog.

### 10.3 Key dialog per provider (`AiKeyDialog(spec, …)`)

* Title „${spec.name}-Key eintragen“ / „… ändern“ (custom: „Eigenen Server einrichten“).
* Intro „Der Key gehört dir: Anfragen laufen über dein eigenes ${spec.name}-Konto und werden dort abgerechnet.“
* Overline „So bekommst du den Key“ + `Steps(spec.keySteps)`, tip lamina (`spec.keyTip`), pill
  `spec.consoleLabel` → `links.open(spec.consoleUrl)`.
* Password field (placeholder `spec.keyPlaceholder`; custom: label „API-Key (optional)“); hint from
  `aiKeyFormatIssue(raw, spec)` (prefix check only when `keyPrefixes` is non-empty; whitespace always).
* Anthropic: expandable „Erweitert: Workspace-ID“ (collapsed by default; auto-expanded after
  `WORKSPACE_REQUIRED`/`WORKSPACE_INVALID` or when an id is stored). Field placeholder `wrkspc_…`, help
  text „Nur nötig, wenn dein Key keinem Workspace zugeordnet ist. Die ID steht in der Konsole unter
  Settings → Workspaces (Spalte „ID“).“ + pill „Workspaces öffnen“ → `https://platform.claude.com/settings/workspaces`.
  Soft hint when it does not start with `wrkspc_`. Saving only a new workspace id with a stored key is
  allowed (key field blank → stored key is tested).
* Custom: field „Server-Adresse“ (placeholder `http://nas.fritz.box:11434/v1`), live validation from
  `CustomServer.issue`, button „Verbindung testen & speichern“.
* Messages (`aiKeyMessage(state, spec)`):

  | State | Text |
  |---|---|
  | Checking | „Prüfe den Key bei ${spec.name} …“ (custom: „Verbinde mit deinem Server …“) |
  | Saved | „✓ Der Key funktioniert und ist gespeichert.“ (custom: „✓ Verbunden – N Modelle gefunden.“) |
  | SavedNoCredit | „Gespeichert ✓ – aber dein Guthaben ist leer. Lade es auf, sonst schlagen Anfragen fehl.“ |
  | SavedUntested | as today |
  | INVALID | `spec.invalidKeyText` ?: „✗ ${spec.name} lehnt den Key ab – hast du ihn vollständig kopiert?“ |
  | NETWORK | „✗ Keine Verbindung zu ${spec.name} – bist du online?“ |
  | WORKSPACE_REQUIRED | „✗ Dieser Key gehört zu keinem Workspace. Trag unten die Workspace-ID ein – oder erstell den Key mit Workspace „Default Workspace“.“ |
  | WORKSPACE_INVALID | „✗ Diese Workspace-ID kennt Anthropic nicht (oder dein Key darf ihn nicht nutzen).“ |
  | SERVER_UNREACHABLE | „✗ Dein Server antwortet nicht. Stimmt die Adresse, und läuft er im selben Netz?“ |
  | SERVER_CERTIFICATE | „✗ Dein Server antwortet, aber nicht mit einem HTTPS-Zertifikat, dem die App traut (selbst signiert – oder kann er nur http?). Nimm http:// mit einem Namen wie nas.fritz.box oder ein Let's-Encrypt-Zertifikat.“ |
  | CLEARTEXT_BLOCKED | „✗ http:// geht nur mit Namen wie nas.fritz.box oder nas.local – nicht mit einer IP-Adresse. Nimm den Namen oder https://.“ |
  | NOT_AN_AI_SERVER | „✗ Unter dieser Adresse läuft kein OpenAI-kompatibler Server. Endet sie auf /v1?“ |
  | UNSUPPORTED | „✗ ${spec.name} läuft erst ab Android 8.“ |
  | AUTH_KEY_REJECTED, KEY_LEAKED, API_BLOCKED, KEY_RESTRICTED, FREE_TIER_UNAVAILABLE (Gemini, v1.3.1) | „✗ “ + `AiTexts.gemini(problem)` (GE §12.4), a pill with the page that fixes it (`aiKeyFixLink`) and „Details“ with the diagnostic; API_BLOCKED and FREE_TIER_UNAVAILABLE also offer „Ohne Test speichern“ (the key works once the project is fixed) |
  | MODEL_UNAVAILABLE | „✗ Der Key funktioniert, aber keines der Gemini-Modelle der App ist für ihn freigeschaltet. …“ (+ „Ohne Test speichern“) |
  | SavedWithFallback | „✓ Der Key funktioniert und ist gespeichert. Gemini 3.8 Flash ist für deinen Key nicht freigeschaltet – die App nutzt jetzt Gemini 3.7 Flash.“ |
  | SavedQuotaExhausted | „Gespeichert ✓ – aber das Kontingent deines Keys ist gerade aufgebraucht. In ein paar Minuten bzw. morgen geht's weiter.“ (+ „Details“) |

* A pasted key is cleaned before it is tested or stored (`cleanApiKey`): spaces, line breaks and quotes around it and
  invisible characters anywhere are dropped; Gemini keys are accepted as `AIza…` and `AQ.…` without a length check.

* `AiKeyEditor.saveAndTest(provider, rawKey: String?, extras: KeyExtras, skipTest)`: test with the
  candidate config, then `setAiKey(provider, …)`, `setAnthropicWorkspaceId`/`setCustomBaseUrl`;
  `OK_NO_CREDIT` stores the key as tested. „Ohne Test speichern“ as today (NETWORK / SERVER_UNREACHABLE).
* `RemoveAiKeyDialog(spec)`: „Danach sind „Frag nach“ und „⚡ Live“ mit ${spec.name} aus, bis du wieder
  einen Key einträgst. Dein bisheriger Verbrauch bleibt sichtbar.“

### 10.4 Model picker

Curated `ModelOption`s of the active provider + „Eigenes Modell …“ → small `GlassDialog` with a text
field („Modell-ID, genau wie beim Anbieter, z. B. gpt-5.6-terra“), hint „Preis geschätzt – dieses
Modell kennt die App nicht.“ when `AiPricing.ratesFor` is a fallback. With a free Gemini key a typed
Pro id (`ProviderCatalog.requiresBilling`: any id with `pro` as one dash-separated part, e.g.
`gemini-3.1-pro-preview-customtools`, `gemini-pro-latest`) is refused like the curated Pro model: the
dialog says „Gemini Pro gibt es nur mit Abrechnung – im kostenlosen Tarif nicht wählbar.“, and a stored
one shows „nur mit Abrechnung“ while the default runs. Custom server: chips with
`serverModels` (from the connection test or a „Vom Server laden“ pill → `AiServices.listServerModels`).
`setAiModel(provider, id)` writes `ai_<p>_model`.

### 10.5 Status hero (`StatusLogic.statusItems`)

Signature: `statusItems(system, source, ai: AiSettings, aiProviderSupported: Boolean)`. KI pill:
neutral „aus – kein Key für ${brand}“ / amber „Key von ${brand} abgelehnt“ / neutral „Key noch nicht
getestet“ / ok „eingerichtet · ${brand}“; device-unsupported provider → neutral „${brand} braucht Android 8“.
The pill is shown on every API level now.

### 10.6 Chat and Live

* Remove all `AiSupport.isSupported` gates (`ArticleScreen.askEnabled`, `TodayScreen` ask/key dialog,
  `TodayViewModel.liveService`, `ArticleViewModel.liveArticles`, `AiKeyViewModel`, `SettingsViewModel`):
  `app.ai` is safe on every API level; blocks explain the rest. `LiveUi.Unsupported` is deleted.
* `AiTexts.blockTitle/blockBody(block, spec)`:
  * NO_KEY: „Noch kein ${spec.name}-Key hinterlegt“ / „Trag einmal deinen Key ein – dann kannst du hier
    nachfragen und brandaktuelle Meldungen abrufen.“ (custom: „Noch kein Server eingerichtet“)
  * UNSUPPORTED_DEVICE: „Claude braucht Android 8“ / „Wähl in den Einstellungen einen anderen
    KI-Anbieter, zum Beispiel OpenAI oder Gemini – die laufen auch hier.“ Button „Anbieter wählen“.
  * LIVE_UNAVAILABLE (Live only): `spec.liveUnavailableText` + button „Anbieter für Live wählen“ →
    settings KI section with the picker in `liveOnly` mode.
  * LIMIT_REACHED: as today.
* `liveUi(state, articles, blocked: AiBlock?, autoRefresh)` replaces the `hasKey` parameter; the VM
  computes `blocked` with `aiBlock(settings.ai, LIVE, …)`.
* `InlineAiKeyDialog(provider: AiProviderId)`: opened for the provider that is blocked (chat provider,
  or live provider).
* Gemini answers: `SearchSuggestions(html)` under the answer bubble (`ui/chat/SearchSuggestions.kt`):
  `AndroidView(WebView)`, transparent background, JavaScript off, file/content access off,
  `loadDataWithBaseURL("https://www.google.com", html, "text/html", "utf-8", null)` only when the html
  changed, every link tap → `links.open(url)` (Custom Tab; both `shouldOverrideUrlLoading` overloads for
  API 24). Full answer width, shown whenever the answer is shown (also from stored history), never
  restyled (GE §6.4). Small caption above it is **not** added (no interstitial content).
* `ChatMessage.note` (e.g. „Ohne Websuche beantwortet …“) as a quiet `bodySmall` line under the answer.
* Every answer stores its author (`ChatMessage.provider`, `StoredChatMessage.provider` = `AiProviderId.key`,
  set from the resolved provider of its turn). The history survives a provider switch (D12), so the mark
  („✦ Gemini“), the error texts, the copy label and „Key ändern“ (→ that provider's key dialog) follow
  the answer, not the current chat provider; answers saved before v1.3.0 fall back to the current one.

---

## 11. Test plan

All JVM unit tests (`app/src/test`), MockWebServer for HTTP, no real keys (`Sse.TEST_KEY` style).

| Area | Test file(s) | Cases |
|---|---|---|
| Migration | `data/AiProvidersMigrationTest.kt` | legacy key + untested + rejected → Anthropic slot; `SONNET`/`OPUS`/garbage model; no legacy data (fresh); idempotent (second run no change); existing new key not overwritten; legacy keys removed; schema 2; limit/live-auto untouched |
| Settings mapping | `data/AiSettingsPrefsTest.kt` | `readAiSettings`: flags only with key; unknown provider key → Anthropic; custom configured only with base URL; write of models/live provider |
| Catalog | `ai/ProviderCatalogTest.kt` | every provider: default model listed (except custom), https console URL, non-empty steps; `live == YES` ⇔ backend live non-null; only Anthropic `needsSdk` |
| Blocks | `ai/AiBlockTest.kt` | order of checks; separate Live provider; LIVE_UNAVAILABLE for Gemini/Mistral/DeepSeek/custom; UNSUPPORTED_DEVICE only for Anthropic; custom without key but with URL = configured |
| Pricing | `ai/AiPricingTest.kt` (rewrite) | each curated model; Sonnet 5.5 cache read 0.10; longest prefix (`gemini-3.5-flash-lite` vs `-flash`, `claude-sonnet-5-5` vs `-5`); Gemini 2026-12-31 vs 2027-01-01; long context OpenAI 272k, Haiku 100k, Gemini Pro 200k, xAI 200k; reported cost wins; custom 0; unknown → fallback + estimated |
| Usage | `ai/UsageStoreTest.kt` | provider param; `estimated_requests` counted and reset on month change |
| SSE | `ai/http/SseReaderTest.kt` | event+data, multi-line data, comments, CRLF, `[DONE]`, EOF flush, error body on 400 with `text/event-stream`, cancellation cancels the call, retry before first event (Retry-After), no retry after first event |
| Errors | `ai/http/ProviderErrorsTest.kt` | table §8.1 row by row (synthetic bodies incl. Gemini array form, OpenAI 429 codes, OpenRouter 402 ± Retry-After) |
| Responses | `ai/openai/ResponsesChatEngineTest.kt`, `ResponsesLiveEngineTest.kt`, `ResponsesFixtures.kt` | OpenAI chat: text + citations + sources from `action.sources` + usage (uncached input math) + search count; commentary phase hidden; request body (store false, developer context, phase, user_location DE, include, no temperature); incomplete → TRUNCATED; `response.failed`/`error`; 401/429 credit/429 slow_down retry/503 retry; Live: function call → validate → accepted ends after 1 request; rejected → 2nd request contains every output item + `function_call_output`; nudge when no call; xAI: call only in `output_item.done`, cost ticks, `no_inline_citations`; Perplexity: `/agent`, `max_steps`, `search_results` → sources, marker stripping across chunks, `cost.total_cost` |
| Gemini | `ai/gemini/GeminiChatEngineTest.kt`, `GeminiFixtures.kt` | text parts, thought parts skipped, grounding chunks appended across chunks, distinct queries, suggestions html, finish reasons, prompt block, chunk error, EOF without finish → Network, 400 `API_KEY_INVALID` (event-stream content type), 401 `AQ.` style, array error body, free-tier fallback (second request without tools, flag set, memo), header `x-goog-api-key` and no `key=` in URL, no temperature in body |
| Compat | `ai/compat/CompatChatEngineTest.kt`, `CompatLiveEngineTest.kt`, `CompatFixtures.kt` | OpenRouter comments, final usage chunk not a 2nd end, `usage.cost`, both server-tool usage names, mid-stream error chunk, annotations, Markdown-link sources + cleaned text, attribution headers, `max_tool_calls` 400 → retry without; Live: fragmented tool-call args by index, whole call, correction messages; Mistral content array with thinking chunk, no `stream_options`; DeepSeek thinking disabled + `stream_options`, usage on last chunk, `insufficient_system_resource` → Overloaded; custom: no Authorization without key, base URL normalization, `ServerUnreachable` (server shut down) |
| Custom server | `ai/compat/CustomServerTest.kt`, `ai/NetworkSecurityConfigTest.kt` | normalize/issue; allowlist equals XML |
| Claude | `ClaudeChatEngineTest`, `ClaudeLiveEngineTest` (update) + new cases | `anthropic-workspace-id` header sent only when set; 400 workspace message → WorkspaceRequired; 400 invalid id → WorkspaceNotFound; 404 workspace → WorkspaceNotFound; 404 other → ModelUnavailable; key checker WORKSPACE_REQUIRED/INVALID; Haiku uses 20250305/20250910 tools and no fallbacks |
| Prompts/schema | `ai/AiPromptsTest.kt`, `LivePublishTest.kt` | `liveSystem(SEARCH_AND_FETCH)` == v1.2 golden; `SEARCH_ONLY` contains neither `web_fetch` nor `web_search`; `chatSystem(false)` has the no-internet paragraph; FULL schema golden; PORTABLE has no `"uri"`, no `image` |
| Services | `ChatServiceTest`, `LiveNewsServiceTest` (update to fake `AiProviderAccess`) | provider name in state; per-provider keyRejected; Gemini suggestions + note persisted and restored; live run stores provider |
| UI logic | `AiLogicTest`, `StatusLogicTest`, `LiveModelsTest` (update) | messages per provider/state; format hints per provider; status pill per provider; LIVE_UNAVAILABLE text |

Manual QA before release (needs the user's keys; checklist in `docs/AI.md`): one chat with search and
one Live run per provider with Live; Gemini free key (fallback note) and paid key (suggestions visible,
links open in a Custom Tab, dark mode); Anthropic unscoped identity-linked key without/with workspace
id; custom server via `http://<name>.fritz.box` and an IP (blocked with hint); Android 7 emulator
(OpenAI chat works, Claude disabled); release build with R8 (`minifyReleaseWithR8` check passes).

---

## 12. Implementation plan (v1.3.0)

Three sequential implementer agents. Each task leaves `./gradlew testDebugUnitTest` green.

**Agent A – Foundation and Claude**
* **T1 Provider catalog, settings model, migration.** Files: `data/model/AiSettingsModels.kt` (new),
  `data/model/SettingsModels.kt`, `data/AiSettingsPrefs.kt` (new), `data/SettingsRepository.kt`,
  `ai/provider/ProviderSpec.kt`, `ai/provider/ProviderCatalog.kt` (new), tests. Delete `AiModel`; fix
  compile errors at call sites minimally (Settings/AiSection temporarily show Anthropic models from the catalog).
* **T2 Pricing and usage.** `ai/AiPricing.kt` (table §9), `ai/UsageStore.kt`, `UsageSnapshot.estimatedRequests`, tests.
* **T3 Seam, resolver, services.** `ai/AiEngines.kt`, `ai/AiContracts.kt` (AiSupport, AiBlock, AiError,
  ChatMessage/ChatUiState/LiveState fields, AiKeyCheck), `ai/AiGate.kt` → `ai/AiResolver.kt`,
  `ai/AiServices.kt` (registry with Claude only for now), `ai/ChatServiceImpl.kt`, `ai/ChatStore.kt`,
  `ai/LiveNewsServiceImpl.kt`, `ai/LiveStore.kt`, `ai/AiTexts.kt` (brand), `ui/today/LiveModels.kt`
  (signature + texts), tests updated. Keep a temporary `@Deprecated val AiSupport.isSupported =
  sdkAvailable` for UI call sites until T10.
* **T4 Claude adaptation, prompts, schema.** `ai/claude/ClaudeBackend.kt` (workspace header, cache key),
  `ClaudeRequests.kt` (Haiku tools, auth), `ClaudeEngines.kt`, `ClaudeErrors.kt`, `ai/AiPrompts.kt`,
  `ai/LivePublish.kt`, `ai/http/JsonMaps.kt`, tests (golden prompt/schema, workspace errors).

**Agent B – Engines**
* **T5 HTTP/SSE and errors.** `ai/http/AiHttp.kt`, `SseReader.kt`, `Retry.kt`, `ProviderErrors.kt`, tests.
* **T6 Responses family.** `ai/openai/*`, fixtures, tests (OpenAI, xAI, Perplexity; chat, live, key checks).
* **T7 Gemini chat.** `ai/gemini/*`, fixtures, tests (incl. free-tier fallback).
* **T8 Chat Completions family + custom server.** `ai/compat/*`, `res/xml/network_security_config.xml`,
  tests; register all backends in `AiServices` (+ `listServerModels`).

**Agent C – UI, docs, release prep**
* **T9 Settings UI.** `ui/settings/AiSection.kt`, `AiProviderPicker.kt` (new), `AiKeyEditor.kt`,
  `AiLogic.kt`, `SettingsViewModel.kt`, `SettingsScreen.kt`, `StatusLogic.kt`, `StatusHero.kt`,
  `SettingsPreviews.kt`, tests.
* **T10 Chat and Live UI.** `ui/chat/ChatParts.kt`, `ChatScreen.kt`, `SearchSuggestions.kt` (new),
  `ChatPreviews.kt`, `ui/today/TodayScreen.kt`, `TodayViewModel.kt`, `LivePanel.kt`,
  `ui/news/ArticleScreen.kt`, `ArticleViewModel.kt`; remove the deprecated `AiSupport.isSupported`.
* **T11 Docs and checks.** Rewrite `docs/AI.md` (multi-provider, keeps the Claude API rules as a
  subsection), update `docs/ARCHITECTURE.md` and `docs/DESIGN.md` (Einstellungen → KI), link-check every
  `consoleUrl` (HEAD/GET, expect 200/30x), run unit tests and `assembleRelease` (R8 check), write the
  manual QA checklist. Version: `scripts/release.ps1` bumps to 1.3.0 when the user releases.

---

## 13. Risks and open points

* **No live verification yet.** All new engines are built from documentation and synthetic fixtures;
  several shapes are UNVERIFIED in the specs (Perplexity function calls only in `output_item.done`,
  OpenRouter streaming annotations and `max_tool_calls`, Gemini free-tier search error, xAI full event
  list). Manual QA with real keys is required before release; parsers ignore unknown fields/events.
* **Gemini terms.** The Search Suggestions must stay visible with every grounded answer (also from
  history) and unmodified; WebView height inside the chat list needs a device check. Live stays off
  for Gemini (D4) – revisit with legal clarity or the Interactions API later.
* **Raw-IP custom servers** are blocked by D10 (only local names over http). If the user needs
  `http://192.168.x.y`, the alternative is base-config cleartext + an HTTPS-only interceptor on
  `container.http` and Coil's client – a deliberate security trade-off for the user to decide.
* **Self-signed HTTPS on the NAS** is not trusted (system CAs + ISRG only); Tailscale `*.ts.net` or a
  Let's Encrypt cert works.
* **Price drift.** Prices are AS_OF 2026-10-09; Gemini Flash doubles on 2027-01-01 (encoded);
  Mistral Large 4 sale ends ≈ 2026-10-20 (list price used); OpenAI `gpt-5.6-sol` promo ends
  2026-11-21. DeepSeek is always priced at peak (overestimates off-peak by up to 2×).
* **Cost estimates** for providers without reported cost exclude Gemini's 5,000 free monthly search
  queries (counted at $0.014) – conservative.
* **Prompt behavior.** The `SEARCH_ONLY` Live prompt and the „no internet“ chat prompt are new text;
  tune after the first real runs.
* **Perplexity 401** means invalid key *or* no credit; marking the key „abgelehnt“ may be misleading –
  the text says both.
* **Console URLs** marked † are not from the research specs and must be link-checked (T11).
* **Migration** cannot be rolled back: a downgrade to v1.2 loses the stored Claude key (legacy keys are
  removed). The in-app updater only moves forward.
* **Scope.** Mistral web search (Conversations API), DeepSeek/self-hosted Live via a client-side search
  function, the Gemini Interactions API, per-provider usage breakdown and Sign in with ChatGPT are out
  of scope for v1.3.0.

---

## 14. QA with the mock server (`tools/mock-ai-server.mjs`)

Real keys are not needed (and never used) to see every provider work end to end. A dependency-free
Node script (Node 18+) serves realistic **streaming** answers in the wire format of every engine
family; a debug build points any provider at it.

```bash
node tools/mock-ai-server.mjs                 # http://127.0.0.1:8787 (emulator: http://10.0.2.2:8787)
node tools/mock-ai-server.mjs --port 9000 --delay 0 --host 0.0.0.0
```

| Endpoint | Family | What it streams |
|---|---|---|
| `POST …/responses` | OpenAI Responses (OpenAI; `grok-*` models get the xAI flavor) | `response.created` → reasoning item (encrypted) → `web_search_call` (in_progress/searching/completed, `action.sources`) → message (`phase:"final_answer"`) with `output_text.delta`s and `url_citation` annotations → `response.completed` with usage. xAI: `cost_in_usd_ticks`, numeric citation titles, function calls only in `output_item.done`. |
| `POST …/agent` | Perplexity Agent API | `search_results` item, text with a `[1]` marker split across two deltas, `cost.total_cost`, `tool_calls_details`. Non-streamed `{"input":"ping"}` → the key check's JSON response. |
| `POST …/models/{m}:streamGenerateContent?alt=sse` | Gemini | One `GenerateContentResponse` per `data:` line, no event names, no `[DONE]`: a thought part (hidden by the app), text in several chunks, `groundingMetadata` (`webSearchQueries`, redirect `groundingChunks` with domain titles, `searchEntryPoint.renderedContent` with light/dark CSS and chip links to Google), `finishReason: STOP`, `usageMetadata`. Grounding only when the request has `googleSearch`. |
| `POST …/chat/completions` | Chat Completions | OpenRouter (a model with `/` or the `openrouter:web_search` tool): `: OPENROUTER PROCESSING` comment, text, `url_citation` annotations, the extra accounting chunk with `usage.cost` and `server_tool_use_details`. Mistral: a `thinking` content part first. DeepSeek: usage in a last chunk with `choices: []` when `stream_options.include_usage`. Anything else = own server (no key needed). Live: `tool_calls` fragments by index, `finish_reason: tool_calls`. |
| `POST /v1/messages` | Anthropic (Claude SDK) | `message_start` → `server_tool_use` (`web_search`) + `web_search_tool_result` → text with a `citations_delta` → `message_delta` (`server_tool_use.web_search_requests`) → `message_stop`. Live: a `tool_use` block streamed as `input_json_delta`, `stop_reason: tool_use`. Non-streamed → the key check's `Message`. |
| `GET …/models`, `…/v1beta/models`, `…/v1beta/models/{m}`, `…/api-key`, `…/key`, `…/user/balance` | key and connection checks | OpenAI/Mistral/own-server model list, Gemini model list (with `supportedGenerationMethods`, the key test's first step) and model info, xAI key status, OpenRouter key info, DeepSeek balance. `POST …/models/{m}:generateContent` answers the Gemini key test's „Antworte nur: OK“. |

**Live:** when a request offers `publish_articles`, the mock first "searches", then calls it once
with one valid article per topic (at most two, topic ids from the schema's `topicId` enum,
`publishedAt` 40 minutes ago in Berlin time, two https sources, 80+ words). After the tool result
`ok` it ends with a short closing line; after a rejection it sends corrected articles.

**Base URLs in the app** (debug builds only: Einstellungen → KI → „Entwickler: Basis-URL“, one field per
provider; „Alle auf Mock-Server“ fills in all of them):

| Provider | Base URL |
|---|---|
| Claude | `http://10.0.2.2:8787` (the SDK adds `/v1/messages`) |
| Gemini | `http://10.0.2.2:8787/v1beta` |
| OpenAI, xAI, Perplexity, OpenRouter, Mistral, DeepSeek | `http://10.0.2.2:8787/v1` |
| Eigener Server | `http://10.0.2.2:8787/v1` as the server address (no key needed) |

`10.0.2.2` is on the cleartext allowlist (D10). Release builds never read the override
(`readAiSettings(…, debugOverrides = BuildConfig.DEBUG)`), `setDebugBaseUrl` stores nothing there,
and the row and its dialog only exist when `BuildConfig.DEBUG`.

**Switches** (comma-separated) make the mock misbehave on purpose:

| Switch | Effect |
|---|---|
| `400` `401` `402` `403` `404` `429` `500` `503` `529` | The family's error body for that status (OpenAI shape; google.rpc for Gemini, labelled `text/event-stream` on the SSE endpoint as observed; Anthropic's `{"type":"error",…}`). Gemini `429` = free-tier quota with `RetryInfo` 37 s (G3: the app explains, it does not hammer). `402` on `/user/balance` and `/api-key` = a key that works without credit. |
| `400-workspace` | Anthropic: „anthropic-workspace-id is required …“ (key check → WORKSPACE_REQUIRED). |
| `400-model`, `429-quota`, `429-short` | Unknown model; OpenAI `insufficient_quota`; a 429 with a 3 s wait (a free Gemini key retries once). |
| `free-tier-search`, `free-tier-search-400` | Gemini rejects `googleSearch` (429 grounding quota with limit 0, or 400 `FAILED_PRECONDITION`); requests without search work. Tests the safety net of a key set to „Mit Abrechnung“ that is a free one. |
| `401-auth-key`, `403-blocked`, `403-disabled`, `403-restricted`, `400-region` | Gemini's refusals of GE §12.4 on every Gemini route, the model list included: `ACCESS_TOKEN_TYPE_UNSUPPORTED`, `API_KEY_SERVICE_BLOCKED`, `SERVICE_DISABLED` (with Help link), `API_KEY_ANDROID_APP_BLOCKED`, `FAILED_PRECONDITION` (free tier not in your country). Other families answer them with the base status (`403-blocked` → their `403`). |
| `400-key-echo` | Gemini: 400 for an invalid key whose body echoes the key it got into every field (code, type, `ErrorInfo.reason` and metadata, quota id, message, Help link) — the Details must stay key-free (GE §12.6). Other families: their `400`. |
| `model-gone`, `model-no-quota` | Gemini: `gemini-3.8-flash` answers 404 `NOT_FOUND` (and is missing from the model list), or 429 with a free-tier quota of 0; every other model works. The chat and the key test fall back to `gemini-3.7-flash` (GE §12.5). |
| `no-search` | Never search, even when asked. |
| `refusal`, `truncated`, `midstream-error` | A refusal, a cut-off answer, an error after the first words. |
| `live-reject`, `live-empty` | Live: the first `publish_articles` call is invalid (body too short); an empty list. |
| `slow` | 400 ms between events (for cancel and „Abbrechen“). |

Set them per request with a path prefix that also works in the app's base URL
(`http://10.0.2.2:8787/mock/429/v1beta`), with `?mock=429,slow` (curl), globally with the
environment variables `MOCK_ERROR` / `MOCK_MODE`, or at runtime with
`curl -X POST "http://127.0.0.1:8787/__mock?set=free-tier-search"` (`/__mock/reset` clears,
`GET /__mock` shows the state). Any non-empty key works; a key containing `reject` gets the
provider's refusal (401; xAI a 400 like the real API, OC §4.8). Use fake values like
`AIza-test-invalid`, `AQ.test-invalid`, `sk-test-invalid` — never real keys.

The log line of every request names the family, model, search/Live and a masked key, and warns
when the app breaks a documented rule (Gemini key as `?key=`, `temperature`/`thinkingBudget` on
Gemini 3, a trailing model turn, Responses without `store: false`, `stream_options` to Mistral).

**Contract test:** `MockServerContractTest` (JVM) runs the real engines of all four families
against the running mock: chat with search, Live with the correction loop, key checks, the Gemini
free-tier fallback, the free quota and the workspace error. It is skipped unless `MOCK_AI_SERVER`
is set (the variable is a test input, so setting or clearing it re-runs the tests):

```bash
node tools/mock-ai-server.mjs --delay 0
MOCK_AI_SERVER=http://127.0.0.1:8787 ./gradlew :app:testDebugUnitTest --tests "*MockServerContractTest"
```

**Suggested emulator pass** (debug build with `-PMB_ID_SUFFIX=.v13`, fake keys): Gemini kostenlos
(chat note „Ohne Websuche …“, no suggestions), Gemini mit Abrechnung (Search Suggestions under the
answer in light and dark, a chip opens a Custom Tab), `free-tier-search` (answer plus „Ohne
Websuche beantwortet …“), `429` on a free key (the calm quota text), Live with Gemini (one sentence
plus „Live-Anbieter wählen“), Live via OpenAI, OpenRouter and Claude (articles appear,
`live-reject` corrects itself), the own server at `http://10.0.2.2:8787/v1`, and an API 24
emulator (Claude shows „Claude braucht Android 8“ with „Anbieter wählen“, every other provider
works).

**Emulator pass done on 2026-10-09** (API 36 emulator, debug build, mock server, fake keys only):
provider picker and capability pills; Gemini key dialog (guide, both key shapes, tariff, privacy
line) and key check; free tier (chat note, no search requests, Pro locked, everything „kostenlos“);
paid tier (grounded answer with Search Suggestions in dark and light, also from history after a
restart; a chip opens Chrome, not the WebView; source pills show the domains);
`free-tier-search` fallback with its note; Gemini in Live (one sentence + „Live-Anbieter wählen“
→ Live picker → OpenAI key dialog) and a Live run via OpenAI (2 articles published); „Alle auf
Mock-Server“. The pass found and fixed a crash of every streaming answer (empty alternate text of
the cursor, present since v1.2). Still open: the API 24 emulator (it did not finish booting here)
and every check with real keys (§11).

## 15. Link check of the console URLs (T11, 2026-10-09)

`curl -L` with a browser user agent:

| URL | Result |
|---|---|
| `https://platform.claude.com/settings/keys`, `…/settings/workspaces` | 200 |
| `https://aistudio.google.com/apikey` | 200 after the Google sign-in redirect (`continue=…/apikey`) |
| `https://console.x.ai` | 200 (`/home`) |
| `https://openrouter.ai/settings/keys` | 200 after the sign-in redirect back to the keys page |
| `https://console.mistral.ai/api-keys` | 307 to the Mistral login (the page exists) |
| `https://platform.openai.com/api-keys`, `https://www.perplexity.ai/account/api/keys` | 403: bot challenge for scripted clients. **Open once in a browser before the release.** |
| `https://platform.deepseek.com/api_keys` | 202: WAF interstitial. **Check in a browser.** |
| `https://aistudio.google.com/projects` (v1.3.1, billing) | 200 after the Google sign-in redirect (`continue=…/projects`) |
| `https://console.cloud.google.com/apis/credentials` (v1.3.1, key restrictions) | 200 after the Google sign-in redirect (`continue=…/apis/credentials`) |
| `https://console.cloud.google.com/apis/library/generativelanguage.googleapis.com` (v1.3.1, enable the API) | 302 loop without cookies (scripted client); **open once in a browser before the release.** |
