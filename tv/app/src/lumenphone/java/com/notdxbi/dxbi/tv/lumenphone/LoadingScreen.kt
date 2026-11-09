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
 * (The logic is the TV's Launcher: the page does the same work underneath on every screen.)
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

    /** The app has been in front since this screen went up */
    internal var seenFront = true

    /** Starts [t]: [videoId] (an episode), else the one they're on, else the first that's out. [fromStart]: from 0, not the saved place */
    fun play(t: Title, videoId: String? = null, fromStart: Boolean = false, shuffle: Boolean = false) = begin(t) {
        // (Shuffle carries on with random episodes after this one; any other Play ends it)
        stremio.shuffling(t.id, shuffle)
        run(t, videoId, fromStart)
    }

    /** Starts a stream chosen from the list (or sent to another app, a Chromecast or a download) */
    fun play(t: Title, option: StreamOption, episodeLine: String?, target: Phone.Target = Phone.Target.Player) = begin(t) {
        episode = episodeLine
        starting(option, target)
    }

    fun cancel() {
        job?.cancel()
        title = null
    }

    /** The player's up (the app has gone behind it): the loading screen goes */
    fun done() {
        job?.cancel()
        title = null
    }

    private fun begin(t: Title, work: suspend () -> Unit) {
        job?.cancel()
        title = t
        episode = null
        seenFront = true
        step = Step("Opening ${t.name}", progress = 0.03f)
        job = scope.launch { work() }
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
            // (checked while the rest answer, so Play needn't wait for it)
            if (pick != null) stremio.probeAhead(pick)
            // Good enough not to wait for the slower addons: ready at once (cached) and 4K, or most have answered
            val soon = pick != null && pick.instant && groups.isNotEmpty() && (pick.res >= 2160 || answered * 3 >= groups.size * 2)
            if (pick != null && (c.sameAsLastTime || c.settled || waited > 6_000 || soon)) return starting(pick, Phone.Target.Player)
            if (c != null && c.settled && pick == null) return fail("No streams found", "Your addons may not have this one yet.")
            delay(400)
        }
        fail("Couldn't find a stream that plays", "Try ⋯ → Choose a Stream on the title's page.")
    }

    /** 4. The stream chosen: off to the player (the loading screen goes once it's up) */
    private suspend fun starting(first: StreamOption, target: Phone.Target) {
        var option = first
        // A stream whose server answers with an error, or with a clip saying so, is passed over before it's
        // played: the next best goes in its place
        for (tried in 0 until 4) {
            val why = stremio.whyNotVideo(option) ?: break
            android.util.Log.w("lumen-phone", "stream passed over before playing: $why")
            stremio.reject(option, why)
            step = Step("That stream isn't available", "Trying another…", 0.85f)
            option = stremio.streams()?.picked ?: return fail("Couldn't find a stream that plays", "Try ⋯ → Choose a Stream on the title's page.")
        }
        val what = listOfNotNull(option.quality, if (option.dv) "Dolby Vision" else if (option.hdr) "HDR" else null).joinToString(" · ")
        step = Step("Starting $what", listOf(option.provider, option.sizeText.orEmpty(), option.bitrateText?.let { "$it stream" }.orEmpty(), internet.orEmpty(), option.source).filter { it.isNotEmpty() && it != "Other" }.joinToString(" · "), 0.88f)
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
        step = Step(text, detail, 1f, failed = true)
    }
}

/** What Play starts for a show: the episode the engine says, else the one in the middle of, else the first that's out */
fun nextVideo(d: Details) = if (d.title?.type != "series") null
else d.videos.firstOrNull { it.id == d.nextVideoId } ?: d.videos.firstOrNull { it.id == d.videoId } ?: d.videos.firstOrNull { it.id == d.resumeVideoId }
    ?: d.videos.firstOrNull { (it.season ?: 0) > 0 && !it.upcoming } ?: d.videos.firstOrNull()

/**
 * The loading screen (iOS LoadingScreen): the title's picture, darkened, with its logo, what's happening and a
 * bar, low on the left. Back (or Cancel) stops it. It goes once the player has opened over the app.
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
    val step = launcher.step
    val progress by animateFloatAsState(step.progress, tween(500), label = "loading")
    Box(Modifier.fillMaxSize().background(Color.Black).clickable(enabled = false) {}) {
        Art(title.backdrop, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.3f to Color.Black.copy(alpha = 0.15f), 1f to Color.Black.copy(alpha = 0.88f))))
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.6f), 0.7f to Color.Transparent)))
        Column(
            Modifier.align(Alignment.BottomStart).windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 28.dp).padding(bottom = 44.dp).widthIn(max = 460.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TitleLogo(title, maxHeight = 96.dp, maxWidth = 360.dp, textSize = 38.sp, modifier = Modifier.padding(bottom = 6.dp))
            launcher.episode?.let { Text(it, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = 0.85f), maxLines = 1) }
            Text(step.text, style = MaterialTheme.typography.titleMedium, color = Dx.Text, modifier = Modifier.padding(top = 14.dp))
            step.detail?.let { Text(it, color = Dx.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
            Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(5.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.22f))) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(if (step.failed) Dx.Problem else Color.White, CircleShape))
            }
            // (low, by the thumb)
            Text(
                if (step.failed) "Close" else "Cancel",
                color = Dx.Text,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 16.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)).clickable { launcher.cancel() }.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
    }
}
