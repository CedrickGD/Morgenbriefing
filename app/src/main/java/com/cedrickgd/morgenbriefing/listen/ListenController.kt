package com.cedrickgd.morgenbriefing.listen

import android.content.Context
import com.cedrickgd.morgenbriefing.data.model.NewsItem
import kotlinx.coroutines.flow.StateFlow

data class ListenState(
    /** TextToSpeech with a German voice is ready on this device. */
    val available: Boolean = false,
    val speaking: Boolean = false,
    val paused: Boolean = false,
    val title: String? = null,
    /** `NewsItem.key` of the article being read. */
    val articleKey: String? = null,
    /** 0f..1f over the whole article. */
    val progress: Float = 0f,
)

/**
 * "Zuhören" (docs/AI.md, Vorlesen): reads headline, lead and body of an article with Android
 * TextToSpeech (Locale.GERMAN) in chunks below getMaxSpeechInputLength(), handles audio focus,
 * stops on stop(). One instance per process (`context.listen`).
 */
class ListenController(private val context: Context) {
    val state: StateFlow<ListenState> get() = TODO("agent reader")

    fun play(item: NewsItem) { TODO("agent reader") }

    fun pauseOrResume() { TODO("agent reader") }

    fun stop() { TODO("agent reader") }
}
