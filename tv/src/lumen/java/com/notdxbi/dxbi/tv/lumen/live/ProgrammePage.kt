package com.notdxbi.dxbi.tv.lumen.live

import androidx.compose.material.icons.filled.Movie
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.notdxbi.dxbi.tv.live.LiveReminders
import com.notdxbi.dxbi.tv.live.LiveTvStore
import com.notdxbi.dxbi.tv.lumen.Action
import com.notdxbi.dxbi.tv.lumen.ActionMenu
import com.notdxbi.dxbi.tv.lumen.Dx
import kotlinx.coroutines.delay
import lumos.iptv.CanonicalChannel
import lumos.iptv.EpgProgramme
import lumos.iptv.GuideChannelRow

/** "60 minutes", "1 hr 5 min" */
internal fun spanText(ms: Long): String {
    val mins = (ms / 60_000L).coerceAtLeast(1L)
    return when {
        mins < 60 -> if (mins == 1L) "1 minute" else "$mins minutes"
        mins % 60 == 0L -> "${mins / 60} hr"
        else -> "${mins / 60} hr ${mins % 60} min"
    }
}

/** "14:05 – 16:10 · Ends in 60 minutes" (or Starts in, or the day it's on) */
internal fun programmeWhen(p: EpgProgramme, nowMs: Long): String {
    val range = "${LiveTimeFormat.formatTime24h(p.startMs)} – ${LiveTimeFormat.formatTime24h(p.endMs)}"
    val rel = when {
        p.isOnAt(nowMs) -> "Ends in ${spanText(p.endMs - nowMs)}"
        p.startMs > nowMs && p.startMs - nowMs < 12 * 3_600_000L -> "Starts in ${spanText(p.startMs - nowMs)}"
        p.startMs > nowMs -> LiveTimeFormat.formatDayHeader(p.startMs)
        else -> "Finished"
    }
    return "$range · $rel"
}

/**
 * The guide's top: a 16:9 picture of the programme the remote is on, with its channel's logo in the corner and a thin
 * progress line (or, while a channel plays, that live picture in the same place), then its title, one plain line of
 * when and where, and two lines about it.
 */
