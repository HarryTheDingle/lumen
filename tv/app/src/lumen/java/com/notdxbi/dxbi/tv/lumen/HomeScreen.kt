package com.notdxbi.dxbi.tv.lumen

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.FilterChip
import androidx.tv.material3.FilterChipDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.Text
import com.notdxbi.dxbi.tv.phone.Continue
import com.notdxbi.dxbi.tv.phone.Load
import com.notdxbi.dxbi.tv.phone.Stremio
import com.notdxbi.dxbi.tv.phone.Title
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Kinds of catalog Lumen leaves out: films and TV only (no YouTube or live TV channels) */
private val LEFT_OUT = setOf("channel", "tv")

/**
 * Home, the Apple TV way (as on the iPad: ios/Dxbi/UI/HomeView.swift): a tall billboard that leads with
 * what they're watching, then Continue Watching, Lumen's own rows and the addons' catalogs in the
 * viewer's order. The remote moves through it; Back from down the page comes back to the top.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(stremio: Stremio, onOpen: (Title) -> Unit, onPlay: (Title) -> Unit) {
    val continueWatching by stremio.continueWatching.collectAsState()
    val suggestions by stremio.billboard.collectAsState()
    val myList by stremio.myList.collectAsState()
    val forYou by stremio.forYou.collectAsState()
    val airing by stremio.airing.collectAsState()
    val allRows by stremio.rows.collectAsState()
    val ready by stremio.ready.collectAsState()
    val account by stremio.account.collectAsState()
    val listMarks by stremio.listMarks.collectAsState()
    var kind by remember { mutableStateOf<String?>(null) }

    val rows = allRows.filter { it.type !in LEFT_OUT }
    // The billboard leads with what they're watching (five at most); suggestions only when nothing's in progress
    val billboard = continueWatching.take(5).map { it.title }.ifEmpty { suggestions }
        .map { t -> listMarks[t.id]?.let { t.copy(inLibrary = it) } ?: t }
    val labels = playLabels(continueWatching)

    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val top = remember { FocusRequester() }
    val context = LocalContext.current
    var lastBack by remember { mutableLongStateOf(0L) }

    LaunchedEffect(billboard.isNotEmpty()) { if (billboard.isNotEmpty()) runCatching { top.requestFocus() } }

    // Back: down the page, to the top; at the top, twice to leave
    BackHandler {
        if (list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0) {
            scope.launch {
                list.animateScrollToItem(0)
                runCatching { top.requestFocus() }
            }
        } else {
            val now = System.currentTimeMillis()
            if (now - lastBack < 2500) (context as? Activity)?.finish()
            else {
                lastBack = now
                Toast.makeText(context, "Press Back again to leave Lumen", Toast.LENGTH_SHORT).show()
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Dx.Background)) {
        val screen = maxHeight
        CompositionLocalProvider(LocalBringIntoViewSpec provides HomeScrolling) {
        LazyColumn(state = list, contentPadding = PaddingValues(bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item(key = "billboard") {
                // Lumen and who's signed in, over the billboard (they scroll away with it)
                Box {
                    if (billboard.isNotEmpty()) {
                        Billboard(
                            billboard, labels, height = screen * 0.86f, focus = top, stremio = stremio, onOpen = onOpen, onPlay = onPlay,
                            // back up onto its buttons: the whole billboard comes back into view, as at the start
                            // (a moment later: the focus move's own scrolling would otherwise cancel it)
                            onFocused = {
                                scope.launch {
                                    delay(80)
                                    if (list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0) list.animateScrollToItem(0)
                                }
                            },
                        )
                    } else {
                        Spacer(Modifier.height(96.dp))
                    }
                    TopBar(account?.email)
                }
            }
            item(key = "kinds") { Kinds(rows.map { it.type }.distinct(), kind) { kind = it } }
            if (kind == null && continueWatching.isNotEmpty()) {
                item(key = "continue") {
                    Shelf("Continue Watching") {
                        items(continueWatching, key = { it.title.id }) { item -> ContinueCard(item, onClick = { onOpen(item.title) }) }
                    }
                }
            }
            if (kind == null) {
                myList.forEachIndexed { i, row ->
                    if (row.items.isNotEmpty()) item(key = "mylist-$i") { TitleShelf(row.name, row.items, onOpen) }
                }
                forYou?.let { row -> item(key = "foryou") { TitleShelf(row.name, row.items, onOpen) } }
                if (airing.isNotEmpty()) {
                    item(key = "airing") {
                        Shelf("New Episodes This Week") {
                            items(airing, key = { it.id }) { a -> AiringCard(a, onClick = { onOpen(Title(id = a.id, type = "series", name = a.name, background = a.background)) }) }
                        }
                    }
                }
            }
            val shown = rows.filter { kind == null || it.type == kind }
            shown.forEachIndexed { i, row ->
                when {
                    // Netflix's Top 10: the first row is numbered
                    row.state == Load.Ready && row.items.isNotEmpty() -> item(key = "row-${row.key}") { TitleShelf(row.heading, row.items, onOpen, rank = i == 0) }
                    row.state == Load.Loading -> item(key = "row-${row.key}") { LoadingShelf(row.heading) }
                    else -> Unit
                }
            }
            if (rows.isEmpty() && ready) {
                item(key = "empty") {
                    Column(Modifier.fillMaxWidth().padding(64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Nothing to show yet", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text("Install addons on your Stremio account to fill Home.", fontSize = 15.sp, color = Dx.Muted)
                    }
                }
            }
        }
        }

    }
}

/**
 * How Home scrolls to what the remote moves onto: not at all while it's on screen (so the billboard stays
 * whole while its buttons are chosen), else so it sits a quarter of the way down, with the row above in view
 */
