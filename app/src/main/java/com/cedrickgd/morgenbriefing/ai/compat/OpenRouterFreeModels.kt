package com.cedrickgd.morgenbriefing.ai.compat

import com.cedrickgd.morgenbriefing.ai.AiException
import com.cedrickgd.morgenbriefing.ai.AiFiles
import com.cedrickgd.morgenbriefing.ai.http.AiHttp
import com.cedrickgd.morgenbriefing.ai.http.array
import com.cedrickgd.morgenbriefing.ai.http.fetch
import com.cedrickgd.morgenbriefing.ai.http.obj
import com.cedrickgd.morgenbriefing.ai.http.objects
import com.cedrickgd.morgenbriefing.ai.http.str
import com.cedrickgd.morgenbriefing.ai.provider.OpenRouterTiers
import com.cedrickgd.morgenbriefing.data.BERLIN
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.IOException
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeParseException

/*
 * OpenRouter's free models for the "Kostenlos" tariff (docs/AI-PROVIDERS.md §16): loaded live
 * from the public `GET /models` (no key needed), filtered to the zero-price chat models, kept for
 * a day, and a small built-in list when nothing could be loaded yet.
 */

/** One zero-price model of OpenRouter. [name] is OpenRouter's, e.g. "Google: Gemma 4 31B (free)". */
@Serializable
data class FreeModel(val id: String, val name: String, val contextLength: Int? = null)

/** Where a [FreeModelList] came from. */
enum class FreeModelSource {
    /** Just loaded from OpenRouter. */
    LIVE,

    /** The list of the last day — or an older one, when OpenRouter cannot be reached right now. */
    CACHE,

    /** The app's small built-in list: nothing was loaded yet and OpenRouter cannot be reached. */
    FALLBACK,
}

/** The free models the picker offers, best first. */
data class FreeModelList(val models: List<FreeModel>, val source: FreeModelSource)

/** The pure rules: which models count as free chat models, their order, names and the default. */
object OpenRouterFree {
    /** The picker's order: these first (when OpenRouter lists them), then the rest by name. */
    val PREFERRED: List<String> = listOf(
        OpenRouterTiers.DEFAULT_FREE_MODEL,
        "google/gemma-4-26b-a4b-it:free",
        "nvidia/nemotron-3-ultra-550b-a55b:free",
        "nvidia/nemotron-3-super-120b-a12b:free",
        OpenRouterTiers.FREE_ROUTER,
    )

    /** Offline: the preferred models as OpenRouter listed them on 2026-10-10. */
    val FALLBACK: List<FreeModel> = listOf(
        FreeModel(OpenRouterTiers.DEFAULT_FREE_MODEL, "Google: Gemma 4 31B (free)", 262_144),
        FreeModel("google/gemma-4-26b-a4b-it:free", "Google: Gemma 4 26B A4B (free)", 262_144),
        FreeModel("nvidia/nemotron-3-ultra-550b-a55b:free", "NVIDIA: Nemotron 3 Ultra (free)", 1_000_000),
        FreeModel("nvidia/nemotron-3-super-120b-a12b:free", "NVIDIA: Nemotron 3 Super (free)", 262_144),
        FreeModel(OpenRouterTiers.FREE_ROUTER, "Free Models Router", 200_000),
    )

    /** A German line for the preferred models; the others show their maker. */
    private val DESCRIPTIONS = mapOf(
        OpenRouterTiers.DEFAULT_FREE_MODEL to "Googles offenes Modell",
        "google/gemma-4-26b-a4b-it:free" to "Googles offenes Modell, kleiner und flotter",
        "nvidia/nemotron-3-ultra-550b-a55b:free" to "NVIDIAs großes offenes Modell",
        "nvidia/nemotron-3-super-120b-a12b:free" to "NVIDIAs mittleres offenes Modell",
        OpenRouterTiers.FREE_ROUTER to "OpenRouter nimmt jedes Mal ein freies Modell",
    )

