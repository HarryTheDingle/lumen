package com.notdxbi.dxbi.tv.lumen

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import kotlin.math.roundToInt
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.HideImage
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.notdxbi.dxbi.tv.phone.Continue
import com.notdxbi.dxbi.tv.phone.Title
import com.notdxbi.dxbi.tv.phone.episodeLabel
import kotlinx.coroutines.delay

/** What a card's menu and caption can do: play the title, add it to the Library, fetch its genres */
class CardActions(
    val play: (Title) -> Unit,
    val setInList: (Title, Boolean) -> Unit,
    /** Titles added to or taken out of the Library here, before Stremio's own lists catch up */
    val listMarks: StateFlow<Map<String, Boolean>>,
    /** A title's trailer, when its row didn't come with one (Home's billboard plays it) */
    val trailer: suspend (Title) -> String?,
    /** A title's genres, for the chosen card's caption when its row didn't bring them */
    val genres: suspend (Title) -> List<String> = { emptyList() },
    /** Every title watched (Stremio.watchedIds): a card's ✓, whatever row it came in */
    val watched: StateFlow<Set<String>> = kotlinx.coroutines.flow.MutableStateFlow(emptySet()),
    /** Never suggest this title (Top Picks leave it out, and steer away from what it's like) */
    val notInterested: ((Title) -> Unit)? = null,
    /** Titles turned down here: their cards stay where they are, dimmed, until the row's made again */
    val turnedDown: StateFlow<Set<String>> = kotlinx.coroutines.flow.MutableStateFlow(emptySet()),
    /** Mark a title watched, or unwatched (which also puts it back to the start) */
    val setWatched: ((Title, Boolean) -> Unit)? = null,
    val watchedState: androidx.compose.runtime.State<Set<String>>? = null,
    val turnedDownState: androidx.compose.runtime.State<Set<String>>? = null,
)

val LocalCardActions = staticCompositionLocalOf<CardActions?> { null }

/**
 * A poster held down: Play, the title's page, and adding it to (or taking it out of) the Library, as Home's Continue
 * Watching cards have their own. Shown while [open]; every poster row has it, Discover's included.
 */
@Composable
fun TitleHoldMenu(title: Title, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val actions = LocalCardActions.current ?: return onDismiss()
    val marks by actions.listMarks.collectAsState()
    val inList = marks[title.id] ?: title.inLibrary
    val seen = (actions.watchedState?.value ?: actions.watched.collectAsState().value).contains(title.id) || (title.watched && title.type == "movie")
    val context = androidx.compose.ui.platform.LocalContext.current
    ActionMenu(
        title.name,
        listOfNotNull(
            Action("Play", Icons.Filled.PlayArrow) { actions.play(title) },
            Action("Go to ${if (title.type == "movie") "Film" else "Show"}", Icons.Outlined.Info) { onOpen() },
            if (inList) Action("Remove from Library", Icons.Filled.Check) { actions.setInList(title, false) }
            else Action("Add to Library", Icons.Filled.Add) { actions.setInList(title, true) },
            actions.setWatched?.let { set ->
                if (seen) Action("Mark as Unwatched", Icons.Outlined.VisibilityOff) { set(title, false) }
                else Action("Mark as Watched", Icons.Outlined.Visibility) { set(title, true) }
            },
            // (a picture that isn't this title's: never used for it again, on every device, and the next best loads)
            if (!Regex("""tt\d+""").matches(title.id)) null
            else Action("Wrong Picture", Icons.Outlined.HideImage) { CheckedArt.report(context, title.id, title.name) },
            // (not for what they've saved: they wanted it)
            actions.notInterested?.takeIf { Regex("""tt\d+""").matches(title.id) && !inList }?.let { act ->
                Action("Not Interested", Icons.Outlined.ThumbDown) { act(title) }
            },
        ),
        onDismiss = onDismiss,
    )
}

/** Each title's genres, once looked up (kept while Lumen's open) */
private val genreMemory = ConcurrentHashMap<String, List<String>>()

