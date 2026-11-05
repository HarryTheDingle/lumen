package com.notdxbi.dxbi.tv

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import java.util.concurrent.Executors

/**
 * Lumen's seeking, as other players do it (Apple TV's, Netflix's): left or right pauses the video and
 * moves a marker along the seek bar instead of jumping straight there, faster the longer it's held,
 * with the time (and, when it can be had, a picture from there) over it. OK plays from there; Back
 * puts things back as they were.
 *
 * The picture comes from the lightest stream of the same video (720p, the smallest file: the plugin's
 * previewSource, as the iPad's previews), else the stream playing, read separately at the marker once
 * it stops a moment. A file it can't read that way just shows the time.
 */
class Scrubber(
    private val activity: PlayerActivity,
    private val exo: ExoPlayer,
    private val line: SeekLine,
    private val url: String,
    private val headers: Map<String, String>,
    private val controls: () -> Controls,
) {
    var active = false
        private set
    private var at = 0L
    private var wasPlaying = false
    private val handler = Handler(Looper.getMainLooper())
    private val density = activity.resources.displayMetrics.density

    // the picture: read on its own thread, one at a time, and only the latest asked for
    private val reader = Executors.newSingleThreadExecutor()
    private var retriever: MediaMetadataRetriever? = null
    private var pictures = !url.startsWith("file:")
    @Volatile private var wanted = -1L

    private val bubble = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        visibility = View.GONE
    }
    private val picture = ImageView(activity).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = GradientDrawable().apply { setColor(Color.BLACK); cornerRadius = 10 * density }
        clipToOutline = true
        visibility = View.GONE
    }
    private val time = TextView(activity).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setShadowLayer(8f, 0f, 0f, Color.BLACK)
        setPadding(0, (6 * density).toInt(), 0, 0)
    }

    init {
        bubble.addView(picture, LinearLayout.LayoutParams((PICTURE_W * density).toInt(), (PICTURE_W * 9 / 16 * density).toInt()))
        bubble.addView(time, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        activity.findViewById<FrameLayout>(R.id.controls).addView(bubble, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private val duration get() = exo.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L

    /** Left or right: [direction] -1 or +1; [held] how long the key's been held (its repeat count) */
    fun step(direction: Int, held: Int) {
        val length = duration
        if (length <= 0) return
        if (!active) {
            active = true
            at = exo.currentPosition
            wasPlaying = exo.playWhenReady
            exo.pause()
            controls().show()
            line.requestFocus()
            handler.post(keepUp)
            // (the stream for pictures opens now: it takes a moment)
            if (pictures && Build.VERSION.SDK_INT >= 27) reader.execute { runCatching { open() } }
        }
        // faster the longer it's held: 10 seconds a press, then 30, then a minute
        val by = when {
            held > 24 -> 60_000L
            held > 6 -> 30_000L
            else -> 10_000L
        }
        at = (at + direction * by).coerceIn(0L, length)
        controls().poke()
        paint()
        // (the picture once the marker stops a moment)
        handler.removeCallbacks(fetch)
        handler.postDelayed(fetch, 250)
        // (left alone a while: back as it was)
        handler.removeCallbacks(giveUp)
        handler.postDelayed(giveUp, GIVE_UP_MS)
    }

    /** OK: plays from the marker */
    fun commit() {
        if (!active) return
        exo.seekTo(at)
        exo.play()
        end()
    }

    /** Back (or left alone): as it was, where it was */
    fun cancel() {
        if (!active) return
        if (wasPlaying) exo.play()
        end()
    }

    private val giveUp = Runnable { cancel() }
    // (the controls stay up while it's choosing: the marker's on them)
    private val keepUp = object : Runnable {
        override fun run() {
            if (!active) return
            controls().poke()
            handler.postDelayed(this, 2000)
        }
    }

    private fun end() {
        active = false
        wanted = -1L
        handler.removeCallbacks(fetch)
        handler.removeCallbacks(giveUp)
        handler.removeCallbacks(keepUp)
        line.scrub = -1f
        bubble.visibility = View.GONE
        picture.setImageDrawable(null)
        picture.visibility = View.GONE
        controls().poke()
    }

    /** The marker, its time and where the picture goes over it */
    private fun paint() {
        val length = duration
        val fraction = if (length > 0) at.toFloat() / length else 0f
        line.scrub = fraction
        time.text = clock(at)
        bubble.visibility = View.VISIBLE
        bubble.post {
            val spot = IntArray(2)
            line.getLocationInWindow(spot)
            val host = IntArray(2)
            (bubble.parent as View).getLocationInWindow(host)
            val x = spot[0] - host[0] + line.width * fraction - bubble.width / 2f
            val maxX = (bubble.parent as View).width - bubble.width.toFloat()
            bubble.translationX = x.coerceIn(0f, maxOf(0f, maxX))
            bubble.translationY = spot[1] - host[1] - bubble.height - 10 * density
        }
    }

    private val fetch = Runnable {
        if (!active || !pictures || Build.VERSION.SDK_INT < 27) return@Runnable
        val target = at
        wanted = target
        reader.execute {
            if (wanted != target) return@execute
            val frame = runCatching {
                open().getScaledFrameAtTime(target * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, (PICTURE_W * density).toInt(), (PICTURE_W * 9 / 16 * density).toInt())
            }.onFailure {
                // (a file it can't read this way: the time alone from now on)
                android.util.Log.i("dxbi-player", "no seek pictures: ${it.message}")
                pictures = false
            }.getOrNull()
            if (frame != null) handler.post { show(target, frame) }
            else android.util.Log.i("dxbi-player", "no seek picture at ${target / 1000}s")
        }
    }

    /** The stream for pictures, opened once (on the reader's thread) */
    private fun open(): MediaMetadataRetriever = retriever ?: MediaMetadataRetriever().also {
        if (url.startsWith("http")) it.setDataSource(url, headers) else it.setDataSource(url)
        retriever = it
    }

    private fun show(target: Long, frame: Bitmap) {
        if (!active || wanted != target) return
        picture.setImageBitmap(frame)
        picture.visibility = View.VISIBLE
        paint()
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        reader.execute { runCatching { retriever?.release() }; retriever = null }
        reader.shutdown()
    }

    private fun clock(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    private companion object {
        const val PICTURE_W = 240
        const val GIVE_UP_MS = 60_000L
    }
}
