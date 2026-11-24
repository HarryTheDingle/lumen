package com.notdxbi.dxbi.tv.lumen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.foundation.relocation.bringIntoViewResponder
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.platform.LocalDensity
import coil3.toBitmap
import coil3.request.allowHardware
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.drawWithCache
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
 * A card the remote can choose, the Apple TV way: it lifts towards you when chosen, larger, with a subtle glass rim
 * (no glow: Harry's call).
 */
@Composable
fun FocusCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    corner: Dp = Dx.RCard,
    onLongClick: (() -> Unit)? = null,
    /**
     * Px of the card's foot kept out of sight (clipped, and the rim stops short of it), read while drawing: a poster
     * grows down into the room kept under it by this going to 0, with nothing laid out or drawn again as it does
     */
    hiddenFoot: (() -> Float)? = null,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(corner)
    val cutShape = remember(corner, hiddenFoot) { hiddenFoot?.let { FootCutShape(corner, it) } }
    var focused by remember { mutableStateOf(false) }
    // (a spring with no bounce, ease-out over about a third of a second: it carries on from where it is when the remote
    // moves on mid-lift, so quick presses never jump)
    val lift by animateFloatAsState(if (focused) 1f else 0f, androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 320f), label = "lift")
    // its glass edge (Glass.kt): the light's spot leans the way the remote came, then settles
    val glass = remember { GlassState() }
    androidx.compose.runtime.LaunchedEffect(focused) { if (focused) glass.arrived() }
    // (chosen: its row's name knows where it is, and moves up out of its way when it's under it)
    val rowLift = LocalRowLift.current
    // (a plain focusable box, not the TV library's Card: that one brings its own scale, border, glow and colour animations,
    // interaction state and a surface of layers for every poster, all switched off here, and building a row of them was
    // the main thread's biggest cost as Home's rows came on screen on a Mi Box)
    var pressed by remember { mutableStateOf(false) }
    var held by remember { mutableStateOf(false) }
    Box(
        modifier
            .zIndex(if (focused) 1f else 0f)
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .then(if (focused && rowLift != null) Modifier.onGloballyPositioned { rowLift.left = it.positionInWindow().x } else Modifier)
            .graphicsLayer {
                val s = 1f + 0.13f * lift
                scaleX = s
                scaleY = s
                // (lifting from the middle of what shows, not of the room under it)
                val cut = hiddenFoot?.invoke() ?: 0f
                if (cut > 0f && size.height > 0f) transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, (size.height - cut) / 2f / size.height)
            }
            .glassEdge(cutShape ?: shape, glass, tracked = focused) { lift }
            // OK as the TV library's cards take it: a click when it's let go, the menu when it's held
            .onKeyEvent { e ->
                if (!e.isSelect()) return@onKeyEvent false
                val native = e.nativeKeyEvent
                when (e.type) {
                    androidx.compose.ui.input.key.KeyEventType.KeyDown -> {
                        if (native.repeatCount == 0 && !native.isLongPress) { pressed = true; held = false }
                        else if (onLongClick != null && !held && pressed) { held = true; onLongClick() }
                        true
                    }
                    androidx.compose.ui.input.key.KeyEventType.KeyUp -> {
                        val click = pressed && !held
                        pressed = false; held = false
                        if (click) onClick()
                        true
                    }
                    else -> false
                }
            }
            .focusable()
            // (no background of its own: what's in it (a picture, with its own while it loads) covers it)
            .then(if (cutShape != null) Modifier.graphicsLayer { clip = true; this.shape = cutShape.now() } else Modifier.clip(shape)),
    ) {
        CompositionLocalProvider(androidx.tv.material3.LocalContentColor provides Color.White) { content() }
    }
}

/**
 * A rounded card with [cut] px of its foot left off. [now] is the shape as it is this moment (a new one each time the cut
 * changes, which is what a layer looks at to know its outline moved)
 */
