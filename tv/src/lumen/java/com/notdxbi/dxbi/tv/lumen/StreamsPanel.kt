package com.notdxbi.dxbi.tv.lumen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.notdxbi.dxbi.tv.phone.StreamChoices
import com.notdxbi.dxbi.tv.phone.StreamOption
import kotlinx.coroutines.delay

private enum class Sort(val label: String) { Best("Best Quality"), Seeds("Most Seeds"), Largest("Largest First"), Smallest("Smallest First"), Addon("Addon Order") }

/**
 * The streams for one film or episode, in a panel down the right (as the iPad's stream sheet:
 * ios/Dxbi/UI/StreamsSheet.swift): Lumen's pick at the top (what played last time, else the best that's
 * ready), then every stream with its file name, quality, HDR, source, codec, languages, size and seeds.
 * The filters change with each press of OK, as there's no menu to open on a remote.
 */
@Composable
fun StreamsPanel(choices: StreamChoices?, heading: String, subheading: String?, onPlay: (StreamOption) -> Unit, onDismiss: () -> Unit) {
    var sort by remember { mutableStateOf(Sort.Best) }
    var quality by remember { mutableStateOf<String?>(null) }
    var provider by remember { mutableStateOf<String?>(null) }
    var instantOnly by remember { mutableStateOf(false) }
    var format by remember { mutableStateOf<String?>(null) }

    // The same file from more than one torrent (same name, same size, same addon) is listed once: the copy with the most
    // seeds, or the one Lumen picked (a list of 30 had the same episode four times in a row)
    val all = choices?.items.orEmpty().sortedWith(compareByDescending<StreamOption> { it.index == choices?.pick }.thenByDescending { it.seeds })
        .distinctBy { if (it.filename.isEmpty() || it.size <= 0) it.index.toString() else "${it.addon}|${it.filename}|${it.size}|${it.instant}" }
        .sortedBy { it.index }
    val kept = all.filter { (quality == null || it.quality == quality) && (provider == null || it.provider == provider) && (!instantOnly || it.instant) && (format == null || format in it.formats) }
    val shown = when (sort) {
        Sort.Best -> kept.sortedWith(StreamOption.BEST)
        Sort.Seeds -> kept.sortedByDescending { it.seeds }
        Sort.Largest -> kept.sortedByDescending { it.size }
        Sort.Smallest -> kept.sortedBy { if (it.size > 0) it.size else Double.MAX_VALUE }
        Sort.Addon -> kept
    }
    val qualities = all.sortedByDescending { it.res }.map { it.quality }.distinct()
    val formats = StreamOption.FORMAT_ORDER.filter { f -> all.any { f in it.formats } }
    val providers = all.map { it.provider }.filter { it.isNotEmpty() }.distinct().sorted()

    val first = remember { FocusRequester() }
    val best = choices?.picked
    // the remote lands on the top of the list once there's something in it
    LaunchedEffect(best != null || shown.isNotEmpty()) {
        if (best != null || shown.isNotEmpty()) {
            delay(80)
            runCatching { first.requestFocus() }
        }
    }

    PageWindow(onBack = onDismiss, dim = 0.6f) {
        Row(Modifier.fillMaxSize()) {
            Spacer(Modifier.weight(1f))
            Box(Modifier.width(560.dp).fillMaxHeight().background(Color(0xFF121212))) {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 28.dp, vertical = 30.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item(key = "heading") {
                        Column(Modifier.padding(bottom = 8.dp)) {
                            Text(heading, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            subheading?.let { Text(it, fontSize = 14.sp, color = Dx.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                    if (best != null) {
                        item(key = "best") { Best(best, choices.sameAsLastTime, Modifier.focusRequester(first)) { onPlay(best) } }
                    }
                    if (all.size > 1) {
                        item(key = "filters") {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                                item { Pill(sort.label, selected = false, onClick = { sort = Sort.entries[(sort.ordinal + 1) % Sort.entries.size] }) }
                                if (qualities.size > 1) item {
                                    Pill(quality ?: "Any Quality", selected = quality != null, onClick = { quality = cycle(qualities, quality) })
                                }
                                // IMAX has a chip of its own (it's the one people look for); the rest cycle in Format
                                if ("IMAX" in formats) item {
                                    Pill("IMAX", selected = format == "IMAX", onClick = { format = if (format == "IMAX") null else "IMAX" })
                                }
                                if (formats.any { it != "IMAX" }) item {
                                    val others = formats.filter { it != "IMAX" }
                                    Pill(format?.takeIf { it != "IMAX" } ?: "Any Format", selected = format != null && format != "IMAX", onClick = { format = cycle(others, format?.takeIf { it != "IMAX" }) })
                                }
                                if (providers.size > 1) item {
                                    Pill(provider ?: "Any Source", selected = provider != null, onClick = { provider = cycle(providers, provider) })
                                }
                                if (all.any { it.instant } && all.any { !it.instant }) item {
                                    Pill("Instant Only", selected = instantOnly, onClick = { instantOnly = !instantOnly })
                                }
                            }
                        }
                    }
                    items(shown, key = { "s${it.index}" }) { s ->
                        StreamRow(
                            s,
                            picked = s.index == choices?.pick,
                            modifier = if (best == null && s == shown.first()) Modifier.focusRequester(first) else Modifier,
                        ) { onPlay(s) }
                    }
                    item(key = "status") { Status(choices, all.isEmpty(), shown.isEmpty()) }
                }
            }
        }
    }
}

/** The next of [options] after [now] (after the last: none, which is any) */
private fun cycle(options: List<String>, now: String?): String? {
    val i = if (now == null) -1 else options.indexOf(now)
    return options.getOrNull(i + 1)
}

@Composable
private fun Best(best: StreamOption, sameAsLastTime: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.88f),
            contentColor = Color.Black,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border.None),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(30.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(if (sameAsLastTime) "Play · Same as Last Time" else "Play the Best Stream", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(
                    listOfNotNull(best.label, best.provider, best.sizeText, best.bitrateText).filter { it.isNotEmpty() }.joinToString(" · "),
                    fontSize = 13.sp,
                    color = Color.Black.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (best.filename.isNotEmpty()) Text(best.filename, fontSize = 12.sp, color = Color.Black.copy(alpha = 0.6f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (best.instant) Icon(Icons.Filled.Bolt, contentDescription = "Instant", tint = Color(0xFF16A34A))
        }
    }
}

@Composable
private fun StreamRow(s: StreamOption, picked: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Dx.Raised.copy(alpha = 0.8f),
            contentColor = Color.White,
            focusedContainerColor = Color(0xFF2C2C2C),
            focusedContentColor = Color.White,
        ),
        border = ClickableSurfaceDefaults.border(
            border = if (picked) Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.45f)), shape = shape) else Border.None,
            focusedBorder = Border(BorderStroke(2.5.dp, Color.White), shape = shape),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Filled(s.quality)
                if (s.imax) Filled("IMAX")
                if (s.dv) Badge("DOLBY VISION") else if (s.hdr10plus) Badge("HDR10+") else if (s.hdr) Badge("HDR")
                if (s.atmos) Badge("ATMOS")
                if (s.openMatte) Badge("OPEN MATTE")
                if (s.threeD) Badge("3D")
                if (s.source.isNotEmpty() && s.source != "Other") Badge(s.source.uppercase(), if (s.source == "CAM") Color(0xFFFF6B6B) else Color.White)
                if (s.codec.isNotEmpty()) Badge(s.codec)
                Spacer(Modifier.weight(1f))
                when {
                    s.instant -> Badge("INSTANT", Color(0xFF4ADE80))
                    s.pending -> Badge("NOT CACHED", Color(0xFFFACC15))
                    s.torrent -> Badge("TORRENT", Dx.Muted)
                }
            }
            // the file name, always: it says what the stream really is
            if (s.filename.isNotEmpty()) Text(s.filename, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 18.sp)
            val from = listOf(s.provider, s.site).filter { it.isNotEmpty() }.distinct().sorted().joinToString(" · ")
            if (from.isNotEmpty()) {
                Text(
                    from,
                    fontSize = if (s.filename.isEmpty()) 15.sp else 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (s.filename.isEmpty()) Color.White else Dx.Muted,
                    maxLines = 1,
                )
            }
            val facts = listOfNotNull(
                s.sizeText,
                s.bitrateText,
                if (s.seeds >= 0) "${s.seeds} seeds" else null,
                s.langs.filter { !it.startsWith("Not listed") }.joinToString(", ").ifEmpty { null },
                if (s.addon != s.provider) s.addon else null,
            ).filter { it.isNotEmpty() }
            if (facts.isNotEmpty()) Text(facts.joinToString(" · "), fontSize = 12.sp, color = Dx.Muted, maxLines = 2)
            s.claimNote?.let { Text(it, fontSize = 11.sp, color = Dx.Muted) }
            if (s.lesser) Text("Upscaled, high frame rate or 3D: may not look as good as it says", fontSize = 11.sp, color = Dx.Faint)
        }
    }
}

/** The quality, in a filled white tag */
@Composable
private fun Filled(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Black,
        color = Color.Black,
        modifier = Modifier.background(Color.White, RoundedCornerShape(4.dp)).border(1.dp, Color.White, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
private fun Status(choices: StreamChoices?, none: Boolean, nothingShown: Boolean) {
    val text = when {
        choices == null || !choices.settled -> if (none) "Finding streams…" else "More addons answering…"
        none -> "No streams. Your addons may not have this one yet."
        nothingShown -> "Nothing matches those filters."
        else -> return
    }
    Text(text, fontSize = 15.sp, color = Dx.Muted, modifier = Modifier.fillMaxWidth().padding(24.dp))
}
