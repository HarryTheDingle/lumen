@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.notdxbi.dxbi.tv.lumen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.notdxbi.dxbi.tv.phone.Stremio
import com.notdxbi.dxbi.tv.phone.Title
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

/**
 * Apple TV's Genius Browse, on Home: a row of moods ("Mind-Bending Mysteries", "Charming Romcoms"), each a soft colour
 * tile. Choosing one doesn't open a page: a row of that mood comes up under it (films and shows mixed), the remote goes
 * onto it, and "More like …" its first title follows. Customise beside the row's name chooses TV Shows & Movies, TV
 * Shows or Movies. Each mood is a Cinemeta genre's popular and best-rated titles that are also of one of its other
 * genres (no TMDB key needed).
 */
data class Mood(val name: String, val genre: String, val with: Set<String>, val top: Color, val bottom: Color, val shows: Boolean = true, val films: Boolean = true)

val MOODS = listOf(
    Mood("Secrets, Lies & Spies", "Thriller", setOf("Action", "Crime", "Mystery"), Color(0xFF8E2DE2), Color(0xFF3A1C71)),
    Mood("Mind-Bending Mysteries", "Mystery", setOf("Sci-Fi", "Thriller", "Fantasy"), Color(0xFF4A3AFF), Color(0xFF1B1464)),
    Mood("Charming Romcoms", "Romance", setOf("Comedy"), Color(0xFF13C2B3), Color(0xFF0B5E6B)),
    Mood("Good for a Night In", "Comedy", setOf("Drama", "Romance"), Color(0xFF2BD96B), Color(0xFF0B6B3A)),
    Mood("Gripping Crime", "Crime", setOf("Thriller", "Action"), Color(0xFF3A6BFF), Color(0xFF0F1F66)),
    Mood("Epic Adventures", "Adventure", setOf("Fantasy", "Action"), Color(0xFF1FA2FF), Color(0xFF0B3B66)),
    Mood("Into the Future", "Sci-Fi", setOf("Drama", "Action", "Thriller"), Color(0xFF6A5CFF), Color(0xFF16124A)),
    Mood("Things That Go Bump", "Horror", setOf("Mystery", "Thriller", "Fantasy"), Color(0xFFE2335E), Color(0xFF4A0F24)),
    Mood("Family Movie Night", "Family", setOf("Comedy", "Adventure", "Animation"), Color(0xFFFFA62B), Color(0xFF8A3B0B), shows = false),
    Mood("True Stories", "Biography", setOf("Drama", "History", "Sport"), Color(0xFFB8A15A), Color(0xFF3D3415)),
    Mood("Laugh-Out-Loud Shows", "Comedy", emptySet(), Color(0xFFFFD43B), Color(0xFF8A6A0B), films = false),
    Mood("War & Heroes", "War", setOf("Drama", "History", "Action"), Color(0xFF7A8B5A), Color(0xFF232B14)),
)

/** What Genius Browse has open: the mood chosen, its titles, and which kind (0 both, 1 shows, 2 films) */
@Stable
class GeniusState {
    var mood by mutableStateOf<Mood?>(null)
    var kind by mutableIntStateOf(0)
    var titles by mutableStateOf<List<Title>>(emptyList())
    var moreLike by mutableStateOf<List<Title>>(emptyList())
    /** Where the remote goes once the mood's row is in */
    val landing = FocusRequester()
    var land by mutableStateOf(false)
}

/** A mood's titles: its genre's popular and best rated, films and shows taken in turn, those also of one of its other genres */
suspend fun moodTitles(feed: DiscoverFeed, mood: Mood, kind: Int): List<Title> = coroutineScope {
    val types = listOfNotNull("series".takeIf { mood.shows && kind != 2 }, "movie".takeIf { mood.films && kind != 1 })
    val lists = types.map { type ->
        async {
            val all = (feed.popular(type, mood.genre) + feed.topRated(type, mood.genre)).map { it.title }.distinctBy { it.id }
            all.filter { t -> mood.with.isEmpty() || t.genres.any { it in mood.with } }
        }
    }.awaitAll()
    // (shows and films in turn, as Apple TV's mood rows mix them)
    val out = ArrayList<Title>()
    val longest = lists.maxOfOrNull { it.size } ?: 0
    for (i in 0 until longest) lists.forEach { l -> l.getOrNull(i)?.let(out::add) }
    out.distinctBy { it.id }.take(30)
}

