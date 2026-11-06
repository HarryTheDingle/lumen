package com.notdxbi.dxbi.tv.lumen

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import androidx.compose.material.icons.outlined.Info
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
import androidx.compose.ui.graphics.graphicsLayer
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
import com.notdxbi.dxbi.tv.phone.Airing
import com.notdxbi.dxbi.tv.phone.Continue
import com.notdxbi.dxbi.tv.phone.Load
import com.notdxbi.dxbi.tv.phone.Stremio
import com.notdxbi.dxbi.tv.phone.Title
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Kinds of catalog Lumen leaves out: films and TV only (no YouTube or live TV channels) */
val LEFT_OUT = setOf("channel", "tv")

/**
 * Home, the Apple TV way (as on the iPad: ios/Dxbi/UI/HomeView.swift): a tall billboard that leads with
 * what they're watching, then Continue Watching, Lumen's own rows and the addons' catalogs in the
 * viewer's order. The remote moves through it; Back from down the page comes back to the top.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(stremio: Stremio, nav: Nav, onPlay: (Title) -> Unit) {
    val onOpen: (Title) -> Unit = { nav.open(it) }
    val continueWatching by stremio.continueWatching.collectAsState()
    val suggestions by stremio.billboard.collectAsState()
    val myList by stremio.myList.collectAsState()
    val forYou by stremio.forYou.collectAsState()
    val picks by stremio.picks.collectAsState()
    val airing by stremio.airing.collectAsState()
    val allRows by stremio.rows.collectAsState()
    val ready by stremio.ready.collectAsState()
    val account by stremio.account.collectAsState()
    val listMarks by stremio.listMarks.collectAsState()
    var showing by remember { mutableStateOf<Title?>(null) }
    /** The billboard's trailer is showing (it plays under Home, which leaves the billboard clear for it) */
    var trailerShowing by remember { mutableStateOf(false) }
    // it plays while Home's what's on screen: no page or menu over it, nothing starting, the app showing
    val launcher = LocalLauncher.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val life by lifecycle.currentStateFlow.collectAsState()
    val trailerAllowed = nav.pages.isEmpty() && launcher?.title == null && life.isAtLeast(Lifecycle.State.RESUMED)

    val rows = allRows.filter { it.type !in LEFT_OUT }
    // The billboard leads with what they're watching (five at most); suggestions only when nothing's in progress
    val described by stremio.described.collectAsState()
    val billboard = remember(continueWatching, described, suggestions) { continueWatching.take(5).map { stremio.filledIn(it.title) }.ifEmpty { suggestions } }
        .map { t -> listMarks[t.id]?.let { t.copy(inLibrary = it) } ?: t }
    val labels = playLabels(continueWatching)
    val statuses = statusLines(continueWatching, airing)

    val list = nav.homeList
    val scope = rememberCoroutineScope()
    val top = remember { FocusRequester() }
    val context = LocalContext.current
    var lastBack by remember { mutableLongStateOf(0L) }

    // the remote starts on Play, the first time Home has something to show
    LaunchedEffect(billboard.isNotEmpty()) {
        if (billboard.isNotEmpty() && !nav.homeStarted) {
            nav.homeStarted = true
            runCatching { top.requestFocus() }
        }
    }

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

    // the billboard's trailer to itself after a few seconds of the remote being still
    val still = rememberStillness(trailerShowing && list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset == 0)
    val uiAlpha = still.alpha()

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { still.key(it) }
            // black, but for the billboard while its trailer plays under it (darkening as the page moves
            // up over it)
            .drawBehind {
                if (!trailerShowing) return@drawBehind drawRect(Color.Black)
                val billboardBottom = size.height * 0.84f
                val up = if (list.firstVisibleItemIndex == 0) list.firstVisibleItemScrollOffset.toFloat() else billboardBottom
                val edge = (billboardBottom - up).coerceAtLeast(0f)
                drawRect(Color.Black.copy(alpha = (up / (billboardBottom * 0.7f)).coerceIn(0f, 1f)), size = androidx.compose.ui.geometry.Size(size.width, edge))
                drawRect(Color.Black, topLeft = androidx.compose.ui.geometry.Offset(0f, edge))
            },
    ) {
        val screen = maxHeight
        CompositionLocalProvider(LocalBringIntoViewSpec provides ScreenScrolling) {
        LazyColumn(state = list, modifier = Modifier.graphicsLayer { alpha = uiAlpha }, contentPadding = PaddingValues(bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item(key = "billboard") {
                // the screen's name in the corner, over the billboard (it scrolls away with it)
                Box {
                    if (billboard.isNotEmpty()) {
                        Billboard(
                            billboard, labels, statuses, height = screen * 0.84f, focus = top, stremio = stremio, onOpen = onOpen, onPlay = onPlay,
                            onShowing = { showing = it },
                            // (trailers fade in on a title's page only, not on Home)
                            trailerAllowed = false,
                            muted = nav.menuOpen,
                            onTrailerShowing = { trailerShowing = it },
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
                    ScreenTag(nav)
                }
            }
            if (continueWatching.isNotEmpty()) {
                item(key = "continue") {
                    Shelf("Continue Watching") {
                        items(continueWatching, key = { it.title.id }) { item -> PreviewContinueCard(item, onClick = { onOpen(item.title) }) }
                    }
                }
            }
            run {
                myList.forEachIndexed { i, row ->
                    if (row.items.isNotEmpty()) item(key = "mylist-$i") { TitleShelf(row.name, row.items, onOpen) }
                }
                // Top Picks for You (made from what this profile actually watched), else popular in what they saved
                picks.firstOrNull()?.let { row -> item(key = "picks") { TitleShelf(row.name, row.items, onOpen) } }
                if (picks.isEmpty()) forYou?.let { row -> item(key = "foryou") { TitleShelf(row.name, row.items, onOpen) } }
                if (airing.isNotEmpty()) {
                    item(key = "airing") {
                        Shelf("New Episodes This Week") {
                            items(airing, key = { it.id }) { a -> AiringCard(a, onClick = { onOpen(Title(id = a.id, type = "series", name = a.name, background = a.background)) }) }
                        }
                    }
                }
                // Because you watched …
                picks.drop(1).forEach { row -> item(key = "because-${row.name}") { TitleShelf(row.name, row.items, onOpen) } }
            }
            rows.forEachIndexed { i, row ->
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
val ScreenScrolling = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        if (offset >= 0f && offset + size <= containerSize) return 0f
        return offset - containerSize * 0.25f
    }
}

/** Play's words for what's in progress: "Resume", else "Play" */
private fun playLabels(watching: List<Continue>): Map<String, String> = watching.take(5).associate { item ->
    item.title.id to (if (item.progress > 0.02f) "Resume" else "Play")
}

/**
 * The billboard's status line, only where there's something real to say: the episode they're on
 * ("Resume S2, E6", "Up Next S2, E6"), else a new episode of a show in their library
 */
private fun statusLines(watching: List<Continue>, airing: List<Airing>): Map<String, String> {
    val out = HashMap<String, String>()
    airing.sortedBy { it.time }.forEach { a ->
        out.putIfAbsent(a.id, if (a.out) "New Episode Out Now" else "New Episode ${a.whenText}")
    }
    watching.take(5).forEach { item ->
        val m = item.videoId?.let { Regex(""".+:(\d+):(\d+)""").matchEntire(it) } ?: return@forEach
        out[item.title.id] = "${if (item.progress > 0.02f) "Resume" else "Up Next"} S${m.groupValues[1]}, E${m.groupValues[2]}"
    }
    return out
}

@Composable
private fun TitleShelf(name: String, items: List<Title>, onOpen: (Title) -> Unit, rank: Boolean = false) {
    Shelf(name) {
        itemsIndexed(items.take(if (rank) 10 else 30), key = { _, t -> t.id }) { i, t ->
            PreviewPosterCard(t, onClick = { onOpen(t) }, rank = if (rank) i + 1 else null)
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
    statuses: Map<String, String>,
    height: androidx.compose.ui.unit.Dp,
    focus: FocusRequester,
    stremio: Stremio,
    onOpen: (Title) -> Unit,
    onPlay: (Title) -> Unit,
    onShowing: (Title) -> Unit,
    trailerAllowed: Boolean,
    muted: Boolean,
    onTrailerShowing: (Boolean) -> Unit,
    onFocused: () -> Unit,
) {
    var index by remember { mutableIntStateOf(0) }
    val current = titles[index.coerceIn(0, titles.lastIndex)]
    fun go(i: Int) {
        index = (i + titles.size) % titles.size
    }
    LaunchedEffect(current) { onShowing(current) }

    // Its trailer, as on Apple TV: a few seconds after a title comes up, with sound while the remote's on
    // the billboard's buttons (quiet once it's moved down into the rows), playing to the end before the
    // next title comes up
    val actions = LocalCardActions.current
    var trailer by remember(current.id) { mutableStateOf(current.trailer) }
    var trailerOn by remember(current.id) { mutableStateOf(false) }
    var trailerShowing by remember(current.id) { mutableStateOf(false) }
    var buttonsFocused by remember { mutableStateOf(false) }
    LaunchedEffect(current.id, trailerAllowed) {
        if (!trailerAllowed) {
            trailerOn = false
            trailerShowing = false
            return@LaunchedEffect
        }
        if (trailer == null) trailer = actions?.trailer?.invoke(current)
        if (trailer == null) return@LaunchedEffect
        delay(2500)
        trailerOn = true
    }
    LaunchedEffect(trailerShowing) { onTrailerShowing(trailerShowing) }
    // turns by itself; any change starts the wait again (not while a trailer plays: its end turns it)
    LaunchedEffect(index, titles.size, trailerOn) {
        if (trailerOn) return@LaunchedEffect
        delay(8000)
        if (titles.size > 1 && !trailerOn) go(index + 1)
    }
    val artAlpha by animateFloatAsState(if (trailerShowing) 0f else 1f, tween(900), label = "billboard-art")

    Box(Modifier.fillMaxWidth().height(height)) {
        if (trailerOn) trailer?.let {
            Trailer(
                it,
                Modifier.fillMaxSize(),
                muted = muted || !buttonsFocused,
                loop = false,
                onPlaying = { trailerShowing = true },
                onEnded = {
                    trailerOn = false
                    trailerShowing = false
                    if (titles.size > 1) go(index + 1)
                },
            )
        }
        Crossfade(current, animationSpec = tween(700), label = "billboard") { t ->
            // dark to the left (for the words), and fading at the bottom into the black under the rows
            Box(Modifier.fillMaxSize().fadeBottom(0.7f)) {
                Art(t.backdrop, Modifier.fillMaxSize().alpha(artAlpha))
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.7f), 0.45f to Color.Black.copy(alpha = 0.2f), 0.7f to Color.Transparent)))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.3f), 0.18f to Color.Transparent)))
            }
        }
        // (the trailer's under all this: its bottom fades into the black too)
        if (trailerShowing) Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.7f to Color.Transparent, 1f to Color.Black)))
        TitleInfo(
            current,
            detailed = false,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = Dx.Gutter, bottom = 34.dp),
            animated = true,
            status = { statuses[it.id] },
            buttonsModifier = Modifier.onFocusChanged {
                buttonsFocused = it.hasFocus
                if (it.hasFocus) onFocused()
            },
        ) {
            PlayButton(
                playLabels[current.id] ?: "Play",
                onClick = { onPlay(current) },
                modifier = Modifier
                    .focusRequester(focus)
                    .onPreviewKeyEvent {
                        if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionLeft && titles.size > 1) {
                            go(index - 1); true
                        } else false
                    },
            )
            RoundButton(
                if (current.inLibrary) Icons.Filled.Check else Icons.Filled.Add,
                if (current.inLibrary) "In your Library" else "Add to Library",
                onClick = { stremio.setInList(current, !current.inLibrary) },
            )
            // the title's page (Apple TV's ⓘ)
            RoundButton(
                Icons.Outlined.Info, "Details", onClick = { onOpen(current) },
                modifier = Modifier.onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionRight && titles.size > 1) {
                        go(index + 1); true
                    } else false
                },
            )
        }
        // which of them, in the middle at the bottom
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp)) { Dots(titles.size, index) }
    }
}

@Composable
private fun Dots(count: Int, index: Int) {
    if (count < 2) return
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { i ->
            val width by animateDpAsState(if (i == index) 18.dp else 6.dp, label = "dot")
            Box(Modifier.size(width, 6.dp).background(if (i == index) Color.White else Color.White.copy(alpha = 0.4f), CircleShape))
        }
    }
}
