package com.cedrickgd.morgenbriefing.ai

import com.cedrickgd.morgenbriefing.data.model.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Why AI requests cannot run right now, in order of precedence: the device (API < 26), a
 * missing key, the monthly limit. Null = good to go.
 */
internal fun aiBlock(supported: Boolean, hasKey: Boolean, usage: UsageSnapshot): AiBlock? = when {
    !supported -> AiBlock.UNSUPPORTED_DEVICE
    !hasKey -> AiBlock.NO_KEY
    usage.limitUsd > 0.0 && usage.spentUsd >= usage.limitUsd -> AiBlock.LIMIT_REACHED
    else -> null
}

/** [aiBlock] as a live flow over the settings and this month's usage. */
internal fun aiBlockFlow(supported: Boolean, settings: Flow<AppSettings>, usage: Flow<UsageSnapshot>): Flow<AiBlock?> =
    combine(settings.map { it.hasAiKey }.distinctUntilChanged(), usage) { hasKey, snapshot ->
        aiBlock(supported, hasKey, snapshot)
    }.distinctUntilChanged()
