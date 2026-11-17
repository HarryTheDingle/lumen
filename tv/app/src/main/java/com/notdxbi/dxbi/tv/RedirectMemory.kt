package com.notdxbi.dxbi.tv

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Where a stream's link really leads, once it's been followed. Debrid links through an addon (StremThru's /playback/…)
 * redirect to the debrid's own download server, and the addon asks the debrid for that address afresh on every request:
 * a second or two before any picture comes, and the player makes several requests as it starts (the file's start, its
 * index, the place to start from). Followed once (by the check before Play, or the player's first request), every later
 * request goes straight to the file. A remembered address that stops working is forgotten and the link followed again.
 */
object RedirectMemory {
    private const val KEEP_MS = 30 * 60 * 1000L
    private class Hop(val to: String, val at: Long)
    private val known = java.util.concurrent.ConcurrentHashMap<String, Hop>()

    fun remember(from: String, to: String) {
        if (from == to) return
        if (known.size > 50) known.clear()
        known[from] = Hop(to, System.currentTimeMillis())
    }

    fun target(from: String): String? = known[from]?.takeIf { System.currentTimeMillis() - it.at < KEEP_MS }?.to

    /** For the player's HTTP client: requests for a followed link go straight to where it led */
    val interceptor = Interceptor { chain ->
        val asked = chain.request()
        val original = asked.url.toString()
        val to = target(original)
        if (to != null) {
            val direct: Response? = runCatching { chain.proceed(asked.newBuilder().url(to).build()) }.getOrNull()
            if (direct != null && direct.code < 400) return@Interceptor direct
            android.util.Log.w("lumen-play", "remembered address failed (${direct?.code}): following the link again")
            direct?.close()
            known.remove(original)
        }
        val response = chain.proceed(asked)
        val landed = response.request.url.toString()
        if (response.code < 400 && landed != original) remember(original, landed)
        response
    }
}
