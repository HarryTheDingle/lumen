package com.notdxbi.dxbi.tv.lumen

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.notdxbi.dxbi.tv.R
import com.notdxbi.dxbi.tv.phone.Title
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope

/**
 * A genre on Discover's Genres row: its name, how Cinemeta spells it, a second genre shown with it (Thriller
 * & Horror is one page), and its colour, for the tile's tint. Anime has no Cinemeta genre: its page is the
 * Kitsu catalogs'.
 */
data class Genre(val name: String, val cinemeta: String, val tint: Color, val also: String? = null)

val GENRES = listOf(
    Genre("Action", "Action", Color(0xFFD9481F)),
    Genre("Anime", "Animation", Color(0xFFD6336C)),
    Genre("Animation", "Animation", Color(0xFFF08C00)),
    Genre("Christmas", "", Color(0xFFB3202A)),
    Genre("Comedy", "Comedy", Color(0xFFE8A317)),
    Genre("Crime", "Crime", Color(0xFF5C6B7A)),
    Genre("Documentary", "Documentary", Color(0xFF2F9E6B)),
    Genre("Drama", "Drama", Color(0xFF7048E8)),
    Genre("Family", "Family", Color(0xFF1C9CD8)),
    Genre("Romance", "Romance", Color(0xFFE64980)),
    Genre("Sci-Fi", "Sci-Fi", Color(0xFF2F6BE8)),
    Genre("Thriller & Horror", "Thriller", Color(0xFF7A2A3A), also = "Horror"),
)

/** Mid-November to early January: Christmas leads the Genres row */
fun inChristmasSeason(): Boolean {
    val c = java.util.Calendar.getInstance()
    val m = c.get(java.util.Calendar.MONTH)
    val d = c.get(java.util.Calendar.DAY_OF_MONTH)
    return (m == java.util.Calendar.NOVEMBER && d >= 15) || m == java.util.Calendar.DECEMBER || (m == java.util.Calendar.JANUARY && d <= 6)
}

/** The picture of a genre's tile (graphics/genres: a photo in the genre's colour), by the genre's name */
private fun genreDrawable(name: String): Int = when (name) {
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

/** A genre as a wide picture in its own colour, its name bottom-left */
@Composable
fun GenreTile(genre: Genre, onClick: () -> Unit) {
    val width = (LocalConfiguration.current.screenWidthDp * 0.2f).dp
    FocusCard(onClick = onClick, corner = 12.dp, modifier = Modifier.size(width, width * 0.5625f)) {
        Box(Modifier.fillMaxSize()) {
            Image(painterResource(genreDrawable(genre.name)), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Text(
                genre.name,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 9.dp, end = 8.dp),
            )
        }
    }
}

/**
 * One genre: its popular and best rated films and series, each its own row (Cinemeta's catalogs; the name
 * large on top). Nothing to switch: they're separate rows.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun GenrePage(nav: Nav, feed: DiscoverFeed, name: String) {
    val entry = GENRES.firstOrNull { it.name == name }
    val genre = entry?.cinemeta?.takeIf { it.isNotEmpty() } ?: name
    // (each row shows as soon as it's in, not all together after the slowest: the catalogs are big and slow)
    var rows by remember { mutableStateOf<List<Pair<String, List<Title>>>?>(null) }
    LaunchedEffect(genre) {
        val slots = HashMap<String, List<Title>>()
        var order = emptyList<String>()
        var pending = 0
        fun publish() {
            val shown = order.mapNotNull { n -> slots[n]?.takeIf { it.isNotEmpty() }?.let { n to it } }
            // (still "Loading…" until there's a row to show, or nothing more is coming)
            if (shown.isNotEmpty() || pending <= 0) rows = shown
        }
        coroutineScope {
            if (name == "Anime") {
                // (anime: the Kitsu catalogs, which is where Lumen's anime comes from)
                val lists = listOf("Trending" to "kitsu-anime-trending", "Airing Now" to "kitsu-anime-airing", "Popular" to "kitsu-anime-popular", "Top Rated" to "kitsu-anime-rating")
                order = lists.map { it.first }
                pending = lists.size
                for ((n, c) in lists) launch {
                    // (later seasons hidden but in Airing Now: the lists show each show once)
                    val l = feed.anime(c).map { it.title }.filter { n == "Airing Now" || !feed.isSequel(it.name) }.take(30)
                    slots[n] = if (l.size >= 4) l else emptyList()
                    pending--
                    publish()
                }
            } else {
                val keys = listOfNotNull(genre, entry?.also)
                // (one genre or two: the lists of each, taking turns, each title once)
                suspend fun merged(fetch: suspend (String) -> List<DiscoverFeed.Item>): List<Title> {
                    val all = keys.map { fetch(it) }
                    val seen = HashSet<String>()
                    val out = ArrayList<Title>()
                    for (i in 0 until (all.maxOfOrNull { it.size } ?: 0)) for (l in all) if (i < l.size && seen.add(l[i].title.id)) out.add(l[i].title)
                    return out.take(30)
                }
                val jobs = listOf<Pair<String, suspend () -> List<Title>>>(
                    "Popular Films" to { merged { feed.popular("movie", it) } },
                    "Popular Series" to { merged { feed.popular("series", it) } },
                    "Top Rated Films" to { merged { feed.topRated("movie", it) } },
                    "Top Rated Series" to { merged { feed.topRated("series", it) } },
                )
                order = jobs.map { it.first }
                pending = jobs.size
                for ((n, job) in jobs) launch {
                    slots[n] = job()
                    pending--
                    publish()
                }
            }
        }
        publish()
    }
    val first = remember { FocusRequester() }
    LaunchedEffect(rows != null) {
        if (rows != null) {
            kotlinx.coroutines.delay(120)
            runCatching { first.requestFocus() }
        }
    }

    PageWindow(onBack = { nav.back() }, home = first) {
        val tint = GENRES.firstOrNull { it.name == name }?.tint ?: Color(0xFF444444)
        Box(Modifier.fillMaxSize().background(Dx.Background).background(Brush.verticalGradient(0f to tint.copy(alpha = 0.3f), 0.4f to Color.Black))) {
            // (the page scrolls as Home and Discover do: without it every move bounced, and again as a card opened)
            CompositionLocalProvider(LocalBringIntoViewSpec provides ScreenScrolling) {
            LazyColumn(contentPadding = PaddingValues(top = 48.dp, bottom = 60.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                item(key = "title") {
                    Text(name, fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(horizontal = Dx.Gutter))
                }
                val r = rows
                if (r == null) {
                    item(key = "loading") { LoadingShelf("Loading…") }
                } else if (r.isEmpty()) {
                    item(key = "empty") { Text("Nothing here right now. Check the TV's connection.", fontSize = 16.sp, color = Dx.Muted, modifier = Modifier.padding(horizontal = Dx.Gutter)) }
                }
                r?.forEachIndexed { i, (n, list) ->
                    item(key = n) {
                        // (the remote starts on the first row)
                        Shelf(n, if (i == 0) Modifier.focusRequester(first) else Modifier) {
                            items(list.distinctBy { it.id }, key = { it.id }) { t -> PreviewPosterCard(t, onClick = { nav.open(t) }) }
                        }
                    }
                }
            }
            }
        }
    }
}
