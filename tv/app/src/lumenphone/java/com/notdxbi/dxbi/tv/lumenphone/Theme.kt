package com.notdxbi.dxbi.tv.lumenphone

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.notdxbi.dxbi.tv.R
import com.notdxbi.dxbi.tv.phone.Title

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
    /** Ready to play at once (a cached stream): the one colour the stream list keeps, as iOS's */
    val Good = Color(0xFF4CD964)

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
    titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
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

/**
 * Lumen's logo, as the iOS app draws it (Theme.swift's Logo): LUMEN in Bebas Neue, letter-spaced, in the icon's
 * light running from cyan through blue to amber, with a soft glow. [height] is the type's size, as iOS's `size`.
 * (The one place the icon's colours stay: everything else is black and white. [LogoInColour] false makes it white.)
 */
const val LogoInColour = true
private val Bebas = FontFamily(Font(R.font.bebas_neue))
private val Spectrum = Brush.horizontalGradient(listOf(Color(0xFF2EE6D6), Color(0xFF2F8BFF), Color(0xFFFFB547)))

@Composable
fun Logo(height: Dp = 40.dp, modifier: Modifier = Modifier) {
    val size = height.value
    Text(
        "LUMEN",
        modifier = modifier.semantics { contentDescription = "Lumen" },
        style = if (LogoInColour) TextStyle(
            brush = Spectrum,
            fontFamily = Bebas,
            fontSize = size.sp,
            letterSpacing = (size * 0.08f).sp,
            shadow = Shadow(Color(0xFF2EE6D6).copy(alpha = 0.35f), blurRadius = size * 0.4f),
        ) else TextStyle(color = Color.White, fontFamily = Bebas, fontSize = size.sp, letterSpacing = (size * 0.08f).sp),
        maxLines = 1,
    )
}

/**
 * A title's logo (its name drawn as on the poster), or its name in large type when it has none (iOS: TitleLogo).
 * Always over dark art, so it's drawn as it is.
 */
@Composable
fun TitleLogo(title: Title, maxHeight: Dp, maxWidth: Dp = 380.dp, textSize: TextUnit = 34.sp, modifier: Modifier = Modifier, centred: Boolean = false) {
    var missing by remember(title.logoUrl) { mutableStateOf(title.logoUrl == null) }
    Box(modifier.fillMaxWidth(), contentAlignment = if (centred) Alignment.Center else Alignment.CenterStart) {
        if (!missing) {
            AsyncImage(
                model = title.logoUrl,
                contentDescription = title.name,
                contentScale = ContentScale.Fit,
                alignment = if (centred) Alignment.Center else Alignment.CenterStart,
                onError = { missing = true },
                modifier = Modifier.height(maxHeight).widthIn(max = maxWidth).fillMaxWidth(),
            )
        } else {
            Text(
                title.name,
                modifier = Modifier.widthIn(max = maxWidth),
                style = TextStyle(fontSize = textSize, fontWeight = FontWeight.ExtraBold, lineHeight = textSize * 1.08f, shadow = Shadow(Color.Black.copy(alpha = 0.5f), blurRadius = 20f)),
                color = Color.White,
                textAlign = if (centred) TextAlign.Center else TextAlign.Start,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A small outlined tag beside a title's year: 4K, Dolby Vision, Watched (iOS's format badges) */
@Composable
fun FormatBadge(text: String, colour: Color = Color.White) {
    Text(
        text,
        color = colour.copy(alpha = 0.9f),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.border(1.dp, colour.copy(alpha = 0.6f), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.5.dp),
    )
}

/** A picture from the web, filling its frame (the raised grey while it loads, or when there's none) */
@Composable
fun Art(url: String?, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop, onError: () -> Unit = {}) {
    Box(modifier.background(Dx.Raised)) {
        if (url != null) AsyncImage(model = url, contentDescription = null, contentScale = contentScale, onError = { onError() }, modifier = Modifier.matchParentSize())
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

/**
 * Lumen is for films and shows: YouTube channels and live TV catalogs are left out everywhere (iOS: Title.watchable).
 * Screens keep only the rows and types in this list, rather than naming what to drop, so a new kind of catalog stays out too.
 */
val WATCHABLE = setOf("movie", "series", "anime")

fun typeName(type: String) = when (type) {
    "movie" -> "Movie"
    "series" -> "TV Show"
    "anime" -> "Anime"
    "channel" -> "Channel"
    "tv" -> "Live TV"
    else -> type.replaceFirstChar { it.uppercase() }
}
