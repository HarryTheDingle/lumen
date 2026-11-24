package com.notdxbi.dxbi.tv.lumen.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlin.math.abs

/**
 * A channel as a rectangle tile in its own colour with its logo in white (as Freely and Sky Glass show them): Sky's
 * channel logos are white marks on nothing, so they sit on the channel's brand colour. A logo from elsewhere (the
 * provider's, in its own colours) sits on a dark tile. No logo at all: the name, in white, on the tile.
 */
@Composable
internal fun ChannelTile(
    logoUrl: String?,
    name: String,
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
    corner: Dp = 8.dp,
) {
    val url = remember(logoUrl) { logoUrl?.trim()?.takeIf { it.isNotEmpty() }?.let(::sharperSkyLogo) }
    var failed by remember(url) { mutableStateOf(false) }
    val whiteMark = url != null && url.contains("newchanlogos")
    val fill = remember(name, whiteMark) { if (url == null || whiteMark) channelBrandColour(name) else NEUTRAL_TILE }
    Box(
        modifier = modifier
            .size(width, height)
            .clip(RoundedCornerShape(corner))
            .background(fill),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null && !failed) {
            AsyncImage(
                model = url,
                contentDescription = name,
                contentScale = ContentScale.Fit,
                onError = { failed = true },
                modifier = Modifier.fillMaxSize().padding(horizontal = width * 0.07f, vertical = height * 0.09f),
            )
        } else {
            Text(
                text = name,
                fontSize = (height.value * 0.26f).coerceIn(9f, 16f).sp,
                lineHeight = (height.value * 0.30f).coerceIn(10f, 18f).sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
        }
    }
}

/** Sky's logos come at 150 px from the guide; tiles on a 4K TV want more */
private fun sharperSkyLogo(url: String): String = url.replace("/newchanlogos/150/150/", "/newchanlogos/300/300/")

private val NEUTRAL_TILE = Color(0xFF2A2A2E)

/** Muted tiles for channels with no brand colour known, steady per name */
private val FALLBACK_TILES = listOf(
    Color(0xFF2E3A4F), Color(0xFF3B2F4F), Color(0xFF2F4A43), Color(0xFF4A3A2C),
    Color(0xFF433044), Color(0xFF2C4250), Color(0xFF3E3E46),
)

/** Brand colours by channel name (most specific first), close to the tiles UK guides use */
private val BRAND_COLOURS: List<Pair<String, Long>> = listOf(
    // BBC
    "bbc one" to 0xFFD8202A, "bbc 1" to 0xFFD8202A, "bbc1" to 0xFFD8202A,
    "bbc two" to 0xFF1F9C83, "bbc 2" to 0xFF1F9C83, "bbc2" to 0xFF1F9C83,
    "bbc three" to 0xFFB0217A, "bbc four" to 0xFF2D3A8C, "bbc news" to 0xFFA4161A,
    "bbc parliament" to 0xFF1E5C3A, "bbc scotland" to 0xFF233E8B, "bbc alba" to 0xFF2C5E8E,
    "cbbc" to 0xFF5A9E1E, "cbeebies" to 0xFFE5A100,
    // ITV
    "itvbe" to 0xFFB8306E, "itv quiz" to 0xFFF04B4B, "itv4" to 0xFF1F5A41, "itv 4" to 0xFF1F5A41,
    "itv3" to 0xFF3E1F6E, "itv 3" to 0xFF3E1F6E, "itv2" to 0xFF3D24C8, "itv 2" to 0xFF3D24C8,
    "itv1" to 0xFF1D5566, "itv 1" to 0xFF1D5566, "itv" to 0xFF1D5566, "stv" to 0xFF1E5AA8,
    // Channel 4 family
    "film4" to 0xFF5B2A86, "film 4" to 0xFF5B2A86, "more4" to 0xFF1A7F7A, "more 4" to 0xFF1A7F7A,
    "e4" to 0xFF6E2C8F, "4seven" to 0xFF2D2D6E, "channel 4" to 0xFF1E1E21,
    // Channel 5 family
    "5usa" to 0xFF1F3F8F, "5 usa" to 0xFF1F3F8F, "5star" to 0xFFB23A7A, "5 star" to 0xFFB23A7A,
    "5action" to 0xFF8F1F1F, "channel 5" to 0xFF2D1B5E,
    // Sky
    "sky cinema" to 0xFFC4001A, "sky sports f1" to 0xFFC40000, "sky sports news" to 0xFF0B2A6F,
    "sky sports premier" to 0xFF3D0A5A, "sky sports football" to 0xFF1B5E20, "sky sports cricket" to 0xFF0D5E3A,
    "sky sports golf" to 0xFF1C5E2E, "sky sports" to 0xFF0B2A6F, "sky atlantic" to 0xFF0A3B5C,
    "sky showcase" to 0xFF1F5FA8, "sky news" to 0xFFB4001C, "sky max" to 0xFF4E2A84, "sky witness" to 0xFF8E2B6E,
    "sky comedy" to 0xFFC27A00, "sky crime" to 0xFFF2C200, "sky documentaries" to 0xFF00757F, "sky history" to 0xFFA4161A,
    "sky nature" to 0xFF2E7D32, "sky arts" to 0xFF6A1B9A, "sky kids" to 0xFF0E7FC2, "sky mix" to 0xFF2B4FA8,
    "sky replay" to 0xFF3B3B8F, "sky sci-fi" to 0xFF243A6E, "sky" to 0xFF1F5FA8,
    // TNT, Premier, Eurosport
    "tnt sports" to 0xFF2A1458, "premier sports" to 0xFF5A1A6E, "eurosport" to 0xFF102A6E, "laliga" to 0xFFD2232A,
    "racing tv" to 0xFF1E5631, "sky sports racing" to 0xFF1E5631, "mutv" to 0xFFC8102E, "lfctv" to 0xFFC8102E,
    // UKTV
    "u&gold" to 0xFF2A2118, "u & gold" to 0xFF2A2118, "gold" to 0xFF2A2118,
    "u&dave" to 0xFF0E2A44, "dave" to 0xFF0E2A44, "u&alibi" to 0xFF2A1840, "alibi" to 0xFF2A1840,
    "u&drama" to 0xFF4A1030, "drama" to 0xFF4A1030, "u&w" to 0xFF3A1240, "u&yesterday" to 0xFF6E4A1E, "yesterday" to 0xFF6E4A1E,
    "u&eden" to 0xFF2E7D32, "eden" to 0xFF2E7D32,
    // Discovery / Warner
    "discovery" to 0xFF0071CE, "dmax" to 0xFF2B2B2B, "quest red" to 0xFFB5122E, "quest" to 0xFFE4002B,
    "really" to 0xFFE04A8A, "food network" to 0xFFD84315, "hgtv" to 0xFF2E7D32, "tlc" to 0xFF1E5AA8,
    "animal planet" to 0xFF2E7D32, "investigation discovery" to 0xFF8E1B1B,
    // others
    "national geographic" to 0xFF3A3A40, "nat geo" to 0xFF3A3A40, "comedy central" to 0xFF1E1E21,
    "mtv" to 0xFF2B2B2B, "nickelodeon" to 0xFFE65100, "nick jr" to 0xFF6A1B9A, "nicktoons" to 0xFFE65100,
    "disney" to 0xFF113CCF, "cartoon network" to 0xFF2B2B2B, "boomerang" to 0xFF1E5AA8, "cartoonito" to 0xFF1E88E5,
    "s4c" to 0xFFD9531E, "rte" to 0xFF1B4F8C, "virgin media" to 0xFFC8102E, "tg4" to 0xFF2C6E8E,
    "gb news" to 0xFF1A2E5A, "talktv" to 0xFFB4001C, "al jazeera" to 0xFFC27A00, "cnn" to 0xFFCC0000,
    "bloomberg" to 0xFF2B2B2B, "cnbc" to 0xFF1E5AA8, "euronews" to 0xFF1E5AA8, "france 24" to 0xFF1E5AA8,
    "great movies" to 0xFF8E1B1B, "great tv" to 0xFF1E5AA8, "legend" to 0xFF5A1F1F, "talking pictures" to 0xFF3E3E46,
    "pbs america" to 0xFF1E5AA8, "blaze" to 0xFFB5122E, "together" to 0xFF2E7D5B, "quidem" to 0xFF3E3E46,
    "qvc" to 0xFF1E5AA8, "ideal world" to 0xFF2E7D32, "jewellery maker" to 0xFF6A1B9A,
)