/**
 * A poster card: constant measured size (`width` x `width * 1.5f`) so focus never triggers relayout in rows or pages;
 * when the remote is on it, a subtle caption strip ("Movie · Drama") fades in over the bottom of the poster via graphicsLayer.
 * Hold OK for Play, Library, and Not Interested.
 */
@Composable
fun PreviewPosterCard(title: Title, onClick: () -> Unit, rank: Int? = null, width: Dp = Dx.Poster) {
    val height = width * 1.5f
    val actions = LocalCardActions.current
    var focused by remember { mutableStateOf(false) }
    var held by remember { mutableStateOf(false) }
    if (held) TitleHoldMenu(title, onOpen = onClick, onDismiss = { held = false })
    val growAnim = animateFloatAsState(if (focused) 1f else 0f, tween(Dx.Fast, easing = Dx.Ease), label = "grow")
    // what it is for the caption: "Movie · Drama", the genre looked up once it's chosen if its row had none
    var genres by remember(title.id) { mutableStateOf(title.genres.ifEmpty { genreMemory[title.id].orEmpty() }) }
    LaunchedEffect(focused) {
        if (!focused || genres.isNotEmpty() || actions == null) return@LaunchedEffect
        // (a moment first: the remote running along a row doesn't ask for every card it passes)
        delay(150)
        genres = actions.genres(title).also { genreMemory[title.id] = it }
    }
    val caption = remember(title.type, genres) { listOfNotNull(typeName(title.type), genres.firstOrNull()).joinToString(" · ") }
    // Apple TV's: the poster the remote is on grows down into the room kept under every poster, a strip of its own
    // colours with what it is in it. The room is there in every row, so nothing moves; the growing is the card's clip
    // and rim reaching down (FocusCard's hiddenFoot), so nothing is measured or drawn again while it does
    val room = with(androidx.compose.ui.platform.LocalDensity.current) { CAPTION_ROOM.toPx() }
    val stripShown by remember { androidx.compose.runtime.derivedStateOf { growAnim.value > 0f } }
    FocusCard(
        onLongClick = { held = true },
        onClick = onClick,
        hiddenFoot = { room * (1f - growAnim.value) },
        modifier = Modifier
            .size(width, height + CAPTION_ROOM)
            .onFocusChanged { focused = it.isFocused || it.hasFocus },
    ) {
        PosterFace(
            title = title,
            rank = rank,
            width = width,
            grow = { growAnim.value },
            caption = caption,
            room = CAPTION_ROOM,
            strip = stripShown,
        )
    }
}

/** The room kept under every poster, which the one the remote is on grows down into */
val CAPTION_ROOM = 30.dp

/**
 * Continue Watching: wide art with the name in plain type and how far they got. OK opens the title's page.
 */
@Composable
fun PreviewContinueCard(item: Continue, onClick: () -> Unit, width: Dp = 270.dp, onLongClick: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    val title = item.title
    val height = width * 0.64f
    var focused by remember { mutableStateOf(false) }
    // (resting on it: Play's work done ahead, so it starts at once: Launcher.warm)
    val launcher = LocalLauncher.current
    LaunchedEffect(focused) { if (focused) launcher?.warm(title) }
    FocusCard(
        onClick = onClick,
        corner = 16.dp,
        onLongClick = onLongClick,
        modifier = modifier
            .width(width)
            .height(height)
            .onFocusChanged { focused = it.isFocused || it.hasFocus },
    ) {
        Box(Modifier.fillMaxSize()) {
            Art(title.backdrop, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.25f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.92f))))
            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // (the name in plain type, as the preview cards: a logo this small is hard to read)
                Text(
                    title.name,
                    style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, shadow = Shadow(Color.Black, blurRadius = 8f)),
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (item.progress > 0f) ProgressLine(item.progress, Modifier.width(56.dp))
                    val words = listOfNotNull(
                        episodeLabel(item.videoId),
                        if (item.newEpisodes > 0) "${item.newEpisodes} new" else null,
                    ).joinToString(" · ").ifEmpty { typeName(title.type) }
                    if (words.isNotEmpty()) Text(words, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White, maxLines = 1)
                }
            }
        }
    }
}

