package com.cedrickgd.morgenbriefing.ai

import android.content.Context
import com.cedrickgd.morgenbriefing.AppContainer

/**
 * Entry point for all AI features, owned by MorgenbriefingApp (`context.ai`). Must be safe to
 * construct on every API level: on API < 26 it hands out services that only ever report
 * [AiBlock.UNSUPPORTED_DEVICE] and never load SDK classes.
 */
class AiServices(private val context: Context, private val container: AppContainer) {
    val chat: ChatService get() = TODO("agent ai")
    val live: LiveNewsService get() = TODO("agent ai")
    val usage: UsageTracker get() = TODO("agent ai")

    /** Cheap key check for the settings screen (one tiny request). */
    suspend fun testKey(key: String): AiKeyCheck = TODO("agent ai")
}
