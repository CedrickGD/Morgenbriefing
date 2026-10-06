package com.cedrickgd.morgenbriefing.ai

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.cedrickgd.morgenbriefing.AppContainer
import com.cedrickgd.morgenbriefing.ai.claude.ClaudeBackend
import com.cedrickgd.morgenbriefing.data.model.Brief
import com.cedrickgd.morgenbriefing.data.model.NewsItem
import com.cedrickgd.morgenbriefing.data.model.NewsSection
import com.cedrickgd.morgenbriefing.data.model.Topics
import com.cedrickgd.morgenbriefing.util.parseDateOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import java.io.File
import java.time.LocalDate

/** The one DataStore for this month's AI spending (DataStore forbids two instances per file). */
private val Context.aiUsageStore: DataStore<Preferences> by preferencesDataStore(name = "ai_usage")

/**
 * Entry point for all AI features, owned by MorgenbriefingApp (`context.ai`). Must be safe to
 * construct on every API level: on API < 26 it hands out services that only ever report
 * [AiBlock.UNSUPPORTED_DEVICE] and never load SDK classes — the Claude backend
 * ([ClaudeBackend], the only code that touches `com.anthropic.*`) is created lazily and only
 * behind [AiSupport.isSupported].
 */
class AiServices(private val context: Context, private val container: AppContainer) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val storeDir = File(context.applicationContext.filesDir, "store")

    private val backend: AiBackend? by lazy {
        if (!AiSupport.isSupported) return@lazy null
        try {
            ClaudeBackend()
        } catch (e: LinkageError) {
            // Defensive: a vendor ROM without the Java APIs the SDK needs behaves like API < 26.
            Log.w(TAG, "Claude SDK not usable on this device", e)
            null
        }
    }

    private val usageStore: UsageStore by lazy {
        UsageStore(
            dataStore = context.applicationContext.aiUsageStore,
            limitUsd = container.settings.settings.map { it.aiMonthlyLimitUsd },
            scope = scope,
        )
    }

    /**
     * Usage of chat and Live requests. Usage only exists for a request Anthropic accepted, so it
     * also confirms a key that was saved without a test
     * ([com.cedrickgd.morgenbriefing.data.model.AppSettings.aiKeyUntested]).
     */
    private val requestUsage: UsageSink by lazy {
        object : UsageSink by usageStore {
            override suspend fun record(modelId: String, usage: TokenUsage) {
                usageStore.record(modelId, usage)
                markKeyWorks()
            }
        }
    }

    private val liveService: LiveNewsServiceImpl by lazy {
        LiveNewsServiceImpl(
            scope = scope,
            settings = container.settings.settings,
            apiKey = ::apiKeyOrNull,
            usageSink = requestUsage,
            usageState = usageStore.usage,
            engine = backend?.live,
            store = LiveStore(File(storeDir, "live"), container.json),
            briefOf = { date -> container.store.loadBrief(date) },
        )
    }

    private val chatService: ChatServiceImpl by lazy {
        ChatServiceImpl(
            scope = scope,
            settings = container.settings.settings,
            apiKey = ::apiKeyOrNull,
            usageSink = requestUsage,
            usageState = usageStore.usage,
            engine = backend?.chat,
            store = ChatStore(File(storeDir, "chat"), container.json),
            contexts = ChatContextSource(::loadChatContext),
        )
    }

    val chat: ChatService get() = chatService
    val live: LiveNewsService get() = liveService
    val usage: UsageTracker get() = usageStore

    /** Cheap key check for the settings screen (one tiny request). */
    suspend fun testKey(key: String): AiKeyCheck {
        val checker = backend?.keys ?: return AiKeyCheck.UNSUPPORTED
        val clean = key.trim()
        if (clean.isEmpty() || clean.any { it.isWhitespace() }) return AiKeyCheck.INVALID
        return checker.check(clean, usageStore)
    }

    private suspend fun markKeyWorks() {
        try {
            container.settings.markAiKeyWorks()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Claude key could not be marked as working", e)
        }
    }

    private suspend fun apiKeyOrNull(): String? = try {
        container.settings.aiKey()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Claude key could not be read", e)
        null
    }

    /**
     * Article or whole-day context for a chat: the brief of that day as the screens show it
     * ([briefFor]: the demo brief without a token, else the stored one, downloaded if missing),
     * plus that day's live articles.
     */
    private suspend fun loadChatContext(chat: ChatContext): LoadedChatContext? {
        val date = parseDateOrNull(chat.date) ?: return null
        val brief = briefFor(date, chat.articleKey)
        val live = liveService.articles(date)
        val key = chat.articleKey
        if (key == null) {
            if (brief == null && live.isEmpty()) return null
            return LoadedChatContext(AiPrompts.BRIEF_TITLE, AiPrompts.briefContext(date, brief, live))
        }
        brief?.findItem(key)?.let { (section, item) ->
            return LoadedChatContext(item.headline, AiPrompts.articleContext(item, date, Topics.forSection(section)))
        }
        live.firstOrNull { it.item.key == key }?.let { article ->
            return LoadedChatContext(
                article.item.headline,
                AiPrompts.articleContext(article.item, date, Topics.byId(article.topicId), liveFetchedAt = article.fetchedAt),
            )
        }
        // Demo mode: the bundled brief may carry another date than the one the screen passed.
        container.briefs.sampleBrief().findItem(key)?.let { (section, item) ->
            return LoadedChatContext(item.headline, AiPrompts.articleContext(item, date, Topics.forSection(section)))
        }
        return null
    }

    /**
     * The brief of [date] the screens show. Without a token "Heute" and the reader show the
     * bundled demo brief (TodayViewModel, ArticleViewModel.findBrief), so the chat takes it first
     * too — even when an older real brief of that day is stored with the same article ids.
     */
    private suspend fun briefFor(date: LocalDate, articleKey: String?): Brief? {
        if (!hasToken()) demoBriefForChat(container.briefs.sampleBrief(), date, articleKey)?.let { return it }
        container.store.loadBrief(date)?.let { return it }
        val fetched = try {
            container.briefs.day(date).brief
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Brief $date could not be loaded for the chat", e)
            null
        }
        return fetched ?: container.briefs.sampleBrief().takeIf { it.date == date.toString() }
    }

    private suspend fun hasToken(): Boolean = try {
        container.settings.current().hasToken
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Settings could not be read", e)
        false
    }

    private fun Brief.findItem(key: String): Pair<NewsSection, NewsItem>? {
        for (section in sections) {
            section.items.firstOrNull { it.key == key }?.let { return section to it }
        }
        return null
    }

    private companion object {
        const val TAG = "AiServices"
    }
}

/**
 * Without a token "Heute" and the reader show the bundled demo brief [sample]; a chat about
 * [date] (and [articleKey], when given) talks about that same text when it is that day's.
 */
internal fun demoBriefForChat(sample: Brief, date: LocalDate, articleKey: String?): Brief? {
    if (sample.date != date.toString()) return null
    if (articleKey != null && sample.sections.none { section -> section.items.any { it.key == articleKey } }) return null
    return sample
}
