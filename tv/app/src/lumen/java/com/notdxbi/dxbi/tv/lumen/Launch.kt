package com.notdxbi.dxbi.tv.lumen

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Text
import com.notdxbi.dxbi.tv.phone.Details
import com.notdxbi.dxbi.tv.phone.Load
import com.notdxbi.dxbi.tv.phone.StreamOption
import com.notdxbi.dxbi.tv.phone.Stremio
import com.notdxbi.dxbi.tv.phone.Title
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Play, everywhere (Home, a card, a title's page): opens the title, finds the best stream and starts it,
 * saying what it's doing on a loading screen over the title's picture (the steps and a bar) until the
 * player's up. There's no list of streams to choose from on the way: … → Choose a Stream has that.
 */
@Stable
class Launcher(private val stremio: Stremio, scope: CoroutineScope) {
    private val scope = CoroutineScope(scope.coroutineContext + Dispatchers.Main)

    /** What's on the loading screen: the step, what it found, how far along (0 to 1) */
    data class Step(val text: String, val detail: String? = null, val progress: Float = 0f, val failed: Boolean = false)

    /** The title being started (null: no loading screen) */
    var title by mutableStateOf<Title?>(null)
        private set
    /** "S2, E5 · The Name", for an episode */
    var episode by mutableStateOf<String?>(null)
        private set
    var step by mutableStateOf(Step(""))
    /** "Your internet: 120 Mbps", once Match my internet speed has measured it (kept on the later steps) */
    private var internet: String? = null
        private set

    private var job: Job? = null

    /** Starts [t]: [videoId] (an episode), else the one they're on, else the first that's out. [fromStart]: from 0, not the saved place */
    fun play(t: Title, videoId: String? = null, fromStart: Boolean = false) = begin(t) { run(t, videoId, fromStart) }

    /** Starts a stream chosen from the list */
    fun play(t: Title, option: StreamOption, episodeLine: String?) = begin(t) {
        episode = episodeLine
        starting(option)
    }

    fun cancel() {
        LoadingHandoff.clear()
        job?.cancel()
        title = null
    }

    /**
     * Back from a player whose stream failed while loading: the loading screen again, while the page opens
     * the next best (its player carries the screen on). None comes: there were no more to try
     */
    fun retry() {
        val r = LoadingHandoff.takeRetry() ?: return
        job?.cancel()
        // (put up while the app is behind the player: it stays until the next player has opened)
        seenFront = false
        title = r.title
        episode = r.episode
        step = r.step
        LoadingHandoff.put(r.title, r.episode, step)
        // (the next episode's streams take a while to come; another of the same one's are ready)
        val next = r.step.text.startsWith("Starting the next")
        job = scope.launch {
            delay(if (next) 45_000 else 15_000)
            if (title != null) {
                LoadingHandoff.clear()
                if (next) fail("Couldn't start the next episode", "Try it from the show's page.")
                else fail("Couldn't play a stream", "None of the best few would play here. Try … → Choose a Stream on the title's page.")
            }
        }
    }

    /** The app has been in front since this loading screen went up (a retry's goes up behind the player) */
    internal var seenFront = true

    /** The player's up: the loading screen goes */
    fun done() {
        job?.cancel()
        title = null
    }

    private fun begin(t: Title, work: suspend () -> Unit) {
        job?.cancel()
        title = t
        episode = null
        step = Step("Opening ${t.name}", progress = 0.03f)
        job = scope.launch { work() }
    }

    private suspend fun run(t: Title, wanted: String?, fromStart: Boolean) {
        val checkSpeed = runCatching { stremio.settings().firstOrNull { it.key == "quickPlaySpeed" }?.value == true }.getOrDefault(false)

        // 1. The title (and for a show, which episode)
        val now = stremio.details.value
        if (now?.metaId != t.id || (wanted != null && now.videoId != wanted)) stremio.open(t.type, t.id, wanted)
        var d = awaitDetails(t.id, 0.03f, 0.12f) ?: return fail("Couldn't open ${t.name}", "Check the TV's connection and try again.")
        val video = wanted ?: nextVideo(d)?.id
        if (video != null && d.videoId != video) {
            stremio.open(t.type, t.id, video)
            for (i in 0 until 60) {
                d = stremio.details.value ?: d
                if (d.metaId == t.id && d.videoId == video) break
                delay(150)
            }
        }
        // (Start from the Beginning, or an ordinary Play clearing it)
        stremio.startFromBeginning(video ?: d.videoId ?: t.id, fromStart)
        d.videos.firstOrNull { it.id == (video ?: d.videoId) }?.let { v ->
            if (v.season != null && v.episode != null) episode = "S${v.season}, E${v.episode}" + (if (v.title.isNotEmpty()) " · ${v.title}" else "")
        }

        // 2. The connection (only when Match my internet speed is on: the plugin measures it first)
        if (checkSpeed) step = Step("Checking your internet speed", "A quick download, to pick a stream that won't buffer", 0.16f)

        // 3. The streams, as the addons answer
        val began = System.currentTimeMillis()
        var speedShown = !checkSpeed
        while (System.currentTimeMillis() - began < 30_000) {
            val c = stremio.streams()
            val dd = stremio.details.value
            if (!speedShown && c != null) {
                speedShown = true
                if (c.speed > 0) {
                    step = Step("Checking your internet speed", "Your connection: ${c.speed.toInt()} Mbps", 0.22f)
                    delay(900)
                }
            }
            val groups = dd?.takeIf { it.metaId == t.id }?.streams.orEmpty()
            val answered = groups.count { it.state != Load.Loading }
            val found = c?.items?.size ?: 0
            if (c != null && c.speed > 0) internet = "Your internet: ${c.speed.toInt()} Mbps"
            step = Step(
                "Finding the best stream",
                listOfNotNull(
                    when {
                        groups.isEmpty() -> "Asking your addons"
                        found == 0 -> "$answered of ${groups.size} addons answered"
                        else -> "$found streams from $answered of ${groups.size} addons"
                    },
                    internet,
                ).joinToString(" · "),
                0.25f + 0.55f * (if (groups.isEmpty()) 0f else answered.toFloat() / groups.size),
            )
            val pick = c?.picked
            val waited = System.currentTimeMillis() - began
            if (pick != null && (c.sameAsLastTime || c.settled || waited > 6_000)) return starting(pick)
            if (c != null && c.settled && pick == null) return fail("No streams found", "Your addons may not have this one yet.")
            delay(400)
        }
        fail("Couldn't find a stream that plays", "Try … → Choose a Stream on the title's page.")
    }

    /** 4. The stream chosen: off to the player (the loading screen goes once it's up) */
    private suspend fun starting(option: StreamOption) {
        val what = listOfNotNull(option.quality, if (option.dv) "Dolby Vision" else if (option.hdr) "HDR" else null).joinToString(" · ")
        step = Step("Starting $what", listOf(option.provider, option.sizeText.orEmpty(), option.bitrateText?.let { "$it stream" }.orEmpty(), internet.orEmpty(), option.source).filter { it.isNotEmpty() && it != "Other" }.joinToString(" · "), 0.88f)
        // (the player carries this screen on from here, to the video's first picture)
        title?.let { LoadingHandoff.put(it, episode, step) }
        stremio.play(option)
        // (the player's slow to open, or didn't: either way the screen doesn't stay)
        delay(25_000)
        if (title != null) done()
    }

    private suspend fun awaitDetails(id: String, from: Float, to: Float): Details? {
        for (i in 0 until 70) {
            val d = stremio.details.value
            if (d?.metaId == id && d.state == Load.Ready) return d
            if (d?.metaId == id && d.state == Load.Err) return null
            step = step.copy(progress = from + (to - from) * i / 70f)
            delay(150)
        }
        return null
    }

    private fun fail(text: String, detail: String) {
        step = Step(text, "$detail Press Back to go back.", 1f, failed = true)
    }
}

val LocalLauncher = staticCompositionLocalOf<Launcher?> { null }

/**
 * The loading screen: the title's picture, darkened, with its logo, what's happening and a bar, low on
 * the left as on its page. Back stops it. It goes once the player's opened, which then carries it on
 * (see PlayerLoading.kt) until the video's first picture.
 */
@Composable
fun LoadingScreen(launcher: Launcher) {
    val title = launcher.title ?: return
    // the player's up (this app's gone behind it): done
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val life by lifecycle.currentStateFlow.collectAsState()
    // (gone once the player covers it completely, the app stopped behind it: going when the app was only paused,
    // the player opening over it showed what was underneath for a moment)
    LaunchedEffect(life) {
        if (life.isAtLeast(Lifecycle.State.RESUMED)) launcher.seenFront = true
        else if (launcher.seenFront && !life.isAtLeast(Lifecycle.State.STARTED)) launcher.done()
    }
    val hold = androidx.compose.runtime.remember { FocusRequester() }

    PageWindow(onBack = { launcher.cancel() }) {
        FocusOnOpen(hold)
        LoadingContent(
            title, launcher.episode, launcher.step,
            // (it holds the remote, so Back comes here)
            Modifier
                .focusRequester(hold)
                .focusable()
                .onPreviewKeyEvent {
                    if (it.key == Key.Back && it.type == KeyEventType.KeyUp) launcher.cancel()
                    it.key == Key.Back
                },
        )
    }
}

/** What the loading screen draws (here, and in the player until its first picture): [progressMs] is how slowly the bar moves */
@Composable
fun LoadingContent(title: Title, episode: String?, step: Launcher.Step, modifier: Modifier = Modifier, progressMs: Int = 500) {
    val progress by animateFloatAsState(step.progress, tween(progressMs), label = "loading")
    Box(Modifier.fillMaxSize().background(Color.Black).then(modifier)) {
        Art(title.backdrop, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.8f), 0.6f to Color.Black.copy(alpha = 0.35f))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f))))
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = Dx.Gutter, bottom = 56.dp).width(460.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TitleLogo(title, maxHeight = 96.dp, maxWidth = 360.dp, textSize = 38.sp, modifier = Modifier.padding(bottom = 6.dp))
            episode?.let { Text(it, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = 0.85f), maxLines = 1) }
            Text(
                step.text,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                modifier = Modifier.padding(top = 18.dp),
            )
            step.detail?.let { Text(it, fontSize = 14.sp, color = Dx.Muted, maxLines = 2) }
            // the bar
            Box(
                Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.22f)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(if (step.failed) Color(0xFFFF6B6B) else Color.White, CircleShape),
                )
            }
        }
    }
}
