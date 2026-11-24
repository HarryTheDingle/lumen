@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.notdxbi.dxbi.tv.lumen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
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
import kotlinx.coroutines.launch

/**
 * Apple TV's Genius Browse. Home keeps one row of moods ("Mind-Bending Mysteries", "Charming Romcoms"), each a soft colour
 * tile; choosing one opens a page of its own: the mood's titles, and under them an endless chain of "More like <title>"
 * rows (see [ChainPage]). The page's header chooses TV Shows & Movies / TV Shows / Movies and a genre. Each mood is a
 * Cinemeta genre's popular and best-rated titles that are also of one of its other genres (no TMDB key needed).
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

val KINDS = listOf("TV Shows & Movies", "TV Shows", "Movies")

/** The genres the second dropdown offers (Cinemeta's own names) */
val GENIUS_GENRES = listOf("Action", "Adventure", "Animation", "Comedy", "Crime", "Documentary", "Drama", "Family", "Fantasy", "History", "Horror", "Mystery", "Romance", "Sci-Fi", "Thriller", "War")

/** Shows and films taken in turn, as Apple TV's rows mix them */
private fun weave(lists: List<List<Title>>): List<Title> {
    val out = ArrayList<Title>()
    for (i in 0 until (lists.maxOfOrNull { it.size } ?: 0)) lists.forEach { l -> l.getOrNull(i)?.let(out::add) }
    return out.distinctBy { it.id }
}

/** A mood's titles: its genre's popular and best rated, films and shows taken in turn, those also of one of its other genres */
suspend fun moodTitles(feed: DiscoverFeed, mood: Mood, kind: Int): List<Title> = coroutineScope {
    // (a kind chosen in the header wins over what the mood usually is)
    val types = when (kind) {
        1 -> listOf("series")
        2 -> listOf("movie")
        else -> listOfNotNull("series".takeIf { mood.shows }, "movie".takeIf { mood.films })
    }
    val lists = types.map { type ->
        async {
            val all = (feed.popular(type, mood.genre) + feed.topRated(type, mood.genre)).map { it.title }.distinctBy { it.id }
            all.filter { t -> mood.with.isEmpty() || t.genres.any { it in mood.with } }
        }
    }.awaitAll()
    weave(lists).take(30)
}

/** The mood's titles of one genre; when that leaves few, the genre's own popular and best rated make it up */
suspend fun moodTitles(feed: DiscoverFeed, mood: Mood, kind: Int, genre: String?): List<Title> = coroutineScope {
    val base = moodTitles(feed, mood, kind)
    if (genre == null) return@coroutineScope base
    val hit = base.filter { genre in it.genres }
    if (hit.size >= 12) return@coroutineScope hit
    val types = when (kind) { 1 -> listOf("series"); 2 -> listOf("movie"); else -> listOf("series", "movie") }
    val own = types.map { type -> async { (feed.popular(type, genre) + feed.topRated(type, genre)).map { it.title }.distinctBy { it.id } } }.awaitAll()
    (hit + weave(own)).distinctBy { it.id }.take(30)
}

/** Home's Genius Browse row: a tile for each mood, which opens the page */
fun androidx.compose.foundation.lazy.LazyListScope.geniusBrowse(feed: DiscoverFeed?, nav: Nav) {
    if (feed == null) return
    item(key = "genius") {
        Shelf("Genius Browse") {
            items(MOODS, key = { it.name }) { m -> MoodTile(m) { nav.push(Page.Genius(m.name)) } }
        }
    }
}

