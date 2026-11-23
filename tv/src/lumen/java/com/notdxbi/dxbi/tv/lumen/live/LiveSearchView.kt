package com.notdxbi.dxbi.tv.lumen.live

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.notdxbi.dxbi.tv.live.LiveTvStore
import com.notdxbi.dxbi.tv.lumen.Dx
import com.notdxbi.dxbi.tv.lumen.Field
import com.notdxbi.dxbi.tv.phone.Dates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import lumos.iptv.CanonicalChannel
import lumos.iptv.EpgProgramme
import lumos.iptv.FixtureStatus
import lumos.iptv.GuideChannelRow
import lumos.iptv.LiveSearchResults
import lumos.iptv.SportsFixture
import java.util.Calendar
import java.util.Date

private data class ScopedLiveSearchBundle(
    val epg: LiveSearchResults,
    val fixtures: List<SportsFixture>,
)

/**
 * Full-height Live TV search & quick discovery hub.
 *
 * Pre-typing shows `On Now on UK TV`, `Today's Big Matches`, and `Catch-up Ready` shelves;
 * typing filters the full IPTV channel lineup, EPG listings, catch-up archives, and sports fixtures.
 */
@Composable
fun LiveSearchView(
    store: LiveTvStore,
    hideSportsScores: Boolean,
    nowMs: Long,
    miniPlayerActive: Boolean,
    contentFocusRequester: FocusRequester,
    onMoveToRail: () -> Unit,
    onOpenCatchup: (CanonicalChannel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val channels by store.channels.collectAsState()
    val mainGuideRows by store.mainGuideRows.collectAsState()
    val nowNextMap by store.nowNextByChannel.collectAsState()
    val sportsFixtures by store.sportsFixtures.collectAsState()
    val sportsLoading by store.sportsLoading.collectAsState()
    val resolvedById by store.resolvedFixtureStreams.collectAsState()
    val epgVersion by store.epgVersion.collectAsState()

    var query by remember { mutableStateOf("") }
    var bundle by remember { mutableStateOf<ScopedLiveSearchBundle?>(null) }

    LaunchedEffect(Unit) {
        if (sportsFixtures.isEmpty() && !sportsLoading) {
            store.loadSportsFixtures()
        }
    }

    LaunchedEffect(query, channels, sportsFixtures, epgVersion) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            bundle = null
            return@LaunchedEffect
        }
        delay(160)
        val qLower = trimmed.lowercase()
        val computed = withContext(Dispatchers.Default) {
            val epgResults = store.search(trimmed, System.currentTimeMillis())
            val matchingFixtures = sportsFixtures.filter { f ->
                f.name.lowercase().contains(qLower) ||
                    f.leagueName.lowercase().contains(qLower) ||
                    f.sport.lowercase().contains(qLower) ||
                    (f.home?.name?.lowercase()?.contains(qLower) == true) ||
                    (f.away?.name?.lowercase()?.contains(qLower) == true) ||
                    (f.ukChannelName?.lowercase()?.contains(qLower) == true) ||
                    f.broadcasts.any { it.lowercase().contains(qLower) }
            }
            ScopedLiveSearchBundle(epg = epgResults, fixtures = matchingFixtures)
        }
        bundle = computed
    }

    val onNowDiscovery = remember(mainGuideRows, nowNextMap) {
        mainGuideRows
            .filter { it.isPlayable && it.linkedChannel != null }
            .take(12)
    }
    val onNowZapIds = remember(onNowDiscovery) { onNowDiscovery.mapNotNull { it.linkedChannel?.id } }

    val todayMatchesDiscovery = remember(sportsFixtures, nowMs) {
        val todayCal = Calendar.getInstance().apply { timeInMillis = nowMs }
        val y = todayCal.get(Calendar.YEAR)
        val d = todayCal.get(Calendar.DAY_OF_YEAR)
        sportsFixtures.filter { f ->
            f.status == FixtureStatus.LIVE || (f.status == FixtureStatus.UPCOMING && Calendar.getInstance().apply { timeInMillis = f.startMs }.let {
                it.get(Calendar.YEAR) == y && it.get(Calendar.DAY_OF_YEAR) == d
            })
        }.take(10)
    }

    val catchupDiscovery = remember(channels) {
        channels.filter { it.hasCatchup && it.maxCatchupDays > 0 }.take(12)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(
                start = 24.dp,
                top = 18.dp,
                bottom = 14.dp,
                end = if (miniPlayerActive) 316.dp else 24.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val voice = com.notdxbi.dxbi.tv.phone.rememberVoiceTyping { said -> query = said }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Field(
                label = "Search channels, shows, catch-up, sports…",
                value = query,
                onChange = { query = it },
                type = KeyboardType.Text,
                downWhenDone = true,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(contentFocusRequester)
                    .onPreviewKeyEvent { ev ->
                        if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionLeft) {
                            onMoveToRail()
                            true
                        } else false
                    },
                leading = {
                    Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                },
            )
            // speak a channel or programme, as Lumen's own Search
            if (voice.available) {
                com.notdxbi.dxbi.tv.lumen.MicButton(voice.listening, { if (voice.listening) voice.stop() else voice.start() })
            }
            if (query.isNotEmpty()) {
                LiveChip(
                    label = "Clear",
                    selected = false,
                    onClick = { query = "" },
                )
            }
        }

        val current = bundle
        when {
            query.isBlank() -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    if (onNowDiscovery.isNotEmpty()) {
                        item(key = "disc:hdr:onnow") {
                            LiveSectionHeading("On Now on UK TV", "${onNowDiscovery.size} channels")
                        }
                        item(key = "disc:row:onnow") {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                itemsIndexed(onNowDiscovery, key = { _, row -> "onnow:${row.guideChannel.id}" }) { idx, row ->
                                    val ch = row.linkedChannel!!
                                    val nowProg = nowNextMap[ch.id]?.first
                                    DiscoveryChannelCard(
                                        row = row,
                                        programme = nowProg,
                                        nowMs = nowMs,
                                        onClick = { store.playGuideRow(context, row, zapChannelIds = onNowZapIds) },
                                        modifier = if (idx == 0) {
                                            Modifier.onPreviewKeyEvent { ev ->
                                                if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionLeft) {
                                                    onMoveToRail()
                                                    true
                                                } else false
                                            }
                                        } else Modifier,
                                    )
                                }
                            }
                        }
                    }

                    if (todayMatchesDiscovery.isNotEmpty()) {
                        item(key = "disc:hdr:matches") {
                            LiveSectionHeading("Today's Big Matches", "${todayMatchesDiscovery.size} events")
                        }
                        item(key = "disc:row:matches") {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                itemsIndexed(todayMatchesDiscovery, key = { _, f -> "tm:${f.id}" }) { idx, fixture ->
                                    val resolved = resolvedById[fixture.id].orEmpty()
                                    SportsFixtureCard(
                                        fixture = fixture,
                                        resolvedStreams = resolved,
                                        hideSportsScores = hideSportsScores,
                                        nowMs = nowMs,
                                        onFocus = {},
                                        onClick = { store.playFixture(context, fixture) },
                                        modifier = Modifier
                                            .width(290.dp)
                                            .then(
                                                if (idx == 0) {
                                                    Modifier.onPreviewKeyEvent { ev ->
                                                        if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionLeft) {
                                                            onMoveToRail()
                                                            true
                                                        } else false
                                                    }
                                                } else Modifier,
                                            ),
                                    )
                                }
                            }
                        }
                    }

                    if (catchupDiscovery.isNotEmpty()) {
                        item(key = "disc:hdr:catchup") {
                            LiveSectionHeading("Catch-up Ready", "7-day UK TV archive")
                        }
                        item(key = "disc:row:catchup") {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                itemsIndexed(catchupDiscovery, key = { _, ch -> "cu:${ch.id}" }) { idx, ch ->
                                    val nowProg = nowNextMap[ch.id]?.first
                                    GlassTile(
                                        onClick = { onOpenCatchup(ch) },
                                        modifier = Modifier
                                            .width(228.dp)
                                            .then(
                                                if (idx == 0) {
                                                    Modifier.onPreviewKeyEvent { ev ->
                                                        if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionLeft) {
                                                            onMoveToRail()
                                                            true
                                                        } else false
                                                    }
                                                } else Modifier,
                                            ),
                                        shape = RoundedCornerShape(14.dp),
                                        fillColor = LivePalette.GlassFill,
                                        focusedFillColor = Color.White,
                                        borderColor = Dx.Amber.copy(alpha = 0.28f),
                                        focusedScale = 1.03f,
                                    ) { focused, ink ->
                                        Column(
                                            modifier = Modifier.padding(14.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            ) {
                                                LandscapeChannelLogo(
                                                    logoUrl = ch.logoUrl,
                                                    name = ch.name,
                                                    width = 52.dp,
                                                    height = 28.dp,
                                                )
                                                HeroPillBadge(
                                                    text = "${ch.maxCatchupDays}d Archive",
                                                    containerColor = Dx.Amber.copy(alpha = 0.22f),
                                                    contentColor = if (focused) Color.Black else Dx.Amber,
                                                )
                                            }
                                            Text(
                                                text = ch.name,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = ink,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = nowProg?.title ?: "Browse catch-up episodes",
                                                fontSize = 12.sp,
                                                color = ink.copy(alpha = 0.68f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            current == null -> {
                Text("Searching…", fontSize = 14.sp, color = Dx.Muted)
            }
            current.epg.channels.isEmpty() &&
                current.epg.onNow.isEmpty() &&
                current.epg.catchup.isEmpty() &&
                current.epg.upcoming.isEmpty() &&
                current.fixtures.isEmpty() -> {
                Text("No Live TV matches for \"${query.trim()}\".", fontSize = 14.sp, color = Dx.Muted)
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (current.epg.channels.isNotEmpty()) {
                        item(key = "s:hdr:ch") {
                            LiveSectionHeading("Channels", "${current.epg.channels.size}")
                        }
                        items(current.epg.channels.take(40), key = { "sch:${it.id}" }) { ch ->
                            val (nowProg, _) = nowNextMap[ch.id] ?: (null to null)
                            SearchChannelRow(
                                channel = ch,
                                nowProg = nowProg,
                                nowMs = nowMs,
                                onMoveToRail = onMoveToRail,
                                onClick = { store.playLive(context, ch) },
                            )
                        }
                    }

                    if (current.fixtures.isNotEmpty()) {
                        item(key = "s:hdr:sp") {
                            LiveSectionHeading("Sports Fixtures", "${current.fixtures.size}")
                        }
                        val chunked = current.fixtures.take(20).chunked(2)
                        items(chunked, key = { pair -> "sf:${pair.joinToString(":") { it.id }}" }) { pair ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                pair.forEachIndexed { colIdx, fixture ->
                                    val resolved = remember(fixture.id, channels) { store.resolveFixtureStreams(fixture) }
                                    val leftMod = if (colIdx == 0) {
                                        Modifier.onPreviewKeyEvent { ev ->
                                            if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionLeft) {
                                                onMoveToRail()
                                                true
                                            } else false
                                        }
                                    } else Modifier
                                    SportsFixtureCard(
                                        fixture = fixture,
                                        resolvedStreams = resolved,
                                        hideSportsScores = hideSportsScores,
                                        nowMs = nowMs,
                                        onFocus = {},
                                        onClick = { store.playFixture(context, fixture) },
                                        modifier = Modifier
                                            .weight(1f)
                                            .then(leftMod),
                                    )
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }

                    if (current.epg.onNow.isNotEmpty()) {
                        item(key = "s:hdr:now") {
                            LiveSectionHeading("On Now", "${current.epg.onNow.size}")
                        }
                        items(current.epg.onNow.take(25), key = { "snow:${it.first.id}:${it.second.startMs}" }) { (ch, prog) ->
                            SearchProgrammeRow(
                                channel = ch,
                                programme = prog,
                                tag = "ON NOW",
                                tagColor = Dx.Blue,
                                onMoveToRail = onMoveToRail,
                                onClick = { store.playLive(context, ch) },
                            )
                        }
                    }

                    if (current.epg.catchup.isNotEmpty()) {
                        item(key = "s:hdr:cu") {
                            LiveSectionHeading("Catch-up", "${current.epg.catchup.size}")
                        }
                        items(current.epg.catchup.take(25), key = { "scu:${it.first.id}:${it.second.startMs}" }) { (ch, prog) ->
                            SearchProgrammeRow(
                                channel = ch,
                                programme = prog,
                                tag = "CATCH-UP",
                                tagColor = Dx.Amber.copy(alpha = 0.85f),
                                onMoveToRail = onMoveToRail,
                                onClick = { store.playCatchup(context, ch, prog) },
                            )
                        }
                    }

                    if (current.epg.upcoming.isNotEmpty()) {
                        item(key = "s:hdr:up") {
                            LiveSectionHeading("Upcoming", "${current.epg.upcoming.size}")
                        }
                        items(current.epg.upcoming.take(25), key = { "sup:${it.first.id}:${it.second.startMs}" }) { (ch, prog) ->
                            SearchProgrammeRow(
                                channel = ch,
                                programme = prog,
                                tag = Dates.formatTime(Date(prog.startMs)),
                                tagColor = Color(0xFF33415C),
                                onMoveToRail = onMoveToRail,
                                onClick = {
                                    Toast.makeText(
                                        context,
                                        "${prog.title} airs ${LiveTimeFormat.formatDayHeader(prog.startMs)} at ${LiveTimeFormat.formatTime12h(prog.startMs)} on ${ch.name}",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoveryChannelCard(
    row: GuideChannelRow,
    programme: EpgProgramme?,
    nowMs: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassTile(
        onClick = onClick,
        modifier = modifier.width(236.dp),
        shape = RoundedCornerShape(14.dp),
        fillColor = LivePalette.CellLiveBg,
        focusedFillColor = Color.White,
        borderColor = Dx.Blue.copy(alpha = 0.35f),
        focusedScale = 1.03f,
    ) { focused, ink ->
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LandscapeChannelLogo(
                    logoUrl = row.logoUrl,
                    name = row.guideChannel.name,
                    width = 52.dp,
                    height = 28.dp,
                )
                row.guideChannel.number?.let { num ->
                    Text(
                        text = num.toString(),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (focused) Color.Black.copy(alpha = 0.7f) else Dx.Blue,
                    )
                }
            }
            Text(
                text = programme?.title ?: row.guideChannel.name,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = programme?.let { "${row.guideChannel.name} · ${it.remainingMinutesAt(nowMs)}m left" }
                    ?: row.guideChannel.name,
                fontSize = 12.sp,
                color = ink.copy(alpha = 0.68f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SearchChannelRow(
    channel: CanonicalChannel,
    nowProg: EpgProgramme?,
    nowMs: Long,
    onMoveToRail: () -> Unit,
    onClick: () -> Unit,
) {
    val quality = formatCompactQuality(channel.streams.firstOrNull()?.quality)
    GlassTile(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .onPreviewKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionLeft) {
                    onMoveToRail()
                    true
                } else false
            },
        shape = RoundedCornerShape(14.dp),
        fillColor = LivePalette.GlassFill,
        focusedFillColor = Color.White,
        borderColor = LivePalette.GlassBorder,
        focusedScale = 1.015f,
    ) { focused, ink ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LandscapeChannelLogo(
                logoUrl = channel.logoUrl,
                name = channel.name,
                width = 56.dp,
                height = 32.dp,
                corner = 7.dp,
            )
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    channel.number?.let { num ->
                        Text(
                            num.toString(),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (focused) Color.Black.copy(alpha = 0.7f) else Dx.Blue,
                        )
                    }
                    Text(
                        text = channel.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (quality != null) {
                        Text(quality, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = ink.copy(alpha = 0.65f))
                    }
                }
                Text(
                    text = nowProg?.let { "${it.title} (${it.remainingMinutesAt(nowMs)}m left)" }
                        ?: "${channel.category.label} · ${channel.streams.size} feed${if (channel.streams.size == 1) "" else "s"}",
                    fontSize = 12.sp,
                    color = ink.copy(alpha = 0.65f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            HeroPillBadge(
                text = "LIVE",
                containerColor = if (focused) Color.Black.copy(alpha = 0.1f) else Dx.Blue.copy(alpha = 0.3f),
                contentColor = ink,
            )
        }
    }
}

@Composable
private fun SearchProgrammeRow(
    channel: CanonicalChannel,
    programme: EpgProgramme,
    tag: String,
    tagColor: Color,
    onMoveToRail: () -> Unit,
    onClick: () -> Unit,
) {
    GlassTile(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .onPreviewKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionLeft) {
                    onMoveToRail()
                    true
                } else false
            },
        shape = RoundedCornerShape(14.dp),
        fillColor = LivePalette.GlassFill,
        focusedFillColor = Color.White,
        borderColor = LivePalette.GlassBorder,
        focusedScale = 1.015f,
    ) { _, ink ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LandscapeChannelLogo(
                logoUrl = channel.logoUrl,
                name = channel.name,
                width = 54.dp,
                height = 30.dp,
                corner = 7.dp,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = programme.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${channel.name} · ${Dates.formatShortDay(programme.startMs)} ${LiveTimeFormat.formatRange12h(programme.startMs, programme.endMs)}",
                    fontSize = 12.sp,
                    color = ink.copy(alpha = 0.65f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            HeroPillBadge(text = tag, containerColor = tagColor, contentColor = Color.White)
        }
    }
}
