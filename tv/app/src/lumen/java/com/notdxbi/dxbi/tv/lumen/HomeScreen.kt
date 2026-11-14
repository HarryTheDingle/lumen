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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
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
    val tasteRows = LocalTaste.current?.rows.orEmpty()
    val airing by stremio.airing.collectAsState()
    val allRows by stremio.rows.collectAsState()
    val ready by stremio.ready.collectAsState()
    val account by stremio.account.collectAsState()
    val listMarks by stremio.listMarks.collectAsState()
    // a Continue Watching card held down: its menu
    var held by remember { mutableStateOf<Title?>(null) }
    held?.let { t ->
        ActionMenu(
            t.name,
            listOf(
                Action("Play", Icons.Filled.PlayArrow) { onPlay(t) },
                Action("Go to ${if (t.type == "movie") "Film" else "Show"}", Icons.Outlined.Info) { onOpen(t) },
                Action("Remove from Continue Watching", Icons.Filled.Close) { stremio.removeFromContinue(t.id) },
            ),
            onDismiss = { held = null },
        )
    }
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
        // (in the Library: what was just chosen, else what the Library's own list has: Continue Watching's titles don't carry it)
        .map { t -> t.copy(inLibrary = listMarks[t.id] ?: (t.inLibrary || myList.any { r -> r.items.any { it.id == t.id } })) }
    val labels = playLabels(continueWatching)
    val statuses = statusLines(continueWatching, airing)

    val list = nav.homeList
    // Apple TV keeps the row the remote is in in the middle of the screen as it moves down the page; on the billboard
    // the page stays at the top
    val onBillboard = remember { mutableStateOf(false) }
    val homeScrolling = remember(list) { HomeScrolling(list) { onBillboard.value } }
    val scope = rememberCoroutineScope()
    val pageScroller = remember(list) { PageScroller(list, scope) }
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
    // (a derived flag: reading the scroll position itself here rebuilt the whole of Home on every frame of a scroll)
    val atTop by remember { derivedStateOf { list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset == 0 } }
    val still = rememberStillness(trailerShowing && atTop)
    val uiAlpha = still.alpha()

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { still.key(it) }
            // black, but for the billboard while its trailer plays under it (darkening as the page moves
            // up over it)
            .drawBehind {
                if (!trailerShowing) return@drawBehind drawRect(Dx.Background)
                val billboardBottom = size.height * 0.84f
                val up = if (list.firstVisibleItemIndex == 0) list.firstVisibleItemScrollOffset.toFloat() else billboardBottom
                val edge = (billboardBottom - up).coerceAtLeast(0f)
                drawRect(Color.Black.copy(alpha = (up / (billboardBottom * 0.7f)).coerceIn(0f, 1f)), size = androidx.compose.ui.geometry.Size(size.width, edge))
                drawRect(Dx.Background, topLeft = androidx.compose.ui.geometry.Offset(0f, edge))
            },
    ) {
        val screen = maxHeight
        val genius = remember { GeniusState() }
        val geniusFeed = LocalDiscoverFeed.current
        CompositionLocalProvider(LocalBringIntoViewSpec provides homeScrolling, LocalPageScroller provides pageScroller) {
        LazyColumn(state = list, modifier = Modifier.graphicsLayer { alpha = uiAlpha }.onGloballyPositioned { pageScroller.viewport = it }, contentPadding = PaddingValues(bottom = pageBottomPad()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item(key = "billboard") {
                // the screen's name in the corner, over the billboard (it scrolls away with it)
                Box {
                    if (billboard.isNotEmpty()) {
                        Billboard(
                            billboard, labels, statuses, height = screen * 0.8f, focus = top, stremio = stremio, onOpen = onOpen, onPlay = onPlay,
                            onShowing = { showing = it },
                            // (trailers fade in on a title's page only, not on Home)
                            trailerAllowed = false,
                            muted = nav.menuOpen,
                            onTrailerShowing = { trailerShowing = it },
                            // back up onto its buttons: the whole billboard comes back into view, as at the start
                            // (a moment later: the focus move's own scrolling would otherwise cancel it)
                            onFocusChange = { onBillboard.value = it },
                            scroll = { if (list.firstVisibleItemIndex == 0) list.firstVisibleItemScrollOffset.toFloat() else 1e6f },
                            artHeight = screen,
                            onFocused = {
                                // back up onto the billboard: the page glides to the top, once
                                pageScroller.toTop()
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
                        items(continueWatching, key = { it.title.id }) { item ->
                            // (held: Apple TV's Up Next menu, with Remove from the row)
                            PreviewContinueCard(item, onClick = { onOpen(item.title) }, onLongClick = { held = item.title })
                        }
                    }
                }
            }
            run {
                myList.forEachIndexed { i, row ->
                    if (row.items.isNotEmpty()) item(key = "mylist-$i") { TitleShelf(row.name, row.items, onOpen) }
                }
                // Top Picks for You (made from what this profile actually watched), else popular in what they saved
                picks.firstOrNull()?.let { row -> item(key = "picks") { TitleShelf(row.name, row.items, onOpen) } }
                // (nothing watched yet to recommend from: what they said they like, else popular in what they saved)
                if (picks.isEmpty()) {
                    val said = tasteRows.firstOrNull()
                    if (said != null) item(key = "taste-0") { TitleShelf(said.name, said.items, onOpen) }
                    else forYou?.let { row -> item(key = "foryou") { TitleShelf(row.name, row.items, onOpen) } }
                }
                if (airing.isNotEmpty()) {
                    item(key = "airing") {
                        Shelf("New Episodes This Week") {
                            items(airing, key = { it.id }) { a -> AiringCard(a, onClick = { onOpen(Title(id = a.id, type = "series", name = a.name, background = a.background)) }) }
                        }
                    }
                }
                // Apple TV's Genius Browse: moods, a row of one coming up under it when chosen
                geniusBrowse(genius, geniusFeed, stremio, onOpen)
                // Because you watched …
                picks.drop(1).forEach { row -> item(key = "because-${row.name}") { TitleShelf(row.name, row.items, onOpen) } }
                if (picks.isEmpty()) tasteRows.drop(1).forEach { row -> item(key = "taste-${row.name}") { TitleShelf(row.name, row.items, onOpen) } }
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
    override val scrollAnimationSpec: androidx.compose.animation.core.AnimationSpec<Float> get() = PageGlide
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
    onFocusChange: (Boolean) -> Unit,
    /** How far the page has scrolled (px): the picture moves slower than the page, and the edge below it hardens */
    scroll: () -> Float,
    /** The picture's height: the whole screen's, so it runs on under the rows below the words (which sit [height] down) */
    artHeight: androidx.compose.ui.unit.Dp,
) {
    var index by remember { mutableIntStateOf(0) }
    val current = titles[index.coerceIn(0, titles.lastIndex)]
    // (which way the picture slides in: on from the right going forward, from the left going back)
    var direction by remember { mutableIntStateOf(1) }
    fun go(i: Int) {
        direction = if (i > index) 1 else -1
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
    var onPlay by remember { mutableStateOf(false) }
    var onInfo by remember { mutableStateOf(false) }
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
    // (and not while the menu's open over it: it waits where it was)
    LaunchedEffect(index, titles.size, trailerOn, muted) {
        if (trailerOn || muted) return@LaunchedEffect
        delay(BILLBOARD_TURN_MS.toLong())
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
        // The picture is the screen's whole height, running on under the rows below the words (they sit over its foot)
        val artPx = with(androidx.compose.ui.platform.LocalDensity.current) { artHeight.roundToPx() }
        Box(
            Modifier
                .fillMaxWidth()
                .graphicsLayer { translationY = -scroll().coerceAtMost(artPx.toFloat()) * 0.4f }
                // (the picture's box is exactly the screen's shape, so a backdrop shows whole and not zoomed in: it moves slower than
                // the page, so its top is always above the screen, and needs no spare picture to slide into view)
                // The picture lifts away a little faster than the page does (as Apple TV's), so its bottom edge and the line along it
                // rise clear of the first row, which is left on the page below it
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints.copy(minHeight = artPx, maxHeight = artPx))
                    layout(placeable.width, constraints.maxHeight) { placeable.place(0, 0) }
                },
        ) {
            // The next picture slides in sideways from the way you're going, fading up, while the last moves away more slowly
            // and fades out, as on Apple TV; its words come up only once it's settled
            androidx.compose.animation.AnimatedContent(
                targetState = current,
                // (by its id: the same title with something changed, as when it's added to the Library, isn't a new slide)
                contentKey = { it.id },
                modifier = Modifier.fillMaxSize().clipToBounds(),
                transitionSpec = {
                    val sign = direction
                    (androidx.compose.animation.slideInHorizontally(tween(720, easing = BillboardEase)) { (it * 0.3f * sign).toInt() } +
                        androidx.compose.animation.fadeIn(tween(560, easing = BillboardEase))) togetherWith
                        (androidx.compose.animation.slideOutHorizontally(tween(720, easing = BillboardEase)) { (-it * 0.12f * sign).toInt() } +
                            androidx.compose.animation.fadeOut(tween(560, easing = BillboardEase)))
                },
                label = "billboard",
            ) { t ->
                // dark to the left, for the words
                Box(Modifier.fillMaxSize()) {
                    // (no grey card under the picture, as Art has: it covers the screen, so that was one more screen of
                    // painting in every frame for nothing; a TV's graphics are short of exactly that)
                    coil3.compose.AsyncImage(
                        model = t.backdrop, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().alpha(artAlpha),
                    )
                    Box(Modifier.fillMaxSize().background(LeftShade))
                    Box(Modifier.fillMaxSize().background(TopShade))
                }
            }
            // The picture's foot is only darkened a little, for the rows over it (Apple TV's: the rows sit on the picture);
            // as the page moves up the picture's bottom edge comes into view as a hard edge with a fine line along it,
            // dividing it from the page below
            Box(
                Modifier.matchParentSize().drawBehind {
                    val base = size.height
                    val p = (scroll() / (base * 0.6f)).coerceIn(0f, 1f)
                    val foot = base * 0.34f
                    drawRect(
                        Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)), startY = size.height - foot, endY = size.height),
                        topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - foot),
                        size = androidx.compose.ui.geometry.Size(size.width, foot),
                    )
                    if (p > 0f) {
                        drawLine(
                            Color.White.copy(alpha = 0.14f * p),
                            androidx.compose.ui.geometry.Offset(0f, size.height - 1f),
                            androidx.compose.ui.geometry.Offset(size.width, size.height - 1f),
                            strokeWidth = 2f,
                        )
                    }
                },
            )
        }
        // (the trailer's under all this: its bottom fades into the black too)
        if (trailerShowing) Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.7f to Color.Transparent, 1f to Dx.Background)))
        TitleInfo(
            current,
            detailed = false,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = Dx.Gutter, bottom = height * 0.0625f),
            animated = true,
            status = { statuses[it.id] },
            buttonsModifier = Modifier
                .onFocusChanged {
                    buttonsFocused = it.hasFocus
                    onFocusChange(it.hasFocus)
                    if (it.hasFocus) onFocused()
                }
                // Left off Play and right off ⓘ turn the billboard (watched on the row, which sees the press before the
                // buttons do: on Play's own modifier, Left never arrived)
                .onPreviewKeyEvent {
                    if (it.type != KeyEventType.KeyDown || titles.size < 2) return@onPreviewKeyEvent false
                    when {
                        it.key == Key.DirectionLeft && onPlay -> { go(index - 1); true }
                        it.key == Key.DirectionRight && onInfo -> { go(index + 1); true }
                        else -> false
                    }
                },
        ) {
            PlayButton(
                playLabels[current.id] ?: "Play",
                onClick = { onPlay(current) },
                modifier = Modifier.focusRequester(focus).onFocusChanged { onPlay = it.isFocused },
            )
            RoundButton(
                if (current.inLibrary) Icons.Filled.Check else Icons.Filled.Add,
                if (current.inLibrary) "In your Library" else "Add to Library",
                onClick = { stremio.setInList(current, !current.inLibrary) },
            )
            // the title's page (Apple TV's ⓘ)
            RoundButton(
                Icons.Outlined.Info, "Details", onClick = { onOpen(current) },
                modifier = Modifier.onFocusChanged { onInfo = it.isFocused },
            )
        }
        // which of them, in the middle at the bottom: going up with the picture as the page moves (not left behind over the
        // rows), and fading as it goes
        Box(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp).graphicsLayer {
                val moved = scroll().coerceAtMost(artPx.toFloat())
                translationY = -moved * 0.4f
                alpha = (1f - moved / (artPx * 0.25f)).coerceIn(0f, 1f)
            },
        ) { Dots(titles.size, index, counting = !trailerOn && !muted) }
    }
}