/** A mood's tile picture (its two colours and soft glow, made once by script), or 0 for a mood without one */
internal fun moodDrawable(name: String): Int = when (name) {
    "Secrets, Lies & Spies" -> com.notdxbi.dxbi.tv.R.drawable.mood_secrets_lies_spies
    "Mind-Bending Mysteries" -> com.notdxbi.dxbi.tv.R.drawable.mood_mind_bending_mysteries
    "Charming Romcoms" -> com.notdxbi.dxbi.tv.R.drawable.mood_charming_romcoms
    "Good for a Night In" -> com.notdxbi.dxbi.tv.R.drawable.mood_good_for_a_night_in
    "Gripping Crime" -> com.notdxbi.dxbi.tv.R.drawable.mood_gripping_crime
    "Epic Adventures" -> com.notdxbi.dxbi.tv.R.drawable.mood_epic_adventures
    "Into the Future" -> com.notdxbi.dxbi.tv.R.drawable.mood_into_the_future
    "Things That Go Bump" -> com.notdxbi.dxbi.tv.R.drawable.mood_things_that_go_bump
    "Family Movie Night" -> com.notdxbi.dxbi.tv.R.drawable.mood_family_movie_night
    "True Stories" -> com.notdxbi.dxbi.tv.R.drawable.mood_true_stories
    "Laugh-Out-Loud Shows" -> com.notdxbi.dxbi.tv.R.drawable.mood_laugh_out_loud_shows
    "War & Heroes" -> com.notdxbi.dxbi.tv.R.drawable.mood_war_heroes
    else -> 0
}

/** A mood's tile: a poster's shape, its colours soft and glowing, its name low on the left */
@Composable
private fun MoodTile(mood: Mood, onClick: () -> Unit) {
    FocusCard(onClick = onClick, modifier = Modifier.width(Dx.Poster).height(Dx.Poster * 1.5f)) {
        // (its colours baked into a picture, res/drawable-nodpi/mood_*: two gradients painted over a dozen tiles on every
        // frame were too much for a Mi Box's GPU while the page moved)
        Box(Modifier.fillMaxSize()) {
            val art = moodDrawable(mood.name)
            if (art != 0) Picture(art, Modifier.fillMaxSize())
            else Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(mood.top, mood.bottom))))
            Text(
                mood.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White, lineHeight = 17.sp, maxLines = 3,
                modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
            )
        }
    }
}

/** A mood's own page: its titles over the chain, and the kind and genre to choose from in the header */
@Composable
fun GeniusPage(nav: Nav, feed: DiscoverFeed, stremio: Stremio, moodName: String) {
    val mood = MOODS.firstOrNull { it.name == moodName } ?: return
    var kind by remember { mutableIntStateOf(0) }
    var genre by remember { mutableStateOf<String?>(null) }
    var titles by remember { mutableStateOf<List<Title>?>(null) }
    LaunchedEffect(kind, genre) {
        titles = null
        titles = moodTitles(feed, mood, kind, genre)
    }
    ChainPage(nav, stremio, mood.name, titles, tint = mood.top, header = { GeniusHeader(kind, { kind = it }, genre, { genre = it }) })
}

/** "Genius Browse" over the two pills */
@Composable
private fun GeniusHeader(kind: Int, onKind: (Int) -> Unit, genre: String?, onGenre: (String?) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 26.dp, bottom = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Genius Browse", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            DropdownPill(KINDS[kind], KINDS, kind) { onKind(it) }
            DropdownPill(genre ?: "All Genres", listOf("All Genres") + GENIUS_GENRES, if (genre == null) 0 else GENIUS_GENRES.indexOf(genre) + 1) { onGenre(if (it == 0) null else GENIUS_GENRES[it - 1]) }
        }
    }
}

