package com.notdxbi.dxbi.tv.lumenphone

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notdxbi.dxbi.tv.R
import com.notdxbi.dxbi.tv.phone.Stremio

/**
 * Genres, as Apple TV's Search page has them and the iPad's Discover (iOS: GenresView.swift): tall tiles with a
 * photo in the genre's colour and its name at the bottom left.
 */
data class Genre(
    val name: String,
    /** The Cinemeta genre its page asks for */
    val key: String,
    /** A second genre shown with it (Thriller & Horror is one page) */
    val also: String? = null,
    /** The page's wash at the top */
    val colour: Color,
    /** Anime has no Cinemeta genre: its page comes from the Kitsu catalogs */
    val anime: Boolean = false,
) {
    /** The tile's picture (graphics/genres, in the resources the Lumen apps share) */
    val picture: Int get() = genrePicture(name)

    companion object {
        val all = listOf(
            Genre("Action", "Action", colour = Color(0xFFE06B1F)),
            Genre("Anime", "Animation", colour = Color(0xFFE64D8C), anime = true),
            Genre("Comedy", "Comedy", colour = Color(0xFF33C7AD)),
            Genre("Crime", "Crime", colour = Color(0xFF6B42B3)),
            Genre("Drama", "Drama", colour = Color(0xFF2966B3)),
            Genre("Documentary", "Documentary", colour = Color(0xFF2980D9)),
            Genre("Animation", "Animation", colour = Color(0xFF4DB899)),
            Genre("Family", "Family", colour = Color(0xFFD95980)),
            Genre("Sci-Fi", "Sci-Fi", colour = Color(0xFF4080CC)),
            Genre("Romance", "Romance", colour = Color(0xFFD94D66)),
            Genre("Thriller & Horror", "Thriller", also = "Horror", colour = Color(0xFF804D59)),
        )
    }
}

fun genrePicture(name: String): Int = when (name) {
    "Christmas" -> R.drawable.genre_christmas
    "Action" -> R.drawable.genre_action
    "Anime" -> R.drawable.genre_anime
    "Comedy" -> R.drawable.genre_comedy
    "Crime" -> R.drawable.genre_crime
    "Drama" -> R.drawable.genre_drama
    "Documentary" -> R.drawable.genre_documentary
    "Animation" -> R.drawable.genre_animation
    "Family" -> R.drawable.genre_family
    "Sci-Fi" -> R.drawable.genre_scifi
    "Romance" -> R.drawable.genre_romance
    else -> R.drawable.genre_thrillerhorror
}

/**
 * Discover's and Search's Genres section: Christmas first in the season (else last), then the rest. Anime only
 * with its addon, which is what plays it. As many tiles across as fit, so the opened Fold gets more than a phone.
 */
@Composable
fun GenresSection(stremio: Stremio, layout: AppLayout, onGenre: (String) -> Unit, onChristmas: () -> Unit, modifier: Modifier = Modifier) {
    val addons by stremio.installedAddons.collectAsState()
    // (the installed addons: asked for here, as nothing else has by now)
    LaunchedEffect(Unit) { stremio.loadAddons() }
    val hasKitsu = addons.any { it.id.contains("kitsu", ignoreCase = true) || it.transportUrl.contains("kitsu", ignoreCase = true) }
    val tiles = buildList {
        if (ChristmasSeason.inSeason) add("Christmas")
        Genre.all.filter { !it.anime || hasKitsu }.forEach { add(it.name) }
        if (!ChristmasSeason.inSeason) add("Christmas")
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BrowseHeader("Genres", layout.gutter)
        BoxWithConstraints(Modifier.padding(horizontal = layout.gutter)) {
            val gap = 10.dp
            val across = maxOf(2, ((maxWidth + gap) / ((if (layout.compact) 100.dp else 150.dp) + gap)).toInt())
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                tiles.chunked(across).forEach { line ->
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        line.forEach { name ->
                            GenreTile(name, Modifier.weight(1f)) { if (name == "Christmas") onChristmas() else onGenre(name) }
                        }
                        // (a short last line keeps its tiles the same size)
                        repeat(across - line.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

/** A tall tile: the genre's picture with its name over a dark foot; Christmas has snow and a brighter rim */
@Composable
private fun GenreTile(name: String, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val christmas = name == "Christmas"
    Box(
        modifier.aspectRatio(2f / 3f).clip(shape).background(Dx.Raised).clickable(onClick = onClick)
            .border(BorderStroke(1.dp, Color.White.copy(alpha = if (christmas) 0.4f else 0.12f)), shape),
    ) {
        Image(painterResource(genrePicture(name)), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f))))
        if (christmas) Snow(flakes = 16, modifier = Modifier.fillMaxSize())
        Text(
            name,
            modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
            color = Dx.Text,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