class FootCutShape(private val corner: Dp, private val cut: () -> Float) : androidx.compose.ui.graphics.Shape {
    private class At(val corner: Dp, val px: Float) : androidx.compose.ui.graphics.Shape {
        override fun createOutline(size: androidx.compose.ui.geometry.Size, layoutDirection: androidx.compose.ui.unit.LayoutDirection, density: androidx.compose.ui.unit.Density): androidx.compose.ui.graphics.Outline {
            val r = with(density) { corner.toPx() }
            return androidx.compose.ui.graphics.Outline.Rounded(androidx.compose.ui.geometry.RoundRect(0f, 0f, size.width, (size.height - px).coerceAtLeast(r * 2), androidx.compose.ui.geometry.CornerRadius(r)))
        }
        override fun equals(other: Any?) = other is At && other.px == px && other.corner == corner
        override fun hashCode() = px.hashCode()
    }
    fun now(): androidx.compose.ui.graphics.Shape = At(corner, cut())
    override fun createOutline(size: androidx.compose.ui.geometry.Size, layoutDirection: androidx.compose.ui.unit.LayoutDirection, density: androidx.compose.ui.unit.Density) =
        now().createOutline(size, layoutDirection, density)
}

/** OK on a remote (the middle of the D-pad), Enter, or a game pad's A */
private fun androidx.compose.ui.input.key.KeyEvent.isSelect(): Boolean = when (nativeKeyEvent.keyCode) {
    android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_NUMPAD_ENTER, android.view.KeyEvent.KEYCODE_BUTTON_A -> true
    else -> false
}

/**
 * How the page moves, as Apple TV's does: a long, soft glide, never a quick snap. A spring that doesn't bounce (it
 * carries on from wherever the last move had got to, so a second press in the middle of one doesn't stall it, which a
 * timed ease did)
 */
val PageGlide: androidx.compose.animation.core.AnimationSpec<Float> = androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 130f, visibilityThreshold = 0.5f)

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
    return androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = k, visibilityThreshold = 0.5f)
}

/**
 * Glides the page to [index]'s top (less [before] px), in the page's own glide. Compose's animateScrollToItem is a quick
 * plain ease of its own; this is the same move as every other on the page. An item off screen is glided towards by an
 * estimate (rows are about alike), then the rest of the way.
 */
suspend fun androidx.compose.foundation.lazy.LazyListState.glideTo(index: Int, before: Float = 0f, centre: Boolean = false) {
    val viewport = layoutInfo.viewportSize.height.toFloat().coerceAtLeast(1f)
    repeat(8) {
        val info = layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == index }
        val distance = if (item != null) {
            val target = if (centre) ((viewport - item.size) / 2f).coerceAtLeast(0f) else before
            item.offset - target
        } else {
            val seen = info.visibleItemsInfo
            val per = if (seen.isEmpty()) viewport * 0.4f else seen.sumOf { it.size }.toFloat() / seen.size + info.mainAxisItemSpacing
            val first = seen.firstOrNull()
            val target = if (centre) viewport * 0.25f else before
            (index - firstVisibleItemIndex) * per - (first?.offset?.let { -it } ?: 0) - target
        }
        if (kotlin.math.abs(distance) < 1f) return
        val moved = animateScrollBy(distance, glide(distance, viewport))
        if (item != null || kotlin.math.abs(moved) < 1f) return
    }
    if (layoutInfo.visibleItemsInfo.none { it.index == index }) {
        val target = if (centre) (viewport * 0.25f).toInt() else before.toInt()
        animateScrollToItem(index, -target.coerceAtLeast(0))
    }
}

/** Sideways along a row: a little quicker, the same softness */
val RowGlide: androidx.compose.animation.core.AnimationSpec<Float> = androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 230f)

