package com.notdxbi.dxbi.tv.lumen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.relocation.bringIntoViewResponder
import kotlinx.coroutines.launch
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import coil3.toBitmap
import coil3.request.allowHardware
import androidx.compose.ui.draw.drawBehind
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.notdxbi.dxbi.tv.phone.Airing
import com.notdxbi.dxbi.tv.phone.Continue
import com.notdxbi.dxbi.tv.phone.Title
import com.notdxbi.dxbi.tv.phone.episodeLabel

/**
 * A card the remote can choose, the Apple TV way: it lifts towards you when chosen, larger, and its glass rim brightens.
 * (No glow or shadow around it: on black there's nothing for either to fall on, and Harry doesn't want the glow.)
 */
@Composable
fun FocusCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    corner: Dp = 12.dp,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(corner)
    var focused by remember { mutableStateOf(false) }
    // (a spring with no bounce, ease-out over about a third of a second: it carries on from where it is when the remote
    // moves on mid-lift, so quick presses never jump)
    val lift by animateFloatAsState(if (focused) 1f else 0f, androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 320f), label = "lift")
    // its glass edge (Glass.kt): the light's spot leans the way the remote came, then settles
    val glass = remember { GlassState() }
    androidx.compose.runtime.LaunchedEffect(focused) { if (focused) glass.arrived() }
    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .graphicsLayer {
                val s = 1f + 0.1f * lift
                scaleX = s
                scaleY = s
            }
            .glassEdge(shape, glass) { lift },
        shape = CardDefaults.shape(shape),
        colors = CardDefaults.colors(containerColor = Dx.Raised, focusedContainerColor = Dx.Raised),
        scale = CardDefaults.scale(focusedScale = 1f),
        // (the edge is the glass rim's, brighter when chosen)
        border = CardDefaults.border(border = Border.None, focusedBorder = Border.None),
        glow = CardDefaults.glow(focusedGlow = Glow.None),
    ) {
        content()
    }
}

/**
 * How the page moves, as Apple TV's does: a long, soft glide, never a quick snap. A spring that doesn't bounce (it
 * carries on from wherever the last move had got to, so a second press in the middle of one doesn't stall it, which a
 * timed ease did)
 */
val PageGlide: androidx.compose.animation.core.AnimationSpec<Float> = androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 130f)

/**
 * The page's glide for a move of [distance] px on a screen [viewport] px tall. A spring takes about as long whatever the
 * distance, so a long move (the billboard down to the first row is twice a row's) went twice as fast as the rest: past a
 * row's worth, the spring softens so every move goes at the same pace, the long ones just taking longer.
 */
fun glide(distance: Float, viewport: Float): androidx.compose.animation.core.AnimationSpec<Float> {
    val row = (viewport * 0.35f).coerceAtLeast(1f)
    val d = kotlin.math.abs(distance)
    if (d <= row) return PageGlide
    val k = (130f * (row / d) * (row / d)).coerceIn(38f, 130f)
    return androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = k)
}

/**
 * Glides the page to [index]'s top (less [before] px), in the page's own glide. Compose's animateScrollToItem is a quick
 * plain ease of its own; this is the same move as every other on the page. An item off screen is glided towards by an
 * estimate (rows are about alike), then the rest of the way.
 */
suspend fun androidx.compose.foundation.lazy.LazyListState.glideTo(index: Int, before: Float = 0f) {
    val viewport = layoutInfo.viewportSize.height.toFloat().coerceAtLeast(1f)
    repeat(3) {
        val info = layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == index }
        val distance = if (item != null) item.offset - before
        else {
            val seen = info.visibleItemsInfo
            val per = if (seen.isEmpty()) viewport * 0.4f else seen.sumOf { it.size }.toFloat() / seen.size + info.mainAxisItemSpacing
            val first = seen.firstOrNull()
            (index - firstVisibleItemIndex) * per - (first?.offset?.let { -it } ?: 0) - before
        }
        if (kotlin.math.abs(distance) < 1f) return
        animateScrollBy(distance, glide(distance, viewport))
        if (item != null) return
    }
}

/** Sideways along a row: a little quicker, the same softness */
val RowGlide: androidx.compose.animation.core.AnimationSpec<Float> = androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 230f)

/** A row moves only when the remote goes past its edge, just enough for the next card and a little of the one after */
@OptIn(ExperimentalFoundationApi::class)
val RowScrolling = object : BringIntoViewSpec {
    override val scrollAnimationSpec: androidx.compose.animation.core.AnimationSpec<Float> get() = RowGlide
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val margin = containerSize * 0.06f
        return when {
            offset < margin -> offset - margin
            offset + size > containerSize - margin -> offset + size - containerSize + margin
            else -> 0f
        }
    }
}

/**
 * Puts the row the remote has just moved into in the middle of the page: once, from where the row is, in one glide.
 * (The scroll rule Compose asks for each frame of a glide chases the card as it moves and lifts, and made the page
 * settle in two or three steps; this decides once and doesn't look again, and a sideways move along a row asks for nothing.)
 */
