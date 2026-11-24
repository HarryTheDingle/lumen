package com.notdxbi.dxbi.tv.live

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Frosted glass over the live picture. The picture is drawn on its own surface, which the app's blur can't reach (and
 * the Mi Box has the system's window blur turned off), so while a frosted box shows, a tiny copy of the picture is taken
 * a few times a second, blurred here, and the boxes draw it behind themselves where they sit.
 */
object LiveFrost {
    /** The blurred picture and where the picture's surface sits in the window (px) */
    class Frame(val image: ImageBitmap, val left: Int, val top: Int, val width: Int, val height: Int)

    val frame = MutableStateFlow<Frame?>(null)

    /** How many frosted boxes are showing: no copies are taken when none are */
    @Volatile var wanted = 0

    private const val W = 64
    private const val H = 36

    fun run(scope: CoroutineScope, view: PlayerView): Job = scope.launch {
        val main = Handler(Looper.getMainLooper())
        while (isActive) {
            if (wanted <= 0) { delay(120); continue }
            val sv = view.videoSurfaceView as? SurfaceView
            if (sv != null && sv.width > 0 && sv.height > 0 && sv.holder.surface?.isValid == true) {
                val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
                val ok = suspendCancellableCoroutine { c ->
                    try {
                        PixelCopy.request(sv, bmp, { r -> if (c.isActive) c.resume(r == PixelCopy.SUCCESS) }, main)
                    } catch (_: Throwable) {
                        if (c.isActive) c.resume(false)
                    }
                }
                if (ok) {
                    blur(bmp, 3)
                    blur(bmp, 3)
                    val loc = IntArray(2)
                    sv.getLocationInWindow(loc)
                    frame.value = Frame(bmp.asImageBitmap(), loc[0], loc[1], sv.width, sv.height)
                }
            }
            delay(150)
        }
    }

    /** A box blur across then down (cheap at this size), in place */
    private fun blur(bmp: Bitmap, r: Int) {
        val w = bmp.width; val h = bmp.height
        val src = IntArray(w * h); bmp.getPixels(src, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        fun pass(from: IntArray, to: IntArray, horizontal: Boolean) {
            val lines = if (horizontal) h else w
            val len = if (horizontal) w else h
            for (line in 0 until lines) {
                for (i in 0 until len) {
                    var a = 0; var rr = 0; var g = 0; var b = 0; var n = 0
                    for (k in -r..r) {
                        val j = (i + k).coerceIn(0, len - 1)
                        val p = if (horizontal) from[line * w + j] else from[j * w + line]
                        a += p ushr 24; rr += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF; n++
                    }
                    val idx = if (horizontal) line * w + i else i * w + line
                    to[idx] = ((a / n) shl 24) or ((rr / n) shl 16) or ((g / n) shl 8) or (b / n)
                }
            }
        }
        pass(src, tmp, true)
        pass(tmp, src, false)
        bmp.setPixels(src, 0, w, 0, 0, w, h)
    }
}
