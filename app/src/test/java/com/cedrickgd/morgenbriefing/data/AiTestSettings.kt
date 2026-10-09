package com.cedrickgd.morgenbriefing.data

import com.cedrickgd.morgenbriefing.data.model.AiProviderId
import com.cedrickgd.morgenbriefing.data.model.AppSettings
import com.cedrickgd.morgenbriefing.data.model.ProviderSettings

/** These settings with a (tested) key stored for [id] and [id] as the chat provider. */
internal fun AppSettings.withAiKey(id: AiProviderId = AiProviderId.ANTHROPIC): AppSettings =
    copy(ai = ai.copy(provider = id).with(id, ai.of(id).copy(hasKey = true)))

/** These settings with [id] as the chat provider and its settings changed by [change]. */
internal fun AppSettings.withProvider(id: AiProviderId, change: (ProviderSettings) -> ProviderSettings = { it }): AppSettings =
    copy(ai = ai.copy(provider = id).with(id, change(ai.of(id))))