/** Home's Genius Browse row, and the mood's own rows under it once one's chosen */
fun LazyListScope.geniusBrowse(state: GeniusState, feed: DiscoverFeed?, stremio: Stremio, onOpen: (Title) -> Unit) {
    if (feed == null) return
    item(key = "genius") {
        Shelf("Genius Browse") {
            items(MOODS, key = { it.name }) { m ->
                MoodTile(m) {
                    if (state.mood != m) { state.mood = m; state.titles = emptyList(); state.moreLike = emptyList() }
                    state.land = true
                }
            }
        }
    }
    val mood = state.mood ?: return
    item(key = "genius-mood") {
        LaunchedEffect(mood, state.kind) {
            state.titles = emptyList()
            state.titles = moodTitles(feed, mood, state.kind)
            state.moreLike = state.titles.firstOrNull()?.let { t -> runCatching { stremio.related(t.type, t.id, t.genres) }.getOrDefault(emptyList()) }.orEmpty()
        }
        // (once its titles are in: the remote onto the first, as Apple TV's moves onto the new row)
        LaunchedEffect(state.titles, state.land) {
            if (!state.land || state.titles.isEmpty()) return@LaunchedEffect
            repeat(30) {
                if (runCatching { state.landing.requestFocus() }.isSuccess) { state.land = false; return@LaunchedEffect }
                delay(32)
            }
        }
        MoodRow(state, onOpen)
    }
    if (state.moreLike.isNotEmpty()) item(key = "genius-more") {
        Shelf("More like ${state.titles.firstOrNull()?.name.orEmpty()}") {
            items(state.moreLike.take(20), key = { it.id }) { t -> PreviewPosterCard(t, onClick = { onOpen(t) }) }
        }
    }
}

/** A mood's tile: a poster's shape, its colours soft and glowing, its name low on the left */
@Composable
private fun MoodTile(mood: Mood, onClick: () -> Unit) {
    FocusCard(onClick = onClick, modifier = Modifier.width(Dx.Poster).height(Dx.Poster * 1.5f)) {
        Box(
            Modifier.fillMaxSize()
                .background(Brush.linearGradient(listOf(mood.top, mood.bottom)))
                .background(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.28f), Color.Transparent))),
        ) {
            Text(
                mood.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White, lineHeight = 17.sp, maxLines = 3,
                modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
            )
        }
    }
}

/** The mood's row: its name, Customise (which kind), and its titles; the remote lands on the first */
@Composable
private fun MoodRow(state: GeniusState, onOpen: (Title) -> Unit) {
    val mood = state.mood ?: return
    Column(Modifier.fillMaxWidth().centredWhenFocused()) {
        Row(Modifier.padding(horizontal = Dx.Gutter), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(mood.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
            // (OK goes round TV Shows & Movies, TV Shows, Movies: a mood that's only one kind has nothing to choose)
            if (mood.shows && mood.films) {
                val shape = RoundedCornerShape(50)
                Surface(
                    onClick = { state.kind = (state.kind + 1) % 3; state.land = true },
                    shape = ClickableSurfaceDefaults.shape(shape),
                    colors = ClickableSurfaceDefaults.colors(containerColor = Glass.Fill, contentColor = Color.White, focusedContainerColor = Color.White, focusedContentColor = Color.Black),
                    border = ClickableSurfaceDefaults.border(border = androidx.tv.material3.Border(BorderStroke(1.dp, Glass.Edge), shape = shape)),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
                ) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(listOf("TV Shows & Movies", "TV Shows", "Movies")[state.kind], fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        CompositionLocalProvider(LocalBringIntoViewSpec provides RowScrolling) {
            if (state.titles.isEmpty()) {
                // (a row of empty cards while the titles come)
                LazyRow(contentPadding = PaddingValues(start = Dx.Gutter, end = Dx.Gutter, top = 14.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    items(6) { Box(Modifier.width(Dx.Poster).height(Dx.Poster * 1.5f + CAPTION_ROOM).padding(bottom = CAPTION_ROOM).background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp))) }
                }
            } else {
                LazyRow(
                    modifier = Modifier.focusRestorer { state.landing },
                    contentPadding = PaddingValues(start = Dx.Gutter, end = Dx.Gutter, top = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    itemsIndexed(state.titles, key = { _, t -> t.id }) { i, t ->
                        if (i == 0) Box(Modifier.focusRequester(state.landing)) { PreviewPosterCard(t, onClick = { onOpen(t) }) }
                        else PreviewPosterCard(t, onClick = { onOpen(t) })
                    }
                }
            }
        }
    }
}