/**
 * Column-aligned horizontal row scrolling: cards stay snapped to the shelf's left gutter and full column stride
 * so vertical columns stay aligned across stacked shelves with no mid-card stops.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberRowScrolling(gutter: Dp = Dx.Gutter, spacing: Dp = 18.dp): BringIntoViewSpec {
    val density = LocalDensity.current
    val gutterPx = with(density) { gutter.toPx() }
    val spacingPx = with(density) { spacing.toPx() }
    return remember(gutterPx, spacingPx) {
        object : BringIntoViewSpec {
            override val scrollAnimationSpec: androidx.compose.animation.core.AnimationSpec<Float> get() = RowGlide
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                if (size <= 0f || containerSize <= 0f) return 0f
                val stride = size + spacingPx
                val usable = (containerSize - 2f * gutterPx).coerceAtLeast(size)
                val cols = ((usable + spacingPx) / stride).toInt().coerceAtLeast(1)
                val lastColOffset = gutterPx + (cols - 1) * stride
                val dist = when {
                    offset < gutterPx - 1f -> offset - gutterPx
                    offset > lastColOffset + 1f -> offset - lastColOffset
                    else -> 0f
                }
                return if (kotlin.math.abs(dist) < 1f) 0f else dist
            }
        }
    }
}

/** Fallback column-aligned horizontal scrolling when density isn't supplied (assumes 5% gutter) */
@OptIn(ExperimentalFoundationApi::class)
val RowScrolling = object : BringIntoViewSpec {
    override val scrollAnimationSpec: androidx.compose.animation.core.AnimationSpec<Float> get() = RowGlide
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        if (size <= 0f || containerSize <= 0f) return 0f
        val gutter = containerSize * 0.05f
        val spacing = containerSize * 0.01875f
        val stride = size + spacing
        val usable = (containerSize - 2f * gutter).coerceAtLeast(size)
        val cols = ((usable + spacing) / stride).toInt().coerceAtLeast(1)
        val lastColOffset = gutter + (cols - 1) * stride
        val dist = when {
            offset < gutter - 1f -> offset - gutter
            offset > lastColOffset + 1f -> offset - lastColOffset
            else -> 0f
        }
        return if (kotlin.math.abs(dist) < 1f) 0f else dist
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
    var jumping = false
        private set

    /** True while PageScroller owns an active vertical glide or jump (suppresses competing BringIntoViewSpec scrolls) */
    val isBusy: Boolean
        get() = jumping || job?.isActive == true

    fun cancel() {
        job?.cancel()
        job = null
        gen++
    }

    suspend fun jumpTo(index: Int, centre: Boolean = false, durationMs: Int = Dx.Fast, onVisible: (() -> Boolean)? = null) {
        cancel()
        jumping = true
        try {
            val viewport = list.layoutInfo.viewportSize.height.toFloat().coerceAtLeast(1f)
            val targetOffset = if (centre) -(viewport * 0.28f).toInt() else 0
            var focused = false
            val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
            if (item != null) {
                val target = if (centre) ((viewport - item.size) / 2f).coerceAtLeast(0f) else 0f
                val delta = item.offset - target
                if (onVisible != null) focused = onVisible()
                if (kotlin.math.abs(delta) >= 1f) {
                    list.animateScrollBy(delta, tween(durationMs, easing = Dx.Ease))
                }
            } else {
                coroutineScope {
                    val watcher = if (onVisible != null) {
                        launch {
                            androidx.compose.runtime.snapshotFlow {
                                list.layoutInfo.visibleItemsInfo.any { it.index == index }
                            }.first { it }
                            repeat(12) {
                                if (onVisible()) {
                                    focused = true
                                    return@launch
                                }
                                kotlinx.coroutines.delay(16)
                            }
                        }
                    } else null
                    try {
                        if (durationMs > Dx.Fast) {
                            val spec = tween<Float>(durationMs, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                            var pass = 0
                            while (pass < 4) {
                                pass++
                                val info = list.layoutInfo
                                val visible = info.visibleItemsInfo.firstOrNull { it.index == index }
                                val delta = if (visible != null) {
                                    val target = if (centre) ((viewport - visible.size) / 2f).coerceAtLeast(0f) else 0f
                                    visible.offset - target
                                } else {
                                    val seen = info.visibleItemsInfo
                                    val per = if (seen.isEmpty()) viewport * 0.4f else seen.sumOf { it.size }.toFloat() / seen.size + info.mainAxisItemSpacing
                                    val first = seen.firstOrNull()
                                    val target = if (centre) viewport * 0.25f else 0f
                                    val extra = if (index == 0 && list.firstVisibleItemIndex > 0) (viewport * 0.8f - per).coerceAtLeast(0f) else 0f
                                    (index - list.firstVisibleItemIndex) * per - extra - (first?.offset?.let { -it } ?: 0) - target
                                }
                                if (kotlin.math.abs(delta) < 1f) break
                                val passSpec = if (pass == 1) spec else tween(Dx.Fast, easing = Dx.Ease)
                                val moved = list.animateScrollBy(delta, passSpec)
                                if (visible != null || kotlin.math.abs(moved) < 1f) break
                            }
                            val finalItem = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
                            if (finalItem == null) {
                                list.animateScrollToItem(index, targetOffset)
                            } else {
                                val target = if (centre) ((viewport - finalItem.size) / 2f).coerceAtLeast(0f) else 0f
                                val rem = finalItem.offset - target
                                if (kotlin.math.abs(rem) >= 1f) {
                                    list.animateScrollBy(rem, tween(Dx.Fast, easing = Dx.Ease))
                                }
                            }
                        } else {
                            list.animateScrollToItem(index, targetOffset)
                        }
                    } finally {
                        watcher?.cancel()
                    }
                }
            }
            if (!focused && onVisible != null) {
                for (attempt in 0 until 15) {
                    if (onVisible()) break
                    kotlinx.coroutines.delay(16)
                }
            }
        } finally {
            jumping = false
        }
    }

    /**
     * The page's one way of moving: [delta] px in the glide that suits its length, replacing whatever else was moving it
     * (never two at once). Asked again for the move already under way, it does nothing.
     */
    /** A page of short rows (the TV guide) glides by this instead, so a row's small move takes as long as Home's */
    var glideSpec: androidx.compose.animation.core.AnimationSpec<Float>? = null

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
                androidx.compose.animation.core.animate(0f, delta, animationSpec = glideSpec ?: glide(delta, vp)) { v, _ ->
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
        if (jumping) return
        val vp = viewport ?: return
        if (!row.isAttached || !vp.isAttached) return
        val top = vp.localPositionOf(row, androidx.compose.ui.geometry.Offset.Zero).y
        val delta = top + row.size.height / 2f - vp.size.height / 2f
        if (kotlin.math.abs(delta) < vp.size.height * 0.03f) return
        move(delta, vp.size.height.toFloat())
    }

    fun toTop() {
        if (jumping) return
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

private class RowFocusRef {
    var coords: androidx.compose.ui.layout.LayoutCoordinates? = null
    var had: Boolean = false
    /** Moved into before it had been placed: centred as soon as it is */
    var waiting: Boolean = false
}

/** A row of the page: when the remote moves into it the page glides to put it in the middle (once, however it moves along it) */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.centredWhenFocused(): Modifier {
    val scroller = LocalPageScroller.current ?: return this
    val ref = remember { RowFocusRef() }
    return this
        .onGloballyPositioned {
            ref.coords = it
            if (ref.waiting && it.isAttached) { ref.waiting = false; scroller.centre(it) }
        }
        .onFocusChanged {
            // (pressed quickly, the remote can land in a row only just made, not yet placed: the page waited for nothing
            // and stayed where it was while the remote went on down out of sight. Now it's centred the moment it's placed)
            if (it.hasFocus && !ref.had) {
                val c = ref.coords
                if (c != null && c.isAttached) scroller.centre(c) else ref.waiting = true
            }
            if (!it.hasFocus) ref.waiting = false
            ref.had = it.hasFocus
        }
        .bringIntoViewResponder(RowOwnsScrolling)
}

/** Room under the last row of a page so that it too can be brought up to the middle of the screen */
@Composable
fun pageBottomPad(): androidx.compose.ui.unit.Dp = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.5f).dp

@OptIn(ExperimentalFoundationApi::class)
private class IdlePrefetchScheduler : androidx.compose.foundation.lazy.layout.PrefetchScheduler {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val queue = ArrayDeque<androidx.compose.foundation.lazy.layout.PrefetchRequest>()
    private var posted = false
    private var active = true
    private var lastScrollMs = 0L

    fun onScrollActivity() {
        lastScrollMs = android.os.SystemClock.uptimeMillis()
        if (!active) return
        if (posted) {
            handler.removeCallbacks(tick)
            posted = false
            scheduleTick(110L)
        }
    }

    fun setActive(on: Boolean) {
        active = on
        if (!on) {
            handler.removeCallbacks(tick)
            posted = false
            queue.clear()
        }
    }

    override fun schedulePrefetch(prefetchRequest: androidx.compose.foundation.lazy.layout.PrefetchRequest) {
        if (!active) return
        queue.addLast(prefetchRequest)
        scheduleTick()
    }

    private fun scheduleTick(delayMs: Long = 16L) {
        if (!active || posted || queue.isEmpty()) return
        val wait = (lastScrollMs + 110L - android.os.SystemClock.uptimeMillis()).coerceAtLeast(delayMs)
        posted = true
        handler.postDelayed(tick, wait)
    }

    private val tick: Runnable = Runnable {
        posted = false
        if (!active || queue.isEmpty()) return@Runnable
        val wait = lastScrollMs + 110L - android.os.SystemClock.uptimeMillis()
        if (wait > 0L) {
            scheduleTick(wait)
            return@Runnable
        }
        val req = queue.first()
        val startNs = System.nanoTime()
        val scope = object : androidx.compose.foundation.lazy.layout.PrefetchRequestScope {
            override fun availableTimeNanos(): Long =
                if (System.nanoTime() - startNs < 16_000_000L) 120_000_000L else 0L
        }
        val hasMore = runCatching { with(req) { scope.execute() } }.getOrDefault(false)
        if (!hasMore && queue.isNotEmpty() && queue.first() === req) queue.removeFirst()
        if (queue.isNotEmpty()) scheduleTick(8L)
    }
}

@OptIn(ExperimentalFoundationApi::class)
class PagePrefetchStrategy : androidx.compose.foundation.lazy.LazyListPrefetchStrategy {
    private val scheduler = IdlePrefetchScheduler()
    override val prefetchScheduler: androidx.compose.foundation.lazy.layout.PrefetchScheduler
        get() = scheduler
    private val handles = LinkedHashMap<Int, androidx.compose.foundation.lazy.layout.LazyLayoutPrefetchState.PrefetchHandle>()

    private fun clearHandles() {
        handles.values.forEach { it.cancel() }
        handles.clear()
    }

    fun setActive(active: Boolean) {
        scheduler.setActive(active)
        if (!active) clearHandles()
    }

    private fun androidx.compose.foundation.lazy.LazyListPrefetchScope.scheduleIndex(index: Int, total: Int) {
        if (index in 0 until total && !handles.containsKey(index)) {
            handles[index] = schedulePrefetch(index)
        }
    }

    override fun androidx.compose.foundation.lazy.LazyListPrefetchScope.onVisibleItemsUpdated(
        layoutInfo: androidx.compose.foundation.lazy.LazyListLayoutInfo,
    ) {
        val visible = layoutInfo.visibleItemsInfo
        val total = layoutInfo.totalItemsCount
        if (visible.isEmpty()) {
            clearHandles()
            return
        }
        val first = visible.first().index
        val last = visible.last().index
        val it = handles.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (entry.key in first..last) {
                it.remove()
            } else if (entry.key !in (first - 1)..(last + 2)) {
                entry.value.cancel()
                it.remove()
            }
        }
        scheduleIndex(last + 1, total)
        scheduleIndex(last + 2, total)
        scheduleIndex(first - 1, total)
    }

    override fun androidx.compose.foundation.lazy.LazyListPrefetchScope.onScroll(
        delta: Float,
        layoutInfo: androidx.compose.foundation.lazy.LazyListLayoutInfo,
    ) {
        if (kotlin.math.abs(delta) >= 2f) scheduler.onScrollActivity()
        val visible = layoutInfo.visibleItemsInfo
        if (visible.isEmpty()) return
        val first = visible.first().index
        val last = visible.last().index
        val total = layoutInfo.totalItemsCount
        val targets = if (delta < 0f) intArrayOf(last + 1, last + 2) else intArrayOf(first - 1, first - 2)
        for (target in targets) {
            scheduleIndex(target, total)
        }
    }

    override fun androidx.compose.foundation.lazy.layout.NestedPrefetchScope.onNestedPrefetch(
        firstVisibleItemIndex: Int,
    ) {
    }
}

/**
 * A vertical page of rows with [PageScroller] and [BringIntoViewSpec] wired up in one place.
 * Pass [scroller] when the screen also drives the scroller directly (e.g. Home's billboard or TitlePage).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PageColumn(
    state: androidx.compose.foundation.lazy.LazyListState? = null,
    modifier: Modifier = Modifier,
    scroller: PageScroller? = null,
    bringIntoViewSpec: BringIntoViewSpec = CentredScrolling,
    contentPadding: PaddingValues = PaddingValues(bottom = pageBottomPad()),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(16.dp),
    prefetch: PagePrefetchStrategy? = null,
    content: LazyListScope.() -> Unit,
) {
    val ownedPrefetch = remember { PagePrefetchStrategy() }
    val listState = state ?: androidx.compose.foundation.lazy.rememberLazyListState(prefetchStrategy = ownedPrefetch)
    val activePrefetch = if (state == null) ownedPrefetch else prefetch
    androidx.compose.runtime.DisposableEffect(activePrefetch) {
        activePrefetch?.setActive(true)
        onDispose { activePrefetch?.setActive(false) }
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val pageScroller = scroller ?: remember(listState) { PageScroller(listState, scope) }
    val effectiveSpec = remember(pageScroller, bringIntoViewSpec) {
        object : BringIntoViewSpec {
            override val scrollAnimationSpec: androidx.compose.animation.core.AnimationSpec<Float>
                get() = bringIntoViewSpec.scrollAnimationSpec

            // (only while a jump (a shortcut, Back to the top) owns the page: an ordinary glide is the spec's own move, and
            // it must keep being asked, or a second press mid-glide was dropped and the page jumped when it caught up)
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
                if (pageScroller.jumping) 0f else bringIntoViewSpec.calculateScrollDistance(offset, size, containerSize)
        }
    }
    CompositionLocalProvider(
        LocalBringIntoViewSpec provides effectiveSpec,
        LocalPageScroller provides pageScroller,
    ) {
        androidx.compose.foundation.lazy.LazyColumn(
            state = listState,
            modifier = modifier.onGloballyPositioned { pageScroller.viewport = it },
            contentPadding = contentPadding,
            verticalArrangement = verticalArrangement,
            content = content,
        )
    }
}

/** Shelf heading isolated in its own scope so row focus changes never recompose the LazyRow below */
@Composable
private fun ShelfTitle(title: String, lift: RowLift, focused: () -> Boolean) {
    val titleAlpha = animateFloatAsState(if (focused()) 1f else 0.6f, tween(Dx.Med, easing = Dx.Ease), label = "row-title")
    val up = with(androidx.compose.ui.platform.LocalDensity.current) { 10.dp.toPx() }
    var right by remember { mutableStateOf(0f) }
    // (eased like the card's own lift: up as a card under it is chosen, back down as the remote moves on past it)
    val under by remember { androidx.compose.runtime.derivedStateOf { focused() && lift.left < right } }
    val shift by animateFloatAsState(if (under) 1f else 0f, androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 320f), label = "row-title-lift")
    Text(
        title,
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = Dx.Gutter)
            .onGloballyPositioned { right = it.positionInWindow().x + it.size.width }
            .graphicsLayer {
                alpha = titleAlpha.value
                // (Apple TV's: a card under the name, growing as it's chosen, lifts the name out of its way)
                translationY = -up * shift
            },
    )
}

