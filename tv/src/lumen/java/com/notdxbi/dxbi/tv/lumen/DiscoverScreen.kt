package com.notdxbi.dxbi.tv.lumen

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import androidx.compose.foundation.lazy.LazyListScope
import kotlinx.coroutines.launch
import com.notdxbi.dxbi.tv.phone.Dates
import com.notdxbi.dxbi.tv.phone.ListRow
import com.notdxbi.dxbi.tv.phone.Stremio
import com.notdxbi.dxbi.tv.phone.Title
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

/** A row of Discover: posters, with a date under each for releases */
private data class FeedRow(val id: String, val title: String, val items: List<Title>, val dates: Map<String, Long> = emptyMap())

/** Each tab's rows, once made (kept while Lumen's open, so coming back is instant) */
private val built = ConcurrentHashMap<String, List<FeedRow>>()

private const val DAY = 86_400_000L

/**
 * Discover, by streaming service (as on the iPad: ios/Dxbi/UI/DiscoverView.swift): For You (what's new on
 * digital, from the Library first, then popular and genre rows), or one service's own page in its colours
 * (new, popular series and films, its genres). The full catalog browser is at the bottom.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DiscoverScreen(stremio: Stremio, nav: Nav, feed: DiscoverFeed) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("lumen", Context.MODE_PRIVATE) }
    var selected by remember { mutableStateOf(prefs.getString("discover.platform", "") ?: "") }
    val library by stremio.library.collectAsState()
    val picks by stremio.picks.collectAsState()
    val watchedIds by stremio.watchedIds.collectAsState()
    val viewer by stremio.viewer.collectAsState()
    val tasteState = LocalTaste.current
    val cacheKey = if (selected.isEmpty()) "forYou:${viewer?.id.orEmpty()}" else selected
    var rows by remember { mutableStateOf(built[cacheKey].orEmpty()) }
    var loadedFor by remember { mutableStateOf(if (built.containsKey(cacheKey)) selected else null) }
    val platform = DiscoverFeed.platforms.firstOrNull { it.id == selected }

    LaunchedEffect(Unit) {
        if (library.isEmpty()) stremio.loadLibrary(null)
        if (picks.isEmpty()) stremio.refreshExtras()
    }

    // whether it's Christmas season, and the installed addons: Anime has a tile only with the Kitsu addon,
    // as without it Stremio can't play an anime title
    val inSeason = remember { feed.christmasInSeason() }
    val addons by stremio.installedAddons.collectAsState()
    LaunchedEffect(Unit) { stremio.loadAddons() }
    val hasKitsu = addons.any { it.id.contains("kitsu", ignoreCase = true) || it.transportUrl.contains("kitsu", ignoreCase = true) }
    // The last tab's rows stay until the new ones are in: a page that emptied and refilled would jump.
    LaunchedEffect(
        selected,
        if (selected.isEmpty()) picks else null,
        if (selected.isEmpty()) viewer?.id else null,
        if (selected.isEmpty()) watchedIds else null,
    ) {
        val made = if (platform != null) {
            built[cacheKey] ?: platformRows(feed, platform)
        } else {
            forYouRows(feed, stremio, picks, watchedIds, viewer?.id, tasteState?.current?.genres.orEmpty())
        }
        built[cacheKey] = made
        rows = made
        loadedFor = selected
    }

    fun choose(id: String) {
        selected = id
        prefs.edit().putString("discover.platform", id).apply()
    }

    val top by animateColorAsState(platform?.colour?.copy(alpha = 0.42f) ?: Color(0xFF1A1A1F), tween(Dx.Slow), label = "discover-top")
    val deep by animateColorAsState(platform?.deep ?: Color(0xFF0B0B0D), tween(Dx.Slow), label = "discover-deep")

    Box(
        Modifier
            .fillMaxSize()
            .background(Dx.Background)
            .background(Brush.verticalGradient(0f to top, 0.28f to deep, 0.62f to Dx.Background)),
    ) {
        val pageListState = androidx.compose.foundation.lazy.rememberLazyListState()
        val pageScope = androidx.compose.runtime.rememberCoroutineScope()
        val pageScroller = remember(pageListState) { PageScroller(pageListState, pageScope) }
        val forYou = selected.isEmpty()
        // The page's items in order, as the column below lays them out: what a section shortcut scrolls to
        val keys = buildList {
            add("top"); add("services")
            if (forYou) add("sections")
            if (loadedFor == null) repeat(3) { add("loading-$it") } else if (rows.isEmpty() && loadedFor == selected) add("empty")
            rows.forEachIndexed { i, row ->
                add("row-${row.id}")
                if (forYou && i == minOf(1, rows.lastIndex)) add("genres")
                if (forYou && i == minOf(3, rows.lastIndex)) add("franchises")
            }
            if (forYou && rows.isEmpty()) { add("genres"); add("franchises") }
        }
        // a requester for each row a shortcut can go to, plus which section currently holds focus
        val landing = remember { HashMap<String, FocusRequester>() }
        fun landingFor(key: String) = landing.getOrPut(key) { FocusRequester() }
        var focusedSection by remember { mutableStateOf<String?>(null) }
        fun Modifier.trackSection(key: String): Modifier = this
            .focusRequester(landingFor(key))
            .onFocusChanged {
                if (it.hasFocus) focusedSection = key
                else if (focusedSection == key) focusedSection = null
            }
        var jumpJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
        fun goTo(key: String) {
            val i = keys.indexOf(key)
            if (i < 0) return
            jumpJob?.cancel()
            jumpJob = pageScope.launch {
                pageScroller.jumpTo(i, centre = true, onVisible = {
                    runCatching { landingFor(key).requestFocus() }
                    focusedSection == key
                })
            }
        }
        // Back, down the page: to the top and the services first (then, from there, Home)
        val chosenPill = remember { FocusRequester() }
        val scrolled by remember { androidx.compose.runtime.derivedStateOf { pageListState.firstVisibleItemIndex > 1 } }
        androidx.activity.compose.BackHandler(enabled = scrolled && nav.pages.isEmpty() && !nav.menuOpen) {
            jumpJob?.cancel()
            jumpJob = pageScope.launch {
                pageScroller.jumpTo(0, onVisible = { runCatching { chosenPill.requestFocus() }.isSuccess })
            }
        }
        PageColumn(state = pageListState, scroller = pageScroller) {
                item(key = "top") { ScreenTag(nav) }
                item(key = "services") {
                    // (each service its icon until the remote's on it: the whole row fits across the screen)
                    Row(Modifier.padding(horizontal = Dx.Gutter, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Pill("For You", selected = forYou, onClick = { choose("") }, compact = true, modifier = if (forYou) Modifier.focusRequester(chosenPill) else Modifier, leading = {
                            Image(painterResource(com.notdxbi.dxbi.tv.R.mipmap.ic_launcher), contentDescription = null, modifier = Modifier.size(24.dp).clip(RoundedCornerShape(Dx.RCard)))
                        })
                        DiscoverFeed.platforms.forEach { p ->
                            Pill(p.name, selected = selected == p.id, onClick = { choose(p.id) }, tint = p.colour, compact = true, modifier = if (selected == p.id) Modifier.focusRequester(chosenPill) else Modifier, leading = {
                                AsyncImage(model = p.icon, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(24.dp).clip(RoundedCornerShape(Dx.RCard)).background(p.colour.copy(alpha = 0.4f)))
                            })
                        }
                        if (loadedFor != selected && loadedFor != null) Text("Loading titles…", fontSize = 14.sp, color = Dx.Muted, modifier = Modifier.padding(start = 8.dp))
                    }
                }
                // For You: what's on the page, each a press away (so the Genres, Franchises and digital rows are found)
                if (forYou) item(key = "sections") {
                    val sections = buildList {
                        rows.firstOrNull { it.id.startsWith("pick-") }?.let { add("Top Picks" to "row-${it.id}") }
                        add("Genres" to "genres")
                        if (rows.any { it.id == "digital" }) add("New on Digital" to "row-digital")
                        if (rows.any { it.id == "coming" }) add("Coming Soon" to "row-coming")
                        if (rows.any { it.id == "films" }) add("Popular Films" to "row-films")
                        if (rows.any { it.id == "series" }) add("Popular Series" to "row-series")
                        add("Franchises" to "franchises")
                    }
                    val pillScrolling = rememberRowScrolling(Dx.Gutter, 8.dp)
                    CompositionLocalProvider(LocalBringIntoViewSpec provides pillScrolling) {
                        LazyRow(contentPadding = PaddingValues(start = Dx.Gutter, end = Dx.Gutter, top = 4.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            item { Text("Jump to", fontSize = 14.sp, color = Dx.Muted, modifier = Modifier.padding(end = 4.dp)) }
                            items(sections, key = { it.second }) { (label, key) -> Pill(label, selected = false, onClick = { goTo(key) }) }
                        }
                    }
                }
                if (loadedFor == null) {
                    items(3, key = { "loading-$it" }) { LoadingShelf(null) }
                } else if (rows.isEmpty() && loadedFor == selected) {
                    item(key = "empty") {
                        Text("Can't connect. Check your connection and try again.", fontSize = 16.sp, color = Dx.Muted, modifier = Modifier.padding(horizontal = Dx.Gutter))
                    }
                }
                rows.forEachIndexed { i, row ->
                    item(key = "row-${row.id}") {
                        Shelf(row.title, Modifier.animateItem(fadeInSpec = tween(Dx.Med), placementSpec = null, fadeOutSpec = tween(200)).trackSection("row-${row.id}")) {
                            items(row.items, key = { it.id }) { t ->
                                val date = row.dates[t.id]
                                if (date != null) DatedPoster(t, date) { nav.open(t) } else PreviewPosterCard(t, onClick = { nav.open(t) })
                            }
                        }
                    }
                    // the Genres row, after the digital rows (For You only)
                    if (forYou && i == minOf(1, rows.lastIndex)) genresItem(nav, inSeason, hasKitsu, Modifier.trackSection("genres"))
                    // (then the Franchises row, after the popular ones, as on the iPad)
                    if (forYou && i == minOf(3, rows.lastIndex)) franchisesItem(stremio, nav, Modifier.trackSection("franchises"))
                }
                if (forYou && rows.isEmpty()) { genresItem(nav, inSeason, hasKitsu, Modifier.trackSection("genres")); franchisesItem(stremio, nav, Modifier.trackSection("franchises")) }
        }
    }
}

/** The Genres row: Christmas first while it's the season, else with the others */
internal fun LazyListScope.genresItem(nav: Nav, inSeason: Boolean, hasKitsu: Boolean, modifier: Modifier = Modifier) {
    item(key = "genres") {
        val all = GENRES.filter { it.name != "Anime" || hasKitsu }
        val list = if (inSeason) all.sortedBy { it.name != "Christmas" } else all
        Shelf("Genres", modifier) {
            items(list, key = { it.name }) { g ->
                GenreTile(g) { nav.push(if (g.name == "Christmas") Page.Christmas else Page.Genre(g.name)) }
            }
        }
    }
}

