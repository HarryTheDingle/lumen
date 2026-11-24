package com.notdxbi.dxbi.tv.lumen

import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import coil3.SingletonImageLoader
import androidx.compose.ui.graphics.asImageBitmap
import com.notdxbi.dxbi.tv.R
import com.notdxbi.dxbi.tv.phone.Title

/**
 * Lumen's look, as the iPad app has it (ios/Dxbi/UI/Theme.swift): black, the icon's cyan for what's
 * chosen, its blue for progress. Sizes are for a TV seen from the sofa (a 1080p screen is 960 × 540 dp).
 */
object Dx {
    /** Lumen's cyan, from the icon: what's chosen, small highlights */
    // (white, as Apple TV's: the cyan Lumen once had is gone everywhere)
    val Accent = Color.White
    /** The icon's blue: progress (watched so far) */
    val Blue = Color(0xFF2F8BFF)
    val Amber = Color(0xFFFFB547)
    /** Text on the accent (it's light) */
    val OnAccent = Color.Black
    /** Black, as Harry wants it (2026-11-11; it was Apple TV's #2C2C2E grey): a chosen card glows in its own colour instead of casting a shadow */
    val Background = Color.Black
    /** A card's face before its picture comes */
    val Raised = Color(0xFF1C1C1E)
    /** Slow in, slow out: how everything moves (the home screen's own curve) */
    val Ease = androidx.compose.animation.core.CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)
    /** A panel's face where Raised is too close to what's behind it */
    val Raised2 = Color(0xFF2A2A2E)
    val Danger = Color(0xFFFF6B6B)
    /** Focus growth: list rows, buttons and pills, round things (avatars, keys) */
    const val FocusRow = 1.03f
    const val FocusButton = 1.06f
    const val FocusRound = 1.1f
    /** Corner radii: badges, cards, panels */
    val RSmall = 4.dp
    val RCard = 12.dp
    val RPanel = 24.dp
    /** Animation durations (ms): quick, medium, slow */
    const val Fast = 200
    const val Med = 320
    const val Slow = 480
    val Muted = Color.White.copy(alpha = 0.62f)
    val Faint = Color.White.copy(alpha = 0.5f)
    /** TV-safe margin at the screen's sides */
    val Gutter = 48.dp
    val Poster = 124.dp
}

@Composable
fun LumenTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Dx.Accent,
            onPrimary = Dx.OnAccent,
            background = Dx.Background,
            surface = Dx.Raised,
            onSurface = Color.White,
            onBackground = Color.White,
        ),
    ) {
        // (one clock for every skeleton on screen, read only while drawing: they sweep together, nothing recomposes, and
        // it runs only while one's showing, so a still screen asks for no frames)
        val clock = remember { SkeletonClock() }
        androidx.compose.runtime.LaunchedEffect(clock.users > 0) {
            if (clock.users == 0) return@LaunchedEffect
            while (true) {
                clock.sweep.snapTo(0f)
                clock.sweep.animateTo(1f, tween(1500, easing = androidx.compose.animation.core.LinearEasing))
                kotlinx.coroutines.delay(250)
            }
        }
        androidx.compose.runtime.CompositionLocalProvider(LocalSkeleton provides clock, content = content)
    }
}

class SkeletonClock {
    var users by androidx.compose.runtime.mutableIntStateOf(0)
    val sweep = androidx.compose.animation.core.Animatable(0f)
}

/** Where the skeletons' light is, 0 to 1 across the screen (see [skeleton]) */
val LocalSkeleton = androidx.compose.runtime.staticCompositionLocalOf<SkeletonClock?> { null }

/**
 * A skeleton while something loads: the card's grey with a soft band of light passing over it, slowly, the same band
 * across the whole screen (it's placed by where the card is, so a row of them reads as one sweep). [on]: still loading
 */
@Composable
fun Modifier.skeleton(on: Boolean = true, shape: androidx.compose.ui.graphics.Shape = androidx.compose.ui.graphics.RectangleShape): Modifier {
    if (!on) return this
    val clock = LocalSkeleton.current ?: return this.background(Dx.Raised, shape)
    androidx.compose.runtime.DisposableEffect(clock) { clock.users++; onDispose { clock.users-- } }
    val screen = with(androidx.compose.ui.platform.LocalDensity.current) { androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp.toPx() }
    var x by remember { mutableStateOf(0f) }
    return this
        .androidx_onPlaced { x = it }
        .drawBehind {
            val band = screen * 0.35f
            val centre = -band + (screen + band * 2) * clock.sweep.value - x
            val outline = shape.createOutline(size, layoutDirection, this)
            drawOutline(outline, Dx.Raised)
            // (the shimmer only where its band is: a gradient over every loading card, every frame, was costly on a Mi Box;
            // outside the band it's clear anyway)
            val left = centre - band
            val right = centre + band
            if (right > 0f && left < size.width) clipRect(left = left.coerceAtLeast(0f), right = right.coerceAtMost(size.width)) {
                drawOutline(outline, Brush.linearGradient(
                    0f to Color.Transparent, 0.5f to Color.White.copy(alpha = 0.06f), 1f to Color.Transparent,
                    start = androidx.compose.ui.geometry.Offset(centre - band / 2, 0f),
                    end = androidx.compose.ui.geometry.Offset(centre + band / 2, size.height * 0.4f),
                ))
            }
        }
}