@Composable
internal fun GuideHeader(
    selection: HeroSelection,
    nowMs: Long,
    leading: (@Composable () -> Unit)?,
    onArtworkError: (String?, EpgProgramme) -> Unit,
    modifier: Modifier = Modifier,
) {
    val enrichments = when (selection) {
        is HeroSelection.Programme -> selection.enrichment
        is HeroSelection.Channel -> selection.enrichment
        else -> null
    }
    val (prog, channelName, logo, chId) = when (selection) {
        is HeroSelection.Programme -> Quad(selection.programme, selection.row.guideChannel.name, selection.row.logoUrl, selection.row.linkedChannel?.id ?: selection.row.guideChannel.id)
        is HeroSelection.Channel -> Quad(selection.nowProgramme, selection.channel.name, tileLogoFor(selection.channel), selection.channel.id)
        else -> Quad(null, null, null, null)
    }
    val art = enrichments?.backdrop?.takeIf { it.isNotBlank() } ?: prog?.iconUrl?.takeIf { it.isNotBlank() }
    Row(modifier.fillMaxWidth().height(MINI_H).padding(horizontal = Dx.Gutter), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Box(Modifier.size(MINI_W, MINI_H)) {
            if (leading != null) leading()
            else if (channelName != null) {
                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(Color(0xFF1C1C1F))) {
                    var failed by remember(art) { mutableStateOf(false) }
                    if (art != null && !failed) {
                        AsyncImage(
                            model = art, contentDescription = null, contentScale = ContentScale.Crop,
                            onError = { failed = true; prog?.let { onArtworkError(chId, it) } },
                            modifier = Modifier.fillMaxSize(),
                        )
                        ChannelTile(logoUrl = logo, name = channelName, width = 46.dp, height = 26.dp, corner = 5.dp, modifier = Modifier.align(Alignment.TopEnd).padding(7.dp))
                    } else {
                        ChannelTile(logoUrl = logo, name = channelName, width = MINI_W, height = MINI_H, corner = 10.dp)
                    }
                    if (prog != null && prog.isOnAt(nowMs)) {
                        Box(Modifier.align(Alignment.BottomCenter).padding(start = 10.dp, end = 10.dp, bottom = 8.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.3f))) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(prog.progressAt(nowMs).coerceIn(0f, 1f)).background(Color.White))
                        }
                    }
                }
            }
        }
        Column(Modifier.weight(1f).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            when (selection) {
                is HeroSelection.Empty -> {
                    Text(selection.title, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(selection.subtitle, fontSize = 13.sp, color = Dx.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                else -> {
                    Text(prog?.title ?: channelName.orEmpty(), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(prog?.let { programmeWhen(it, nowMs) }, channelName).joinToString(" · "),
                        style = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Dx.Muted, fontFeatureSettings = "tnum"),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    val about = enrichments?.description?.takeIf { it.isNotBlank() } ?: prog?.description?.takeIf { it.isNotBlank() }
                    if (about != null) Text(about, fontSize = 13.sp, lineHeight = 18.sp, color = Color.White.copy(alpha = 0.82f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private data class Quad(val a: EpgProgramme?, val b: String?, val c: String?, val d: String?)

/**
 * A programme's page (OK on one still to come, or OK held on any): its picture across the screen, the title top left,
 * then along the bottom one line of when and where, the buttons, and what else is on that channel.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun ProgrammePage(
    store: LiveTvStore,
    row: GuideChannelRow,
    programme: EpgProgramme,
    zapChannelIds: List<String>,
    favourite: Boolean,
    onOpenCatchup: (CanonicalChannel) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var prog by remember(programme) { mutableStateOf(programme) }
    val enrichments by store.enrichments.collectAsState()
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000L); nowMs = System.currentTimeMillis() } }
    val linked = row.linkedChannel
    val chId = linked?.id ?: row.guideChannel.id
    LaunchedEffect(prog.id) { store.enrichIfMissing(chId, prog) }
    val more = enrichments[prog.id]
    val art = more?.backdrop?.takeIf { it.isNotBlank() } ?: prog.iconUrl?.takeIf { it.isNotBlank() }
    val about = more?.description?.takeIf { it.isNotBlank() } ?: prog.description?.takeIf { it.isNotBlank() }
    val schedule = remember(row.guideChannel.id, nowMs / 300_000L) {
        store.programmesForWindow(channel = linked, guideChannelId = row.guideChannel.id, fromMs = nowMs, toMs = nowMs + 12 * 3_600_000L)
            .filter { it.endMs > nowMs }.take(12)
    }
    // Lumen has it (a film, or an episode the guide numbers) and has a stream for it: offered as Watch in Lumen
    var inLumen by remember(prog.id) { mutableStateOf<com.notdxbi.dxbi.tv.live.LumenHandoff.Request?>(null) }
    LaunchedEffect(prog.id) {
        val ch = linked ?: return@LaunchedEffect
        if (prog.isLiveSport || Regex("news|sport|weather|live", RegexOption.IGNORE_CASE).containsMatchIn(prog.category.orEmpty())) return@LaunchedEffect
        val found = runCatching { store.findInLumen(ch, prog) }.getOrNull() ?: return@LaunchedEffect
        val imdb = found.imdbId ?: return@LaunchedEffect
        val episode = com.notdxbi.dxbi.tv.live.LumenHandoff.episodeOf(prog.subtitle, prog.title, prog.description)
        val (type, videoId) = when {
            found.mediaType == "movie" -> "movie" to null
            episode != null -> "series" to "$imdb:${episode.first}:${episode.second}"
            else -> return@LaunchedEffect
        }
        val has = kotlinx.coroutines.withTimeoutOrNull(10_000L) { runCatching { store.hasStreams(type, videoId ?: imdb) }.getOrDefault(false) } == true
        if (has) inLumen = com.notdxbi.dxbi.tv.live.LumenHandoff.Request(
            id = imdb, type = type, name = prog.title, background = found.backdrop,
            videoId = videoId, channelId = ch.id, channelName = ch.name,
        )
    }
    val rid = "prog:${row.guideChannel.id}:${prog.startMs}"
    var reminded by remember(rid) { mutableStateOf(LiveReminders.has(rid)) }
    var menu by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    LaunchedEffect(prog.id) { delay(60); runCatching { first.requestFocus() } }
    BackHandler(onBack = onClose)

    val onNow = prog.isOnAt(nowMs)
    val later = prog.startMs > nowMs
    val canCatchup = !later && row.hasCatchup && linked != null

    // (opened by holding OK: that same press is still down, and its repeats and release mustn't press the first button;
    // the page answers OK once it's been let go and pressed again)
    var armed by remember { mutableStateOf(false) }
    Box(
        Modifier.fillMaxSize().background(Color.Black).onPreviewKeyEvent { e ->
            val select = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
            if (!select || armed) return@onPreviewKeyEvent false
            when {
                e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0 -> { armed = true; false }
                e.type == KeyEventType.KeyUp -> { armed = true; true }
                else -> true
            }
        },
    ) {
        if (art != null) {
            AsyncImage(model = art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xFF22252C), Color.Black))))
            ChannelTile(logoUrl = row.logoUrl, name = row.guideChannel.name, width = 300.dp, height = 168.dp, corner = 18.dp,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 80.dp, end = 80.dp))
        }
        // the picture darkens towards the bottom and the left, where the words are
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, 0.38f to Color.Transparent, 0.7f to Color.Black.copy(alpha = 0.78f), 1f to Color.Black.copy(alpha = 0.95f))))
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.55f), 0.55f to Color.Transparent)))

        Text(
            prog.title, fontSize = 40.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = Dx.Gutter, top = Dx.Gutter, end = 300.dp),
        )

        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(bottom = 12.dp)) {
            Column(Modifier.padding(horizontal = Dx.Gutter).widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    listOfNotNull(programmeWhen(prog, nowMs), row.guideChannel.name, prog.subtitle?.takeIf { it.isNotBlank() }).joinToString(" · "),
                    style = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Dx.Muted, fontFeatureSettings = "tnum"),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (about != null) Text(about, fontSize = 13.sp, lineHeight = 18.sp, color = Color.White.copy(alpha = 0.85f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.padding(horizontal = Dx.Gutter).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val watch: () -> Unit = { onClose(); store.playGuideRow(context, row, zapChannelIds = zapChannelIds) }
                // the film pages' own buttons: the first white, the rest round, opening out with their name when chosen
                when {
                    later -> {
                        com.notdxbi.dxbi.tv.lumen.PlayButton(if (reminded) "Reminder set" else "Remind me", {
                            LiveReminders.toggle(context, LiveReminders.Reminder(id = rid, title = prog.title, startMs = prog.startMs, channelId = linked?.id, channelName = row.guideChannel.name))
                            reminded = LiveReminders.has(rid)
                        }, Modifier.focusRequester(first), icon = if (reminded) Icons.Filled.Check else Icons.Filled.Notifications)
                        if (row.isPlayable) com.notdxbi.dxbi.tv.lumen.RoundButton(Icons.Filled.PlayArrow, "Watch channel", watch, expands = true)
                    }
                    onNow -> {
                        com.notdxbi.dxbi.tv.lumen.PlayButton("Watch channel", watch, Modifier.focusRequester(first))
                        if (canCatchup) com.notdxbi.dxbi.tv.lumen.RoundButton(Icons.Filled.Replay, "Watch from start", { onClose(); store.playCatchup(context, linked!!, prog, zapChannelIds = zapChannelIds) }, expands = true)
                    }
                    else -> {
                        if (canCatchup) com.notdxbi.dxbi.tv.lumen.PlayButton("Watch", { onClose(); store.playCatchup(context, linked!!, prog, zapChannelIds = zapChannelIds) }, Modifier.focusRequester(first))
                        if (row.isPlayable) {
                            if (canCatchup) com.notdxbi.dxbi.tv.lumen.RoundButton(Icons.Filled.PlayArrow, "Watch channel", watch, expands = true)
                            else com.notdxbi.dxbi.tv.lumen.PlayButton("Watch channel", watch, Modifier.focusRequester(first))
                        }
                    }
                }
                inLumen?.let { req ->
                    com.notdxbi.dxbi.tv.lumen.RoundButton(Icons.Filled.Movie, "Watch in Lumen", {
                        onClose()
                        // (from the start, in Lumen's picture; when it ends Lumen offers this channel again)
                        com.notdxbi.dxbi.tv.live.LumenHandoff.startMsOnce = 0L
                        com.notdxbi.dxbi.tv.live.LumenHandoff.returnTo = req.channelId to req.channelName
                        com.notdxbi.dxbi.tv.live.LumenHandoff.pending.value = req
                        // (the channel playing in the corner stops: Lumen's film takes over the screen and the sound)
                        com.notdxbi.dxbi.tv.live.LiveMiniPlayer.stop(store)
                    }, expands = true)
                }
                com.notdxbi.dxbi.tv.lumen.RoundButton(Icons.Filled.MoreHoriz, "More", { menu = true },
                    if (later || onNow || canCatchup || row.isPlayable) Modifier else Modifier.focusRequester(first), expands = true)
                Spacer(Modifier.weight(1f))
                ChannelTile(logoUrl = row.logoUrl, name = row.guideChannel.name, width = 86.dp, height = 48.dp, corner = 8.dp)
            }
            if (schedule.size > 1) {
                Spacer(Modifier.height(20.dp))
                Text("On ${row.guideChannel.name}", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = Dx.Gutter))
                // (room above and below for a card to lift into, so its rim isn't cut off)
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides com.notdxbi.dxbi.tv.lumen.rememberRowScrolling(gutter = Dx.Gutter)) {
                LazyRow(contentPadding = PaddingValues(horizontal = Dx.Gutter, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    items(schedule, key = { it.id }) { p ->
                        ScheduleCard(p, enrichments[p.id]?.backdrop ?: p.iconUrl, row, here = p.id == prog.id, nowMs = nowMs) { prog = p }
                    }
                }
                }
            }
        }
    }

    if (menu) {
        val actions = buildList {
            if (linked != null) add(Action(if (favourite) "Remove from favourites" else "Add to favourites") { store.toggleFavourite(linked.id) })
            if (row.hasCatchup && linked != null) add(Action("Earlier programmes (catch-up)") { onClose(); onOpenCatchup(linked) })
        }
        if (actions.isEmpty()) menu = false
        else ActionMenu(heading = prog.title, actions = actions, onDismiss = { menu = false })
    }
}

@Composable
private fun ScheduleCard(p: EpgProgramme, art: String?, row: GuideChannelRow, here: Boolean, nowMs: Long, onClick: () -> Unit) {
    // Lumen's own card (as the films on Home): it lifts when chosen, with the glass rim
    Column(Modifier.width(196.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        com.notdxbi.dxbi.tv.lumen.FocusCard(onClick = onClick, modifier = Modifier.width(196.dp).height(110.dp), corner = 10.dp) {
            Box(Modifier.fillMaxSize().background(Color(0xFF1C1C1F))) {
                var failed by remember(art) { mutableStateOf(false) }
                if (!art.isNullOrBlank() && !failed) {
                    AsyncImage(model = art, contentDescription = null, contentScale = ContentScale.Crop, onError = { failed = true }, modifier = Modifier.fillMaxSize())
                } else {
                    ChannelTile(logoUrl = row.logoUrl, name = row.guideChannel.name, width = 196.dp, height = 110.dp, corner = 0.dp)
                }
                Text(
                    when { here -> "Showing"; p.isOnAt(nowMs) -> "On now"; else -> LiveTimeFormat.formatTime24h(p.startMs) },
                    style = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White, fontFeatureSettings = "tnum"),
                    modifier = Modifier.padding(8.dp).background(Color.Black.copy(alpha = 0.65f), CircleShape).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        Text(p.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (here) Color.White else Color.White.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