/** Search's genres, with nothing typed: portrait tiles in rows down the page */
internal fun LazyListScope.genresGrid(nav: Nav, inSeason: Boolean, perRow: Int) {
    val list = GENRES.filter { it.name != "Anime" }.let { all -> if (inSeason) all.sortedBy { it.name != "Christmas" } else all }
    list.chunked(perRow).forEachIndexed { i, line ->
        item(key = "genres-$i") {
            Row(
                Modifier.fillMaxWidth().centredWhenFocused().padding(horizontal = Dx.Gutter, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                line.forEach { g -> GenreTile(g, portrait = true) { nav.push(if (g.name == "Christmas") Page.Christmas else Page.Genre(g.name)) } }
            }
        }
    }
}

/** A release: its poster with the day it's out on digital under it (white when it's still to come) */
@Composable
private fun DatedPoster(t: Title, date: Long, onClick: () -> Unit) {
    // (room under the poster for it to grow into when chosen, clear of the date)
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PosterCard(t, onClick = onClick)
        Text(
            Dates.formatShortDay(date),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = if (date > System.currentTimeMillis()) Color.White else Dx.Muted,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Loading

private suspend fun forYouRows(
    feed: DiscoverFeed,
    stremio: Stremio,
    picks: List<ListRow>,
    watchedIds: Set<String>,
    viewerId: String?,
    fallbackTasteGenres: List<String>,
): List<FeedRow> = coroutineScope {
    val releases = async { feed.digitalYear() }
    val films = async { feed.popular("movie") }
    val series = async { feed.popular("series") }

    val pickRows = picks.mapNotNull { row ->
        val items = row.items.filter { it.id !in watchedIds }.distinctBy { it.id }
        if (items.isNotEmpty()) row.name to items else null
    }.mapIndexed { idx, (name, items) ->
        FeedRow("pick-$idx", name, items)
    }

    val tasteGenreRows = if (pickRows.size < 2) {
        val genres = (viewerId?.let { id ->
            runCatching { stremio.taste(id) }.getOrNull()?.let { Taste.fromJson(it.toString()) }?.genres
        }.orEmpty().ifEmpty { fallbackTasteGenres }).take(2)
        genres.mapIndexed { idx, g ->
            async {
                val cinemeta = GENRES.firstOrNull { it.name == g }?.cinemeta?.takeIf { it.isNotEmpty() } ?: g
                val m = async { feed.popular("movie", cinemeta) }
                val s = async { feed.popular("series", cinemeta) }
                val movies = m.await().map { it.title }
                val shows = s.await().map { it.title }
                val seen = HashSet<String>(watchedIds)
                val combined = ArrayList<Title>()
                val max = maxOf(movies.size, shows.size)
                for (i in 0 until max) {
                    if (i < movies.size && seen.add(movies[i].id)) combined.add(movies[i])
                    if (i < shows.size && seen.add(shows[i].id)) combined.add(shows[i])
                }
                if (combined.isNotEmpty()) FeedRow("taste-$idx", "Because you like $g", combined.take(25)) else null
            }
        }.awaitAll().filterNotNull()
    } else {
        emptyList()
    }

    val all = releases.await()
    val now = System.currentTimeMillis()
    val today = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val out = all.filter { it.date <= now }
    val recent = out.filter { it.date >= today - 42 * DAY && it.id !in watchedIds }
    val coming = all.filter { it.date > now }.sortedBy { it.date }
    val dates = all.reversed().associate { it.id to it.date }

    buildList {
        addAll(pickRows)
        addAll(tasteGenreRows)
        if (recent.isNotEmpty()) add(FeedRow("digital", "New on Digital", recent.take(30).map { it.title }, dates))
        if (coming.isNotEmpty()) add(FeedRow("coming", "Coming Soon to Digital", coming.take(25).map { it.title }, dates))
        val f = films.await().filter { it.title.id !in watchedIds }
        val s = series.await().filter { it.title.id !in watchedIds }
        if (f.isNotEmpty()) add(FeedRow("films", "Popular Films", f.take(25).map { it.title }))
        if (s.isNotEmpty()) add(FeedRow("series", "Popular Series", s.take(25).map { it.title }))
    }
}

private suspend fun platformRows(feed: DiscoverFeed, p: DiscoverFeed.Platform): List<FeedRow> = coroutineScope {
    val seriesList = async { feed.platform(p, "series") }
    val filmList = async { feed.platform(p, "movie") }
    val series = seriesList.await()
    val films = filmList.await()
    val all = series + films
    val now = System.currentTimeMillis()
    buildList {
        // new on the service: came out in the last four months, newest first
        val new = all.filter { (it.released ?: 0) >= now - 120 * DAY && (it.released ?: Long.MAX_VALUE) <= now }.sortedByDescending { it.released }
        if (new.size >= 3) add(FeedRow("new", "New on ${p.name}", new.take(25).map { it.title }))
        if (series.isNotEmpty()) add(FeedRow("series", "Popular Series", series.take(30).map { it.title }))
        if (films.isNotEmpty()) add(FeedRow("films", "Popular Films", films.take(30).map { it.title }))
        // its main genres, as rows
        val counts = all.flatMap { it.title.genres }.groupingBy { it }.eachCount()
        counts.entries.sortedByDescending { it.value }.take(6).forEach { (genre, _) ->
            val items = all.filter { genre in it.title.genres }
            if (items.size >= 5) add(FeedRow("genre-$genre", genre, items.take(25).map { it.title }))
        }
    }
}
