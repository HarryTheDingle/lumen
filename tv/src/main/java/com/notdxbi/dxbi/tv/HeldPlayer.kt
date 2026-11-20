package com.notdxbi.dxbi.tv

import android.os.Handler
import android.os.Looper
import androidx.media3.exoplayer.ExoPlayer

/**
 * A player closed with Back, kept paused with everything it had downloaded: closed by accident, or
 * gone back to the title's page for a moment, Play or Resume on the same stream takes it back and it carries on at
 * once, from what's already here (a debrid server's start, its index and the place to start from took several seconds
 * on the Mi Box). Kept until another title is played (or half an hour, or the TV runs short of memory).
 */
object HeldPlayer {
    /** (a safety limit: it's let go before this when another title is played, or the TV runs short of memory) */
    private const val KEEP_MS = 30 * 60 * 1000L

    /**
     * The player and the parts of it its screen had (the sound processing, the subtitle delay, subtitles left hidden after a
     * go back); [video] what it was playing (an episode's or a film's id)
     */
    class Kept(val exo: ExoPlayer, val sound: SoundMode, val delay: SubtitleDelay, val url: String, val video: String, val subtitlesHidden: Boolean = false)

    private var kept: Kept? = null
    private val handler = Handler(Looper.getMainLooper())
    private val drop = Runnable { release() }

    fun hold(k: Kept) {
        release()
        kept = k
        handler.postDelayed(drop, KEEP_MS)
        android.util.Log.i("lumen-play", "player kept for ${KEEP_MS / 60_000} minutes or another title, ${k.exo.totalBufferedDuration / 1000} s in hand")
    }

    /**
     * The kept player, when it's playing the same episode or film ([video]), whichever address its stream came by this
     * time (a debrid link is often made again, and matching the address let it go after a trip to Home); anything else
     * lets it go
     */
    fun take(url: String, video: String): Kept? {
        val k = kept ?: return null
        handler.removeCallbacks(drop)
        kept = null
        if (k.url == url || (video.isNotEmpty() && k.video == video)) return k
        k.exo.release()
        return null
    }

    /** The TV is short of memory: what it had downloaded goes first */
    fun trim(level: Int) {
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && kept != null) {
            android.util.Log.i("lumen-play", "kept player let go: the TV's short of memory")
            release()
        }
    }

    fun release() {
        handler.removeCallbacks(drop)
        kept?.exo?.release()
        kept = null
    }
}

/** How far the subtitles are moved (ms), shared by the player's text renderer and its controls */
class SubtitleDelay { @Volatile var ms = 0L }
