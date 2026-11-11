package com.notdxbi.dxbi.tv

import android.content.Context
import org.json.JSONObject

/**
 * The stream last played of each film or episode (Stremio's player link, which carries the stream itself), kept on the
 * TV for a few hours: Play or Resume on it again goes straight to that stream, with no asking the addons, no choosing and
 * no checking (the player's link is the one that played). One that won't play is forgotten, and Play goes the long way.
 */
object PlayedLinks {
    private const val KEEP_MS = 6 * 60 * 60 * 1000L
    private const val PREFS = "played-links"

    /** The video a player link is for: its route ends …/type/id/videoId */
    fun videoOf(link: String): String? =
        link.substringBefore('?').trimEnd('/').substringAfterLast('/').takeIf { it.isNotEmpty() }?.let { runCatching { android.net.Uri.decode(it) }.getOrDefault(it) }

    fun put(context: Context, link: String) {
        val video = videoOf(link) ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val e = prefs.edit()
        // (old ones go as new ones come)
        prefs.all.forEach { (k, v) -> if (runCatching { now - JSONObject(v as String).optLong("at") > KEEP_MS }.getOrDefault(true)) e.remove(k) }
        e.putString(video, JSONObject().put("link", link).put("at", now).toString()).apply()
    }

    fun get(context: Context, video: String): String? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(video, null) ?: return null
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (System.currentTimeMillis() - o.optLong("at") > KEEP_MS) return null
        return o.optString("link").takeIf { it.isNotEmpty() }
    }

    fun forget(context: Context, video: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(video).apply()
    }
}
