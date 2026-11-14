package com.notdxbi.dxbi.tv

import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.util.concurrent.Executors

/**
 * The little picture over the seek bar while it's dragged: where the seek will land. ExoPlayer has no previews of
 * its own, so this reads single frames from the lightest stream of the same video (the page's previewSource, as the
 * iPad's SeekPreview does), else from the stream that's playing, with Android's MediaMetadataRetriever. Frames are
 * asked for one at a time, only the latest place counts, and a file it can't read that way just shows the time.
 */
class PlayerThumbs(private val url: String, private val headers: Map<String, String>, private val widthPx: Int) {
    /** The picture for the place last asked for (null: none yet, or this file can't give any) */
    var frame by mutableStateOf<ImageBitmap?>(null)
        private set

    private val reader = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var retriever: MediaMetadataRetriever? = null
    @Volatile private var wanted = -1L
    @Volatile private var working = true
    private val usable = Build.VERSION.SDK_INT >= 27 && url.startsWith("http")

    /** The controls are up: scrubbing may be next, so the stream opens now (it takes a moment to read its index) */
    fun warm() {
        if (!usable || retriever != null) return
        reader.execute { runCatching { open() } }
    }

    /** The finger is at [ms]: its picture, once the finger has rested a moment */
    fun want(ms: Long) {
        if (!usable || !working) return
        wanted = ms
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ fetch(ms) }, 140)
    }

    /** Done scrubbing */
    fun clear() {
        wanted = -1L
        handler.removeCallbacksAndMessages(null)
        frame = null
    }

    private fun fetch(ms: Long) {
        reader.execute {
            // (a later place has been asked for since: skip this one)
            if (wanted != ms) return@execute
            val bitmap = runCatching {
                // (the closest keyframe: quick, and as near as a preview needs)
                open().getScaledFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, widthPx, widthPx * 9 / 16)
            }.onFailure {
                android.util.Log.i("lumen-phone", "no seek pictures from this stream: ${it.message}")
                working = false
            }.getOrNull()
            if (bitmap != null) handler.post { if (wanted == ms) frame = bitmap.asImageBitmap() }
        }
    }

    private fun open(): MediaMetadataRetriever = retriever ?: MediaMetadataRetriever().also {
        it.setDataSource(url, headers)
        retriever = it
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        reader.execute { runCatching { retriever?.release() }; retriever = null }
        reader.shutdown()
    }
}
