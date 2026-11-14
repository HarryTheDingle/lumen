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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import kotlin.math.roundToInt
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Info
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.staticCompositionLocalOf
import com.notdxbi.dxbi.tv.phone.Score
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableIntStateOf
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
import kotlinx.coroutines.delay

/** How long a title stays chosen before its card opens out */
private const val OPEN_AFTER_MS = 900L

/** Posters opening out into a wide card on rest: off (Apple TV's don't: the caption and the title's page do that job) */
private const val PREVIEW_EXPANDS = false

/** What a card that's opened out can do: play the title, add it to the Library, show its scores */
class CardActions(
    val play: (Title) -> Unit,
    val setInList: (Title, Boolean) -> Unit,
    /** Titles added to or taken out of the Library here, before Stremio's own lists catch up */
    val listMarks: StateFlow<Map<String, Boolean>>,
    val scores: suspend (Title) -> List<Score>,
    /** A title's trailer, when its row didn't come with one (Home's billboard plays it) */
    val trailer: suspend (Title) -> String?,
    /** Every title watched (Stremio.watchedIds): a card's ✓, whatever row it came in */
    val watched: StateFlow<Set<String>> = kotlinx.coroutines.flow.MutableStateFlow(emptySet()),
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
    ActionMenu(
        title.name,
        listOf(
            Action("Play", Icons.Filled.PlayArrow) { actions.play(title) },
            Action("Go to ${if (title.type == "movie") "Film" else "Show"}", Icons.Outlined.Info) { onOpen() },
            if (inList) Action("Remove from Library", Icons.Filled.Check) { actions.setInList(title, false) }
            else Action("Add to Library", Icons.Filled.Add) { actions.setInList(title, true) },
        ),
        onDismiss = onDismiss,
    )
}

/** Each title's scores, once fetched (kept while Lumen's open: they're slow to come) */
private val scoreMemory = ConcurrentHashMap<String, List<Score>>()

/** A score's name, whole ("Rotten Tomatoes"); short, its mark's stand-in where only text will do */
fun scoreName(s: Score, short: Boolean = false): String = when (s.source) {
    "imdb" -> "IMDb"
    "tomatoes" -> if (short) "RT" else "Rotten Tomatoes"
    "popcorn" -> if (short) "RT Audience" else "Rotten Tomatoes Audience"
    else -> s.name
}

