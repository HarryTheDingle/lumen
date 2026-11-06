package com.notdxbi.dxbi.tv.lumen

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import com.notdxbi.dxbi.tv.LoadingCover
import com.notdxbi.dxbi.tv.phone.Title
import kotlinx.coroutines.delay

/**
 * What the loading screen hands the player when it opens (the title, its episode line, the step it got to),
 * taken once by the player. It goes stale after a minute (a player opened some other way shows none).
 */
object LoadingHandoff {
    class Info(val title: Title, val episode: String?, val step: Launcher.Step, val at: Long, val nextVideo: String? = null)

    @Volatile
    private var info: Info? = null

    fun put(title: Title, episode: String?, step: Launcher.Step) {
        info = Info(title, episode, step, System.currentTimeMillis())
    }

    fun clear() {
        info = null
    }

    fun take(): Info? = info.also { info = null }?.takeIf { System.currentTimeMillis() - it.at < 60_000 }

    /**
     * A stream that failed while loading: the app's loading screen goes up behind the player before it closes,
     * so the same screen stays on through to the next best (see Launcher.retry)
     */
    val retries = kotlinx.coroutines.flow.MutableStateFlow<Info?>(null)

    fun retry(title: Title, episode: String?, why: String) {
        retries.value = Info(title, episode, Launcher.Step("Trying another stream", why.ifEmpty { null }?.let { "The last one didn't play: $it" }, 0.9f), System.currentTimeMillis())
    }

    /** The next episode: the same screen, while it's started the way Play starts one (see Launcher.retry) */
    fun next(title: Title, episode: String, videoId: String) {
        retries.value = Info(title, episode, Launcher.Step("Starting the next episode", null, 0.3f), System.currentTimeMillis(), videoId)
    }

    /** Another stream of the same video, chosen from the player's list: the same screen, while the page opens it */
    fun switching(title: Title, episode: String?) {
        retries.value = Info(title, episode, Launcher.Step("Switching stream", null, 0.9f), System.currentTimeMillis())
    }

    fun takeRetry(): Info? = retries.value.also { retries.value = null }?.takeIf { System.currentTimeMillis() - it.at < 30_000 }
}

/**
 * The loading screen carried on in the player, over the video, until its first picture: the same art, logo
 * and step, the bar creeping on to full, then it fades away. If the stream fails first it says so here
 * (Back goes back) instead of leaving a black player.
 */
class PlayerLoading(private val info: LoadingHandoff.Info, private val onGone: () -> Unit) : LoadingCover {
    private var step by mutableStateOf(info.step)
    private var ready by mutableStateOf(false)
    private var fading by mutableStateOf(false)

    override var showing = true
        private set

    override fun detail(text: String) {
        if (!ready && !step.failed) step = step.copy(detail = text)
    }

    override fun ready() {
        if (!step.failed) ready = true
    }

    override fun next(episode: String, videoId: String) {
        LoadingHandoff.next(info.title, episode, videoId)
    }

    override fun switching() {
        LoadingHandoff.switching(info.title, info.episode)
    }

    override fun retrying(message: String) {
        ready = false
        step = Launcher.Step("That stream didn't play", "Trying another…", 0.9f)
        LoadingHandoff.retry(info.title, info.episode, message)
    }

    override fun failed(message: String) {
        ready = false
        step = Launcher.Step("Couldn't play this stream", "$message. Press Back to go back.", 1f, failed = true)
    }

    @Composable
    fun Content() {
        // the bar: on to nearly full while the video loads, full once it plays
        val shown = when {
            step.failed -> step
            ready -> step.copy(progress = 1f)
            else -> step.copy(progress = 0.96f)
        }
        val fade by animateFloatAsState(if (fading) 0f else 1f, tween(450), label = "loading-fade", finishedListener = {
            if (fading) {
                showing = false
                onGone()
            }
        })
        LaunchedEffect(ready) {
            if (!ready) return@LaunchedEffect
            delay(300)
            fading = true
        }
        LoadingContent(info.title, info.episode, shown, Modifier.alpha(fade), progressMs = if (ready) 250 else 7000)
    }
}
