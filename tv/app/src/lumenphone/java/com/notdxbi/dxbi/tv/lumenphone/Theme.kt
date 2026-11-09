package com.notdxbi.dxbi.tv.lumenphone

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.notdxbi.dxbi.tv.R

/**
 * Lumen's look on a phone, ported from the iPhone and iPad app (ios/Dxbi/UI/Theme.swift): a black page, white
 * for what's chosen and for progress (Apple TV's way: the icon's cyan and blue stay out of the screens),
 * greys for the rest. Sizes are in dp, where the iOS app's points were the same size on screen.
 */
object Dx {
    /** The page's black, edge to edge */
    val Background = Color.Black
    /** Cards, fields and anything a poster hasn't loaded into yet (iOS: white 12%) */
    val Raised = Color(0xFF1F1F1F)
    val Text = Color.White
    val Muted = Color.White.copy(alpha = 0.62f)
    val Faint = Color.White.copy(alpha = 0.4f)
    /** The thin rim round cards and bars */
    val Line = Color.White.copy(alpha = 0.12f)
    /** What's chosen: white, as on Apple TV (the tab bar's icon, a button's fill) */
    val Selected = Color.White
    /** Text on a white button */
    val OnSelected = Color.Black
    /** A problem (a wrong password), said in a soft red */
    val Problem = Color(0xFFFF6B6B)

    /** The space at a screen's sides (iOS: gutter 20) */
    val Gutter = 20.dp
    /** A row's posters: three and a bit across on a phone; larger on the opened Fold and tablets (iOS: 112 and 180) */
    val PosterCompact = 112.dp
    val PosterWide = 180.dp
    /** Text no wider than reads well (iOS: readable 720) */
    val Readable = 720.dp
}

/** The look of the text, after iOS's system styles (title2 bold for a row's name, headline, subheadline…) */
private val LumenTypography = Typography(
    headlineMedium = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Medium),
)

private val LumenColours: ColorScheme = darkColorScheme(
    primary = Dx.Selected,
    onPrimary = Dx.OnSelected,
    background = Dx.Background,
    onBackground = Dx.Text,
    surface = Dx.Background,
    onSurface = Dx.Text,
    surfaceVariant = Dx.Raised,
    onSurfaceVariant = Dx.Muted,
    outline = Dx.Line,
)

@Composable
fun LumenPhoneTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LumenColours, typography = LumenTypography, content = content)
}

/** Lumen's logo: the glass letters (graphics/Lumen Logo transparent.png), as on the TV app */
@Composable
fun Logo(height: Dp = 40.dp, modifier: Modifier = Modifier) {
    Image(
        painterResource(R.drawable.lumen_logo),
        contentDescription = "Lumen",
        contentScale = ContentScale.Fit,
        modifier = modifier.height(height),
    )
}

/** A picture from the web, filling its frame (the raised grey while it loads, or when there's none) */
@Composable
fun Art(url: String?, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    Box(modifier.background(Dx.Raised)) {
        if (url != null) AsyncImage(model = url, contentDescription = null, contentScale = contentScale, modifier = Modifier.matchParentSize())
    }
}

/** "S1, E3" from Stremio's video id (tt0000:1:3) */
fun episodeLabel(videoId: String?): String? {
    val parts = videoId?.split(":") ?: return null
    if (parts.size < 3) return null
    val season = parts[parts.size - 2].toIntOrNull() ?: return null
    val episode = parts[parts.size - 1].toIntOrNull() ?: return null
    return "S$season, E$episode"
}

fun typeName(type: String) = when (type) {
    "movie" -> "Movie"
    "series" -> "TV Show"
    "anime" -> "Anime"
    "channel" -> "Channel"
    "tv" -> "Live TV"
    else -> type.replaceFirstChar { it.uppercase() }
}