/**
 * A poster that previews itself, as Netflix's TV app does (Lumen's hover cards, for a remote): rest on it
 * and it opens out sideways into a wide card with the title's picture, logo, year, genres and scores
 * (Rotten Tomatoes, IMDb), with Play and + in it, pushing its neighbours along. Moving on closes it at
 * once. (Trailers play in Home's billboard, not here: a card's too small and too many for a TV's
 * processor.)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PreviewPosterCard(title: Title, onClick: () -> Unit, rank: Int? = null, width: Dp = Dx.Poster) {
    val height = width * 1.5f
    val actions = LocalCardActions.current
    var focused by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf(false) }
    var scores by remember(title.id) { mutableStateOf(scoreMemory[title.id]) }
    val reveal = remember { BringIntoViewRequester() }

    LaunchedEffect(focused) {
        if (!focused) {
            open = false
            return@LaunchedEffect
        }
        if (!PREVIEW_EXPANDS) return@LaunchedEffect
        delay(OPEN_AFTER_MS)
        open = true
        // the wider card may now run past the screen's edge: the row moves to show all of it
        delay(320)
        runCatching { reveal.bringIntoView() }
    }
    LaunchedEffect(open) {
        if (open && scores == null && actions != null) {
            scores = actions.scores(title).also { scoreMemory[title.id] = it }
        }
    }
    val cardWidth by animateDpAsState(if (open) height * 16f / 9f else width, tween(300), label = "preview-width")
    val marks = actions?.listMarks?.collectAsState()?.value.orEmpty()
    val inList = marks[title.id] ?: title.inLibrary

    // Play and + are drawn inside the open card; the remote stays on the card and moves between them:
    // down to Play, right to +, up back to the card. OK does whichever is lit.
    var lit by remember { mutableIntStateOf(0) }
    LaunchedEffect(open) { if (!open) lit = 0 }
    val buttons = open && actions != null
    var held by remember { mutableStateOf(false) }
    if (held) TitleHoldMenu(title, onOpen = onClick, onDismiss = { held = false })
    // Apple TV's: the poster the remote is on grows down into the room kept under every poster, the strip a smear of
    // the poster's own colours with what it is ("Movie · Drama") in it. The room is there in every row, so nothing moves.
    val grow by animateFloatAsState(if (focused && !open) 1f else 0f, tween(260, easing = androidx.compose.animation.core.FastOutSlowInEasing), label = "grow")
    val watchedNow = actions?.watched?.collectAsState()?.value?.contains(title.id) == true || title.watched
    Column(Modifier.height(height + CAPTION_ROOM)) {
    FocusCard(
        onLongClick = { held = true },
        onClick = {
            when (lit) {
                1 -> actions?.play?.invoke(title)
                2 -> actions?.setInList?.invoke(title, !inList)
                else -> onClick()
            }
        },
        modifier = Modifier
            .width(cardWidth)
            .height(height + CAPTION_ROOM * grow)
            .bringIntoViewRequester(reveal)
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .onPreviewKeyEvent {
                if (!buttons || it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (it.key) {
                    Key.DirectionDown -> if (lit == 0) { lit = 1; true } else false
                    Key.DirectionUp -> if (lit != 0) { lit = 0; true } else false
                    Key.DirectionRight -> if (lit == 1) { lit = 2; true } else false
                    Key.DirectionLeft -> if (lit == 2) { lit = 1; true } else false
                    else -> false
                }
            },
    ) {
        Crossfade(open, animationSpec = tween(250), label = "preview") { wide ->
            if (!wide) {
                PosterFace(title, rank, width, watched = watchedNow, grow = { grow })
            } else {
                Box(Modifier.fillMaxSize()) {
                    Art(title.backdrop, Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.3f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.92f))))
                    Column(
                        Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        // (the name in plain type: a logo this small is hard to read)
                        Text(
                            title.name,
                            style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, shadow = Shadow(Color.Black, blurRadius = 8f)),
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        FactsLine(listOfNotNull(title.year, typeName(title.type), title.genres.firstOrNull(), title.runtime), scores)
                        if (buttons) {
                            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                InCardButton(lit == 1) {
                                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Play", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                                InCardButton(lit == 2, round = true) {
                                    Icon(if (inList) Icons.Filled.Check else Icons.Filled.Add, contentDescription = if (inList) "In your Library" else "Add to Library", modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    }
}

/**
 * Continue Watching, as the preview cards: wide art with the name in plain type and how far they got; rest on it
 * and it opens out sideways, with the year, genre and scores, and Resume and + in it (down to Resume, right to +,
 * up back to the card). OK on the card opens the title's page.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PreviewContinueCard(item: Continue, onClick: () -> Unit, width: Dp = 270.dp, onLongClick: (() -> Unit)? = null) {
    val title = item.title
    val height = width * 0.64f
    val actions = LocalCardActions.current
    var focused by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf(false) }
    var scores by remember(title.id) { mutableStateOf(scoreMemory[title.id]) }
    val reveal = remember { BringIntoViewRequester() }

    LaunchedEffect(focused) {
        if (!focused) {
            open = false
            return@LaunchedEffect
        }
        if (!PREVIEW_EXPANDS) return@LaunchedEffect
        delay(OPEN_AFTER_MS)
        open = true
        delay(320)
        runCatching { reveal.bringIntoView() }
    }
    LaunchedEffect(open) {
        if (open && scores == null && actions != null) {
            scores = actions.scores(title).also { scoreMemory[title.id] = it }
        }
    }
    val cardWidth by animateDpAsState(if (open) width * 1.5f else width, tween(300), label = "continue-width")
    // (resting on it: Play's work done ahead, so it starts at once: Launcher.warm)
    val launcher = LocalLauncher.current
    LaunchedEffect(focused) { if (focused) launcher?.warm(title) }
    val marks = actions?.listMarks?.collectAsState()?.value.orEmpty()
    val inList = marks[title.id] ?: title.inLibrary

    var lit by remember { mutableIntStateOf(0) }
    LaunchedEffect(open) { if (!open) lit = 0 }
    val buttons = open && actions != null
    FocusCard(
        onClick = {
            when (lit) {
                1 -> actions?.play?.invoke(title)
                2 -> actions?.setInList?.invoke(title, !inList)
                else -> onClick()
            }
        },
        corner = 16.dp,
        onLongClick = onLongClick,
        modifier = Modifier
            .width(cardWidth)
            .height(height)
            .bringIntoViewRequester(reveal)
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .onPreviewKeyEvent {
                if (!buttons || it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (it.key) {
                    Key.DirectionDown -> if (lit == 0) { lit = 1; true } else false
                    Key.DirectionUp -> if (lit != 0) { lit = 0; true } else false
                    Key.DirectionRight -> if (lit == 1) { lit = 2; true } else false
                    Key.DirectionLeft -> if (lit == 2) { lit = 1; true } else false
                    else -> false
                }
            },
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
                if (open) {
                    FactsLine(listOfNotNull(title.year, typeName(title.type), title.genres.firstOrNull()), scores)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (item.progress > 0f) ProgressLine(item.progress, Modifier.width(56.dp))
                    val words = listOfNotNull(
                        episodeLabel(item.videoId),
                        if (item.newEpisodes > 0) "${item.newEpisodes} new" else null,
                    ).joinToString(" · ").ifEmpty { if (open) "" else typeName(title.type) }
                    // (open, the facts line already says Movie)
                    if (words.isNotEmpty()) Text(words, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White, maxLines = 1)
                }
                if (buttons) {
                    Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        InCardButton(lit == 1) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (item.progress > 0f) "Resume" else "Play", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                        InCardButton(lit == 2, round = true) {
                            Icon(if (inList) Icons.Filled.Check else Icons.Filled.Add, contentDescription = if (inList) "In your Library" else "Add to Library", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}

/**
 * An open card's facts ("2003 · Film · Comedy · 97 min"), then its scores (Rotten Tomatoes, Metacritic, IMDb), which
 * fade in at the end of the line when they're found (they're looked up as the card opens)
 */
