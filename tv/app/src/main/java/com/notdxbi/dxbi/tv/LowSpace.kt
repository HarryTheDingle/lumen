package com.notdxbi.dxbi.tv

import android.content.Context
import android.os.StatFs
import android.util.Log
import android.widget.Toast
import java.io.File

/**
 * A TV with its storage nearly full (a 4 GB Sony was at 95%) can't start Stremio's engine at all: the page loads, but
 * the core never comes up, so Play finds no streams (and updates fail with "insufficient storage"). Our own cache is
 * the only thing we can give back, so when space is short it's cleared before the page starts.
 */
object LowSpace {
    private const val SHORT = 500L * 1024 * 1024
    private const val CRITICAL = 300L * 1024 * 1024

    /** The page, for clearing its cache (set by MainActivity) */
    @Volatile var page: java.lang.ref.WeakReference<android.webkit.WebView>? = null

    /**
     * When a player closes (an episode ended, or backed out of, or Next up): the page's cache and any torrent left behind are
     * cleared. After a whole episode on a Streamer the engine stopped answering, and clearing the app's cache brought it back.
     */
    fun afterPlayback(context: Context) {
        File(context.cacheDir, "torrents").deleteRecursively()
        page?.get()?.let { web -> web.post { try { web.clearCache(true) } catch (_: Exception) {} } }
        Log.i("lumen-boot", "player closed: cache cleared")
    }

    fun free(context: Context): Long = try { StatFs(context.filesDir.path).availableBytes } catch (_: Exception) { Long.MAX_VALUE }

    fun relieve(context: Context) {
        // A torrent's half-downloaded video is deleted when playback stops, but not when Android or a crash ended the app
        // mid-film: it would sit there (up to a whole film) until the next torrent. Nothing plays at launch, so always clear it
        File(context.cacheDir, "torrents").deleteRecursively()
        if (free(context) >= SHORT) return
        val before = free(context)
        context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        context.externalCacheDir?.listFiles()?.forEach { it.deleteRecursively() }
        val after = free(context)
        Log.i("lumen-boot", "storage was short (${before shr 20} MB free): cleared cache, now ${after shr 20} MB")
        if (after < CRITICAL) {
            Toast.makeText(context, "Your TV is almost out of storage. Free some space (delete apps you don't use) so Lumen can start.", Toast.LENGTH_LONG).show()
        }
    }
}
