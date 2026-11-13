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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.animation.core.animateFloat
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
    /** Started from Shuffle: the next episode, from the player's Next, is the player's pick (the page's, random) either way */
    private var shuffling = false

    fun play(t: Title, videoId: String? = null, fromStart: Boolean = false, shuffle: Boolean = false) = begin(t) {
        // (Shuffle carries on with random episodes after this one; any other Play ends it)
        shuffling = shuffle
        stremio.shuffling(t.id, shuffle)
        run(t, videoId, fromStart)
    }

    /** Starts a stream chosen from the list */
    fun play(t: Title, option: StreamOption, episodeLine: String?) = begin(t) {
        episode = episodeLine
        starting(option)
    }

    private var warming: Job? = null

    /**
     * The remote's resting on [t] (a Continue Watching card): what Play would do is done ahead, quietly, a moment later:
     * the title opened, the episode it'd start found, its streams asked for, and the pick checked with its server. Play
     * then finds it all ready and goes straight to starting it. Moving on to another card starts that one's instead.
     */
    fun warm(t: Title) {
        warming?.cancel()
        if (title != null) return
        warming = scope.launch {
            delay(1200)
            if (title != null) return@launch
            if (stremio.details.value?.metaId != t.id) stremio.open(t.type, t.id, null)
            var d: Details? = null
            for (i in 0 until 60) {
                val x = stremio.details.value
                if (x?.metaId == t.id && x.state == Load.Ready) { d = x; break }
                delay(150)
            }
            val dd = d ?: return@launch
            val video = nextVideo(dd)?.id
            if (video != null && dd.videoId != video) stremio.open(t.type, t.id, video)
            for (i in 0 until 40) {
                if (title != null) return@launch
                val c = stremio.streams()
                val pick = c?.picked
                if (c != null && pick != null && (video == null || c.videoId == video) && (c.settled || c.sameAsLastTime || pick.instant)) {
                    stremio.probeAhead(pick)
                    c.videoId?.let { stremio.prefetchSkips(t.type, t.id, it) }
                    return@launch
                }
                delay(300)
            }
        }
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
    /** Play went straight to the stream from last time (PlayedLinks): the title and video, for retry() */
    private var fromSaved: Pair<Title, String>? = null

    fun retry() {
        val r = LoadingHandoff.takeRetry() ?: return
        job?.cancel()
        // (the stream from last time wouldn't play: forgotten, and Play goes the long way, choosing and checking, at once)
        fromSaved?.takeIf { it.first.id == r.title.id && !r.step.text.startsWith("Starting the next") }?.let { (t, video) ->
            fromSaved = null
            stremio.forgetPlayed(video)
            android.util.Log.w("lumen-play", "the stream from last time didn't play: the long way")
            seenFront = false
            title = r.title
            episode = r.episode
            step = r.step
            job = scope.launch {
                delay(1300)
                play(t, video)
            }
            return
        }
        fromSaved = null
        // (put up while the app is behind the player: it stays until the next player has opened)
        seenFront = false
        title = r.title
        episode = r.episode
        step = r.step
        LoadingHandoff.put(r.title, r.episode, step)
        // (the next episode's streams take a while to come; another of the same one's are ready)
        val next = r.step.text.startsWith("Starting the next")
        // The next episode: started as Play starts one, once the page has gone back from the player (it was
        // opened by the page itself before, and Back from it brought the stream up again)
        if (next && r.nextVideo != null) {
            job = scope.launch {
                delay(1300)
                play(r.title, r.nextVideo, shuffle = shuffling)
            }
            return
        }
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
        fromSaved = null
        title = t
        episode = null
        step = Step("Opening ${t.name}", progress = 0.03f)
        job = scope.launch { work() }
    }

    // (how long each step of Play takes, in the log: adb logcat -s lumen-play)
    private var playStarted = 0L
    private fun mark(what: String) = android.util.Log.i("lumen-play", "+${System.currentTimeMillis() - playStarted} ms $what")

    private suspend fun run(t: Title, wanted: String?, fromStart: Boolean) {
        playStarted = System.currentTimeMillis()
        mark("Play ${t.name}")
        val checkSpeed = runCatching { stremio.settings().firstOrNull { it.key == "quickPlaySpeed" }?.value == true }.getOrDefault(false)

        // 1. The title (and for a show, which episode)
        val now = stremio.details.value
        if (now?.metaId != t.id || (wanted != null && now.videoId != wanted)) stremio.open(t.type, t.id, wanted)
        var d = awaitDetails(t.id, 0.03f, 0.12f) ?: return fail("Couldn't open ${t.name}", "Check the TV's connection and try again.")
        mark("title open")
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
        mark("episode settled")
        // The stream played last time, in the last few hours: straight back to it, with no asking the addons, no choosing
        // and no checking (PlayedLinks). If it won't play, retry() forgets it and goes the long way at once
        val target = video ?: d.videoId ?: t.id
        stremio.playedLink(target)?.let { link ->
            mark("the stream from last time")
            fromSaved = t to target
            step = Step("Starting", null, 0.88f)
            title?.let { LoadingHandoff.put(it, episode, step) }
            stremio.playLink(link)
            delay(25_000)
            if (title != null) done()
            return
        }
        d.videos.firstOrNull { it.id == (video ?: d.videoId) }?.let { v ->
            if (v.season != null && v.episode != null) episode = "S${v.season}, E${v.episode}" + (if (v.title.isNotEmpty()) " · ${v.title}" else "")
        }

        // 2. The connection (only when Match my internet speed is on: the plugin measures it first)
        // (no "Checking your internet speed" step: the speed's known before Play now, from the last test or this network's memory)

        // 3. The streams, as the addons answer
        val began = System.currentTimeMillis()
        var speedShown = !checkSpeed
        var reads = 0
        while (System.currentTimeMillis() - began < 30_000) {
            val c = stremio.streams()
            if (reads++ < 4) mark("streams read: ${c?.items?.size ?: 0}, settled ${c?.settled}, for ${if (c?.videoId == (video ?: d.videoId)) "this video" else c?.videoId}")
            val dd = stremio.details.value
            if (!speedShown && c != null) {
                speedShown = true
                // (the speed goes on the next line of the screen with the streams: Play used to stop here for 0.9 s to show it)
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
            // (checked while the rest answer, so Play needn't wait for it)
            if (pick != null) stremio.probeAhead(pick)
            // Good enough not to wait for the slower addons: ready at once (cached) and 4K, or most have answered
            // (waiting for every addon, or 6 seconds, was most of the time on the loading screen)
            val soon = pick != null && pick.instant && groups.isNotEmpty() && (pick.res >= 2160 || answered * 3 >= groups.size * 2)
            // (or ready to play from the addon that's first in Stremio's order, once that one's answered: the one the
            // viewer relies on, Harry's StremThru Torz; the rest, a fallback like Easynews+, needn't be waited for)
            val top = groups.firstOrNull()
            val fromTop = pick != null && pick.instant && top != null && top.state != Load.Loading && pick.addon == top.addon
            if (pick != null && (c.sameAsLastTime || c.settled || waited > 6_000 || soon || fromTop)) {
                mark("picked ${pick.quality} ${pick.bitrateText.orEmpty()} from ${pick.addon} (${if (c.sameAsLastTime) "same as last time" else if (c.settled) "all answered" else if (fromTop) "first addon ready" else if (soon) "ready 4K" else "waited 6 s"})" + (c.lowered?.let { " — " + it.note } ?: ""))
                return starting(pick)
            }
            if (c != null && c.settled && pick == null) return fail("No streams found", "Your addons may not have this one yet.")
            delay(400)
        }
        fail("Couldn't find a stream that plays", "Try … → Choose a Stream on the title's page.")
    }

    /** 4. The stream chosen: off to the player (the loading screen goes once it's up) */
    private suspend fun starting(first: StreamOption) {
        var option = first
        // A stream whose server answers with an error, or with a clip saying so (ElfHosted's "Couldn't fetch this
        // release"), is passed over before it's played: the next best goes in its place
        for (tried in 0 until 8) {
            val why = stremio.whyNotVideo(option) ?: break
            if (tried == 7) return fail("Couldn't find a stream that plays", "The providers are returning errors. Try again in a few minutes.")
            android.util.Log.w("dxbi-player", "stream passed over before playing: $why")
            stremio.reject(option, why)
            // (quietly: the next best goes in its place, and only when none plays does the screen say anything went wrong)
            step = step.copy(progress = 0.85f)
            option = stremio.streams()?.picked ?: return fail("Couldn't find a stream that plays", "Try … → Choose a Stream on the title's page.")
        }
        mark("stream checked")
        val what = listOfNotNull(option.quality, if (option.dv) "Dolby Vision" else if (option.hdr) "HDR" else null).joinToString(" · ")
        step = Step("Starting $what", listOf(option.provider, option.sizeText.orEmpty(), option.bitrateText?.let { "$it stream" }.orEmpty(), internet.orEmpty(), option.source).filter { it.isNotEmpty() && it != "Other" }.joinToString(" · "), 0.88f)
        // (the player carries this screen on from here, to the video's first picture)
        title?.let { LoadingHandoff.put(it, episode, step) }
        stremio.play(option)
        mark("handed to the player")
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

    PageWindow(onBack = { launcher.cancel() }, fadeIn = false) {
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
    // The bar never stands still: up to each step as it comes, then on towards the next, slower and slower (never
    // getting there: the next step carries it on), with a soft light running along it, so a slow server never looks
    // like a hang
    // (from where the last screen's bar had got to: the player's carries on the app's, it doesn't start again from nothing)
    val shown = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(if (step.progress > 0.05f) LoadingBar.last.coerceAtMost(step.progress) else 0f) }
    androidx.compose.runtime.SideEffect { LoadingBar.last = shown.value }
    LaunchedEffect(step.progress, step.failed) {
        if (step.failed) return@LaunchedEffect shown.animateTo(1f, tween(400)).let { }
        if (shown.value < step.progress) shown.animateTo(step.progress, tween(progressMs, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        val ceiling = (step.progress + 0.55f * (1f - step.progress)).coerceAtMost(0.97f)
        shown.animateTo(ceiling, tween(12_000, easing = androidx.compose.animation.core.LinearOutSlowInEasing))
    }
    val progress = shown.value
    val sweep by androidx.compose.animation.core.rememberInfiniteTransition(label = "bar-light").animateFloat(
        -0.3f, 1.3f, androidx.compose.animation.core.infiniteRepeatable(tween(1600, easing = androidx.compose.animation.core.LinearEasing)), label = "bar-light",
    )
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
                        .background(if (step.failed) Color(0xFFFF6B6B) else Color.White.copy(alpha = 0.85f), CircleShape)
                        .drawWithContent {
                            drawContent()
                            if (step.failed) return@drawWithContent
                            val x = size.width * sweep
                            val band = size.width.coerceAtLeast(1f) * 0.35f
                            drawRect(
                                Brush.horizontalGradient(
                                    0f to Color.Transparent, 0.5f to Color.White, 1f to Color.Transparent,
                                    startX = x - band / 2, endX = x + band / 2,
                                ),
                            )
                        },
                )
            }
        }
    }
}

/** How far the loading bar had got, for the next screen's to carry on from */
object LoadingBar { @Volatile var last = 0f }