/**
 * A title's trailer, with its sound, filling the space this takes up: YouTube's privacy-enhanced player
 * (as Lumen's hover cards play them) with everything of the player's hidden, just the video. It shows
 * only once the video's really playing, so a trailer that won't play leaves nothing behind.
 *
 * The player goes under the screen it's on, which leaves a clear space for it (Home's billboard, a
 * title's page): a web page's video only shows when it's drawn straight to the screen, and inside the
 * screens' own layers it came out black. [muted]: quiet (it keeps playing). [loop]: from the start
 * again at the end, else [onEnded]. [onPlaying]: once it's going.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun Trailer(
    id: String,
    modifier: Modifier = Modifier,
    muted: Boolean = false,
    loop: Boolean = true,
    onPlaying: () -> Unit = {},
    onEnded: () -> Unit = {},
    /** Watching it, not a picture behind a page: the whole frame (not cropped), from the start, with [paused] and [jump] (seconds, added up) from the remote */
    watching: Boolean = false,
    paused: Boolean = false,
    jump: Int = 0,
) {
    val host = LocalView.current
    val context = LocalContext.current
    var shown by remember(id) { mutableStateOf(false) }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(Dx.Slow, easing = Dx.Ease), label = "trailer")
    val playing by rememberUpdatedState(onPlaying)
    val ended by rememberUpdatedState(onEnded)
    val hush by rememberUpdatedState(muted)
    // (carrying on where it got to before, when a title's page opens from Home's billboard)
    val start = remember(id) { if (watching) 0.0 else trailerTimes[id] ?: 0.0 }
    val web = remember(id) {
        WebView(context).apply {
            // the remote never lands on it: it's a picture, nothing more
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            setBackgroundColor(AndroidColor.BLACK)
            settings.javaScriptEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.domStorageEnabled = true
            // (a video not going yet, starting, buffering or looping back round, shows the web view's own poster: a grey
            // frame with a big play mark, which covered the whole page over a trailer; a clear one shows the picture under it)
            webChromeClient = object : WebChromeClient() {
                override fun getDefaultVideoPoster(): android.graphics.Bitmap =
                    android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
            }
            // the page says when its video's going, how far it's got, and when it's over
            addJavascriptInterface(TrailerSignal(id, onPlaying = { post { shown = true; playing() } }, onEnded = { post { ended() } }), "LumenTrailer")
            val script = trailerScript(start, watching)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    view.evaluateJavascript("window.lumenMuted=$hush;window.lumenPaused=false;$script", null)
                }
            }
            val q = "autoplay=1&mute=0&controls=0&cc_load_policy=0&disablekb=1&fs=0&iv_load_policy=3&modestbranding=1&playsinline=1&rel=0" +
                if (loop) "&loop=1&playlist=$id" else ""
            // YouTube's player wants to know which site it's on: Stremio's, as in Lumen's own cards
            loadUrl("https://www.youtube-nocookie.com/embed/$id?$q", mapOf("Referer" to "https://web.stremio.com/"))
        }
    }
    DisposableEffect(web) {
        // just under the screen's own view (Lumen's Compose view, or a page's window): over what's under that
        val anchor = host.parent as? View
        val container = anchor?.parent as? ViewGroup
        container?.addView(web, container.indexOfChild(anchor), FrameLayout.LayoutParams(0, 0))
        onDispose {
            web.loadUrl("about:blank")
            container?.removeView(web)
            web.destroy()
        }
    }
    LaunchedEffect(web) { snapshotFlow { alpha }.collect { web.alpha = it } }
    // quiet when asked (the page's moved away from it), and it stays playing
    LaunchedEffect(muted) { web.evaluateJavascript("window.lumenMuted=$muted", null) }
    LaunchedEffect(paused) { web.evaluateJavascript("window.lumenPaused=$paused", null) }
    var jumped by remember { mutableStateOf(jump) }
    LaunchedEffect(jump) {
        if (jump != jumped) web.evaluateJavascript("window.lumenSeek=(window.lumenSeek||0)+${jump - jumped}", null)
        jumped = jump
    }
    // where it goes: under this space, following it as it moves
    var place by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    LaunchedEffect(place) {
        val r = place ?: return@LaunchedEffect
        val lp = web.layoutParams as? FrameLayout.LayoutParams ?: return@LaunchedEffect
        val w = r.width.roundToInt()
        val h = r.height.roundToInt()
        if (lp.width != w || lp.height != h) {
            lp.width = w
            lp.height = h
            web.layoutParams = lp
        }
        web.translationX = r.left
        web.translationY = r.top
    }
    Box(modifier.onGloballyPositioned { place = it.boundsInWindow() })
}

