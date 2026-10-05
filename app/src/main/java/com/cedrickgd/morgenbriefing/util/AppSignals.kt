package com.cedrickgd.morgenbriefing.util

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Process-wide UI signals between screens that do not share a ViewModel.
 *
 * Kept deliberately tiny: everything persistent lives in the repositories; these are only
 * "please reload" nudges for ViewModels that are alive at that moment.
 */
internal object AppSignals {
    private val briefSource = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Emits after the GitHub token was saved, changed or removed. */
    val briefSourceChanged: SharedFlow<Unit> = briefSource.asSharedFlow()

    fun notifyBriefSourceChanged() {
        briefSource.tryEmit(Unit)
    }
}
