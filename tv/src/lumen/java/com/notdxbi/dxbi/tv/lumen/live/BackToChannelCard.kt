package com.notdxbi.dxbi.tv.lumen.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.notdxbi.dxbi.tv.live.LiveTvRuntime
import com.notdxbi.dxbi.tv.live.LumenHandoff
import com.notdxbi.dxbi.tv.lumen.Dx
import com.notdxbi.dxbi.tv.lumen.PageWindow
import com.notdxbi.dxbi.tv.phone.Engine
import kotlinx.coroutines.delay

/**
 * Back from Lumen's player after a channel's "Watch in Lumen": the channel again, live, in 10 seconds unless they'd
 * rather stay in Lumen.
 */
@Composable
fun BackToChannelCard(engine: Engine) {
    val context = LocalContext.current
    val offer by LumenHandoff.backOffer.collectAsState()
    val (channelId, channelName) = offer ?: return
    var left by remember(offer) { mutableIntStateOf(10) }
    val back = remember { FocusRequester() }

    fun goBack() {
        LumenHandoff.backOffer.value = null
        val store = LiveTvRuntime.store(context, engine)
        store.channels.value.firstOrNull { it.id == channelId }?.let { store.playLive(context, it) }
    }

    LaunchedEffect(offer) {
        delay(80)
        runCatching { back.requestFocus() }
        while (left > 0) {
            delay(1_000L)
            left--
        }
        goBack()
    }

    PageWindow(onBack = { LumenHandoff.backOffer.value = null }, fadeIn = true) {
        Box(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 36.dp, top = 36.dp)
                    .width(340.dp)
                    .liveGlass(RoundedCornerShape(22.dp))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Back from Lumen", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Dx.Muted)
                Text("Back to $channelName live in $left", style = androidx.compose.ui.text.TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White, fontFeatureSettings = "tnum"))
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.2f))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth((10 - left) / 10f).background(Color.White))
                }
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LiveChip(label = "Back to $channelName", selected = true, onClick = { goBack() }, modifier = Modifier.focusRequester(back))
                    LiveChip(label = "Stay in Lumen", selected = false, onClick = { LumenHandoff.backOffer.value = null })
                }
            }
        }
    }
}