    private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * The free chat models of a `GET /models` answer in picker order ([ordered]); null when
     * [body] is no model list. A model counts when it costs nothing ([isFree]), reads and writes
     * text only ([isChatModel]) and has not expired before [today].
     */
    fun parse(body: String, today: LocalDate): List<FreeModel>? {
        val root = try {
            JSON.parseToJsonElement(body) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return null
        if (root.array("data") == null) return null
        val models = root.objects("data").mapNotNull { model ->
            val id = model.str("id")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            if (!isFree(model) || !isChatModel(model) || expired(model, today)) return@mapNotNull null
            FreeModel(id = id, name = model.str("name")?.trim()?.takeIf { it.isNotEmpty() } ?: id, contextLength = model.str("context_length")?.toIntOrNull())
        }
        return ordered(models.distinctBy { it.id })
    }

    /**
     * Prompt and completion cost 0 (OpenRouter writes prices as decimal strings, "0"); a price per
     * request must be 0 too. A router with variable prices ("-1") or a missing price is not free.
     */
    internal fun isFree(model: JsonObject): Boolean {
        val pricing = model.obj("pricing") ?: return false
        val prompt = price(pricing, "prompt") ?: return false
        val completion = price(pricing, "completion") ?: return false
        val request = if (pricing.containsKey("request")) price(pricing, "request") ?: return false else BigDecimal.ZERO
        return prompt.signum() == 0 && completion.signum() == 0 && request.signum() == 0
    }

    private fun price(pricing: JsonObject, key: String): BigDecimal? = (pricing[key] as? JsonPrimitive)?.content?.trim()?.let {
        try {
            BigDecimal(it)
        } catch (_: NumberFormatException) {
            null
        }
    }

    /**
     * A model the app can chat with: text in, text only out (no music or image models such as
     * Lyria), and an instruction model — OpenRouter lists `tools` among the `supported_parameters`
     * of those, not of guardrail classifiers ("Content Safety"). Fields OpenRouter does not send
     * are not held against a model.
     */
    internal fun isChatModel(model: JsonObject): Boolean {
        val architecture = model.obj("architecture")
        val outputs = architecture?.array("output_modalities")?.mapNotNull { (it as? JsonPrimitive)?.content?.lowercase() }
        val inputs = architecture?.array("input_modalities")?.mapNotNull { (it as? JsonPrimitive)?.content?.lowercase() }
        val modality = architecture?.str("modality")?.lowercase()
        val textOut = when {
            outputs != null -> outputs.isNotEmpty() && outputs.all { it == "text" }
            modality != null -> modality.substringAfter("->", "") == "text"
            else -> true
        }
        val textIn = when {
            inputs != null -> "text" in inputs
            modality != null -> "text" in modality.substringBefore("->").split('+')
            else -> true
        }
        val parameters = model.array("supported_parameters")?.mapNotNull { (it as? JsonPrimitive)?.content }
        return textOut && textIn && (parameters == null || "tools" in parameters)
    }

    /** `expiration_date` (YYYY-MM-DD) lies before [today]. */
    private fun expired(model: JsonObject, today: LocalDate): Boolean {
        val date = model.str("expiration_date")?.trim()?.takeIf { it.length >= 10 } ?: return false
        return try {
            LocalDate.parse(date.take(10)).isBefore(today)
        } catch (_: DateTimeParseException) {
            false
        }
    }

    /** [PREFERRED] first in their order, then the rest by title. */
    fun ordered(models: List<FreeModel>): List<FreeModel> {
        val (preferred, rest) = models.partition { it.id in PREFERRED }
        return preferred.sortedBy { PREFERRED.indexOf(it.id) } + rest.sortedBy { title(it).lowercase() }
    }

    /** Gemma 4 31B when OpenRouter lists it, else the first model; the default without a list. */
    fun defaultOf(models: List<FreeModel>?): String {
        if (models.isNullOrEmpty()) return OpenRouterTiers.DEFAULT_FREE_MODEL
        return models.firstOrNull { it.id == OpenRouterTiers.DEFAULT_FREE_MODEL }?.id ?: models.first().id
    }

    /**
     * The free model requests use: [selected], unless a known list ([models]) no longer has it —
     * OpenRouter's free variants come and go — then [defaultOf] that list. Without a list the
     * choice stands.
     */
    fun effective(selected: String, models: List<FreeModel>?): String {
        if (models.isNullOrEmpty() || models.any { it.id == selected }) return selected
        return defaultOf(models)
    }

    /** "Gemma 4 31B" for "Google: Gemma 4 31B (free)"; the router gets a German name. */
    fun title(model: FreeModel): String {
        if (model.id == OpenRouterTiers.FREE_ROUTER) return "Zufälliges Gratis-Modell"
        val name = model.name.substringAfter(": ", model.name).replace(FREE_SUFFIX, "").replace(SPACES, " ").trim()
        return name.ifEmpty { model.id }
    }

    /** The maker before the colon ("Google"), or null. */
    fun maker(model: FreeModel): String? = model.name.substringBefore(": ", "").trim().takeIf { it.isNotEmpty() }

    /** The picker's second line: what the model is, and that it is free. */
    fun subtitle(model: FreeModel): String {
        val what = DESCRIPTIONS[model.id] ?: maker(model) ?: "Über OpenRouter"
        return "$what · kostenlos"
    }

    private val FREE_SUFFIX = Regex("""\(free\)""", RegexOption.IGNORE_CASE)
    private val SPACES = Regex("""\s+""")
}

/** The cache file of [OpenRouterFreeModels]. */
@Serializable
internal data class StoredFreeModels(
    val version: Int = 1,
    /** Epoch millis of the download. */
    val fetchedAt: Long,
    /** The API base it came from (a debug build may point OpenRouter at a mock server). */
    val baseUrl: String,
    val models: List<FreeModel>,
)

/**
 * OpenRouter's free models, loaded with [http] from `{base}/models` (public, sent without a key)
 * and kept in memory and in [file] for [maxAgeMillis] (a day). Never throws except for
 * cancellation: without network the last list (of any age) or [OpenRouterFree.FALLBACK] is used.
 */
internal class OpenRouterFreeModels(
    private val http: AiHttp,
    private val file: File,
    private val json: Json,
    private val clock: Clock = Clock.system(BERLIN),
    private val maxAgeMillis: Long = DAY_MILLIS,
) {
    private val lock = Mutex()

    @Volatile
    private var memory: StoredFreeModels? = null

    /** The free models for the API at [baseUrl]: the day's list, else a new one, else what is known. */
    suspend fun list(baseUrl: String, force: Boolean = false): FreeModelList = lock.withLock {
        val base = baseUrl.trimEnd('/')
        val now = clock.millis()
        val cached = cached(base)
        if (!force && cached != null && now - cached.fetchedAt in 0 until maxAgeMillis) {
            return@withLock FreeModelList(cached.models, FreeModelSource.CACHE)
        }
        val fetched = fetch(base)
        if (!fetched.isNullOrEmpty()) {
            val stored = StoredFreeModels(fetchedAt = now, baseUrl = base, models = fetched)
            memory = stored
            save(stored)
            return@withLock FreeModelList(fetched, FreeModelSource.LIVE)
        }
        if (cached != null) return@withLock FreeModelList(cached.models, FreeModelSource.CACHE)
        FreeModelList(OpenRouterFree.FALLBACK, FreeModelSource.FALLBACK)
    }

    /**
     * The last list loaded from [baseUrl] (of any age) without going online; null when there is
     * none. Never waits for a download that is under way: a chat request must not stall behind
     * the settings loading the list (the file is replaced atomically, the memory copy is volatile).
     */
    suspend fun known(baseUrl: String): List<FreeModel>? = cached(baseUrl.trimEnd('/'))?.models

    private suspend fun cached(base: String): StoredFreeModels? {
        memory?.let { if (it.baseUrl == base) return it }
        val stored = withContext(Dispatchers.IO) { AiFiles.readOrNull(file) }?.let { text ->
            try {
                json.decodeFromString(StoredFreeModels.serializer(), text)
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        } ?: return null
        if (stored.baseUrl != base || stored.models.isEmpty()) return null
        memory = stored
        return stored
    }

    private suspend fun fetch(base: String): List<FreeModel>? = try {
        // Public list: no key, only OpenRouter's attribution headers.
        val reply = http.fetch(http.get("$base/models", CompatDialect.OPENROUTER.extraHeaders))
        if (reply.code in 200..299) OpenRouterFree.parse(reply.body, LocalDate.now(clock)) else null
    } catch (e: CancellationException) {
        throw e
    } catch (_: IOException) {
        null
    } catch (_: AiException) {
        // An address OkHttp cannot parse (a broken debug base URL).
        null
    }

    private suspend fun save(stored: StoredFreeModels) {
        try {
            withContext(Dispatchers.IO) { AiFiles.writeAtomically(file, json.encodeToString(StoredFreeModels.serializer(), stored)) }
        } catch (_: IOException) {
            // Kept in memory; the next start loads it again.
        }
    }

    companion object {
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}
