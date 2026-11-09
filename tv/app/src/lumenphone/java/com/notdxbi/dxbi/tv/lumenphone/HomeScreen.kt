package com.notdxbi.dxbi.tv.lumenphone

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notdxbi.dxbi.tv.phone.Load
import com.notdxbi.dxbi.tv.phone.Stremio
import com.notdxbi.dxbi.tv.phone.Title
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How long each billboard title stays (iOS: 8 seconds) */
private const val BILLBOARD_MS = 8000

/**
 * Home, the Apple TV way (iOS HomeView): a tall billboard to swipe through, then Continue Watching, the library's
 * rows, Lumen's own picks (Top Picks, New Episodes This Week, Because you watched…) and the addons' catalogs in the
 * viewer's order, the first of them numbered as a Top 10. Tapping a title opens its page; Play on the billboard
 * opens it and plays.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(stremio: Stremio, layout: AppLayout, bottomSpace: Dp, onProfile: () -> Unit, onOpen: (Title) -> Unit = {}) {
    val ready by stremio.ready.collectAsState()
    val rows by stremio.rows.collectAsState()
    val resuming by stremio.continueWatching.collectAsState()
    val billboard by stremio.billboard.collectAsState()
    val myList by stremio.myList.collectAsState()
    val forYou by stremio.forYou.collectAsState()
    val picks by stremio.picks.collectAsState()
    val airing by stremio.airing.collectAsState()
    val account by stremio.account.collectAsState()
    val viewers by stremio.viewers.collectAsState()
    val viewer by stremio.viewer.collectAsState()
    // (details the page filled in later: a billboard title's backdrop and logo)
    val described by stremio.described.collectAsState()
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }

    // What they're watching, taking turns with suggestions (Top Picks for You, then what's popular) that they aren't
    // watching and haven't put in their Library: four of each at most (iOS: billboardTitles)
    val slides = remember(resuming, billboard, myList, picks, described) {
        val watching = resuming.take(4).map { stremio.filledIn(it.title) }
        val taken = (resuming.map { it.title.id } + myList.flatMap { r -> r.items.map { it.id } }).toMutableSet()
        val suggested = (picks.firstOrNull()?.items.orEmpty() + billboard).filter { it.backdrop != null && taken.add(it.id) }.take(4).map { stremio.filledIn(it) }
        buildList { for (i in 0 until maxOf(watching.size, suggested.size)) { watching.getOrNull(i)?.let(::add); suggested.getOrNull(i)?.let(::add) } }
    }
    // Play on the billboard for what's in progress: "Resume S2 E5", "Play S3 E1"
    val playLabels = remember(resuming) {
        resuming.take(5).associate { item ->
            val verb = if (item.progress > 0.02f) "Resume" else "Play"
            item.title.id to (episodeLabel(item.videoId)?.replace(",", "")?.let { "$verb $it" } ?: verb)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Dx.Background)) {
        val hero = if (layout.compact) minOf(maxHeight * 0.74f, maxWidth * 1.55f) else minOf(maxHeight * 0.7f, maxWidth * 0.85f)
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { refreshing = true; stremio.refreshExtras(); scope.launch { delay(1200); refreshing = false } },
        ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomSpace + 32.dp), verticalArrangement = Arrangement.spacedBy(30.dp)) {
                item(key = "billboard") {
                    if (slides.isNotEmpty()) Billboard(stremio, slides, playLabels, hero, layout, onOpen)
                    else Box(Modifier.fillMaxWidth().height(hero * 0.3f))
                }
                if (resuming.isNotEmpty()) item(key = "continue") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SectionHeader("Continue Watching", layout.gutter)
                        LazyRow(contentPadding = PaddingValues(horizontal = layout.gutter), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            // (it opens the title: Resume is there, rather than playing straight from Home)
                            items(resuming, key = { "${it.title.id}:${it.videoId}" }) { ContinueCard(it.copy(title = stremio.filledIn(it.title)), layout.wideCard, onClick = { onOpen(it.title) }) }
                        }
                    }
                }
                myList.forEach { row ->
                    if (row.items.isNotEmpty()) item(key = "list:${row.name}") { PosterShelf(row.name, row.items, layout, onOpen) }
                }
                // Top Picks (or, with nothing watched yet to recommend from, popular in what they saved)
                picks.firstOrNull()?.let { row -> item(key = "pick0") { PosterShelf(row.name, row.items, layout, onOpen) } }
                if (picks.isEmpty()) forYou?.takeIf { it.items.isNotEmpty() }?.let { row -> item(key = "foryou") { PosterShelf(row.name, row.items, layout, onOpen) } }
                if (airing.isNotEmpty()) item(key = "airing") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SectionHeader("New Episodes This Week", layout.gutter)
                        LazyRow(contentPadding = PaddingValues(horizontal = layout.gutter), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(airing, key = { it.id }) { a ->
                                AiringCard(a, layout.wideCard) { onOpen(Title(id = a.id, type = "series", name = a.name, background = a.background)) }
                            }
                        }
                    }
                }
                // Because you watched …: on what this profile actually watched
                picks.drop(1).forEach { row -> item(key = "pick:${row.name}") { PosterShelf(row.name, row.items, layout, onOpen) } }
                val shownRows = rows.filter { it.state != Load.Err }
                items(shownRows.size, key = { "row:${shownRows[it].key}:${shownRows[it].index}" }) { i ->
                    val row = shownRows[i]
                    when {
                        row.state == Load.Ready && row.items.isNotEmpty() -> PosterShelf(row.heading, row.items, layout, onOpen, ranked = i == 0)
                        row.state != Load.Ready -> LoadingShelf(row.heading, layout)
                    }
                }
                if (rows.isEmpty()) item(key = "empty") {
                    if (!ready || account == null) LoadingShelf("", layout)
                    else Text(
                        "Nothing to show yet. Install addons to fill Home: tap your picture, then Addons.",
                        modifier = Modifier.fillMaxWidth().padding(horizontal = layout.gutter),
                        color = Dx.Muted, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        // The title and the profile button float over the billboard, which shows through the status bar
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = layout.gutter, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Home", modifier = Modifier.weight(1f), color = Dx.Text, style = MaterialTheme.typography.headlineMedium.copy(shadow = Shadow(Color.Black.copy(alpha = 0.6f), blurRadius = 12f)))
            ProfileButton(if (viewers.size > 1) viewer?.name else account?.email, if (viewers.size > 1) viewer?.colour else null, onProfile)
        }
    }
}

/**
 * The picture at the top right that opens Profile (iOS: the avatar in a glass circle): a dark glass disc with a
 * white ring, so it reads over any backdrop, light or dark. The viewer's colour when the account has profiles.
 */
