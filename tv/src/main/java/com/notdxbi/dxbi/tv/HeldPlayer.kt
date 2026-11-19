package com.notdxbi.dxbi.tv

import android.os.Handler
import android.os.Looper
import androidx.media3.exoplayer.ExoPlayer

/**
 * A player closed with Back, kept paused for a few minutes with everything it had downloaded: closed by accident, or
 * gone back to the title's page for a moment, Play or Resume on the same stream takes it back and it carries on at
 * once, from what's already here (a debrid server's start, its index and the place to start from took several seconds
 * on the Mi Box). Anything else played, or the few minutes up, and it's let go.
 */
object HeldPlayer {
    private const val KEEP_MS = 3 * 60 * 1000L

    /** The player and the parts of it its screen had (the sound processing, the subtitle delay) */
    class Kept(val exo: ExoPlayer, val sound: SoundMode, val delay: SubtitleDelay, val url: String)

    private var kept: Kept? = null
    private val handler = Handler(Looper.getMainLooper())
    private val drop = Runnable { release() }

    fun hold(k: Kept) {
        release()
        kept = k
        handler.postDelayed(drop, KEEP_MS)
        android.util.Log.i("lumen-play", "player kept for ${KEEP_MS / 60_000} minutes, ${k.exo.totalBufferedDuration / 1000} s in hand")
    }

    /** The kept player, when it's playing [url]; anything else lets it go */
    fun take(url: String): Kept? {
        val k = kept ?: return null
        handler.removeCallbacks(drop)
        kept = null
        if (k.url == url) return k
        k.exo.release()
        return null
    }

    fun release() {
        handler.removeCallbacks(drop)
        kept?.exo?.release()
        kept = null
    }
}

/** How far the subtitles are moved (ms), shared by the player's text renderer and its controls */
class SubtitleDelay { @Volatile var ms = 0L }