/**
 * Apple TV's page dots: the one showing is a longer pill that fills with white as its time runs (it turns to the next
 * when full); the others small. [counting]: the time's running (not while a trailer plays, or the menu's over it).
 */
@Composable
private fun Dots(count: Int, index: Int, counting: Boolean) {
    if (count < 2) return
    val fill = remember { androidx.compose.animation.core.Animatable(0f) }
    var filling by remember { androidx.compose.runtime.mutableIntStateOf(-1) }
    LaunchedEffect(index, counting) {
        // (a new one starts empty, and so does one whose time starts again: the turn waits its whole time again then)
        if (filling != index || counting) { filling = index; fill.snapTo(0f) }
        if (counting) fill.animateTo(1f, tween(((1f - fill.value) * BILLBOARD_TURN_MS).toInt().coerceAtLeast(1), easing = androidx.compose.animation.core.LinearEasing))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { i ->
            val here = i == index
            val width by animateDpAsState(if (here) 30.dp else 7.dp, androidx.compose.animation.core.spring(dampingRatio = 0.85f, stiffness = 380f), label = "dot")
            Box(Modifier.size(width, 7.dp).clip(CircleShape).background(Color.White.copy(alpha = if (here) 0.3f else 0.45f))) {
                if (here) Box(Modifier.fillMaxHeight().fillMaxWidth().graphicsLayer {
                    // (drawn as a scale from the left: nothing re-measured each frame)
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                    scaleX = fill.value
                }.background(Color.White, CircleShape))
            }
        }
    }
}