@Composable
fun ProfileButton(name: String?, colour: String?, onClick: () -> Unit) {
    val fill = remember(colour) { colour?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() } }
    Box(
        Modifier.size(46.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)).border(1.5.dp, Color.White.copy(alpha = 0.85f), CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(fill ?: Color.White.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
            Text((name?.firstOrNull() ?: '?').uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
}

/**
 * The tall pictures at the top (iOS Billboard): the title's art with its logo, what it is, two lines of its story,
 * Play and +, a swipe to the next, and dots at the bottom where the current one is a bar that fills as its time
 * runs out. It turns by itself every few seconds, not while a finger is on it. Tapping it opens the title.
 */
@Composable
private fun Billboard(stremio: Stremio, titles: List<Title>, playLabels: Map<String, String>, height: Dp, layout: AppLayout, onOpen: (Title) -> Unit) {
    val pager = rememberPagerState { titles.size }
    val context = LocalContext.current
    val actions = LocalTitleActions.current
    val progress = remember { Animatable(0f) }
    val airing by stremio.airing.collectAsState()
    val resuming by stremio.continueWatching.collectAsState()

    // Turns by itself (not while a finger's on it); any change starts the wait again
    LaunchedEffect(pager.currentPage, titles.size) {
        progress.snapTo(0f)
        if (titles.size < 2) return@LaunchedEffect
        progress.animateTo(1f, tween(BILLBOARD_MS, easing = LinearEasing))
        while (pager.isScrollInProgress) delay(200)
        pager.animateScrollToPage((pager.currentPage + 1) % titles.size)
    }
    LaunchedEffect(pager.isScrollInProgress) { if (pager.isScrollInProgress) progress.snapTo(0f) }

    Box(Modifier.fillMaxWidth().height(height)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), key = { titles[it].id }) { page ->
            val title = titles[page]
            Box(Modifier.fillMaxSize().clickable { onOpen(title) }) {
                Art(title.backdrop ?: title.art, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.35f), 0.25f to Color.Transparent, 0.45f to Color.Transparent, 0.92f to Color.Black)))
                Column(
                    Modifier.align(Alignment.BottomStart).widthIn(max = 520.dp).padding(horizontal = 24.dp).padding(bottom = 44.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TitleLogo(title, maxHeight = 110.dp, maxWidth = 320.dp, textSize = 40.sp, modifier = Modifier.padding(bottom = 2.dp))
                    Text((listOf(if (title.type == "movie") "Film" else typeName(title.type)) + title.genres.take(2)).joinToString(" · "), color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, maxLines = 1)
                    title.description?.let { Text(it, color = Dx.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    status(title, airing, resuming)?.let { Text(it, color = Dx.Text, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 1) }
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        PlayPill(playLabels[title.id] ?: "Play") { actions?.play(title) ?: onOpen(title) }
                        // (what the Library says, as everywhere else: the title's own mark isn't kept for what's being watched)
                        val saved = inLibrary(stremio, title)
                        GlassCircle(onClick = {
                            stremio.setInList(title, !saved)
                            say(context, if (saved) "Removed from your Library" else "Added to your Library")
                        }) { Icon(if (saved) Icons.Filled.Check else Icons.Filled.Add, contentDescription = if (saved) "In your Library" else "Add to Library", tint = Color.White) }
                    }
                }
            }
        }
        if (titles.size > 1) Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            repeat(titles.size) { i ->
                val current = i == pager.currentPage
                val w by animateDpAsState(if (current) 30.dp else 7.dp, tween(300), label = "dot")
                Box(Modifier.size(w, 7.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.4f))) {
                    if (current) Box(Modifier.size(7.dp + 23.dp * progress.value, 7.dp).background(Color.White))
                }
            }
        }
    }
}

/** What's new, from what Stremio knows: an episode coming this week or out now, or new episodes waiting (else nothing) */
private fun status(title: Title, airing: List<com.notdxbi.dxbi.tv.phone.Airing>, resuming: List<com.notdxbi.dxbi.tv.phone.Continue>): String? {
    if (title.type != "movie") airing.firstOrNull { it.id == title.id }?.let { a -> return if (a.out) "New Episode Out Now" else "S${a.season}, E${a.episode} · ${a.whenText}" }
    val n = resuming.firstOrNull { it.title.id == title.id }?.newEpisodes ?: 0
    return when { n == 1 -> "New Episode Out Now"; n > 1 -> "$n New Episodes Out Now"; else -> null }
}