/** Where the chosen card of a row is (its left edge, in the window): its row's name moves up when it's under it */
class RowLift {
    var left by androidx.compose.runtime.mutableFloatStateOf(Float.MAX_VALUE)
}

val LocalRowLift = androidx.compose.runtime.staticCompositionLocalOf<RowLift?> { null }

/**
 * A row the remote can't fall out of sideways: past its last card (or, with [left], its first) nothing happens, as on
 * Apple TV. Without it Compose looked for the nearest thing that way anywhere on the page, so Right on a title's only
 * trailer jumped down to About at the foot of the page.
 */
fun Modifier.keepsSideways(left: Boolean = false): Modifier = focusProperties {
    onExit = {
        if (requestedFocusDirection == androidx.compose.ui.focus.FocusDirection.Right || (left && requestedFocusDirection == androidx.compose.ui.focus.FocusDirection.Left)) cancelFocusChange()
    }
}.focusGroup()

/**
 * A row's own scroll state, made so that when the page is about to bring the row on screen, all the cards that will show
 * in it are built ahead (in the time spare between frames). Compose's default builds only the first two, and the rest
 * were built in the middle of the page's glide: on a Mi Box that frame was missed and the page jumped.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberShelfState(initialIndex: Int = 0): androidx.compose.foundation.lazy.LazyListState =
    androidx.compose.foundation.lazy.rememberLazyListState(
        initialFirstVisibleItemIndex = initialIndex,
        prefetchStrategy = remember { androidx.compose.foundation.lazy.LazyListPrefetchStrategy(nestedPrefetchItemCount = 8) },
    )

/** A titled row of cards that scrolls sideways as the remote moves along it */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Shelf(title: String, modifier: Modifier = Modifier, spacing: Dp = 18.dp, content: LazyListScope.() -> Unit) {
    // (the row the remote is in has its name bright; the others' are softer, as on Apple TV)
    val rowFocused = remember { mutableStateOf(false) }
    val rowLift = remember { RowLift() }
    val rowScrolling = rememberRowScrolling(Dx.Gutter, spacing)
    Column(
        modifier.fillMaxWidth().centredWhenFocused().onFocusChanged { rowFocused.value = it.hasFocus },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ShelfTitle(title, rowLift) { rowFocused.value }
        CompositionLocalProvider(LocalBringIntoViewSpec provides rowScrolling, LocalRowLift provides rowLift) {
            LazyRow(
                state = rememberShelfState(),
                // (up or down into the row, the remote lands on the card in the same column it came from: the rows' cards line
                // up in columns (rememberRowScrolling), Harry's grid; it doesn't jump back to the card it was last on there)
                modifier = Modifier.keepsSideways(),
                // (room around the cards for them to grow when chosen without overlapping row titles)
                contentPadding = PaddingValues(start = Dx.Gutter, end = Dx.Gutter, top = 20.dp, bottom = 26.dp),
                horizontalArrangement = Arrangement.spacedBy(spacing),
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
        style = TextStyle(fontSize = maxOf(12f, width.value * 0.1f).sp, fontWeight = FontWeight.Bold, shadow = Shadow(Color.Black.copy(alpha = 0.6f), blurRadius = 6f)),
        color = Color.White,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, end = 8.dp, bottom = 9.dp),
    )
}

/** New episodes this week: the episode's picture, the show and when */
@Composable
fun AiringCard(item: Airing, onClick: () -> Unit, width: Dp = 300.dp) {
    FocusCard(onClick = onClick, corner = Dx.RCard, modifier = Modifier.size(width, width * 0.64f)) {
        Box(Modifier.fillMaxSize()) {
            Art(item.still ?: item.background, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f))))
            Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                Text(
                    if (item.out) "NEW EPISODE" else item.countdown.uppercase(),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (item.out) Color.White else Dx.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(item.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${episodeLabel(item.season, item.episode)}${if (item.more > 0) " +${item.more}" else ""} · ${item.whenText}",
                    fontSize = 12.sp,
                    color = Dx.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A row still loading: skeleton cards where its posters will be, laid out as the row will be so nothing moves when it comes */
@Composable
fun LoadingShelf(title: String?) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // (no name yet: a skeleton bar where it'll be)
        if (title != null) Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Dx.Faint, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = Dx.Gutter))
        else Box(Modifier.padding(horizontal = Dx.Gutter, vertical = 4.dp).size(180.dp, 18.dp).skeleton(shape = RoundedCornerShape(Dx.RCard)))
        // (the room under each poster that a chosen card grows into, kept here too, so nothing moves when the row comes)
        Row(Modifier.padding(start = Dx.Gutter, end = Dx.Gutter, top = 20.dp, bottom = 26.dp + CAPTION_ROOM), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            repeat(8) { Box(Modifier.size(Dx.Poster, Dx.Poster * 1.5f).skeleton(shape = RoundedCornerShape(Dx.RCard))) }
        }
    }
}

/** A grid's poster still loading */
@Composable
fun LoadingPoster() {
    Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).skeleton(shape = RoundedCornerShape(Dx.RCard)))
}

/**
 * A row that's loading and then ready: the skeleton row fades into the real one (the same size, so nothing below moves)
 */
@Composable
fun ShelfSwap(loading: Boolean, title: String, content: @Composable () -> Unit) {
    androidx.compose.animation.Crossfade(loading, animationSpec = tween(Dx.Med, easing = Dx.Ease), label = "shelf") { l ->
        if (l) LoadingShelf(title) else content()
    }
}