/** How long each billboard title stays before the next */
private const val BILLBOARD_TURN_MS = 8000

/**
 * Home's scrolling, as Apple TV's: whatever the remote moves to sits in the middle of the screen (the row's name above
 * it, the next row's coming up below), so the page moves a row at a time and always shows the same shape. On the
 * billboard it doesn't move (its own focus handler brings the page back to the top).
 */
@OptIn(ExperimentalFoundationApi::class)
class HomeScrolling(private val list: androidx.compose.foundation.lazy.LazyListState, private val onBillboard: () -> Boolean) : BringIntoViewSpec {
    override val scrollAnimationSpec: androidx.compose.animation.core.AnimationSpec<Float> get() = PageGlide
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        // back up on the billboard: the page glides down to the top in this one move (not a second one after it)
        if (onBillboard()) return 0f
        if (size <= 0f) return 0f
        return settle(offset + size / 2f - containerSize / 2f, containerSize)
    }
}

/**
 * Every other list page's scrolling (Discover, genres, Search, Settings): the same as Home's, the row the remote moves to
 * sits in the middle of the screen and the page glides there in one soft move, so the whole app moves alike.
 */
@OptIn(ExperimentalFoundationApi::class)
object CentredScrolling : BringIntoViewSpec {
    override val scrollAnimationSpec: androidx.compose.animation.core.AnimationSpec<Float> get() = PageGlide
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        if (size <= 0f) 0f else settle(offset + size / 2f - containerSize / 2f, containerSize)
}

/**
 * Moving sideways along a row, the focused card's size changes a little as it lifts, which made the page move a few
 * pixels and come back (a bounce on every press): a move that small isn't made. A move to another row is far more.
 */
fun settle(distance: Float, containerSize: Float): Float = if (kotlin.math.abs(distance) < containerSize * 0.03f) 0f else distance


private val BillboardEase = androidx.compose.animation.core.CubicBezierEasing(0.22f, 0.0f, 0.12f, 1f)
private val LeftShade = Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.7f), 0.45f to Color.Black.copy(alpha = 0.2f), 0.7f to Color.Transparent)
private val TopShade = Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.3f), 0.18f to Color.Transparent)
