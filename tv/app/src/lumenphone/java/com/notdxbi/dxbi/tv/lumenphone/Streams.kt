package com.notdxbi.dxbi.tv.lumenphone

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notdxbi.dxbi.tv.Phone
import com.notdxbi.dxbi.tv.phone.StreamChoices
import com.notdxbi.dxbi.tv.phone.StreamOption

private enum class Sort(val label: String) { Best("Best Quality"), Seeds("Most Seeds"), Largest("Largest First"), Smallest("Smallest First"), Addon("Addon Order") }

/** The stream list in a sheet that rises from the bottom (iOS's, with its two heights); on the opened Fold it's a centred card */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamsModal(
    choices: StreamChoices?,
    heading: String,
    subheading: String?,
    onDismiss: () -> Unit,
    tapTarget: Phone.Target = Phone.Target.Player,
    onPlay: (StreamOption, Phone.Target) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false), containerColor = Color(0xFF111111)) {
        StreamsSheet(choices, heading, subheading, tapTarget, onPlay)
    }
}

/**
 * The streams for one film or episode, as Lumen's list shows them (iOS StreamsSheet): Lumen's pick at the top
 * (what you chose last time, else the best that's ready), then every stream with its file name, quality, HDR,
 * source, codec, languages, size and seeds, to filter and sort. ⋮ on a stream: another app, or a download.
 */
@Composable
fun StreamsSheet(
    choices: StreamChoices?,
    heading: String,
    subheading: String?,
    tapTarget: Phone.Target = Phone.Target.Player,
    onPlay: (StreamOption, Phone.Target) -> Unit,
) {
    var sort by rememberSaveable { mutableStateOf(Sort.Best) }
    var quality by rememberSaveable { mutableStateOf<String?>(null) }
    var provider by rememberSaveable { mutableStateOf<String?>(null) }
    var format by rememberSaveable { mutableStateOf<String?>(null) }
    var instantOnly by rememberSaveable { mutableStateOf(false) }
    val all = choices?.items.orEmpty()
    val shown = remember(all, sort, quality, provider, format, instantOnly) {
        val kept = all.filter {
            (quality == null || it.quality == quality) && (provider == null || it.provider == provider) &&
                (format == null || format in it.formats || (format == "IMAX" && it.imax)) && (!instantOnly || it.instant)
        }
        when (sort) {
            Sort.Best -> kept.sortedWith(StreamOption.BEST)
            Sort.Seeds -> kept.sortedByDescending { it.seeds }
            Sort.Largest -> kept.sortedByDescending { it.size }
            Sort.Smallest -> kept.sortedBy { if (it.size > 0) it.size else Double.MAX_VALUE }
            Sort.Addon -> kept
        }
    }
    val searching = choices == null || !choices.settled

    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 720.dp), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item(key = "heading") {
            Column {
                Text(heading, style = MaterialTheme.typography.titleLarge, color = Dx.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                subheading?.let { Text(it, color = Dx.Muted, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        choices?.picked?.let { best ->
            item(key = "pick") {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.White).clickable { onPlay(best, tapTarget) }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.Black, modifier = Modifier.size(28.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (choices.sameAsLastTime) "Play · Same as Last Time" else "Play the Best Stream", color = Color.Black, style = MaterialTheme.typography.labelLarge)
                        Text(listOf(best.label, best.provider, best.sizeText).filter { !it.isNullOrBlank() }.joinToString(" · "), color = Color.Black.copy(alpha = 0.65f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (best.filename.isNotBlank()) Text(best.filename, color = Color.Black.copy(alpha = 0.55f), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (best.instant) Icon(Icons.Filled.Bolt, contentDescription = "Ready to play", tint = Color(0xFF2E9E4F))
                }
            }
        }
        if (all.size > 1) item(key = "filters") {
            val qualities = all.map { it.quality }.distinct().sortedByDescending { q -> all.first { it.quality == q }.res }
            val providers = all.map { it.provider }.filter { it.isNotBlank() }.distinct().sorted()
            val formats = StreamOption.FORMAT_ORDER.filter { f -> all.any { f in it.formats } }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Menu(sort.label, Sort.entries.map { it to it.label }) { sort = it } }
                if (qualities.size > 1) item { Menu(quality ?: "Any Quality", listOf<Pair<String?, String>>(null to "Any Quality") + qualities.map { it to it }) { quality = it } }
                if (formats.isNotEmpty()) item { Menu(format ?: "Any Format", listOf<Pair<String?, String>>(null to "Any Format") + formats.map { it to it }) { format = it } }
                if (providers.size > 1) item { Menu(provider ?: "Any Source", listOf<Pair<String?, String>>(null to "Any Source") + providers.map { it to it }) { provider = it } }
                if (all.any { it.instant } && all.any { !it.instant }) item { Chip("Instant Only", instantOnly) { instantOnly = !instantOnly } }
            }
        }
        items(shown, key = { it.index }) { s -> StreamRow(s, picked = s.index == choices?.pick, tapTarget = tapTarget, onPlay = onPlay) }
        item(key = "status") {
            when {
                searching -> Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = Dx.Text, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(if (all.isEmpty()) "Finding streams…" else "More addons answering…", color = Dx.Muted)
                }
                all.isEmpty() -> Text("No streams found. Your addons may not have this one yet.", color = Dx.Muted, modifier = Modifier.padding(8.dp))
                shown.isEmpty() -> Text("Nothing matches those filters.", color = Dx.Muted, modifier = Modifier.padding(8.dp))
            }
        }
    }
}

@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(36.dp).clip(CircleShape).background(if (on) Color.White else Color.White.copy(alpha = 0.12f)).clickable(onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (on) Color.Black else Dx.Text, style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp)) }
}