/** A frosted pill that opens a small list under it: OK chooses (a tick on what's chosen), Back closes */
@Composable
private fun DropdownPill(label: String, options: List<String>, chosen: Int, onChoose: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val shape = RoundedCornerShape(50)
    Surface(
        onClick = { open = true },
        modifier = Modifier.onGloballyPositioned { bounds = it.boundsInWindow() },
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(containerColor = Glass.Fill, contentColor = Color.White, focusedContainerColor = Color.White, focusedContentColor = Color.Black),
        border = ClickableSurfaceDefaults.border(border = androidx.tv.material3.Border(BorderStroke(1.dp, Glass.Edge), shape = shape)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = Dx.FocusButton),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(16.dp))
        }
    }
    if (open) {
        val close = { open = false }
        val list = rememberLazyListState(initialFirstVisibleItemIndex = (chosen - 2).coerceAtLeast(0))
        val landing = remember { FocusRequester() }
        PageWindow(onBack = close, fadeIn = false) {
            FocusOnOpen(landing)
            val dp = androidx.compose.ui.platform.LocalDensity.current.density
            Box(Modifier.fillMaxSize()) {
                val panel = RoundedCornerShape(Dx.RPanel)
                LazyColumn(
                    state = list,
                    modifier = Modifier
                        .offset { IntOffset(bounds.left.toInt(), (bounds.bottom + 10 * dp).toInt()) }
                        .width(250.dp).heightIn(max = 330.dp)
                        .background(Dx.Raised.copy(alpha = 0.94f), panel)
                        .border(BorderStroke(1.dp, Glass.Edge), panel),
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    itemsIndexed(options) { i, name ->
                        val row = RoundedCornerShape(Dx.RCard)
                        Surface(
                            onClick = { onChoose(i); close() },
                            modifier = Modifier.fillMaxWidth().then(if (i == chosen) Modifier.focusRequester(landing) else Modifier),
                            shape = ClickableSurfaceDefaults.shape(row),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = if (i == chosen) Glass.Fill else Color.Transparent, contentColor = Color.White,
                                focusedContainerColor = Color.White, focusedContentColor = Color.Black,
                            ),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = Dx.FocusRow),
                        ) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(name, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.weight(1f))
                                if (i == chosen) Icon(Icons.Filled.Check, contentDescription = "Chosen", modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One row of the chain: whose "More like" it is (null for the first), its name, its titles */
data class ChainRow(val forId: String?, val name: String, val titles: List<Title>) {
    val key: String get() = "${forId.orEmpty()}:$name:${titles.firstOrNull()?.id.orEmpty()}"
}

/** The page never scrolls to chase a card: the chain moves it a row at a time itself */
private object NoPageChase : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

/**
 * Apple TV's endless chain, as a page: the first row ([firstRow], called [firstName]; null while it loads), under it
 * "More like <the title the remote's on>". Moving along a row swaps everything under it; Down makes the row below the
 * top one (two full rows always show) and a new "More like" opens beneath it, for as long as the remote keeps going.
 * Up walks back. [header] (above the first row) shows when the page is at its top.
 */
@Composable
fun ChainPage(nav: Nav, stremio: Stremio, firstName: String, firstRow: List<Title>?, tint: Color? = null, header: (@Composable () -> Unit)? = null) {
    var rows by remember { mutableStateOf<List<ChainRow>>(emptyList()) }
    // the title the remote's on, and which row it's in
    var hover by remember { mutableStateOf<Pair<Int, Title>?>(null) }
    var emptyFor by remember { mutableStateOf<Pair<Int, String>?>(null) }
    LaunchedEffect(firstRow, firstName) {
        hover = null
        emptyFor = null
        // (a filter changing sends the first row back to null while the new one loads: the rows stay in place, as skeletons,
        // so the page's frame doesn't collapse and jump; only an answer, full or empty, replaces them)
        if (firstRow == null && rows.isNotEmpty()) return@LaunchedEffect
        rows = if (firstRow.isNullOrEmpty()) emptyList() else listOf(ChainRow(null, firstName, firstRow))
    }
    // hovering a title immediately reloads the row under it as "More like" it (moving on cancels the in-flight load)
    var pending by remember { mutableStateOf<Int?>(null) }
    val feed = LocalDiscoverFeed.current
    LaunchedEffect(hover) {
        val (i, t) = hover ?: return@LaunchedEffect
        if (rows.getOrNull(i + 1)?.forId == t.id) return@LaunchedEffect
        if (emptyFor == (i to t.id)) emptyFor = null
        // (a new row's slot shows its skeleton at once; a row being swapped keeps its frame and only its posters give way to
        // skeleton shapes, and only if the load takes a moment, so moving quickly along a row doesn't flash them)
        if (rows.size <= i + 1) pending = i + 1
        val late = if (rows.size > i + 1) launch { delay(220); pending = i + 1 } else null
        try {
            // (Lumos's engine has no Related, so the feed makes them; the engine's is the fallback)
            val found = (feed?.related(t.type, t.id, t.genres) ?: emptyList()).ifEmpty { runCatching { stremio.related(t.type, t.id, t.genres) }.getOrDefault(emptyList()) }
                .filter { it.id != t.id }.distinctBy { it.id }
            if (rows.size <= i) return@LaunchedEffect
            // (what the rows above already show is left out when there's enough besides; a few repeats make up a short row)
            val seen = rows.take(i + 1).flatMap { r -> r.titles.map { it.id } }.toSet()
            val fresh = found.filter { it.id !in seen }
            val list = (if (fresh.size >= 6) fresh else fresh + found.filter { it.id in seen }.take(8 - fresh.size)).take(20)
            if (list.isEmpty()) {
                emptyFor = i to t.id
                return@LaunchedEffect
            }
            rows = rows.take(i + 1) + ChainRow(t.id, "More like ${t.name}", list)
        } finally {
            late?.cancel()
            // (cancelled by moving on, or done: the skeleton goes either way)
            if (pending == i + 1) pending = null
        }
    }

    val first = remember { FocusRequester() }
    var landed by remember { mutableStateOf(false) }
    LaunchedEffect(rows.isNotEmpty()) {
        // (the remote goes onto the first poster once, not again when a filter reloads it)
        if (rows.isEmpty() || landed) return@LaunchedEffect
        delay(120)
        if (runCatching { first.requestFocus() }.isSuccess) landed = true
    }

    PageWindow(onBack = { nav.back() }, home = first) {
        Box(Modifier.fillMaxSize().background(Dx.Background)) {
            val scroll = rememberScrollState()
            // (the mood's colour at the top goes as the page moves up from it)
            if (tint != null) Box(Modifier.fillMaxSize().graphicsLayer { alpha = (1f - scroll.value / 300f).coerceIn(0f, 1f) }.background(Brush.verticalGradient(0f to tint.copy(alpha = 0.3f), 0.4f to Color.Transparent)))
            val scope = rememberCoroutineScope()
            val density = androidx.compose.ui.platform.LocalDensity.current
            val tops = remember { HashMap<Int, Float>() }
            // where a row sits when it's the top one (the first row, with no header above it, sits there too)
            val topGap = 32.dp
            fun show(i: Int) {
                val target = if (i == 0) 0f else (tops[i] ?: return) - with(density) { topGap.toPx() }
                scope.launch { val to = target.toInt().coerceAtLeast(0); scroll.animateScrollTo(to, glide((to - scroll.value).toFloat(), scroll.viewportSize.toFloat())) }
            }
            CompositionLocalProvider(LocalBringIntoViewSpec provides NoPageChase) {
                Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                    if (header != null) header() else Spacer(Modifier.height(topGap))
                    val totalSlots = if (rows.isEmpty()) 1 else rows.size + if (pending == rows.size) 1 else 0
                    for (i in 0 until totalSlots) {
                        key(i) {
                            // (the slot keeps one frame whatever it's doing: the row's title line and its card places stay, and
                            // only the posters swap for skeleton shapes while it loads, so nothing jumps or collapses)
                            Box(Modifier.padding(bottom = 8.dp).onGloballyPositioned { tops[i] = it.positionInParent().y }) {
                                if (rows.isEmpty() && i == 0 && firstRow != null) {
                                    Text("Nothing here yet", fontSize = 16.sp, color = Dx.Muted, modifier = Modifier.padding(horizontal = Dx.Gutter, vertical = 24.dp))
                                } else {
                                    ChainRowView(
                                        rows.getOrNull(i), if (i == 0) firstName else null, if (i == 0) first else null,
                                        loading = i >= rows.size || pending == i || (firstRow == null && rows.isNotEmpty()),
                                        modifier = Modifier,
                                        onTitle = { hover = i to it },
                                        onEnter = { show(i) },
                                        onOpen = { nav.open(it) },
                                        canGoDown = {
                                            val cur = hover
                                            if (cur == null || cur.first != i) true
                                            else rows.getOrNull(i + 1)?.forId == cur.second.id || emptyFor == (i to cur.second.id)
                                        },
                                    )
                                }
                            }
                        }
                    }
                    // (room under the last row so it can come up to the top)
                    Spacer(Modifier.height(LocalConfiguration.current.screenHeightDp.dp))
                }
            }
            // once the first row has gone up: a black fade over what peeks of the row before, and a small chevron
            val away = with(density) { (scroll.value / (topGap.toPx() * 2f)).coerceIn(0f, 1f) }
            Box(Modifier.fillMaxWidth().height(topGap).alpha(away).background(Brush.verticalGradient(0f to Color.Black, 0.7f to Color.Black, 1f to Color.Transparent))) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp).size(22.dp))
            }
        }
    }
}

