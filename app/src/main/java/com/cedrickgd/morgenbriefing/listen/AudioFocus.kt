package com.cedrickgd.morgenbriefing.listen

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import androidx.annotation.RequiresApi

/** What happened to our audio focus, already reduced to what the reader has to do. */
internal enum class FocusChange {
    /** Another app took over for good (music started): pause, do not come back by ourselves. */
    LOST,

    /** A short interruption (call, navigation prompt): pause and continue on [GAINED]. */
    LOST_TRANSIENT,

    GAINED,
}

/**
 * Audio focus for reading aloud: [AudioFocusRequest] on Android 8+, the legacy stream API below.
 * Speech is never ducked — a quieter voice is hard to follow — so every loss pauses.
 */
internal abstract class AudioFocus {
    abstract fun request(): Boolean

    abstract fun abandon()

    companion object {
        /** Attributes of the spoken article; also set on the TextToSpeech instance. */
        val attributes: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        fun create(audio: AudioManager, handler: Handler, onChange: (FocusChange) -> Unit): AudioFocus =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ModernAudioFocus(audio, handler, onChange)
            } else {
                LegacyAudioFocus(audio, handler, onChange)
            }

        fun map(change: Int): FocusChange? = when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> FocusChange.GAINED
            AudioManager.AUDIOFOCUS_LOSS -> FocusChange.LOST
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            -> FocusChange.LOST_TRANSIENT
            else -> null
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
private class ModernAudioFocus(
    private val audio: AudioManager,
    handler: Handler,
    onChange: (FocusChange) -> Unit,
) : AudioFocus() {
    private val request: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes)
        .setWillPauseWhenDucked(true)
        .setAcceptsDelayedFocusGain(false)
        .setOnAudioFocusChangeListener({ change -> map(change)?.let(onChange) }, handler)
        .build()

    override fun request(): Boolean =
        audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    override fun abandon() {
        audio.abandonAudioFocusRequest(request)
    }
}

private class LegacyAudioFocus(
    private val audio: AudioManager,
    private val handler: Handler,
    private val onChange: (FocusChange) -> Unit,
) : AudioFocus() {
    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        // Legacy callbacks arrive on the thread that requested focus; hop to ours to be sure.
        handler.post { map(change)?.let(onChange) }
    }

    @Suppress("DEPRECATION")
    override fun request(): Boolean =
        audio.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) ==
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    @Suppress("DEPRECATION")
    override fun abandon() {
        audio.abandonAudioFocus(listener)
    }
}