/** What the trailer's page tells the app (called from its script) */
class TrailerSignal(private val id: String, private val onPlaying: () -> Unit, private val onEnded: () -> Unit) {
    @JavascriptInterface
    fun playing() = onPlaying()

    /** How far it's got, so a title's page can carry on from there */
    @JavascriptInterface
    fun time(seconds: Double) {
        trailerTimes[id] = seconds
    }

    @JavascriptInterface
    fun ended() = onEnded()
}

/** How far each trailer got (a title's page carries on from there) */
val trailerTimes = ConcurrentHashMap<String, Double>()

/**
 * What goes into YouTube's player page: nothing of the player but its video (every button, title, logo,
 * caption, overlay and progress bar hidden, whatever YouTube calls them: it changes them often), the
 * video filling the space. Then it plays from [start] seconds, quiet while window.lumenMuted, and says
 * once it's going, how far it's got, and when it's over.
 */
private fun trailerScript(start: Double, whole: Boolean = false): String {
    // (behind a page the trailer is a background, quiet; watched on its own from the Trailers row it's at full volume)
    val volume = if (whole) 1.0 else 0.35
    val css = "html,body{margin:0!important;overflow:hidden!important;background:#000!important}" +
        "body *{visibility:hidden!important}" +
        "video{visibility:visible!important;object-fit:${if (whole) "contain" else "cover"}!important;width:100vw!important;height:100vh!important;left:0!important;top:0!important}"
    return """
        (function () {
          var s = document.createElement('style');
          s.textContent = ${org.json.JSONObject.quote(css)};
          document.documentElement.appendChild(s);
          var said = false, over = false, sought = !($start > 0);
          setInterval(function () {
            var v = document.querySelector('video');
            if (!v) return;
            var quiet = !!window.lumenMuted;
            if (v.muted !== quiet) v.muted = quiet;
            if (!quiet && Math.abs(v.volume - $volume) > 0.01) v.volume = $volume;
            if (!sought && v.readyState > 0) { v.currentTime = $start; sought = true; }
            if (v.ended) { if (!over) { over = true; LumenTrailer.ended(); } return; }
            if (window.lumenSeek) { v.currentTime = Math.max(0, v.currentTime + window.lumenSeek); window.lumenSeek = 0; }
            var hold = !!window.lumenPaused;
            if (!v.paused && hold) v.pause();
            if (v.paused && !hold) v.play().catch(function () {});
            if (!said && !v.paused && v.currentTime > 0.3) { said = true; LumenTrailer.playing(); }
            LumenTrailer.time(v.currentTime);
          }, 300);
        })();
    """.trimIndent()
}

/**
 * The shared poster face (art, fallback title, badge/tag, watched mark, turned-down dimming, and optional
 * focus caption strip rendered inside the poster's fixed bounds via graphicsLayer) used by both [PosterCard]
 * and [PreviewPosterCard].
 */