class PageScroller(val list: androidx.compose.foundation.lazy.LazyListState, private val scope: kotlinx.coroutines.CoroutineScope) {
    var viewport: androidx.compose.ui.layout.LayoutCoordinates? = null

    // The move in flight: where it's going and how far along it is, so the same move asked for again (the focus landing
    // on what a glide is already heading to) is let be, not restarted from standstill (that stalled it, and a spring
    // that restarts is a slower one)
    private var job: kotlinx.coroutines.Job? = null
    private var goal = 0f
    private var done = 0f
    private var gen = 0

    /**
     * The page's one way of moving: [delta] px in the glide that suits its length, replacing whatever else was moving it
     * (never two at once). Asked again for the move already under way, it does nothing.
     */
    fun move(delta: Float, viewportPx: Float = viewport?.size?.height?.toFloat() ?: list.layoutInfo.viewportSize.height.toFloat()) {
        val vp = viewportPx.coerceAtLeast(1f)
        if (kotlin.math.abs(delta) < 1f) return
        if (job?.isActive == true && kotlin.math.abs(delta - (goal - done)) < vp * 0.06f) return
        job?.cancel()
        val mine = ++gen
        goal = delta
        done = 0f
        job = scope.launch {
            list.scroll {
                var last = 0f
                androidx.compose.animation.core.animate(0f, delta, animationSpec = glide(delta, vp)) { v, _ ->
                    scrollBy(v - last)
                    last = v
                    if (gen == mine) done = v
                }
            }
        }
    }

    /** A section's top to [before] px under the top of the screen: from where it is if it's in view, else towards it first */
    fun toItem(index: Int, before: Float = 0f) {
        val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
        if (item != null) move(item.offset - before)
        else scope.launch { job?.cancel(); list.glideTo(index, before) }
    }

    fun centre(row: androidx.compose.ui.layout.LayoutCoordinates) {
        val vp = viewport ?: return
        if (!row.isAttached || !vp.isAttached) return
        val top = vp.localPositionOf(row, androidx.compose.ui.geometry.Offset.Zero).y
        val delta = top + row.size.height / 2f - vp.size.height / 2f
        if (kotlin.math.abs(delta) < vp.size.height * 0.03f) return
        move(delta, vp.size.height.toFloat())
    }

    fun toTop() {
        scope.launch { job?.cancel(); list.glideTo(0) }
    }
}

val LocalPageScroller = androidx.compose.runtime.staticCompositionLocalOf<PageScroller?> { null }

/** Takes what a row's cards ask the page for (to be brought into view) and asks the page for nothing: the row does it */
@OptIn(ExperimentalFoundationApi::class)
private object RowOwnsScrolling : androidx.compose.foundation.relocation.BringIntoViewResponder {
    override fun calculateRectForParent(localRect: androidx.compose.ui.geometry.Rect): androidx.compose.ui.geometry.Rect = androidx.compose.ui.geometry.Rect.Zero
    override suspend fun bringChildIntoView(localRect: () -> androidx.compose.ui.geometry.Rect?) {}
}

/** A row of the page: when the remote moves into it the page glides to put it in the middle (once, however it moves along it) */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.centredWhenFocused(): Modifier {
    val scroller = LocalPageScroller.current ?: return this
    var coords by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    var had by remember { mutableStateOf(false) }
    return this
        .onGloballyPositioned { coords = it }
        .onFocusChanged {
            if (it.hasFocus && !had) coords?.let(scroller::centre)
            had = it.hasFocus
        }
        .bringIntoViewResponder(RowOwnsScrolling)
}

/** Room under the last row of a page so that it too can be brought up to the middle of the screen */
@Composable
fun pageBottomPad(): androidx.compose.ui.unit.Dp = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.5f).dp

/**
 * A vertical page of rows with [PageScroller] and [BringIntoViewSpec] wired up in one place.
 * Pass [scroller] when the screen also drives the scroller directly (e.g. Home's billboard or TitlePage).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PageColumn(
    state: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
    modifier: Modifier = Modifier,
    scroller: PageScroller? = null,
    bringIntoViewSpec: BringIntoViewSpec = CentredScrolling,
    contentPadding: PaddingValues = PaddingValues(bottom = pageBottomPad()),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(16.dp),
    content: LazyListScope.() -> Unit,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val pageScroller = scroller ?: remember(state) { PageScroller(state, scope) }
    CompositionLocalProvider(
        LocalBringIntoViewSpec provides bringIntoViewSpec,
        LocalPageScroller provides pageScroller,
    ) {
        androidx.compose.foundation.lazy.LazyColumn(
            state = state,
            modifier = modifier.onGloballyPositioned { pageScroller.viewport = it },
            contentPadding = contentPadding,
            verticalArrangement = verticalArrangement,
            content = content,
        )
    }
}

/** A titled row of cards that scrolls sideways as the remote moves along it */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Shelf(title: String, modifier: Modifier = Modifier, content: LazyListScope.() -> Unit) {
    // (the row the remote is in has its name bright; the others' are softer, as on Apple TV)
    var rowFocused by remember { mutableStateOf(false) }
    val titleAlpha by animateFloatAsState(if (rowFocused) 1f else 0.6f, tween(320), label = "row-title")
    Column(modifier.fillMaxWidth().centredWhenFocused().onFocusChanged { rowFocused = it.hasFocus }) {
        Text(
            title,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White.copy(alpha = titleAlpha),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = Dx.Gutter),
        )
        Spacer(Modifier.height(10.dp))
        CompositionLocalProvider(LocalBringIntoViewSpec provides RowScrolling) {
            LazyRow(
                // (back into the row, the remote lands on the card it was last on, as Apple TV's rows do, not whichever is nearest)
                modifier = @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class) Modifier.focusRestorer(),
                // (room around the cards for them to grow and glow when chosen)
                contentPadding = PaddingValues(start = Dx.Gutter, end = Dx.Gutter, top = 14.dp, bottom = 0.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                content = content,
            )
        }
    }
}