@OptIn(ExperimentalFoundationApi::class)
private val HomeScrolling = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        if (offset >= 0f && offset + size <= containerSize) return 0f
        return offset - containerSize * 0.25f
    }
}

@Composable
private fun TopBar(email: String?) {
    Row(Modifier.fillMaxWidth().padding(horizontal = Dx.Gutter, vertical = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        Logo(44.dp)
        Spacer(Modifier.weight(1f))
        email?.let { Avatar(it) }
    }
}

/** Play's words for what's in progress: "Resume S2 E5", "Play S3 E1" (a new episode) */
private fun playLabels(watching: List<Continue>): Map<String, String> = watching.take(5).associate { item ->
    val verb = if (item.progress > 0.02f) "Resume" else "Play"
    val m = item.videoId?.let { Regex(""".+:(\d+):(\d+)""").matchEntire(it) }
    item.title.id to (if (m != null) "$verb S${m.groupValues[1]} E${m.groupValues[2]}" else verb)
}

@Composable
private fun TitleShelf(name: String, items: List<Title>, onOpen: (Title) -> Unit, rank: Boolean = false) {
    Shelf(name) {
        itemsIndexed(items.take(if (rank) 10 else 30), key = { _, t -> t.id }) { i, t ->
            PreviewPosterCard(t, onClick = { onOpen(t) }, rank = if (rank) i + 1 else null)
        }
    }
}

/** Netflix's Movies · TV Shows chips: Home with only that kind */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Kinds(types: List<String>, chosen: String?, choose: (String?) -> Unit) {
    val kinds = listOf("movie", "series", "anime").filter { it in types }
    if (kinds.size < 2) return
    LazyRow(contentPadding = PaddingValues(horizontal = Dx.Gutter), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(kinds) { t ->
            val on = chosen == t
            FilterChip(
                selected = on,
                onClick = { choose(if (on) null else t) },
                colors = FilterChipDefaults.colors(
                    containerColor = Color.White.copy(alpha = 0.1f),
                    contentColor = Color.White,
                    focusedContainerColor = Color.White,
                    focusedContentColor = Color.Black,
                    selectedContainerColor = Dx.Accent.copy(alpha = 0.25f),
                    selectedContentColor = Dx.Accent,
                    focusedSelectedContainerColor = Dx.Accent,
                    focusedSelectedContentColor = Dx.OnAccent,
                ),
            ) {
                Text(if (t == "movie") "Movies" else if (t == "series") "TV Shows" else typeName(t), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * The tall billboard at the top of Home: one title at a time, fading into the next by itself every few
 * seconds. Left from Play and right from + change it too (and the wait starts again). Enter on the
 * picture's words isn't possible on a remote, so the title opens from Play's neighbour: Details.
 */
@Composable
private fun Billboard(
    titles: List<Title>,
    playLabels: Map<String, String>,
    height: androidx.compose.ui.unit.Dp,
    focus: FocusRequester,
    stremio: Stremio,
    onOpen: (Title) -> Unit,
    onPlay: (Title) -> Unit,
    onFocused: () -> Unit,
) {
    var index by remember { mutableIntStateOf(0) }
    val current = titles[index.coerceIn(0, titles.lastIndex)]
    fun go(i: Int) {
        index = (i + titles.size) % titles.size
    }
    // turns by itself; any change starts the wait again
    LaunchedEffect(index, titles.size) {
        delay(8000)
        if (titles.size > 1) go(index + 1)
    }

    Box(Modifier.fillMaxWidth().height(height)) {
        Crossfade(current, animationSpec = tween(700), label = "billboard") { t ->
            Box(Modifier.fillMaxSize()) {
                Art(t.backdrop, Modifier.fillMaxSize())
                // dark to the left (for the words) and to the bottom (into the rows)
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.85f), 0.45f to Color.Black.copy(alpha = 0.35f), 0.7f to Color.Transparent)))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.35f), 0.2f to Color.Transparent, 0.6f to Color.Transparent, 1f to Color.Black)))
            }
        }
        Column(Modifier.align(Alignment.BottomStart).padding(start = Dx.Gutter, bottom = 40.dp).widthIn(max = 420.dp)) {
            AnimatedContent(current, transitionSpec = { fadeIn(tween(500)) togetherWith fadeOut(tween(300)) }, label = "billboard-info") { t ->
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    t.year?.let { Badge(it) }
                    TitleLogo(t, maxHeight = 110.dp, maxWidth = 380.dp, textSize = 40.sp)
                    Text(
                        (listOf(typeName(t.type)) + t.genres.take(2)).joinToString(" · "),
                        fontSize = 14.sp,
                        color = Dx.Muted,
                    )
                    t.description?.let {
                        Text(it, fontSize = 13.sp, color = Dx.Faint, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 18.sp)
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(
                Modifier.onFocusChanged { if (it.hasFocus) onFocused() },
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = { onPlay(current) },
                    modifier = Modifier
                        .focusRequester(focus)
                        .onPreviewKeyEvent {
                            if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionLeft && titles.size > 1) {
                                go(index - 1); true
                            } else false
                        },
                    colors = ButtonDefaults.colors(
                        containerColor = Color.White.copy(alpha = 0.85f),
                        contentColor = Color.Black,
                        focusedContainerColor = Color.White,
                        focusedContentColor = Color.Black,
                    ),
                    scale = ButtonDefaults.scale(focusedScale = 1.08f),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(playLabels[current.id] ?: "Play", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick = { onOpen(current) },
                    colors = glassColours(),
                    scale = ButtonDefaults.scale(focusedScale = 1.08f),
                ) {
                    Text("Details", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
                IconButton(
                    onClick = { stremio.setInList(current, !current.inLibrary) },
                    modifier = Modifier.onPreviewKeyEvent {
                        if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionRight && titles.size > 1) {
                            go(index + 1); true
                        } else false
                    },
                    colors = IconButtonDefaults.colors(
                        containerColor = Color.White.copy(alpha = 0.16f),
                        contentColor = Color.White,
                        focusedContainerColor = Color.White,
                        focusedContentColor = Color.Black,
                    ),
                    scale = IconButtonDefaults.scale(focusedScale = 1.1f),
                ) {
                    Icon(if (current.inLibrary) Icons.Filled.Check else Icons.Filled.Add, contentDescription = if (current.inLibrary) "In your Library" else "Add to Library")
                }
            }
            Spacer(Modifier.height(18.dp))
            Dots(titles.size, index)
        }
    }
}

@Composable
private fun glassColours() = ButtonDefaults.colors(
    containerColor = Color.White.copy(alpha = 0.16f),
    contentColor = Color.White,
    focusedContainerColor = Color.White,
    focusedContentColor = Color.Black,
)

@Composable
private fun Dots(count: Int, index: Int) {
    if (count < 2) return
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        repeat(count) { i ->
            val width by animateDpAsState(if (i == index) 20.dp else 7.dp, label = "dot")
            Box(Modifier.size(width, 7.dp).background(if (i == index) Color.White else Color.White.copy(alpha = 0.4f), CircleShape))
        }
    }
}