@Composable
fun PosterFace(
    title: Title,
    rank: Int? = null,
    width: Dp = Dx.Poster,
    fill: Boolean = false,
    grow: () -> Float = { 0f },
    caption: String? = null,
    /** Room under the poster that the chosen card grows into (0: none, the caption sits over the poster's foot) */
    room: Dp = 0.dp,
    /** The strip under the poster is up (the chosen card, and while it closes) */
    strip: Boolean = false,
) {
    if (room > 0.dp) {
        Box(Modifier.fillMaxSize()) {
            // the strip (GrowStrip): the poster's own foot carried on, blurred and darkening, only on the chosen card
            Box(Modifier.fillMaxWidth().height(width * 1.5f)) {
                PosterFace(title, rank, width, fill = true, grow = { 0f }, caption = null)
            }
            // (over the poster's foot, fading in, so the poster runs on into it with no edge)
            if (strip) GrowStrip(title, room, grow)
            if (strip && caption != null) Text(
                caption,
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 8.dp, vertical = 8.dp)
                    .graphicsLayer { alpha = ((grow() - 0.5f) * 2f).coerceIn(0f, 1f) },
            )
        }
        return
    }
    val actions = LocalCardActions.current
    val watched = (actions?.watchedState?.value ?: actions?.watched?.collectAsState()?.value)?.contains(title.id) == true || (title.watched && title.type == "movie")
    val dimmed = (actions?.turnedDownState?.value ?: actions?.turnedDown?.collectAsState()?.value)?.contains(title.id) == true
    Box(Modifier.fillMaxSize()) {
        // (no poster, or one that won't load: its name instead of a blank grey card, as search results had)
        var noArt by remember(title.art) { mutableStateOf(title.art == null) }
        Art(title.art, Modifier.fillMaxSize(), onError = { noArt = true })
        if (hasNameOnPoster(title) || noArt) PosterName(title, width)
        // (no numbers on the Top 10: Harry's call; the row's order says it)
        CardLabel(title.id, width, inTopTen = rank != null)
        // (turned down: dimmed with a thumb, in place, so the remote doesn't lose its card)
        if (dimmed) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.62f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.ThumbDown, contentDescription = "Not interested", tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(34.dp))
            }
        }
        // caption scrim + text inside the bottom of the poster, driven strictly by graphicsLayer (no relayout)
        if (caption != null) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(48.dp)
                    .graphicsLayer {
                        val g = grow()
                        alpha = g
                        translationY = (1f - g) * 8.dp.toPx()
                    }
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.45f to Color.Black.copy(alpha = 0.55f),
                            1f to Color.Black.copy(alpha = 0.88f),
                        ),
                    ),
            )
            Text(
                caption,
                // (small enough for "TV Show · Comedy" across a poster)
                fontSize = 12.sp,
                letterSpacing = (-0.1).sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 8.dp, end = if (watched) 32.dp else 8.dp, bottom = 7.dp)
                    .graphicsLayer {
                        val g = grow()
                        alpha = ((g - 0.25f) / 0.75f).coerceIn(0f, 1f)
                        translationY = (1f - g) * 6.dp.toPx()
                    },
            )
        }
        // (the ✓ in the corner at the foot: the label has the top)
        if (watched) WatchedMark(Modifier.align(Alignment.BottomEnd).padding(6.dp))
    }
}

/**
 * Under the chosen poster: its foot carried on, blurred, fading up from inside the poster (an overlap, so there's no
 * edge) and darkening to the caption. Drawn on its own layer, so its fade is one small offscreen at a time
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.GrowStrip(title: Title, room: Dp, grow: () -> Float) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val canBlur = android.os.Build.VERSION.SDK_INT >= 31
    val art = fresh(title.art)
    val smear = remember(art, canBlur) { art?.let { coil3.request.ImageRequest.Builder(context).data(it).apply { if (!canBlur) size(8, 12) }.build() } }
    val overlap = 56.dp
    Box(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(room + overlap)
            .graphicsLayer { alpha = grow(); compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                // (clear at the top, where it lies over the poster, solid below the poster's foot)
                drawRect(Brush.verticalGradient(0f to Color.Transparent, overlap.toPx() / size.height to Color.Black), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
            },
    ) {
        if (smear != null) coil3.compose.AsyncImage(
            model = smear, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            alignment = Alignment.BottomCenter,
            modifier = Modifier.fillMaxSize().then(if (canBlur) Modifier.blur(14.dp, androidx.compose.ui.draw.BlurredEdgeTreatment.Rectangle) else Modifier),
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, 0.45f to Color.Black.copy(alpha = 0.25f), 1f to Color.Black.copy(alpha = 0.6f))))
    }
}

/** Watched: a white tick on a small frosted disc, so it shows on any poster */
@Composable
fun WatchedMark(modifier: Modifier = Modifier, size: Dp = 22.dp) {
    Box(
        modifier.size(size).background(Color.Black.copy(alpha = 0.38f), CircleShape).border(BorderStroke(1.dp, Glass.Edge), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Check, contentDescription = "Watched", tint = Color.White, modifier = Modifier.size(size * 0.62f))
    }
}

