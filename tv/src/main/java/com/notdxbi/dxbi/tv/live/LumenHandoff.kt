package com.notdxbi.dxbi.tv.live

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * "Watch in Lumen": a film or episode on a channel that Lumen has too (in a far better picture). The live player shows
 * [offer]; choosing it leaves a [Request] in [pending] for Lumen's screens, which open the title and play it, from the
 * start or from where the channel had got to ([startMsOnce], taken by MainActivity.play). When Lumen's player closes,
 * [backOffer] asks whether to go back to the channel.
 */
object LumenHandoff {
    /** What the channel is showing, found in Lumen */
    data class Offer(
        val programmeKey: String,
        val name: String,
        /** "Film" or "S2, E4" */
        val label: String,
        /** How far into it the channel is */
        val joinMs: Long,
        val request: Request,
    )

    data class Request(
        /** Lumen's id: the IMDb id */
        val id: String,
        /** "movie" or "series" */
        val type: String,
        val name: String,
        val background: String?,
        /** An episode's id (tt…:season:episode); null for a film */
        val videoId: String?,
        val channelId: String,
        val channelName: String,
    )

    val offer = MutableStateFlow<Offer?>(null)
    /** Programmes whose offer was taken or turned down: not offered again (back on the channel after Lumen, say) */
    val answered: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    fun dismiss() {
        offer.value?.let { answered += it.programmeKey }
        offer.value = null
    }
    val pending = MutableStateFlow<Request?>(null)
    /** The position Lumen's next player should start from (once) */
    @Volatile var startMsOnce: Long? = null
    /** The channel to offer going back to, while Lumen plays what it was showing */
    @Volatile var returnTo: Pair<String, String>? = null
    @Volatile var playerOpened = false
    /** (channel id, channel name): Lumen's player has closed; offer the channel again */
    val backOffer = MutableStateFlow<Pair<String, String>?>(null)

    /** "S2 E4", "Series 2 Episode 4", "S02E04" in a guide entry */
    fun episodeOf(vararg texts: String?): Pair<Int, Int>? {
        val t = texts.filterNotNull().joinToString(" ")
        Regex("""\bS(?:eries|eason)?\s*(\d{1,3})\s*,?\s*E(?:p(?:isode)?)?\s*(\d{1,3})\b""", RegexOption.IGNORE_CASE).find(t)?.let {
            return it.groupValues[1].toInt() to it.groupValues[2].toInt()
        }
        return null
    }
}
