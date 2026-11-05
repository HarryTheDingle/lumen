package com.notdxbi.dxbi.tv.lumen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import coil3.compose.AsyncImage
import com.notdxbi.dxbi.tv.R
import com.notdxbi.dxbi.tv.phone.Title

/**
 * Lumen's look, as the iPad app has it (ios/Dxbi/UI/Theme.swift): black, the icon's cyan for what's
 * chosen, its blue for progress. Sizes are for a TV seen from the sofa (a 1080p screen is 960 × 540 dp).
 */
object Dx {
    /** Lumen's cyan, from the icon: what's chosen, small highlights */
    val Accent = Color(0xFF2EE6D6)
    /** The icon's blue: progress (watched so far) */
    val Blue = Color(0xFF2F8BFF)
    val Amber = Color(0xFFFFB547)
    /** The icon's light, cyan through blue to amber: the logo */
    val Spectrum = Brush.horizontalGradient(listOf(Accent, Blue, Amber))
    /** Text on the accent (it's light) */
    val OnAccent = Color(0xFF06141A)
    val Background = Color.Black
    val Raised = Color(0xFF1F1F1F)
    val Muted = Color.White.copy(alpha = 0.62f)
    val Faint = Color.White.copy(alpha = 0.4f)
    /** TV-safe margin at the screen's sides */
    val Gutter = 48.dp
    val Poster = 124.dp
}

@Composable
fun LumenTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Dx.Accent,
            onPrimary = Dx.OnAccent,
            background = Dx.Background,
            surface = Dx.Raised,
            onSurface = Color.White,
            onBackground = Color.White,
        ),
        content = content,
    )
}

/** Lumen's logo: the glass letters lit cyan through blue to amber (graphics/Lumen Logo transparent.png) */
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

/** A title's logo (its name drawn as on the poster), or its name in large type when it has none */
@Composable
fun TitleLogo(title: Title, maxHeight: Dp, maxWidth: Dp, textSize: TextUnit, modifier: Modifier = Modifier, centred: Boolean = false) {
    var missing by remember(title.logo) { mutableStateOf(title.logo == null) }
    Box(modifier.fillMaxWidth(), contentAlignment = if (centred) Alignment.Center else Alignment.CenterStart) {
        if (!missing) {
            AsyncImage(
                model = title.logo,
                contentDescription = title.name,
                contentScale = ContentScale.Fit,
                alignment = if (centred) Alignment.Center else Alignment.CenterStart,
                onError = { missing = true },
                modifier = Modifier.height(maxHeight).fillMaxWidth(),
            )
        } else {
            Text(
                title.name,
                style = TextStyle(
                    fontSize = textSize,
                    fontWeight = FontWeight.Black,
                    lineHeight = textSize * 1.05f,
                    shadow = Shadow(Color.Black.copy(alpha = 0.5f), blurRadius = 20f),
                ),
                color = Color.White,
                textAlign = if (centred) TextAlign.Center else TextAlign.Start,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A small outlined tag (a year, New, 4K…) */
@Composable
fun Badge(text: String, colour: Color = Color.White) {
    Text(
        text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = colour,
        modifier = Modifier
            .border(BorderStroke(1.dp, colour.copy(alpha = 0.7f)), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/**
 * A progress line in the icon's blue, with a soft glow, over a track that shows on light art too;
 * a little progress still shows (a dot, not a sliver)
 */
@Composable
fun ProgressLine(value: Float, modifier: Modifier = Modifier) {
    val p = value.coerceIn(0f, 1f)
    BoxWithConstraints(modifier.height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.28f))) {
        if (p > 0f) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(maxOf(maxWidth * p, 4.dp))
                    .shadow(4.dp, CircleShape, ambientColor = Dx.Blue, spotColor = Dx.Blue)
                    .background(Dx.Blue, CircleShape),
            )
        }
    }
}

/** The signed-in person: their initial in a circle */
@Composable
fun Avatar(email: String?, size: Dp = 36.dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(Brush.verticalGradient(listOf(Color(0xFF5999BF), Color(0xFF33668C))))
            .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text((email ?: "?").take(1).uppercase(), fontSize = (size.value * 0.45f).sp, fontWeight = FontWeight.SemiBold, color = Color.White)
    }
}

/** "S1, E3" from Stremio's video id (tt0000:1:3) */
fun episodeLabel(videoId: String?): String? {
    val parts = videoId?.split(':') ?: return null
    if (parts.size < 3) return null
    val s = parts[parts.size - 2].toIntOrNull() ?: return null
    val e = parts[parts.size - 1].toIntOrNull() ?: return null
    return "S$s, E$e"
}

fun typeName(type: String) = when (type) {
    "movie" -> "Movie"
    "series" -> "TV Show"
    "anime" -> "Anime"
    "channel" -> "Channel"
    "tv" -> "Live TV"
    else -> type.replaceFirstChar { it.uppercase() }
}
