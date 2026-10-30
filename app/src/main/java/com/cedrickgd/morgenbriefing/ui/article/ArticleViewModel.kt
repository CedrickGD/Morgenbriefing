package com.cedrickgd.morgenbriefing.ui.article

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
import com.cedrickgd.morgenbriefing.data.article.ArticleException
import com.cedrickgd.morgenbriefing.data.model.AppSettings
import com.cedrickgd.morgenbriefing.data.model.ArticleBlock
import com.cedrickgd.morgenbriefing.data.model.ArticleContent
import com.cedrickgd.morgenbriefing.data.model.NewsItem
import com.cedrickgd.morgenbriefing.data.model.Topic
import com.cedrickgd.morgenbriefing.data.model.Topics
import com.cedrickgd.morgenbriefing.data.remote.RssClient
import com.cedrickgd.morgenbriefing.ui.nav.ArticleRoute
import com.cedrickgd.morgenbriefing.ui.news.hostOf
import com.cedrickgd.morgenbriefing.util.attempt
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.ZonedDateTime

/** What the article reader shows. */
@Immutable
data class ArticleUiState(
    /** The feed item (headline, teaser, source, picture); a stub when the feed no longer has it. */
    val item: NewsItem,
    val topic: Topic,
    /** The extracted article; null while loading or when loading failed. */
    val content: ArticleContent? = null,
    val loading: Boolean = true,
    /** Short German reason when the article could not be loaded. */
    val error: String? = null,
    val showImages: Boolean = true,
    /** Reference for "vor 3 Min.". */
    val now: ZonedDateTime,
)

/**
 * Loads the readable article for [route]: the feed item is taken from the news repository (by
 * id, else by URL), the text from [AppContainer.articles]. When the feed has forgotten the
 * item (opened from the mini player much later), a stub carries the URL and the page's own
 * title fills the headline.
 */
class ArticleViewModel(
    private val container: AppContainer,
    private val route: ArticleRoute,
    private val clock: () -> ZonedDateTime = { ZonedDateTime.now(ZoneId.of("Europe/Berlin")) },
) : ViewModel() {

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<ArticleUiState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        load(refresh = false)
    }

    private fun initialState(): ArticleUiState {
        val known = container.news.state.value.topics.values.asSequence()
            .flatMap { it.items.asSequence() }
            .firstOrNull { (route.itemId != null && it.id == route.itemId) || it.url == route.url }
        val item = known ?: NewsItem(
            id = route.itemId ?: RssClient.stableId(route.url),
            headline = "",
            url = route.url,
            source = hostOf(route.url),
            sourceId = "",
            topicId = route.topicId ?: FALLBACK_TOPIC,
        )
        return ArticleUiState(item = item, topic = Topics.resolveOrNeutral(item.topicId, AppSettings()), now = clock())
    }

    /** Loads (or, with [refresh], re-reads) the article. */
    fun load(refresh: Boolean) {
        job?.cancel()
        job = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val settings = attempt { container.settings.current() }.getOrNull() ?: AppSettings()
            _state.update { s -> s.copy(topic = Topics.resolveOrNeutral(s.item.topicId, settings), showImages = settings.showImages) }
            attempt { container.articles.pruneOnce() }.onFailure { Log.w(TAG, "Could not prune the article cache", it) }
            val result = attempt { container.articles.load(_state.value.item, refresh) }
            ensureActive()
            result.fold(
                onSuccess = { content ->
                    _state.update { s ->
                        val item = if (s.item.headline.isBlank()) s.item.copy(headline = content.title) else s.item
                        s.copy(item = item, content = withoutTeaser(content, item.summary), loading = false, error = null, now = clock())
                    }
                },
                onFailure = { e ->
                    Log.i(TAG, "Article unavailable: ${e.message}")
                    _state.update {
                        it.copy(loading = false, error = (e as? ArticleException)?.reason ?: "Der Artikel ließ sich gerade nicht laden")
                    }
                },
            )
        }
    }

    fun retry() = load(refresh = true)

    companion object {
        private const val TAG = "ArticleViewModel"
        private const val FALLBACK_TOPIC = "welt"
        private val NOISE = Regex("[\\s\\p{Punct}…„“”‚‘’«»]+")

        /**
         * The page's lead paragraph is usually the feed's teaser, which the reader already shows
         * above the body: drop the first paragraph when it says the same (the feed often cuts it
         * with "…", so a prefix match counts).
         */
        internal fun withoutTeaser(content: ArticleContent, summary: String): ArticleContent {
            val teaser = NOISE.replace(summary, "").lowercase()
            if (teaser.length < 20) return content
            val first = content.blocks.indexOfFirst { it.type == ArticleBlock.PARAGRAPH }
            if (first < 0) return content
            val paragraph = NOISE.replace(content.blocks[first].text, "").lowercase()
            val same = paragraph == teaser ||
                (summary.trimEnd().endsWith("…") && paragraph.startsWith(teaser)) ||
                (paragraph.length >= 40 && teaser.startsWith(paragraph))
            return if (same) content.copy(blocks = content.blocks.filterIndexed { i, _ -> i != first }) else content
        }

        fun factory(route: ArticleRoute): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as MorgenbriefingApp
                ArticleViewModel(container = app.container, route = route)
            }
        }
    }
}
