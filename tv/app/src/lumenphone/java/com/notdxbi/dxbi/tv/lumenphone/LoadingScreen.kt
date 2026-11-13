package com.notdxbi.dxbi.tv.lumenphone

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.safeDrawing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import org.json.JSONArray
import org.json.JSONObject
import com.notdxbi.dxbi.tv.phone.StreamChoices
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.notdxbi.dxbi.tv.Phone
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

/** The first moments, before Stremio's page has started (and while another account is read in): the logo on black */
@Composable
fun StartingScreen() {
    Box(Modifier.fillMaxSize().background(Dx.Background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Logo(64.dp)
            CircularProgressIndicator(color = Dx.Text, modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
        }
    }
}

/**
 * Play, from anywhere (Home, a card's menu, a title's page): opens the title, finds the best stream and starts it,
 * saying what it's doing on a loading screen over the title's picture (the steps and a bar) until the player's
 * up. As on iOS and Lumen TV, there's no list on the way: Choose a Stream on the title's page has it.
 * (The logic is the TV's Launcher: the page does the same work underneath on every screen. The player carries this
 * screen on over its video until the first picture, and a stream that won't play goes back to it for the next best:
 * see PlayerLoading.kt.)
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
    private var internet: String? = null
    private var job: Job? = null

    /** The app has been in front since this screen went up (a retry's goes up behind the player, so it isn't) */
    internal var seenFront = true

    /** Started from Shuffle: the next episode, from the player's Next, is the page's random one either way */
    private var shuffling = false

    init {
        // A stream failed while loading (or Next was pressed, or another stream was chosen) in the player: it has put this
        // screen up behind itself before closing, and the page opens the next best
        this.scope.launch { LoadingHandoff.retries.collect { if (it != null) retry() } }
    }

    /** Starts [t]: [videoId] (an episode), else the one they're on, else the first that's out. [fromStart]: from 0, not the saved place */
    fun play(t: Title, videoId: String? = null, fromStart: Boolean = false, shuffle: Boolean = false) {
        // (On mobile data nothing starts by itself: the best stream may be a 4K one. The list is asked for instead)
        if (stremio.onMeteredNetwork()) { asking = Ask(t, videoId); return }
        start(t, videoId, fromStart, shuffle)
    }

    /** A stream wanted on mobile data: the list, to choose from (Shell shows it) */
    data class Ask(val title: Title, val videoId: String?)
    var asking by mutableStateOf<Ask?>(null)

    private fun start(t: Title, videoId: String?, fromStart: Boolean, shuffle: Boolean) = begin(t) {
        // (Shuffle carries on with random episodes after this one; any other Play ends it)
        shuffling = shuffle
        stremio.shuffling(t.id, shuffle)
        run(t, videoId, fromStart)
    }

    /** Starts a stream chosen from the list (or sent to another app, a Chromecast or a download) */
    fun play(t: Title, option: StreamOption, episodeLine: String?, target: Phone.Target = Phone.Target.Player) = begin(t) {
        episode = episodeLine
        starting(option, target, chosen = true)
    }

    fun cancel() {
        LoadingHandoff.clear()
        job?.cancel()
        title = null
    }

    /** The player's up (the app has gone behind it): the loading screen goes */
    fun done() {
        job?.cancel()
        title = null
    }

    /**
     * Back from a player whose stream failed while loading: the loading screen again, while the page opens the next best
     * (its player carries the screen on). If none comes there were no more to try
     */
    private fun retry() {
        val r = LoadingHandoff.takeRetry() ?: return
        job?.cancel()
        android.util.Log.i(TAG, "retry: ${r.step.text} / ${r.step.detail}")
        seenFront = false
        title = r.title
        episode = r.episode
        step = r.step
        LoadingHandoff.put(r.title, r.episode, step)
        // (the next episode's streams take a while to come; another of the same one's are ready)
        val next = r.step.text.startsWith("Starting the next")
        // The next episode: started as Play starts one, once the page has gone back from the player
        if (next && r.nextVideo != null) {
            job = scope.launch {
                delay(1300)
                play(r.title, r.nextVideo, shuffle = shuffling)
            }
            return
        }
        job = scope.launch {
            delay(if (next) 45_000 else 20_000)
            if (title != null) {
                LoadingHandoff.clear()
                if (next) fail("Couldn't start the next episode", "Try it from the show's page.")
                else fail("Couldn't play a stream", "None of the best few would play here. Try ⋯ → Choose a Stream on the title's page.")
            }
        }
    }

    private fun begin(t: Title, work: suspend () -> Unit) {
        job?.cancel()
        title = t
        episode = null
        seenFront = true
        step = Step("Opening ${t.name}", progress = 0.03f)
        job = scope.launch { work() }
    }

    /**
     * The open title's streams as the page describes them, or null when it doesn't answer in time. (Asked for here with a
     * short wait, not through Stremio.streams: on a busy phone that waits twenty seconds for one answer and the loading
     * screen's own thirty were gone in two.)
     */
    private suspend fun streams(): StreamChoices? {
        val answer = try {
            stremio.engine.call("dx", JSONObject().put("method", "phone.streams").put("args", JSONArray()), 8_000)
        } catch (e: TimeoutCancellationException) {
            android.util.Log.w(TAG, "the page didn't answer phone.streams in 8 s")
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w(TAG, "phone.streams failed: ${e.message}")
            null
        }
        return (answer as? JSONObject)?.let { runCatching { StreamChoices.from(it) }.onFailure { e -> android.util.Log.w(TAG, "couldn't read phone.streams: ${e.message}") }.getOrNull() }
    }

    private suspend fun run(t: Title, wanted: String?, fromStart: Boolean) {
        val checkSpeed = runCatching { stremio.settings().firstOrNull { it.key == "quickPlaySpeed" }?.value == true }.getOrDefault(false)

        // 1. The title (and for a show, which episode)
        val now = stremio.details.value
        if (now?.metaId != t.id || (wanted != null && now.videoId != wanted)) stremio.open(t.type, t.id, wanted)
        var d = awaitDetails(t.id, 0.03f, 0.12f) ?: return fail("Couldn't open ${t.name}", "Check your connection and try again.")
        val video = wanted ?: nextVideo(d)?.id
        if (video != null && d.videoId != video) {
            stremio.open(t.type, t.id, video)
            for (i in 0 until 60) {
                d = stremio.details.value ?: d
                if (d.metaId == t.id && d.videoId == video) break
                delay(150)
            }
        }
        val videoId = video ?: d.videoId ?: t.id
        // (Start from the Beginning, or an ordinary Play clearing it)
        stremio.startFromBeginning(videoId, fromStart)
        d.videos.firstOrNull { it.id == videoId }?.let { v ->
            if (v.season != null && v.episode != null) episode = "S${v.season}, E${v.episode}" + (if (v.title.isNotEmpty()) " · ${v.title}" else "")
        }

        // 2. The connection (only when Match my internet speed is on: the plugin measures it first)
        if (checkSpeed) step = Step("Checking your internet speed", "A quick download, to pick a stream that won't buffer", 0.16f)

        // 3. The streams, as the addons answer. It waits as long as they keep answering: the thirty seconds that
        // used to end it were no use on a busy phone, where each of the page's answers can itself take several
        val began = System.currentTimeMillis()
        var lastNews = began
        var seen = -1
        var speedShown = !checkSpeed
        var best: StreamOption? = null
        var asked = 0
        var answers = 0
        while (true) {
            val nowMs = System.currentTimeMillis()
            if (nowMs - began > MAX_WAIT_MS || nowMs - lastNews > QUIET_MS) break
            asked++
            val c = streams()
            if (c != null) answers++
            // (taken as they come, whatever video id the page names: the title was opened above, and a mismatch in the ids used to be
            // a way to wait for a pick that was already there; the log says if they differ)
            val mine = c
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
            val found = mine?.items?.size ?: 0
            // (the sign of life that keeps it waiting: more addons answered, more streams, or the page itself answering)
            val news = answered * 1000 + found + if (c != null) 1 else 0
            if (news != seen) {
                seen = news
                lastNews = System.currentTimeMillis()
            }
            if (c != null && c.speed > 0) internet = "Your internet: ${c.speed.toInt()} Mbps"
            step = Step(
                "Finding the best stream",
                listOfNotNull(
                    when {
                        c == null && asked > 1 -> "Still waiting for the page"
                        groups.isEmpty() -> "Asking your addons"
                        found == 0 -> "$answered of ${groups.size} addons answered"
                        else -> "$found streams from $answered of ${groups.size} addons"
                    },
                    internet,
                ).joinToString(" · "),
                0.25f + 0.55f * (if (groups.isEmpty()) 0f else answered.toFloat() / groups.size),
            )
            // (a pick this phone can show: a Dolby Vision-only one has no decoder on a Pixel)
            val pick = mine?.let { PlayerCaps.pick(it) }
            if (pick != null) best = pick
            val waited = System.currentTimeMillis() - began
            android.util.Log.d(TAG, "streams #$asked at ${waited}ms: page=${c != null} videoId=${c?.videoId} want=$videoId items=$found pick=${mine?.pick} settled=${mine?.settled} same=${mine?.sameAsLastTime} addons=$answered/${groups.size}")
            // (checked while the rest answer, so Play needn't wait for it)
            if (pick != null) stremio.probeAhead(pick)
            // Good enough not to wait for the slower addons: ready at once (cached) and 4K, or most have answered
            val soon = pick != null && pick.instant && groups.isNotEmpty() && (pick.res >= 2160 || answered * 3 >= groups.size * 2)
            if (pick != null && mine != null && (mine.sameAsLastTime || mine.settled || waited > 6_000 || soon)) return starting(pick, Phone.Target.Player)
            if (mine != null && mine.settled && pick == null) return fail("No streams found", "Your addons may not have this one yet.")
            delay(400)
        }
        android.util.Log.w(TAG, "gave up after ${System.currentTimeMillis() - began}ms: $asked asks, $answers answers, best=${best?.index}")
        // (a stream was found, even if the page then stopped answering: play it, rather than fail with one in hand)
        best?.let { return starting(it, Phone.Target.Player) }
        if (answers == 0) fail("Lumen's page isn't answering", "The phone may be busy. Try again in a moment.")
        else fail("Couldn't find a stream that plays", "Try ⋯ → Choose a Stream on the title's page.")
    }

    /** 4. The stream chosen: off to the player (the loading screen goes once it's up). [chosen]: picked by hand, so not changed for this phone */
    private suspend fun starting(first: StreamOption, target: Phone.Target, chosen: Boolean = false) {
        var option = first
        // A stream whose server answers with an error, or with a clip saying so, is passed over before it's
        // played: the next best goes in its place. (Not for a download, another app or a cast: they check for themselves)
        if (target == Phone.Target.Player) for (tried in 0 until 8) {
            val why = stremio.whyNotVideo(option) ?: break
            if (tried == 7) return fail("Couldn't find a stream that plays", "The providers are returning errors. Try again in a few minutes.")
            android.util.Log.w(TAG, "stream ${option.index} passed over before playing: $why")
            stremio.reject(option, why)
            step = Step("That stream isn't available", "Trying another…", 0.85f)
            var next: StreamOption? = null
            // (the page rebuilds its list a moment after a rejection: a few looks before giving up)
            for (look in 0 until 4) {
                next = streams()?.let { PlayerCaps.pick(it) }
                if (next != null && next.index != option.index) break
                delay(600)
            }
            option = next ?: return fail("Couldn't find a stream that plays", "Try ⋯ → Choose a Stream on the title's page.")
        }
        val what = listOfNotNull(option.quality, if (option.dv) "Dolby Vision" else if (option.hdr) "HDR" else null).joinToString(" · ")
        step = Step("Starting $what", listOf(option.provider, option.sizeText.orEmpty(), option.bitrateText?.let { "$it stream" }.orEmpty(), internet.orEmpty(), option.source).filter { it.isNotEmpty() && it != "Other" }.joinToString(" · "), 0.88f)
        android.util.Log.i(TAG, "starting stream ${option.index} (${option.quality}${if (option.dv) " DV" else ""}) to $target")
        // (the player carries this screen on from here, to the video's first picture)
        if (target == Phone.Target.Player) title?.let { LoadingHandoff.put(it, episode, step) }
        stremio.play(option, target).join()
        // A download or a Chromecast leaves the app where it is: nothing covers the screen
        if (target == Phone.Target.Download || target == Phone.Target.Cast) {
            delay(1200)
            return done()
        }
        // (the player's slow to open, or didn't: either way the screen doesn't stay)
        delay(25_000)
        if (title != null) done()
    }

    private suspend fun awaitDetails(id: String, from: Float, to: Float): Details? {
        // (a busy phone takes longer to open a title: about 20 seconds, as the TV's, then it's said so)
        for (i in 0 until 140) {
            val d = stremio.details.value
            if (d?.metaId == id && d.state == Load.Ready) return d
            if (d?.metaId == id && d.state == Load.Err) return null
            step = step.copy(progress = from + (to - from) * i / 140f)
            delay(150)
        }
        return null
    }

    private fun fail(text: String, detail: String) {
        android.util.Log.w(TAG, "failed: $text ($detail)")
        step = Step(text, detail, 1f, failed = true)
    }

    private companion object {
        const val TAG = "lumen-phone"
        /** The most the streams are waited for in all, and how long with no news at all (no more addons answering, no page answer) */
        const val MAX_WAIT_MS = 120_000L
        const val QUIET_MS = 30_000L
    }
}