internal fun channelBrandColour(name: String): Color {
    val n = name.lowercase().replace(Regex("\\s+(hd|uhd|fhd|4k|sd|\\+1)\\b"), "").trim()
    BRAND_COLOURS.firstOrNull { (key, _) -> n.startsWith(key) || n.contains(" $key") || n == key }?.let { return Color(it.second) }
    return FALLBACK_TILES[abs(n.hashCode()) % FALLBACK_TILES.size]
}

/** The guide's logo for a provider channel (Sky's white mark, for the coloured tile), or the provider's own */
internal fun tileLogoFor(channel: lumos.iptv.CanonicalChannel): String? =
    com.notdxbi.dxbi.tv.live.LiveTvRuntime.currentStore()?.mainGuideRows?.value
        ?.firstOrNull { it.linkedChannel?.id == channel.id }?.guideChannel?.logoUrl?.takeIf { it.isNotBlank() }
        ?: channel.logoUrl


/**
 * The colour the guide washes its top with for a channel: its tile's colour, or where the tile is dark (its logo
 * carries the colour), the colour people know the channel by
 */
private val WASH: List<Pair<String, Long>> = listOf(
    "national geographic" to 0xFFE5B800, "nat geo" to 0xFFE5B800, "comedy central" to 0xFFE5B800, "channel 4" to 0xFF5DBB3F,
    "u&gold" to 0xFFF2A02B, "gold" to 0xFFF2A02B, "u&dave" to 0xFF1E8CC8, "dave" to 0xFF1E8CC8, "u&alibi" to 0xFF6A3FA8,
    "alibi" to 0xFF6A3FA8, "u&drama" to 0xFFD0367E, "drama" to 0xFFD0367E, "u&w" to 0xFF9C2BA0, "itv1" to 0xFF2A9DB5, "itv 1" to 0xFF2A9DB5,
    "itv" to 0xFF2A9DB5, "dmax" to 0xFFE36B1C, "cartoon network" to 0xFF8E8E8E, "mtv" to 0xFFE5B800,
)

internal fun channelWashColour(name: String): Color {
    val n = name.lowercase().replace(Regex("\\s+(hd|uhd|fhd|4k|sd|\\+1)\\b"), "").trim()
    WASH.firstOrNull { (key, _) -> n.startsWith(key) || n.contains(" $key") || n == key }?.let { return Color(it.second) }
    return channelBrandColour(name)
}
