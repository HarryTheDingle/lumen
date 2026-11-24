package com.notdxbi.dxbi.tv.lumen.live

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.notdxbi.dxbi.tv.lumen.Dx

/** A quiet white spinner: a short arc turning on a faint ring */
@Composable
internal fun LiveSpinner(size: Dp = 34.dp, modifier: Modifier = Modifier) {
    val turn = rememberInfiniteTransition(label = "spin")
    val angle by turn.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "angle")
    Canvas(modifier.size(size).graphicsLayer { rotationZ = angle }) {
        val w = (size.toPx() * 0.09f)
        val st = Stroke(width = w, cap = StrokeCap.Round)
        drawArc(Color.White.copy(alpha = 0.16f), 0f, 360f, false, style = st)
        drawArc(Color.White, -90f, 90f, false, style = st)
    }
}

/**
 * Live TV is still finding its channels or tuning one: a spinner and what's happening, never "nothing here" (that's
 * only said once everything has answered). [channelName] with a logo shows that channel's tile above it.
 */
@Composable
internal fun LiveLoading(
    message: String,
    modifier: Modifier = Modifier,
    logoUrl: String? = null,
    channelName: String? = null,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            if (channelName != null) ChannelTile(logoUrl = logoUrl, name = channelName, width = 150.dp, height = 84.dp, corner = 12.dp)
            LiveSpinner()
            Text(message, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Dx.Muted, textAlign = TextAlign.Center)
        }
    }
}