/**
 * A row of the chain: its name (bright while the remote's in it) over its posters; the remote lands back where it was.
 * While [loading] the frame stays (title line, card places) and the posters fade out for skeleton card shapes in the
 * same places; the new posters then fade in over them. A null [row] is a slot still waiting for its first answer.
 */
@Composable
private fun ChainRowView(
    row: ChainRow?,
    placeholderName: String?,
    firstCard: FocusRequester?,
    loading: Boolean,
    modifier: Modifier,
    onTitle: (Title) -> Unit,
    onEnter: () -> Unit,
    onOpen: (Title) -> Unit,
    canGoDown: () -> Boolean,
) {
    val inRow = remember { mutableStateOf(false) }
    val rowScrolling = rememberRowScrolling(Dx.Gutter, 18.dp)
    val cardsAlpha by animateFloatAsState(if (loading || row == null) 0f else 1f, tween(Dx.Fast), label = "chain-cards")
    Column(
        modifier.fillMaxWidth()
            .onFocusChanged {
                if (it.hasFocus && !inRow.value) onEnter()
                inRow.value = it.hasFocus
            }
            .onPreviewKeyEvent {
                if (it.key == Key.DirectionDown && it.type == KeyEventType.KeyDown && !canGoDown()) true
                else false
            },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val name = row?.name ?: placeholderName
        if (name != null) ChainRowHeading(name) { inRow.value }
        // (no name yet: a skeleton bar where it'll be, as LoadingShelf has)
        else Box(Modifier.padding(horizontal = Dx.Gutter, vertical = 4.dp).size(180.dp, 18.dp).skeleton(shape = RoundedCornerShape(Dx.RCard)))
        Box {
            // (the skeleton cards sit under the posters, in the same places, and show through as the posters fade)
            if (cardsAlpha < 1f) {
                Row(
                    Modifier.padding(start = Dx.Gutter, end = Dx.Gutter, top = 20.dp, bottom = 26.dp).graphicsLayer { alpha = 1f - cardsAlpha },
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    repeat(8) { Box(Modifier.size(Dx.Poster, Dx.Poster * 1.5f).skeleton(shape = RoundedCornerShape(Dx.RCard))) }
                }
            }
            // (while it loads the old posters, once faded, aren't kept: nothing unseen can take the remote)
            if (row != null && (!loading || cardsAlpha > 0f)) {
                val rowKey = row.key
                CompositionLocalProvider(LocalBringIntoViewSpec provides rowScrolling) {
                    LazyRow(
                        modifier = Modifier.graphicsLayer { alpha = cardsAlpha },
                        contentPadding = PaddingValues(start = Dx.Gutter, end = Dx.Gutter, top = 20.dp, bottom = 26.dp),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        itemsIndexed(row.titles, key = { _, t -> t.id }) { i, t ->
                            val shouldStagger = i <= 8
                            val entranceAlpha = remember(rowKey, t.id) { Animatable(if (shouldStagger) 0f else 1f) }
                            if (shouldStagger) {
                                LaunchedEffect(rowKey, t.id) {
                                    delay(i * 45L)
                                    entranceAlpha.animateTo(1f, tween(Dx.Fast))
                                }
                            }
                            Box(
                                Modifier
                                    .graphicsLayer { alpha = entranceAlpha.value }
                                    .onFocusChanged { if (it.hasFocus) onTitle(t) }
                                    .then(if (i == 0 && firstCard != null) Modifier.focusRequester(firstCard) else Modifier),
                            ) {
                                PreviewPosterCard(t, onClick = { onOpen(t) })
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Isolated chain row heading so focus alpha and "More like ..." title crossfades never recompose the card row */
@Composable
private fun ChainRowHeading(title: String, focused: () -> Boolean) {
    val titleAlpha = animateFloatAsState(if (focused()) 1f else 0.6f, tween(Dx.Med), label = "chain-title")
    Crossfade(
        targetState = title,
        animationSpec = tween(Dx.Fast),
        modifier = Modifier.padding(horizontal = Dx.Gutter).graphicsLayer { alpha = titleAlpha.value },
        label = "chain-row-title",
    ) { heading ->
        Text(
            heading,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
