package com.notdxbi.dxbi.tv

import org.json.JSONArray
import org.json.JSONObject

/**
 * Lumen: one stream of the playing video, as the player's Streams list shows it (the page's own description of
 * it: see phone.streams and phone.alternatives in dxbi.plugin.js)
 */
class StreamAlt(
    /** Its place in the page's list (what phone.source and phone.play take) */
    val index: Int,
    /** Its player link, to tell which one is playing */
    val key: String,
    val label: String,
    val provider: String,
    val addon: String,
    val sizeBytes: Double,
    val mbps: Double,
    val seeds: Int,
    val torrent: Boolean,
    val instant: Boolean,
    var playing: Boolean = false,
) {
    /** "1080p · HDR · Provider" */
    val title: String get() = listOf(label, provider.ifEmpty { addon }).filter { it.isNotEmpty() && it != "?" }.joinToString(" · ").ifEmpty { "Stream" }

    /** "2.3 GB · 8 Mbps · 45 seeds · Addon" */
    val facts: String get() = listOfNotNull(
        if (sizeBytes >= 1e9) "%.1f GB".format(sizeBytes / 1e9) else if (sizeBytes > 0) "%.0f MB".format(sizeBytes / 1e6) else null,
        if (mbps > 0) "%.0f Mbps".format(mbps) else null,
        if (seeds >= 0) "$seeds seeds" else null,
        addon.takeIf { provider.isNotEmpty() && it.isNotEmpty() && it != provider },
    ).joinToString(" · ")

    companion object {
        /** The link's own part, the same however the page writes the start of it */
        fun keyOf(link: String): String = link.substringAfter("/player/", link).substringBefore("?")

        fun from(index: Int, o: JSONObject) = StreamAlt(
            index, keyOf(o.optString("play")), o.optString("label"), o.optString("provider"), o.optString("addon"),
            o.optDouble("size", 0.0), o.optDouble("mbps", 0.0), o.optInt("seeds", -1), o.optBoolean("torrent"), o.optBoolean("instant"),
        )

        /** The playing one first (when the page lists it), then the others the page offers, best first */
        fun list(page: JSONObject, playingKey: String): List<StreamAlt> {
            val items = page.optJSONArray("items") ?: JSONArray()
            val out = mutableListOf<StreamAlt>()
            for (i in 0 until items.length()) {
                val o = items.optJSONObject(i) ?: continue
                if (keyOf(o.optString("play")) == playingKey) out += from(i, o).also { it.playing = true }
            }
            val all = page.optJSONArray("all") ?: JSONArray()
            for (i in 0 until all.length()) {
                val o = all.optJSONObject(i) ?: continue
                val alt = from(o.optInt("index", -1), o)
                if (alt.index >= 0 && alt.key != playingKey && out.none { it.key == alt.key }) out += alt
            }
            return out.take(1 + 25)
        }
    }
}
