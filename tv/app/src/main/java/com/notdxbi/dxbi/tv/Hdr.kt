package com.notdxbi.dxbi.tv

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.ExoPlayer

/**
 * What kind of HDR the picture is, for the stats: " · HDR10", " · HLG" or " · Dolby Vision", else "". The video's own colour
 * description says when the file has one; many MKVs don't, so then the file's name (in the address) does, as the stream list's
 * badges are worked out from it.
 */
fun hdrName(exo: ExoPlayer, video: Format?): String {
    if (video == null) return ""
    if (video.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION || video.codecs?.let { it.startsWith("dvh") || it.startsWith("dvav") } == true) return " · Dolby Vision"
    when (video.colorInfo?.colorTransfer) {
        C.COLOR_TRANSFER_ST2084 -> return " · HDR10"
        C.COLOR_TRANSFER_HLG -> return " · HLG"
    }
    val name = exo.currentMediaItem?.localConfiguration?.uri?.toString()?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }?.lowercase() ?: return ""
    return when {
        Regex("""dolby[ ._-]?vision|[ ._\-\[(]dv[ ._\-\])]|[ ._-]dovi[ ._-]""").containsMatchIn(name) -> " · Dolby Vision"
        Regex("""hdr10\+|hdr10plus""").containsMatchIn(name) -> " · HDR10+"
        Regex("""hdr10|[ ._\-\[(]hdr[ ._\-\])]""").containsMatchIn(name) -> " · HDR10"
        Regex("""[ ._\-]hlg[ ._\-]""").containsMatchIn(name) -> " · HLG"
        else -> ""
    }
}

/** A track made for talking over the film (the director's, a description for the blind), not the film's own sound */
fun isCommentary(f: Format): Boolean {
    if (f.roleFlags and (C.ROLE_FLAG_COMMENTARY or C.ROLE_FLAG_DESCRIBES_VIDEO) != 0) return true
    val text = ((f.label ?: "") + " " + (f.id ?: "")).lowercase()
    return Regex("""commentar|director|descriptive|audio desc|\bad\b|isolated|trivia""").containsMatchIn(text)
}