@Composable
private fun FactsLine(facts: List<String>, scores: List<Score>?) {
    val style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, shadow = Shadow(Color.Black, blurRadius = 6f))
    val found = scores.orEmpty().take(3)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(facts.joinToString(" · "), style = style, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        // (nothing is reserved for them: the facts stay where they are and the scores come in after them, each with its mark)
        androidx.compose.animation.AnimatedVisibility(
            visible = found.isNotEmpty(),
            enter = androidx.compose.animation.fadeIn(tween(700)),
        ) {
            Row(Modifier.padding(start = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                found.forEach { ScoreChip(it.source, it.value, size = 12.dp, fontSize = 12.sp) }
            }
        }
    }
}

/** A button drawn in an open card: white with dark words when it's the lit one, else frosted */
@Composable
private fun InCardButton(lit: Boolean, round: Boolean = false, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(50)
    androidx.compose.runtime.CompositionLocalProvider(androidx.tv.material3.LocalContentColor provides if (lit) Color.Black else Color.White) {
        Row(
            Modifier
                .height(28.dp)
                .then(if (round) Modifier.width(28.dp) else Modifier)
                .background(if (lit) Color.White else Color.White.copy(alpha = 0.22f), shape)
                .padding(horizontal = if (round) 0.dp else 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) { content() }
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
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(500), label = "trailer")
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
            webChromeClient = WebChromeClient()
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

/** The room kept under every poster, which the one the remote is on grows down into */
val CAPTION_ROOM = 30.dp

/**
 * The poster side of the card (and Netflix's Top 10 number). [grow] (0 to 1, read as it draws): how far it has grown
 * down into its caption strip.
 */
@Composable
private fun PosterFace(title: Title, rank: Int?, width: Dp, watched: Boolean = title.watched, grow: () -> Float = { 0f }) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Box(Modifier.fillMaxSize()) {
        // the strip under the poster: the poster's own foot carried on, blurred, darkening (only the chosen card has it, so
        // the blur is one small one at a time; TVs before Android 12 can't blur, and get a tiny copy stretched instead)
        val grown = grow() > 0f
        if (grown) {
            val canBlur = android.os.Build.VERSION.SDK_INT >= 31
            val smear = remember(title.art, canBlur) {
                title.art?.let { coil3.request.ImageRequest.Builder(context).data(it).apply { if (!canBlur) size(8, 12) }.build() }
            }
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(CAPTION_ROOM + 56.dp).graphicsLayer { alpha = grow() }) {
                if (smear != null) coil3.compose.AsyncImage(
                    model = smear, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    alignment = Alignment.BottomCenter,
                    modifier = Modifier.fillMaxSize().then(if (canBlur) Modifier.blur(14.dp, androidx.compose.ui.draw.BlurredEdgeTreatment.Rectangle) else Modifier),
                )
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, 0.45f to Color.Black.copy(alpha = 0.2f), 1f to Color.Black.copy(alpha = 0.55f))))
            }
        }
        // (the poster's foot fades into the strip, so there's no edge between them)
        Box(Modifier.fillMaxWidth().height(width * 1.5f).then(if (grown) Modifier.fadeFoot(grow) else Modifier)) {
            // (no poster, or one that won't load: its name instead of a blank grey card, as search results had)
            var noArt by remember(title.art) { mutableStateOf(title.art == null) }
            Art(title.art, Modifier.fillMaxSize(), onError = { noArt = true })
            if (hasNameOnPoster(title) || noArt) PosterName(title, width)
            if (rank != null) {
                Text(
                    "$rank",
                    style = TextStyle(fontSize = (width.value * 0.3f).sp, fontWeight = FontWeight.Black, shadow = Shadow(Color.Black.copy(alpha = 0.6f), blurRadius = 12f)),
                    color = Color.White,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            if (watched) WatchedMark(Modifier.align(Alignment.TopEnd).padding(6.dp))
        }
        // what it is, in the strip (a moment after it opens)
        Text(
            listOfNotNull(typeName(title.type), title.genres.firstOrNull()).joinToString(" · "),
            fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 9.dp, vertical = 7.dp)
                .graphicsLayer { alpha = ((grow() - 0.5f) * 2f).coerceIn(0f, 1f) },
        )
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

/** The bottom fifth of what's drawn fading away as [amount] goes to 1 (a grown poster's foot into its blurred strip) */
private fun Modifier.fadeFoot(amount: () -> Float): Modifier = this
    .graphicsLayer(compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        val a = amount()
        drawRect(Brush.verticalGradient(0.78f to Color.Black, 1f to Color.Black.copy(alpha = 1f - a)), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
    }
