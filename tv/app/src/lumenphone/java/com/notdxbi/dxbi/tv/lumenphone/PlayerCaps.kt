package com.notdxbi.dxbi.tv.lumenphone

import android.media.MediaCodecList
import com.notdxbi.dxbi.tv.phone.StreamChoices
import com.notdxbi.dxbi.tv.phone.StreamOption

/**
 * What this phone can decode, so the stream Play picks is one it can show. The page's pick knows nothing of the
 * device: it ranks a Dolby Vision-only release (no HDR10 under it) just below HDR10 ones, but a Pixel has no Dolby
 * Vision decoder, so such a stream either fails to start or shows green and purple. Streams that carry HDR10
 * (Dolby Vision profile 8, the usual kind) are fine: they play as HDR10.
 */
object PlayerCaps {
    private val decoders: Set<String> by lazy {
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }.flatMap { it.supportedTypes.toList() }.map { it.lowercase() }.toSet()
        }.getOrDefault(emptySet())
    }

    val dolbyVision: Boolean get() = "video/dolby-vision" in decoders

    /** Why [s] won't play well on this phone (null: it should) */
    fun problem(s: StreamOption): String? = when {
        s.dvOnly && !dolbyVision -> "Dolby Vision only: this phone can't show it properly"
        s.codec == "AV1" && "video/av01" !in decoders -> "AV1: this phone can't decode it"
        s.codec == "HEVC" && "video/hevc" !in decoders -> "HEVC: this phone can't decode it"
        else -> null
    }

    /**
     * The stream to play for [c]: the page's pick when this phone can show it, else the best one it can, ready streams
     * first and none heavier than the connection allows when that's known. The page's pick stays the answer when
     * nothing better exists (a failed stream is passed over in the player anyway).
     */
    fun pick(c: StreamChoices): StreamOption? {
        val picked = c.picked ?: return null
        if (problem(picked) == null) return picked
        val fine = c.items.filter {
            it.index != picked.index && !it.pending && it.source != "CAM" && !it.lesser && problem(it) == null &&
                (c.speed <= 0 || it.mbps <= 0 || it.mbps <= c.speed * 0.8)
        }
        val next = fine.sortedWith(compareByDescending<StreamOption> { it.instant }.thenComparing(StreamOption.BEST)).firstOrNull() ?: return picked
        android.util.Log.i("lumen-phone", "pick ${picked.index} (${picked.quality}) is ${problem(picked)}; playing ${next.index} (${next.quality}) instead")
        return next
    }
}