private fun Modifier.androidx_onPlaced(at: (Float) -> Unit) =
    this.then(Modifier.onGloballyPositioned { at(it.positionInRoot().x) })

/** Lumen's logo: the glass letters lit cyan through blue to amber (graphics/Lumen Logo transparent.png) */
@Composable
fun Logo(height: Dp = 40.dp, modifier: Modifier = Modifier) {
    Image(
        painterResource(R.drawable.lumen_logo),
        contentDescription = "Lumen",
        contentScale = ContentScale.Fit,
        modifier = modifier.height(height),
    )
}

/** A picture from the web, filling its frame (the raised grey while it loads, or when there's none) */
@Composable
fun Art(asked: String?, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop, onError: (() -> Unit)? = null) {
    val url = fresh(asked)
    // (a skeleton while it comes; the picture fades in over it, the loader's crossfade)
    var loading by remember(url) { mutableStateOf(url != null) }
    // (the grey under it only while it loads: once the picture's there it's one less shape to draw on every frame)
    Box(modifier.then(if (loading) Modifier.background(Dx.Raised).skeleton() else Modifier)) {
        if (url != null) AsyncImage(
            model = url, contentDescription = null, contentScale = contentScale,
            onSuccess = { loading = false },
            onError = { loading = false; onError?.invoke() },
            modifier = Modifier.matchParentSize(),
        )
    }
}

/** A title's logo (its name drawn as on the poster), or its name in large type when it has none */
@Composable
fun TitleLogo(title: Title, maxHeight: Dp, maxWidth: Dp, textSize: TextUnit, modifier: Modifier = Modifier, centred: Boolean = false) {
    var missing by remember(title.logoUrl) { mutableStateOf(title.logoUrl == null) }
    Box(modifier.fillMaxWidth(), contentAlignment = if (centred) Alignment.Center else Alignment.CenterStart) {
        if (!missing) {
            AsyncImage(
                model = fresh(title.logoUrl),
                contentDescription = title.name,
                contentScale = ContentScale.Fit,
                alignment = if (centred) Alignment.Center else Alignment.CenterStart,
                onError = { missing = true },
                modifier = Modifier.height(maxHeight).fillMaxWidth(),
            )
        } else {
            Text(
                title.name,
                style = TextStyle(
                    fontSize = textSize,
                    fontWeight = FontWeight.Black,
                    lineHeight = textSize * 1.05f,
                    shadow = Shadow(Color.Black.copy(alpha = 0.5f), blurRadius = 20f),
                ),
                color = Color.White,
                textAlign = if (centred) TextAlign.Center else TextAlign.Start,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A small outlined tag (a year, New, 4K…) */
@Composable
fun Badge(text: String, colour: Color = Color.White) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = colour,
        modifier = Modifier
            .border(BorderStroke(1.dp, colour.copy(alpha = 0.7f)), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/**
 * A progress line in the icon's blue, with a soft glow, over a track that shows on light art too;
 * a little progress still shows (a dot, not a sliver)
 */
@Composable
fun ProgressLine(value: Float, modifier: Modifier = Modifier) {
    val p = value.coerceIn(0f, 1f)
    BoxWithConstraints(modifier.height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.28f))) {
        if (p > 0f) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(maxOf(maxWidth * p, 4.dp))
                    .shadow(4.dp, CircleShape, ambientColor = Dx.Blue, spotColor = Dx.Blue)
                    .background(Dx.Blue, CircleShape),
            )
        }
    }
}

/** The signed-in person: their initial in a circle */
@Composable
fun Avatar(email: String?, size: Dp = 36.dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(Brush.verticalGradient(listOf(Color(0xFF4A4A4C), Color(0xFF3A3A3C))))
            .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text((email ?: "?").take(1).uppercase(), fontSize = (size.value * 0.45f).sp, fontWeight = FontWeight.SemiBold, color = Color.White)
    }
}

fun typeName(type: String) = when (type) {
    "movie" -> "Movie"
    "series" -> "TV Show"
    "anime" -> "Anime"
    "channel" -> "Channel"
    "tv" -> "Live TV"
    else -> type.replaceFirstChar { it.uppercase() }
}

/**
 * Apple TV's title-page background: the art blurred right down and dimmed, still under the whole page. The blur is
 * worked out once per picture on a small copy (a box blur, three passes, much like a Gaussian) and the result stretched
 * over the screen: nothing for the TV to redo as the page moves, which a live blur would be on every frame.
 */
