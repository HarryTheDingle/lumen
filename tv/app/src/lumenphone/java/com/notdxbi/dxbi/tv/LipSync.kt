package com.notdxbi.dxbi.tv

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink

/**
 * Lip sync: how much later (or, below 0, earlier) the sound plays than the picture. It's the TV's and the sound system's
 * own delay that it makes up for, so it's one setting for the whole device, kept, and used by both players (films and
 * Live TV).
 */
object LipSync {
    private const val PREFS = "lip_sync"
    private const val KEY = "ms"
    const val STEP_MS = 40L
    const val MAX_MS = 500L

    /** Sound delay in ms: above 0 the sound comes later, below 0 sooner */
    @Volatile var ms: Long = 0L
        private set

    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        ms = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY, 0L)
    }

    /** Moves the sound [byMs] later (or sooner, below 0), kept for next time; returns the new delay */
    fun shift(context: Context, byMs: Long): Long = set(context, ms + byMs)

    fun set(context: Context, value: Long): Long {
        ms = value.coerceIn(-MAX_MS, MAX_MS)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY, ms).apply()
        return ms
    }

    /** "In time", "Sound 0.12 s later", "Sound 0.08 s sooner" */
    fun label(value: Long = ms): String = when {
        value == 0L -> "In time"
        value > 0 -> "Sound %.2f s later".format(value / 1000f)
        else -> "Sound %.2f s sooner".format(-value / 1000f)
    }
}

/**
 * The sound's clock, moved by the lip sync delay: the picture keeps time with what this says, so reporting the sound as
 * further on than it is shows each frame earlier than its sound (the sound later), and the other way round.
 */
@OptIn(UnstableApi::class)
class LipSyncSink(sink: AudioSink) : ForwardingAudioSink(sink) {
    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val p = super.getCurrentPositionUs(sourceEnded)
        if (p == AudioSink.CURRENT_POSITION_NOT_SET) return p
        return (p + LipSync.ms * 1000L).coerceAtLeast(0L)
    }
}
