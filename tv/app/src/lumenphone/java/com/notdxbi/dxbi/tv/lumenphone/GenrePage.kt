package com.notdxbi.dxbi.tv.lumenphone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import com.notdxbi.dxbi.tv.phone.Title
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * One genre: its popular and best rated films and series, each a row of its own (Cinemeta's catalogs), washed in
 * the genre's colour (iOS: GenreView). Each row shows as soon as it's in, not all together after the slowest:
 * the catalogs are big and some answer late.
 */
@Composable
fun GenrePage(name: String, layout: AppLayout, bottomSpace: Dp, onBack: () -> Unit, onOpen: (Title) -> Unit) {
    val feed = discoverFeed(LocalContext.current)
    val genre = Genre.all.firstOrNull { it.name == name }
    // The rows so far, in their set order (null until there's a first one, or nothing more is coming)
    var rows by remember(name) { mutableStateOf<List<Pair<String, List<Title>>>?>(null) }

    LaunchedEffect(name) {
        val slots = HashMap<String, List<Title>>()
        var order = emptyList<String>()
        var pending = 0
        fun publish() {
            val shown = order.mapNotNull { n -> slots[n]?.takeIf { it.isNotEmpty() }?.let { n to it } }
            if (shown.isNotEmpty() || pending <= 0) rows = shown
        }
        coroutineScope {
            if (genre?.anime == true) {
                // (anime: the Kitsu catalogs, which is where Lumen's anime comes from)
                val lists = listOf("Trending" to "kitsu-anime-trending", "Airing Now" to "kitsu-anime-airing", "Popular" to "kitsu-anime-popular", "Top Rated" to "kitsu-anime-rating")
                order = lists.map { it.first }
                pending = lists.size
                for ((n, c) in lists) launch {
                    // (each show once, as its first season, with its other seasons on its page; what's airing now keeps its sequels)
                    val l = feed.anime(c).map { it.title }.filter { n == "Airing Now" || !feed.isSequel(it.name) }.take(30)
                    slots[n] = if (l.size >= 4) l else emptyList()
                    pending--
                    publish()
                }
            } else {
                val keys = listOfNotNull(genre?.key ?: name, genre?.also)
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
                    // (a list of fewer than four is left out, as it'd look like a mistake)
                    slots[n] = job().takeIf { it.size >= 4 }.orEmpty()
                    pending--
                    publish()
                }
            }
        }
        publish()
    }

    val tint = genre?.colour ?: Color(0xFF444444)
    Box(Modifier.fillMaxSize().background(Dx.Background).background(Brush.verticalGradient(0f to tint.copy(alpha = 0.4f), 0.3f to tint.copy(alpha = 0.1f), 0.6f to Dx.Background))) {
        LazyColumn(
            contentPadding = PaddingValues(top = statusBarTop() + 60.dp, bottom = bottomSpace + 40.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            item(key = "title") {
                Text(name, color = Dx.Text, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = layout.gutter))
            }
            val shown = rows
            if (shown == null) item(key = "loading") { BrowseSpinner() }
            else if (shown.isEmpty()) item(key = "empty") { BrowseEmpty("Nothing to show", "Couldn't reach the catalogs.") }
            items(shown.orEmpty(), key = { it.first }) { (n, list) -> BrowseShelf(n, list, layout, onOpen = onOpen) }
        }
        BrowseBar(null, layout, onBack)
    }
}