/**
 * What a trailer shows while it loads: the title's backdrop, dimmed and drifting in very slowly, with
 * three soft dots and "Trailer" under them. It's opaque over the video (which sits under the screen),
 * so when [started] it fades away over 600 ms and the first frame comes through, not a cut. [failed]
 * swaps the dots for [failure] and fades the whole thing out. One infinite transition, only while
 * it's waiting, and only graphicsLayer (nothing re-lays-out) so a slow box keeps up.
 */
@Composable
fun TrailerLoading(backdrop: String?, started: Boolean, failed: Boolean = false, failure: String = "", modifier: Modifier = Modifier) {
    val cover by animateFloatAsState(if (started) 0f else 1f, tween(if (started) 600 else 300), label = "trailerCover")
    if (cover <= 0f && started) return
    val message by animateFloatAsState(if (failed) 1f else 0f, tween(Dx.Med, easing = Dx.Ease), label = "trailerFailed")
    Box(modifier.fillMaxSize().graphicsLayer { alpha = cover }.background(Color.Black)) {
        // (the loader's own infinite transition goes once it has started or failed, so nothing keeps ticking)
        val waiting = !started && !failed
        val phase = if (waiting) {
            androidx.compose.animation.core.rememberInfiniteTransition(label = "trailerWait").animateFloat(
                0f, 1f,
                androidx.compose.animation.core.infiniteRepeatable(tween(1500, easing = androidx.compose.animation.core.LinearEasing)),
                label = "dots",
            ).value
        } else 0f
        val drift by animateFloatAsState(if (started) 1f else 1.07f, tween(if (started) 600 else 40000, easing = androidx.compose.animation.core.LinearEasing), label = "trailerDrift")
        Box(Modifier.fillMaxSize().graphicsLayer { scaleX = drift; scaleY = drift; alpha = 0.34f * (1f - message) }) { Art(backdrop, Modifier.fillMaxSize()) }
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.graphicsLayer { alpha = 1f - message }) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    repeat(3) { i ->
                        // (each dot swells and brightens a third of a cycle after the last)
                        val wave = (1f - ((phase - i / 3f + 1f) % 1f) * 1.6f).coerceIn(0f, 1f)
                        Box(Modifier.size(9.dp).graphicsLayer { alpha = 0.25f + 0.75f * wave; scaleX = 0.8f + 0.3f * wave; scaleY = 0.8f + 0.3f * wave }.background(Color.White, CircleShape))
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(if (failed) failure else "Trailer", color = Dx.Muted, fontSize = 15.sp, modifier = Modifier.graphicsLayer { alpha = if (failed) message else 1f - message })
        }
    }
}

/**
 * Posters decoded ahead into the picture memory, at the size a row's cards ask for, a few from each row: Home's rows
 * further down are ready before the remote gets there (decoding is on [ImageThreads]' low-priority threads)
 */
object PosterPrefetch {
    private val asked = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    fun ahead(context: android.content.Context, rows: List<List<Title>>, perRow: Int = 7, maxRows: Int = 12) {
        val loader = coil3.SingletonImageLoader.get(context)
        val density = context.resources.displayMetrics.density
        val w = (Dx.Poster.value * density).toInt()
        val h = (Dx.Poster.value * 1.5f * density).toInt()
        for (row in rows.filter { it.isNotEmpty() }.take(maxRows)) for (t in row.take(perRow)) {
            val url = freshUrl(t.art) ?: continue
            if (!asked.add(url)) continue
            loader.enqueue(coil3.request.ImageRequest.Builder(context).data(url).size(w, h).build())
        }
    }
}