/** What Play starts for a show: the episode the engine says, else the one in the middle of, else the first that's out */
fun nextVideo(d: Details) = if (d.title?.type != "series") null
else d.videos.firstOrNull { it.id == d.nextVideoId } ?: d.videos.firstOrNull { it.id == d.videoId } ?: d.videos.firstOrNull { it.id == d.resumeVideoId }
    ?: d.videos.firstOrNull { (it.season ?: 0) > 0 && !it.upcoming } ?: d.videos.firstOrNull()


/**
 * The loading screen (iOS LoadingScreen): the title's picture, darkened, with its logo, what's happening and a
 * bar, low on the left. Back (or Cancel) stops it. It goes once the player has opened over the app, which carries it
 * on over the video until the first picture (PlayerLoading).
 */
@Composable
fun LoadingScreen(launcher: Launcher) {
    val title = launcher.title ?: return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val life by lifecycle.currentStateFlow.collectAsState()
    // (gone once the player covers the app completely: going when it was only paused showed what was under it)
    LaunchedEffect(life) {
        if (life.isAtLeast(Lifecycle.State.RESUMED)) launcher.seenFront = true
        else if (launcher.seenFront && !life.isAtLeast(Lifecycle.State.STARTED)) launcher.done()
    }
    BackHandler { launcher.cancel() }
    LoadingContent(title, launcher.episode, launcher.step, Modifier.clickable(enabled = false) {}, onClose = { launcher.cancel() })
}

