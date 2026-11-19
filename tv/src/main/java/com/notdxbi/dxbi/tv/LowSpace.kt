package com.notdxbi.dxbi.tv

import android.content.Context
import android.os.StatFs
import android.util.Log
import android.widget.Toast

/**
 * A TV with its storage nearly full (a 4 GB Sony was at 95%) can't start Stremio's engine at all: the page loads, but
 * the core never comes up, so Play finds no streams (and updates fail with "insufficient storage"). Our own cache is
 * the only thing we can give back, so when space is short it's cleared before the page starts.
 */
object LowSpace {
    private const val SHORT = 500L * 1024 * 1024
    private const val CRITICAL = 300L * 1024 * 1024

    fun free(context: Context): Long = try { StatFs(context.filesDir.path).availableBytes } catch (_: Exception) { Long.MAX_VALUE }

    fun relieve(context: Context) {
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
