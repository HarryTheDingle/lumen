package com.notdxbi.dxbi.tv.lumenphone.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.notdxbi.dxbi.tv.Phone
import com.notdxbi.dxbi.tv.live.LiveEngine
import com.notdxbi.dxbi.tv.live.LiveReminders
import com.notdxbi.dxbi.tv.live.LiveTvRuntime
import com.notdxbi.dxbi.tv.live.LumenHandoff
import com.notdxbi.dxbi.tv.lumenphone.Dx
import com.notdxbi.dxbi.tv.lumenphone.Launcher
import com.notdxbi.dxbi.tv.phone.Title
import com.notdxbi.dxbi.tv.phone.Stremio
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * What Live TV puts over the rest of Lumen: a reminder that's due, "Watch in Lumen" (a channel's film or episode opened
 * and played here), and the offer to go back to the channel when Lumen's player closes. Always composed in the shell.
 */
@Composable
fun LiveOverlays(stremio: Stremio, launcher: Launcher) {
    val context = LocalContext.current
    val engine = remember { LiveEngine.get(context) }

    // does Lumen have streams for this (Watch in Lumen is only offered when it does)
    LaunchedEffect(Unit) {
        engine.hasStreamsHook = { type, videoId ->
            withTimeoutOrNull(16_000L) {
                suspendCancellableCoroutine { cont ->
                    Phone.playerStreams(type, videoId.substringBefore(':'), videoId) { found -> if (cont.isActive) cont.resume((found?.optJSONArray("items")?.length() ?: 0) > 0) }
                }
            } ?: false
        }
    }

    // back from Lumen's player: offer the channel
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) LumenHandoff.let { h ->
                if (h.playerOpened) { h.playerOpened = false; h.backOffer.value = h.returnTo; h.returnTo = null }
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    // a channel's film or episode, to play here
    val handoff by LumenHandoff.pending.collectAsState()
    LaunchedEffect(handoff) {
        val r = handoff ?: return@LaunchedEffect
        LumenHandoff.pending.value = null
        if (LumenHandoff.returnTo != null) LumenHandoff.playerOpened = true
        launcher.play(Title(id = r.id, type = r.type, name = r.name, background = r.background), videoId = r.videoId, fromStart = LumenHandoff.startMsOnce == 0L)
    }

    ReminderCard(engine)
    BackToChannelCard(engine)
}

@Composable
private fun ReminderCard(engine: LiveEngine) {
    val context = LocalContext.current
    val all by LiveReminders.all.collectAsState()
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { LiveReminders.load(context); while (true) { nowMs = System.currentTimeMillis(); delay(15_000L) } }
    val due = remember(all, nowMs) { LiveReminders.due(nowMs) } ?: return
    fun dismiss() = LiveReminders.dismiss(context, due.id)
    fun play() {
        dismiss()
        val store = LiveTvRuntime.store(context, engine)
        val fixture = due.fixtureId?.let { id -> (store.sportsFixtures.value + store.teamSchedules.value.flatMap { it.live + it.upcoming }).firstOrNull { it.id == id } }
        val channel = due.channelId?.let { id -> store.channels.value.firstOrNull { it.id == id } }
        when { fixture != null -> store.playFixture(context, fixture); channel != null -> store.playLive(context, channel) }
    }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp).padding(bottom = 72.dp).widthIn(max = 420.dp)
                .clip(RoundedCornerShape(18.dp)).background(Color(0xF21C1C1E)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val mins = (due.startMs - nowMs) / 60_000L
            Text(if (mins >= 1) "Starts in $mins min" + (due.channelName?.let { " on $it" } ?: "") else "Starting now" + (due.channelName?.let { " on $it" } ?: ""), color = Dx.Muted, style = MaterialTheme.typography.labelMedium)
            Text(due.title, color = Dx.Text, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LiveButton("Watch", onClick = { play() }); LiveButton("Dismiss", primary = false, onClick = { dismiss() })
            }
        }
    }
}

@Composable
private fun BackToChannelCard(engine: LiveEngine) {
    val context = LocalContext.current
    val offer by LumenHandoff.backOffer.collectAsState()
    val (channelId, channelName) = offer ?: return
    var left by remember(offer) { mutableIntStateOf(10) }
    fun goBack() {
        LumenHandoff.backOffer.value = null
        val store = LiveTvRuntime.store(context, engine)
        store.channels.value.firstOrNull { it.id == channelId }?.let { store.playLive(context, it) }
    }
    LaunchedEffect(offer) { while (left > 0) { delay(1_000L); left-- }; goBack() }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp).widthIn(max = 420.dp)
                .clip(RoundedCornerShape(18.dp)).background(Color(0xF21C1C1E)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Back from Lumen", color = Dx.Muted, style = MaterialTheme.typography.labelMedium)
            Text("Back to $channelName live in $left", color = Dx.Text, style = MaterialTheme.typography.titleMedium, maxLines = 2)
            ProgressLine((10 - left) / 10f, Modifier.padding(vertical = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LiveButton("Back to channel", onClick = { goBack() }); LiveButton("Stay in Lumen", primary = false, onClick = { LumenHandoff.backOffer.value = null })
            }
        }
    }
}
