package com.notdxbi.dxbi.tv.lumen.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.notdxbi.dxbi.tv.live.LiveReminders
import com.notdxbi.dxbi.tv.live.LiveTvRuntime
import com.notdxbi.dxbi.tv.lumen.Dx
import com.notdxbi.dxbi.tv.lumen.PageWindow
import com.notdxbi.dxbi.tv.phone.Engine
import kotlinx.coroutines.delay

/**
 * A reminder that's due: a card in the bottom corner two minutes before, wherever you are in Lumen, with Watch
 * (the match's best stream, or the channel) and Dismiss. Back dismisses it too.
 */
@Composable
fun ReminderCard(engine: Engine) {
    val context = LocalContext.current
    val all by LiveReminders.all.collectAsState()
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        LiveReminders.load(context)
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(15_000L)
        }
    }
    val due = remember(all, nowMs) { LiveReminders.due(nowMs) } ?: return
    val watch = remember { FocusRequester() }
    LaunchedEffect(due.id) { delay(80); runCatching { watch.requestFocus() } }

    fun dismiss() = LiveReminders.dismiss(context, due.id)
    fun play() {
        dismiss()
        val store = LiveTvRuntime.store(context, engine)
        val fixture = due.fixtureId?.let { id ->
            (store.sportsFixtures.value + store.teamSchedules.value.flatMap { it.live + it.upcoming }).firstOrNull { it.id == id }
        }
        val channel = due.channelId?.let { id -> store.channels.value.firstOrNull { it.id == id } }
        when {
            fixture != null -> store.playFixture(context, fixture)
            channel != null -> store.playLive(context, channel)
        }
    }

    PageWindow(onBack = { dismiss() }, fadeIn = true) {
        Box(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 36.dp, bottom = 36.dp)
                    .width(340.dp)
                    .background(LiveSurface, RoundedCornerShape(22.dp))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val mins = ((due.startMs - nowMs) / 60_000L)
                Text(
                    when {
                        mins >= 1 -> "Starts in $mins min" + (due.channelName?.let { " on $it" } ?: "")
                        else -> "Starting now" + (due.channelName?.let { " on $it" } ?: "")
                    },
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Dx.Muted,
                )
                Text(due.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LiveChip(label = "Watch", selected = true, onClick = { play() }, modifier = Modifier.focusRequester(watch))
                    LiveChip(label = "Dismiss", selected = false, onClick = { dismiss() })
                }
            }
        }
    }
}
