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

/**
 * A card the remote can choose, the Apple TV way: it lifts towards you when chosen, larger, casting a
 * soft shadow down under it, with a faint white edge
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
    val lift by animateFloatAsState(if (focused) 1f else 0f, tween(180), label = "lift")
    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        // (grown and lifted here rather than by the card, so the shadow grows with it: Android's light
        // sits above the screen, so a lifted card's shadow falls below it)
        modifier = modifier
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .graphicsLayer {
                val s = 1f + 0.1f * lift
                scaleX = s
                scaleY = s
                this.shape = shape
                shadowElevation = 26.dp.toPx() * lift
                ambientShadowColor = Color.Black
                spotShadowColor = Color.Black
            },
        shape = CardDefaults.shape(shape),
        colors = CardDefaults.colors(containerColor = Dx.Raised, focusedContainerColor = Dx.Raised),
        scale = CardDefaults.scale(focusedScale = 1f),
        border = CardDefaults.border(
            border = Border.None,
            focusedBorder = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)), shape = shape),
        ),
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

    fun centre(row: androidx.compose.ui.layout.LayoutCoordinates) {
        val vp = viewport ?: return
        if (!row.isAttached || !vp.isAttached) return
        val top = vp.localPositionOf(row, androidx.compose.ui.geometry.Offset.Zero).y
        val delta = top + row.size.height / 2f - vp.size.height / 2f
        if (kotlin.math.abs(delta) < vp.size.height * 0.03f) return
        scope.launch { list.animateScrollBy(delta, PageGlide) }
    }

    fun toTop() {
        scope.launch {
            if (list.firstVisibleItemIndex == 0) list.animateScrollBy(-list.firstVisibleItemScrollOffset.toFloat(), PageGlide)
            else list.animateScrollToItem(0)
        }
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
                // (room around the cards for them to grow and glow when chosen)
                contentPadding = PaddingValues(horizontal = Dx.Gutter, vertical = 12.dp),
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
    FocusCard(onClick = onClick, modifier = if (fill) Modifier.fillMaxWidth().aspectRatio(2f / 3f) else Modifier.size(width, width * 1.5f)) {
        Box(Modifier.fillMaxSize()) {
            // (no poster, or one that won't load: its name instead of a blank grey card, as search results had)
            var noArt by remember(title.art) { mutableStateOf(title.art == null) }
            Art(title.art, Modifier.fillMaxSize(), onError = { noArt = true })
            if (hasNameOnPoster(title) || noArt) PosterName(title, width)
            if (rank != null) {
                Text(
                    "$rank",
                    style = TextStyle(
                        fontSize = (width.value * 0.3f).sp,
                        fontWeight = FontWeight.Black,
                        shadow = Shadow(Color.Black.copy(alpha = 0.6f), blurRadius = 12f),
                    ),
                    color = Color.White,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            if (title.watched) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "Watched",
                    tint = Color.White,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp).background(Color.Black, CircleShape),
                )
            }
        }
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
    FocusCard(onClick = onClick, corner = 16.dp, modifier = Modifier.size(width, width * 0.56f)) {
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
                Text(item.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "S${item.season}, E${item.episode}${if (item.more > 0) " +${item.more}" else ""} · ${item.whenText}",
                    fontSize = 12.sp,
                    color = Dx.Muted,
                    maxLines = 1,
                )
            }
        }
    }
}

/** A row still loading: grey cards where its posters will be */
@Composable
fun LoadingShelf(title: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Dx.Faint, modifier = Modifier.padding(horizontal = Dx.Gutter))
        Spacer(Modifier.height(10.dp))
        Row(Modifier.padding(horizontal = Dx.Gutter, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            repeat(6) { Box(Modifier.size(Dx.Poster, Dx.Poster * 1.5f).background(Dx.Raised, RoundedCornerShape(12.dp))) }
        }
    }
}
