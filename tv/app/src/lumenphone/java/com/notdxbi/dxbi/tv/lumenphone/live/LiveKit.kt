package com.notdxbi.dxbi.tv.lumenphone.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.notdxbi.dxbi.tv.lumenphone.Dx
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** UK guides use the 24-hour clock */
internal object LiveTime {
    private val hm = object : ThreadLocal<SimpleDateFormat>() { override fun initialValue() = SimpleDateFormat("HH:mm", Locale.UK) }
    private val day = object : ThreadLocal<SimpleDateFormat>() { override fun initialValue() = SimpleDateFormat("EEE d MMM", Locale.UK) }
    private val weekday = object : ThreadLocal<SimpleDateFormat>() { override fun initialValue() = SimpleDateFormat("EEEE", Locale.UK) }
    fun hm(ms: Long): String = hm.get()!!.format(Date(ms))
    fun range(a: Long, b: Long) = "${hm(a)} – ${hm(b)}"
    fun day(ms: Long): String = day.get()!!.format(Date(ms))
    fun weekday(ms: Long): String = weekday.get()!!.format(Date(ms))
    fun clock(ms: Long): String {
        val total = (ms.coerceAtLeast(0L) / 1000L).toInt()
        val h = total / 3600; val m = (total / 60) % 60; val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
    /** "starts in 12 min", "in 2 h", "tomorrow 20:00" */
    fun until(ms: Long, now: Long = System.currentTimeMillis()): String {
        val d = ms - now
        return when {
            d < 0 -> "now"
            d < 3_600_000L -> "in ${(d / 60_000L).coerceAtLeast(1)} min"
            d < 12 * 3_600_000L -> "in ${d / 3_600_000L} h ${(d / 60_000L) % 60} min"
            else -> "${day(ms)} ${hm(ms)}"
        }
    }
}

@Composable
internal fun LiveChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Dx.Selected else Color.White.copy(alpha = 0.10f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (selected) Dx.OnSelected else Dx.Text, style = MaterialTheme.typography.titleSmall, maxLines = 1)
    }
}

/** A white pill button with dark text (or a glass one) */
@Composable
internal fun LiveButton(text: String, modifier: Modifier = Modifier, primary: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(if (primary) Dx.Selected else Color.White.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (primary) Dx.OnSelected else Dx.Text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
internal fun LiveSpinner(modifier: Modifier = Modifier) {
    CircularProgressIndicator(modifier = modifier, color = Color.White, strokeWidth = 2.5.dp)
}

@Composable
internal fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, color = Dx.Text, style = MaterialTheme.typography.titleLarge, modifier = modifier.padding(horizontal = Dx.Gutter, vertical = 8.dp))
}

/** A thin bar showing how far through a programme it is */
@Composable
internal fun ProgressLine(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.18f))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(3.dp).background(Color.White))
    }
}

internal fun Modifier.card(): Modifier = this
    .clip(RoundedCornerShape(14.dp))
    .background(Dx.Raised)
    .border(0.5.dp, Dx.Line, RoundedCornerShape(14.dp))

@Composable
internal fun EmptyNote(title: String, body: String, modifier: Modifier = Modifier, action: @Composable (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = Dx.Text, style = MaterialTheme.typography.titleLarge)
        Text(body, color = Dx.Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp, bottom = 16.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        action?.invoke()
    }
}