/**
 * A title in a row: its poster, and (Netflix's Top 10) a large number in the corner. [fill]: as wide as
 * the space it's given (a grid's), keeping the poster's shape
 */
@Composable
fun PosterCard(title: Title, onClick: () -> Unit, rank: Int? = null, width: Dp = Dx.Poster, fill: Boolean = false) {
    // (held down: Play, its page, the Library)
    var held by remember { mutableStateOf(false) }
    if (held) TitleHoldMenu(title, onOpen = onClick, onDismiss = { held = false })
    FocusCard(onClick = onClick, onLongClick = { held = true }, modifier = if (fill) Modifier.fillMaxWidth().aspectRatio(2f / 3f) else Modifier.size(width, width * 1.5f)) {
        PosterFace(title = title, rank = rank, width = width, fill = fill)
    }
}

/** Anime posters (Kitsu, MAL) are often art without the name on it */
fun hasNameOnPoster(title: Title) = title.id.startsWith("kitsu:") || title.id.startsWith("mal:")

/** The title in text over a dark foot, for posters that have no name on them (put in the poster's box) */
@Composable
fun BoxScope.PosterName(title: Title, width: Dp) {
    Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.78f))))
    Text(
        title.name,
        style = TextStyle(fontSize = maxOf(11f, width.value * 0.1f).sp, fontWeight = FontWeight.Bold, shadow = Shadow(Color.Black.copy(alpha = 0.6f), blurRadius = 6f)),
        color = Color.White,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, end = 8.dp, bottom = 9.dp),
    )
}

/** New episodes this week: the episode's picture, the show and when */
@Composable
fun AiringCard(item: Airing, onClick: () -> Unit, width: Dp = 300.dp) {
    FocusCard(onClick = onClick, corner = 16.dp, modifier = Modifier.size(width, width * 0.64f)) {
        Box(Modifier.fillMaxSize()) {
            Art(item.still ?: item.background, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f))))
            Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                Text(
                    if (item.out) "NEW EPISODE" else item.countdown.uppercase(),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (item.out) Color.White else Dx.Muted,
                )
                Text(item.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${episodeLabel(item.season, item.episode)}${if (item.more > 0) " +${item.more}" else ""} · ${item.whenText}",
                    fontSize = 12.sp,
                    color = Dx.Muted,
                    maxLines = 1,
                )
            }
        }
    }
}

/** A row still loading: skeleton cards where its posters will be, laid out as the row will be so nothing moves when it comes */
@Composable
fun LoadingShelf(title: String?) {
    Column(Modifier.fillMaxWidth()) {
        // (no name yet: a skeleton bar where it'll be)
        if (title != null) Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Dx.Faint, maxLines = 1, modifier = Modifier.padding(horizontal = Dx.Gutter))
        else Box(Modifier.padding(horizontal = Dx.Gutter, vertical = 4.dp).size(180.dp, 18.dp).skeleton(shape = RoundedCornerShape(6.dp)))
        Spacer(Modifier.height(10.dp))
        Row(Modifier.padding(start = Dx.Gutter, end = Dx.Gutter, top = 14.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            repeat(8) { Box(Modifier.size(Dx.Poster, Dx.Poster * 1.5f).skeleton(shape = RoundedCornerShape(12.dp))) }
        }
    }
}

/** A grid's poster still loading */
@Composable
fun LoadingPoster() {
    Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).skeleton(shape = RoundedCornerShape(12.dp)))
}

/**
 * A row that's loading and then ready: the skeleton row fades into the real one (the same size, so nothing below moves)
 */
@Composable
fun ShelfSwap(loading: Boolean, title: String, content: @Composable () -> Unit) {
    androidx.compose.animation.Crossfade(loading, animationSpec = tween(420, easing = androidx.compose.animation.core.FastOutSlowInEasing), label = "shelf") { l ->
        if (l) LoadingShelf(title) else content()
    }
}
