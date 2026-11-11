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

/**
 * A card the remote can choose, the Apple TV way: it lifts towards you when chosen, larger, with a faint white edge and a
 * soft glow around it in the main colour of its picture ([art]; a soft white when it has none). On black there's
 * nothing for a shadow to fall on, so the glow does what the shadow did.
 */
@Composable
fun FocusCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    corner: Dp = 12.dp,
    onLongClick: (() -> Unit)? = null,
    art: String? = null,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(corner)
    val context = androidx.compose.ui.platform.LocalContext.current
    var focused by remember { mutableStateOf(false) }
    val lift by animateFloatAsState(if (focused) 1f else 0f, tween(180), label = "lift")
    // (worked out only once the remote's on it: a row of cards does nothing until then)
    val tint by produceState<Color?>(artColours[art], art, focused) {
        if (focused && art != null && value == null) value = artColour(context, art)
    }
    val glow by androidx.compose.animation.animateColorAsState(tint ?: GlowNeutral, tween(300), label = "glow")
    val cornerPx = with(androidx.compose.ui.platform.LocalDensity.current) { corner.toPx() }
    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        // (grown here rather than by the card, so the glow grows with it)
        modifier = modifier
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .graphicsLayer {
                val s = 1f + 0.1f * lift
                scaleX = s
                scaleY = s
            }
            .drawBehind {
                if (lift <= 0f) return@drawBehind
                // A soft halo: rounded rings, fainter as they spread (cheap for a TV's graphics, unlike a real blur)
                val spread = 16.dp.toPx()
                val rings = 8
                for (i in rings downTo 1) {
                    val f = i / rings.toFloat()
                    val out = spread * f
                    drawRoundRect(
                        glow.copy(alpha = 0.11f * (1f - f * 0.85f) * lift),
                        topLeft = androidx.compose.ui.geometry.Offset(-out, -out),
                        size = androidx.compose.ui.geometry.Size(size.width + out * 2, size.height + out * 2),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerPx + out),
                    )
                }
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

private val GlowNeutral = Color(0xFFBFC6D0)
private val artColours = java.util.concurrent.ConcurrentHashMap<String, Color>()

/**
 * A picture's main colour, for its card's glow: the most colourful of an 8×8 copy's pixels (an average of the whole
 * comes out a muddy brown), lifted so it glows on black. Kept for as long as Lumen's open.
 */
private suspend fun artColour(context: android.content.Context, url: String): Color? {
    artColours[url]?.let { return it }
    val request = coil3.request.ImageRequest.Builder(context).data(url).size(8, 8).allowHardware(false).build()
    val result = coil3.SingletonImageLoader.get(context).execute(request) as? coil3.request.SuccessResult ?: return null
    val bitmap = result.image.toBitmap()
    val hsv = FloatArray(3)
    var best = -1f
    var picked = FloatArray(3)
    for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
        android.graphics.Color.colorToHSV(bitmap.getPixel(x, y), hsv)
        // colourful and not too dark
        val score = hsv[1] * (0.35f + hsv[2])
        if (score > best) { best = score; picked = hsv.copyOf() }
    }
    if (best < 0f) return null
    // (a grey picture glows a soft white; anything with colour, that colour, bright)
    val colour = if (picked[1] < 0.18f) GlowNeutral
    else Color(android.graphics.Color.HSVToColor(floatArrayOf(picked[0], picked[1].coerceIn(0.45f, 0.85f), picked[2].coerceAtLeast(0.85f))))
    artColours[url] = colour
    return colour
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
    FocusCard(onClick = onClick, art = title.art, modifier = if (fill) Modifier.fillMaxWidth().aspectRatio(2f / 3f) else Modifier.size(width, width * 1.5f)) {
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
    FocusCard(onClick = onClick, corner = 16.dp, art = item.still ?: item.background, modifier = Modifier.size(width, width * 0.64f)) {
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
        Row(Modifier.padding(horizontal = Dx.Gutter, vertical = 18.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            repeat(6) { Box(Modifier.size(Dx.Poster, Dx.Poster * 1.5f).background(Dx.Raised, RoundedCornerShape(12.dp))) }
        }
    }
}