@Composable
private fun <T> Menu(label: String, options: List<Pair<T, String>>, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.height(36.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)).clickable { open = true }.padding(start = 14.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Dx.Text, style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp))
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = Dx.Muted, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (value, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onPick(value) }) }
        }
    }
}

@Composable
private fun StreamRow(s: StreamOption, picked: Boolean, tapTarget: Phone.Target, onPlay: (StreamOption, Phone.Target) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Dx.Raised.copy(alpha = 0.7f))
            .then(if (picked) Modifier.border(1.5.dp, Color.White, shape) else Modifier)
            .clickable { onPlay(s, tapTarget) }.padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Tag(s.quality, filled = true)
            if (s.imax) Tag("IMAX")
            if (s.dv) Tag("DOLBY VISION") else if (s.hdr10plus) Tag("HDR10+") else if (s.hdr) Tag("HDR")
            if (s.atmos) Tag("ATMOS")
            if (s.openMatte) Tag("OPEN MATTE")
            if (s.threeD) Tag("3D")
            if (s.source.isNotEmpty() && s.source != "Other") Tag(s.source.uppercase(), colour = if (s.source == "CAM") Dx.Problem else Color.White)
            if (s.codec.isNotEmpty()) Tag(s.codec)
            Spacer(Modifier.weight(1f))
            when {
                s.instant -> Tag("INSTANT", colour = Dx.Good)
                s.pending -> Tag("NOT CACHED", colour = Color(0xFFFFD60A))
                s.torrent -> Tag("TORRENT", colour = Dx.Muted)
            }
            Box {
                Icon(Icons.Filled.MoreVert, contentDescription = "More ways to play", tint = Dx.Muted, modifier = Modifier.size(24.dp).clip(CircleShape).clickable { menu = true })
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Play") }, onClick = { menu = false; onPlay(s, Phone.Target.Player) })
                    DropdownMenuItem(text = { Text("Play with External Player") }, onClick = { menu = false; onPlay(s, Phone.Target.External) })
                    // (a torrent plays from the phone itself: a TV can't reach it, and it isn't a file to save)
                    if (!s.torrent) {
                        DropdownMenuItem(text = { Text("Cast to a TV") }, onClick = { menu = false; onPlay(s, Phone.Target.Cast) })
                        DropdownMenuItem(text = { Text("Download") }, onClick = { menu = false; onPlay(s, Phone.Target.Download) })
                    }
                }
            }
        }
        // the file name, always: it says what the stream really is
        if (s.filename.isNotBlank()) Text(s.filename, color = Dx.Text, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Text(
            listOf(s.provider, s.site).filter { it.isNotBlank() }.distinct().sorted().joinToString(" · "),
            color = if (s.filename.isBlank()) Dx.Text else Dx.Muted,
            style = if (s.filename.isBlank()) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        val facts = listOfNotNull(
            s.sizeText, s.bitrateText, s.seeds.takeIf { it >= 0 }?.let { "$it seeds" },
            s.langs.filterNot { it.startsWith("Not listed") }.takeIf { it.isNotEmpty() }?.joinToString(", "),
            s.addon.takeIf { it.isNotBlank() && it != s.provider },
        )
        if (facts.isNotEmpty()) Text(facts.joinToString(" · "), color = Dx.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        s.claimNote?.let { Text(it, color = Dx.Faint, style = MaterialTheme.typography.bodySmall) }
        if (s.lesser) Text("Upscaled, high frame rate or 3D: may not look as good as it says", color = Dx.Faint, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Tag(text: String, filled: Boolean = false, colour: Color = Color.White) {
    Text(
        text,
        color = if (filled) Color.Black else colour,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Black,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(if (filled) Color.White else colour.copy(alpha = 0.16f)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
