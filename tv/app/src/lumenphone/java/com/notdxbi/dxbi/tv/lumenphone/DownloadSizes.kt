package com.notdxbi.dxbi.tv.lumenphone

import com.notdxbi.dxbi.tv.phone.StreamOption

/**
 * Which stream to download, as Small, Medium or High (iOS: StreamOption.downloadSizes, which picks the smallest
 * decent file at each of three resolutions). Downloads were coming out far too big, since a title's "best" stream
 * is often a 30 GB remux, so this goes by what an hour of video costs and aims at what Netflix's downloads cost:
 *
 *   High    up to 5 GB an hour  (about 11 Mbit/s)   1080p, the fullest picture that fits
 *   Medium  up to 2 GB an hour  (about 4.4 Mbit/s)  1080p when it fits well, else 720p
 *   Small   up to 1 GB an hour  (about 2.2 Mbit/s)  720p, or 1080p only when it's a modern codec
 *
 * So a 45 minute episode is about 3.7, 1.5 and 0.75 GB at most, and a two hour film 10, 4 and 2 GB.
 * Never 4K (a phone's screen can't show it, and it's the 68 GB files that were the trouble), and never a stream
 * that isn't ready to download (torrents, uncached debrid), 3D, upscaled, Dolby Vision with nothing under it
 * (wrong colours), or a cam. Where a tier has nothing under its ceiling it still gets the smallest decent file
 * there is, so the size shown is always the real one; where two tiers land on the same file it's shown once.
 */
data class DownloadSize(val name: String, val option: StreamOption, val gbPerHour: Double) {
    val sizeText: String get() = option.sizeText ?: "Size not known"
    val perHourText: String get() = if (gbPerHour >= 10) "%.0f GB an hour".format(gbPerHour) else "%.1f GB an hour".format(gbPerHour)
}

private class Tier(val name: String, val ceilingGbPerHour: Double)

private val TIERS = listOf(Tier("High", 5.0), Tier("Medium", 2.0), Tier("Small", 1.0))

/**
 * How much picture a codec gets from the same bytes, against H.264 (a HEVC or AV1 file looks like a bigger H.264
 * one): so at the same size they win, and a thin HEVC isn't thrown out as a smear.
 */
private fun efficiency(codec: String): Double = when (codec.uppercase()) {
    "AV1" -> 1.5
    "HEVC", "H.265", "X265" -> 1.35
    else -> 1.0
}

/**
 * The least H.264 Mbit/s that still looks decent at a resolution (below it a picture is a smear: a file that
 * small is hiding a lot): 1080p 2.8, 720p 1.4, anything smaller 0.7. A stream's own rate is multiplied by its
 * codec's [efficiency] before it's held to this.
 */
private fun floorMbps(res: Int) = if (res >= 1080) 2.8 else if (res >= 720) 1.4 else 0.7

/**
 * Small, Medium and High from a title's streams ([minutes]: the film or episode's length when known; otherwise
 * it's worked out from the streams' own sizes and bitrates, else 100 minutes is assumed). Empty when no stream
 * is sized and ready to download, when the whole list is the way to choose.
 */
fun downloadSizes(items: List<StreamOption>, minutes: Int = 0): List<DownloadSize> {
    val ready = items.filter { !it.torrent && !it.pending && it.size > 0 && !it.lesser && !it.threeD && !it.dvOnly && it.source != "CAM" }
    if (ready.isEmpty()) return emptyList()
    val hours = lengthHours(items, minutes)
    fun gbh(o: StreamOption) = o.size / 1e9 / hours
    // (a phone's picture: 1080p at most, unless that's all there is)
    val capped = ready.filter { it.res in 1..1080 }.ifEmpty { ready }
    fun decent(o: StreamOption): Boolean {
        // (the bitrate from the size and length, which is true even where the list gave none)
        val mbps = gbh(o) * 8000 / 3600
        return mbps * efficiency(o.codec) >= floorMbps(o.res)
    }
    // the better picture comes first: bigger resolution, then more picture for the bytes (the codec counted), then ready to go
    val better = compareByDescending<StreamOption> { minOf(it.res, 1080) }
        .thenByDescending { gbh(it) * efficiency(it.codec) }
        .thenByDescending { it.instant }
        .thenByDescending { it.seeds }

    val picks = TIERS.mapNotNull { tier ->
        val pick = capped.filter { gbh(it) <= tier.ceilingGbPerHour && decent(it) }.minWithOrNull(better)
            // (nothing small enough: the smallest decent one there is, shown with its real size)
            ?: capped.filter(::decent).minByOrNull { it.size }
            ?: capped.minByOrNull { it.size }
        pick?.let { tier to it }
    }
    // each file once, under the biggest name it was picked for
    val seen = mutableSetOf<Int>()
    return picks.filter { (_, o) -> seen.add(o.index) }.map { (tier, o) -> DownloadSize(tier.name, o, gbh(o)) }
}

/** The length in hours: given, else what the streams' own sizes and bitrates agree on, else a film's usual hour and three quarters */
private fun lengthHours(items: List<StreamOption>, minutes: Int): Double {
    if (minutes > 0) return minutes / 60.0
    val seconds = items.filter { it.size > 0 && it.mbps > 0 }.map { it.size * 8 / (it.mbps * 1e6) }.sorted()
    if (seconds.isNotEmpty()) return seconds[seconds.size / 2] / 3600
    return 100 / 60.0
}
