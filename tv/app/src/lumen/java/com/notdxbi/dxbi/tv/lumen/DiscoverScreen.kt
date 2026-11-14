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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.GridView
import androidx.compose.runtime.Composable
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import androidx.compose.foundation.lazy.LazyListScope
import kotlinx.coroutines.launch
import com.notdxbi.dxbi.tv.phone.Load
import com.notdxbi.dxbi.tv.phone.Stremio
import com.notdxbi.dxbi.tv.phone.Title
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
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
    var rows by remember { mutableStateOf(built[selected].orEmpty()) }
    var loadedFor by remember { mutableStateOf(if (built.containsKey(selected)) selected else null) }
    val library by stremio.library.collectAsState()
    val platform = DiscoverFeed.platforms.firstOrNull { it.id == selected }

    LaunchedEffect(Unit) { if (library.isEmpty()) stremio.loadLibrary(null) }

    // whether it's Christmas season (kept, so the row doesn't jump about when it opens again), and the installed
    // addons: Anime has a tile only with the Kitsu addon, as without it Stremio can't play an anime title
    var inSeason by remember { mutableStateOf(prefs.getBoolean("discover.christmas", false)) }
    val addons by stremio.installedAddons.collectAsState()
    LaunchedEffect(Unit) { stremio.loadAddons() }
    val hasKitsu = addons.any { it.id.contains("kitsu", ignoreCase = true) || it.transportUrl.contains("kitsu", ignoreCase = true) }
    LaunchedEffect(Unit) {
        val c = stremio.christmas() ?: return@LaunchedEffect
        inSeason = c.inSeason
        prefs.edit().putBoolean("discover.christmas", c.inSeason).apply()
    }
    // (For You again once the Library's in: its "out on digital" row follows it.) The last tab's rows
    // stay until the new ones are in: a page that emptied and refilled would jump.
    // (For You: what's new and coming on digital, then popular. Top Picks and Because you watched are Home's, the genre rows
    // are the Genres tiles', and the Library's films out on digital are the Calendar's: none of them twice)
    LaunchedEffect(selected) {
        val made = if (platform != null) platformRows(feed, platform) else forYouRows(feed)
        built[selected] = made
        rows = made
        loadedFor = selected
    }

    fun choose(id: String) {
        selected = id
        prefs.edit().putString("discover.platform", id).apply()
    }

    val top by animateColorAsState(platform?.colour?.copy(alpha = 0.42f) ?: Color(0xFF1A1A1F), tween(450), label = "discover-top")
    val deep by animateColorAsState(platform?.deep ?: Color(0xFF0B0B0D), tween(450), label = "discover-deep")

    Box(
        Modifier
            .fillMaxSize()
            .background(Dx.Background)
            .background(Brush.verticalGradient(0f to top, 0.28f to deep, 0.62f to Dx.Background)),
    ) {
        val pageListState = androidx.compose.foundation.lazy.rememberLazyListState()
        val pageScope = androidx.compose.runtime.rememberCoroutineScope()
        val pageScroller = remember { PageScroller(pageListState, pageScope) }
        val forYou = selected.isEmpty()
        // The page's items in order, as the column below lays them out: what a section shortcut scrolls to
        val keys = buildList {
            add("top"); add("services")
            if (forYou) add("sections")
            if (loadedFor == null) add("loading") else if (rows.isEmpty() && loadedFor == selected) add("empty")
            rows.forEachIndexed { i, row ->
                add("row-${row.id}")
                if (forYou && i == minOf(1, rows.lastIndex)) add("genres")
                if (forYou && i == minOf(3, rows.lastIndex)) add("franchises")
            }
            if (forYou && rows.isEmpty()) { add("genres"); add("franchises") }
            add("browse")
        }
        // a requester for each row a shortcut can go to
        val landing = remember { HashMap<String, FocusRequester>() }
        fun landingFor(key: String) = landing.getOrPut(key) { FocusRequester() }
        fun goTo(key: String) {
            val i = keys.indexOf(key)
            if (i < 0) return
            pageScope.launch {
                pageListState.animateScrollToItem(i)
                repeat(20) {
                    if (runCatching { landingFor(key).requestFocus() }.isSuccess) return@launch
                    kotlinx.coroutines.delay(30)
                }
            }
        }
        // Back, down the page: to the top and the services first (then, from there, Home)
        val chosenPill = remember { FocusRequester() }
        val scrolled by remember { androidx.compose.runtime.derivedStateOf { pageListState.firstVisibleItemIndex > 1 } }
        androidx.activity.compose.BackHandler(enabled = scrolled && nav.pages.isEmpty() && !nav.menuOpen) {
            pageScope.launch {
                pageListState.animateScrollToItem(0)
                runCatching { chosenPill.requestFocus() }
            }
        }
        CompositionLocalProvider(LocalBringIntoViewSpec provides CentredScrolling, LocalPageScroller provides pageScroller) {
            LazyColumn(state = pageListState, modifier = Modifier.onGloballyPositioned { pageScroller.viewport = it }, contentPadding = PaddingValues(bottom = pageBottomPad()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item(key = "top") { ScreenTag(nav) }
                item(key = "services") {
                    // (each service its icon until the remote's on it: the whole row fits across the screen)
                    Row(Modifier.padding(horizontal = Dx.Gutter, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Pill("For You", selected = forYou, onClick = { choose("") }, compact = true, modifier = if (forYou) Modifier.focusRequester(chosenPill) else Modifier, leading = {
                            Image(painterResource(com.notdxbi.dxbi.tv.R.mipmap.ic_launcher), contentDescription = null, modifier = Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)))
                        })
                        DiscoverFeed.platforms.forEach { p ->
                            Pill(p.name, selected = selected == p.id, onClick = { choose(p.id) }, tint = p.colour, compact = true, modifier = if (selected == p.id) Modifier.focusRequester(chosenPill) else Modifier, leading = {
                                AsyncImage(model = p.icon, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)).background(p.colour.copy(alpha = 0.4f)))
                            })
                        }
                        if (loadedFor != selected && loadedFor != null) Text("Loading…", fontSize = 14.sp, color = Dx.Muted, modifier = Modifier.padding(start = 8.dp))
                    }
                }
                // For You: what's on the page, each a press away (so the Genres, Franchises and digital rows are found)
                if (forYou) item(key = "sections") {
                    val sections = buildList {
                        if (rows.any { it.id == "digital" }) add("New on Digital" to "row-digital")
                        if (rows.any { it.id == "coming" }) add("Coming Soon" to "row-coming")
                        add("Genres" to "genres")
                        if (rows.any { it.id == "films" }) add("Popular Films" to "row-films")
                        if (rows.any { it.id == "series" }) add("Popular Series" to "row-series")
                        add("Franchises" to "franchises")
                        add("All Catalogs" to "browse")
                    }
                    CompositionLocalProvider(LocalBringIntoViewSpec provides RowScrolling) {
                        LazyRow(contentPadding = PaddingValues(start = Dx.Gutter, end = Dx.Gutter, top = 4.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            item { Text("Jump to", fontSize = 14.sp, color = Dx.Muted, modifier = Modifier.padding(end = 4.dp)) }
                            items(sections, key = { it.second }) { (label, key) -> Pill(label, selected = false, onClick = { if (key == "browse") nav.push(Page.Catalogs) else goTo(key) }) }
                        }
                    }
                }
                if (loadedFor == null) {
                    item(key = "loading") { LoadingShelf("Loading…") }
                } else if (rows.isEmpty() && loadedFor == selected) {
                    item(key = "empty") {
                        Text("Couldn't reach the catalogs. Check the TV's connection.", fontSize = 16.sp, color = Dx.Muted, modifier = Modifier.padding(horizontal = Dx.Gutter))
                    }
                }
                rows.forEachIndexed { i, row ->
                    item(key = "row-${row.id}") {
                        Shelf(row.title, Modifier.focusRequester(landingFor("row-${row.id}"))) {
                            items(row.items, key = { it.id }) { t ->
                                val date = row.dates[t.id]
                                if (date != null) DatedPoster(t, date) { nav.open(t) } else PreviewPosterCard(t, onClick = { nav.open(t) })
                            }
                        }
                    }
                    // the Genres row, after the digital rows (For You only)
                    if (forYou && i == minOf(1, rows.lastIndex)) genresItem(nav, inSeason, hasKitsu, Modifier.focusRequester(landingFor("genres")))
                    // (then the Franchises row, after the popular ones, as on the iPad)
                    if (forYou && i == minOf(3, rows.lastIndex)) franchisesItem(stremio, nav, Modifier.focusRequester(landingFor("franchises")))
                }
                if (forYou && rows.isEmpty()) { genresItem(nav, inSeason, hasKitsu, Modifier.focusRequester(landingFor("genres"))); franchisesItem(stremio, nav, Modifier.focusRequester(landingFor("franchises"))) }
                item(key = "browse") {
                    Button(
                        onClick = { nav.push(Page.Catalogs) },
                        modifier = Modifier.padding(horizontal = Dx.Gutter),
                        colors = ButtonDefaults.colors(
                            containerColor = Color.White.copy(alpha = 0.14f),
                            contentColor = Color.White,
                            focusedContainerColor = Color.White,
                            focusedContentColor = Color.Black,
                        ),
                    ) {
                        Icon(Icons.Filled.GridView, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Browse All Catalogs", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
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

/** A release: its poster with the day it's out on digital under it (white when it's still to come) */
@Composable
private fun DatedPoster(t: Title, date: Long, onClick: () -> Unit) {
    // (room under the poster for it to grow into when chosen, clear of the date)
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PosterCard(t, onClick = onClick)
        Text(
            SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(date)),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = if (date > System.currentTimeMillis()) Color.White else Dx.Muted,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Loading

private suspend fun forYouRows(feed: DiscoverFeed): List<FeedRow> = coroutineScope {
    val releases = async { feed.digitalYear() }
    val films = async { feed.popular("movie") }
    val series = async { feed.popular("series") }
    val all = releases.await()
    val now = System.currentTimeMillis()
    val today = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val out = all.filter { it.date <= now }
    val recent = out.filter { it.date >= today - 42 * DAY }
    val coming = all.filter { it.date > now }.sortedBy { it.date }
    val dates = all.reversed().associate { it.id to it.date }

    buildList {
        if (recent.isNotEmpty()) add(FeedRow("digital", "New on Digital", recent.take(30).map { it.title }, dates))
        if (coming.isNotEmpty()) add(FeedRow("coming", "Coming Soon to Digital", coming.take(25).map { it.title }, dates))
        val f = films.await()
        val s = series.await()
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

// ---------------------------------------------------------------------------------------------
// Every catalog

/** Stremio's catalogs, one at a time: its kind, catalog and genre to choose, then a grid of posters */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun CatalogBrowser(stremio: Stremio, nav: Nav) {
    val discover by stremio.discover.collectAsState()
    val allRows by stremio.allRows.collectAsState()
    LaunchedEffect(allRows.size) { stremio.discoverStart() }
    val first = remember { FocusRequester() }
    var asked by remember { mutableIntStateOf(-1) }

    PageWindow(onBack = { nav.back() }, home = first) {
        val d = discover
        FocusOnOpen(first, d != null)
        // (the grid scrolls as the rest of Lumen does: the row the remote's on glides to the middle, once, no bouncing)
        Box(Modifier.fillMaxSize().background(Dx.Background)) {
            CompositionLocalProvider(LocalBringIntoViewSpec provides CentredScrolling) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(Dx.Poster + 14.dp),
                contentPadding = PaddingValues(start = Dx.Gutter, end = Dx.Gutter, top = 30.dp, bottom = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text("All Catalogs", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
                if (d == null) {
                    item(span = { GridItemSpan(maxLineSpan) }) { Text("Loading…", fontSize = 16.sp, color = Dx.Muted) }
                    return@LazyVerticalGrid
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    // (the choice rows scroll sideways by the rows' own rule)
                    CompositionLocalProvider(LocalBringIntoViewSpec provides RowScrolling) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        val types = d.types.filter { it.name !in LEFT_OUT }
                        if (types.size > 1) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(types) { t ->
                                    Pill(
                                        when (t.name) { "movie" -> "Movies"; "series" -> "TV Shows"; else -> typeName(t.name) },
                                        selected = t.selected,
                                        onClick = { t.link?.let(stremio::discover) },
                                        modifier = if (t == types.first()) Modifier.focusRequester(first) else Modifier,
                                    )
                                }
                            }
                        }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(d.catalogs) { c ->
                                Pill(
                                    c.name,
                                    selected = c.selected,
                                    onClick = { c.link?.let(stremio::discover) },
                                    modifier = if (types.size <= 1 && c == d.catalogs.first()) Modifier.focusRequester(first) else Modifier,
                                )
                            }
                        }
                        if (d.genres.isNotEmpty()) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                item { Pill("All Genres", selected = d.genre == null, onClick = { stremio.discoverGenre(null) }) }
                                items(d.genres) { g -> Pill(g, selected = d.genre == g, onClick = { stremio.discoverGenre(g) }) }
                            }
                        }
                    }
                    }
                }
                itemsIndexed(d.items, key = { i, t -> "$i-${t.id}" }) { i, t ->
                    PosterCard(t, onClick = { nav.open(t) }, fill = true)
                    // the next page, as the remote nears the end
                    if (i >= d.items.size - 8 && d.nextPage) {
                        LaunchedEffect(d.items.size) {
                            if (asked != d.items.size) {
                                asked = d.items.size
                                stremio.discoverMore()
                            }
                        }
                    }
                }
                if (d.state == Load.Loading) item(span = { GridItemSpan(maxLineSpan) }) { Text("Loading…", fontSize = 15.sp, color = Dx.Muted) }
                if (d.state == Load.Err) item(span = { GridItemSpan(maxLineSpan) }) { Text("This catalog has nothing for that choice.", fontSize = 15.sp, color = Dx.Muted) }
            }
            }
        }
    }
}