/** What the loading screen draws, here and in the player until its first picture: [progressMs] is how slowly the bar moves */
@Composable
fun LoadingContent(title: Title, episode: String?, step: Launcher.Step, modifier: Modifier = Modifier, progressMs: Int = 500, onClose: (() -> Unit)? = null) {
    val progress by animateFloatAsState(step.progress, tween(progressMs), label = "loading")
    Box(Modifier.fillMaxSize().background(Color.Black).then(modifier)) {
        Art(title.backdrop, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.3f to Color.Black.copy(alpha = 0.15f), 1f to Color.Black.copy(alpha = 0.88f))))
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.6f), 0.7f to Color.Transparent)))
        Column(
            // (clear of a camera cut-out and the gesture bar, in the player's window as in the app's)
            Modifier.align(Alignment.BottomStart).windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 28.dp).padding(bottom = 44.dp).widthIn(max = 460.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TitleLogo(title, maxHeight = 96.dp, maxWidth = 360.dp, textSize = 38.sp, modifier = Modifier.padding(bottom = 6.dp))
            episode?.let { Text(it, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = 0.85f), maxLines = 1) }
            Text(step.text, style = MaterialTheme.typography.titleMedium, color = Dx.Text, modifier = Modifier.padding(top = 14.dp))
            step.detail?.let { Text(it, color = Dx.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
            Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(5.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.22f))) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(if (step.failed) Dx.Problem else Color.White, CircleShape))
            }
            // (low, by the thumb)
            if (onClose != null) Text(
                if (step.failed) "Close" else "Cancel",
                color = Dx.Text,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 16.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)).clickable { onClose() }.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * On mobile data Play asks which stream (as the iPad does) rather than start the best, which may be a 4K one that
 * uses a lot of data: the list, over wherever Play was pressed. A stream chosen here isn't remembered for the title.
 */
@Composable
fun MobileDataAsk(stremio: Stremio, launcher: Launcher) {
    val ask = launcher.asking ?: return
    var choices by remember(ask) { mutableStateOf<StreamChoices?>(null) }
    LaunchedEffect(ask) {
        val now = stremio.details.value
        if (now?.metaId != ask.title.id || (ask.videoId != null && now.videoId != ask.videoId)) stremio.open(ask.title.type, ask.title.id, ask.videoId)
        while (true) {
            val c = stremio.streams()
            if (c != null) choices = c
            if (c?.settled == true) break
            delay(800)
        }
    }
    StreamsModal(choices, ask.title.name, "You're on mobile data: choose a stream", { launcher.asking = null }) { option, target ->
        launcher.asking = null
        launcher.play(ask.title, option, null, target)
    }
}