@Composable
fun BlurredArt(url: String?, modifier: Modifier = Modifier, dim: Float = 0.42f) {
    val context = LocalContext.current
    val image by androidx.compose.runtime.produceState(url?.let { blurCache.get(it) }, url) {
        if (url == null || value != null) return@produceState
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            runCatching {
                val request = ImageRequest.Builder(context).data(url).size(BLUR_W, BLUR_W * 9 / 16)
                    .allowHardware(false).build()
                val result = SingletonImageLoader.get(context).execute(request)
                val bmp = (result as? coil3.request.SuccessResult)?.image?.toBitmap() ?: return@runCatching null
                blurred(bmp).asImageBitmap().also { blurCache.put(url, it) }
            }.getOrNull()
        }
    }
    Box(modifier.background(Color(0xFF14181C))) {
        androidx.compose.animation.AnimatedVisibility(image != null, enter = androidx.compose.animation.fadeIn(tween(Dx.Med, easing = Dx.Ease)), modifier = Modifier.fillMaxSize()) {
            image?.let {
                Image(it, contentDescription = null, contentScale = ContentScale.Crop, filterQuality = androidx.compose.ui.graphics.FilterQuality.High, modifier = Modifier.fillMaxSize())
            }
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))
    }
}

private const val BLUR_W = 160
private val blurCache = android.util.LruCache<String, androidx.compose.ui.graphics.ImageBitmap>(8)

/** A copy of [src] at [BLUR_W] wide, blurred (three box passes each way, radius 7: about a 12 px Gaussian at that size) */
private fun blurred(src: android.graphics.Bitmap): android.graphics.Bitmap {
    val w = BLUR_W
    val h = (src.height * w / src.width.coerceAtLeast(1)).coerceAtLeast(1)
    val small = android.graphics.Bitmap.createScaledBitmap(src.copy(android.graphics.Bitmap.Config.ARGB_8888, false), w, h, true)
    val px = IntArray(w * h)
    small.getPixels(px, 0, w, 0, 0, w, h)
    val tmp = IntArray(w * h)
    repeat(3) {
        boxPass(px, tmp, w, h, 7, horizontal = true)
        boxPass(tmp, px, w, h, 7, horizontal = false)
    }
    return android.graphics.Bitmap.createBitmap(px, w, h, android.graphics.Bitmap.Config.ARGB_8888)
}

private fun boxPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
    val lines = if (horizontal) h else w
    val len = if (horizontal) w else h
    val span = 2 * r + 1
    for (line in 0 until lines) {
        fun at(i: Int): Int { val c = i.coerceIn(0, len - 1); return if (horizontal) line * w + c else c * w + line }
        var rs = 0; var gs = 0; var bs = 0
        for (i in -r..r) { val p = src[at(i)]; rs += (p shr 16) and 255; gs += (p shr 8) and 255; bs += p and 255 }
        for (i in 0 until len) {
            dst[at(i)] = (0xFF shl 24) or ((rs / span) shl 16) or ((gs / span) shl 8) or (bs / span)
            val out = src[at(i - r)]; val inn = src[at(i + r + 1)]
            rs += ((inn shr 16) and 255) - ((out shr 16) and 255)
            gs += ((inn shr 8) and 255) - ((out shr 8) and 255)
            bs += (inn and 255) - (out and 255)
        }
    }
}

/**
 * Apple TV's frosted glass for small things over dark or busy pictures (round buttons, badges, boxes): a light
 * see-through fill with a faint top-lit edge, so they read on black as well as on bright art.
 */
object Glass {
    val Fill = Color.White.copy(alpha = 0.16f)
    val Edge = Brush.verticalGradient(0f to Color.White.copy(alpha = 0.38f), 1f to Color.White.copy(alpha = 0.08f))
}

/** A glass fill and edge in [shape] */
fun Modifier.glass(shape: androidx.compose.ui.graphics.Shape, fill: Color = Glass.Fill): Modifier =
    this.background(fill, shape).border(BorderStroke(1.dp, Glass.Edge), shape)

/**
 * The app's own pictures (genre and mood tiles), decoded once and kept: painterResource decoded one on the main thread
 * every time a tile was first made, a hitch as each row of them came on screen. [warmPictures] decodes them all in the
 * background as Lumen starts.
 */
object Pictures {
    private val kept = java.util.concurrent.ConcurrentHashMap<Int, androidx.compose.ui.graphics.ImageBitmap>()

    fun get(context: android.content.Context, id: Int): androidx.compose.ui.graphics.ImageBitmap =
        kept.getOrPut(id) { android.graphics.BitmapFactory.decodeResource(context.resources, id).asImageBitmap() }

    fun warm(context: android.content.Context, ids: List<Int>) {
        val app = context.applicationContext
        Thread({ ids.forEach { runCatching { get(app, it) } } }, "lumen-pictures").apply { priority = Thread.MIN_PRIORITY }.start()
    }
}

/** One of the app's own pictures, from [Pictures] */
@Composable
fun Picture(id: Int, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val bitmap = remember(id) { Pictures.get(context, id) }
    androidx.compose.foundation.Image(androidx.compose.ui.graphics.painter.BitmapPainter(bitmap), contentDescription = null, contentScale = contentScale, modifier = modifier)
}
