package com.notdxbi.dxbi.tv.lumen.live

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.notdxbi.dxbi.tv.live.LiveTvStore
import com.notdxbi.dxbi.tv.lumen.Dx
import com.notdxbi.dxbi.tv.phone.Dates
import lumos.iptv.CanonicalChannel
import lumos.iptv.EpgProgramme
import lumos.iptv.FixtureStatus
import lumos.iptv.GuideChannelRow
import lumos.iptv.MatchReason
import lumos.iptv.ResolvedFixtureStream
import lumos.iptv.SportsFixture
import java.util.Calendar

private enum class SportsMode(val label: String) {
    MATCHES("Matches"),
    CHANNELS("Channels"),
}

private enum class SportFilter(val label: String) {
    ALL("All"),
    FOOTBALL("Football"),
    NFL("NFL"),
    NBA("NBA"),
    NHL("NHL"),
    MLB("MLB"),
    F1("F1"),
    UFC("UFC"),
    GOLF("Golf"),
    TENNIS("Tennis"),
}

private data class FixtureGroup(
    val title: String,
    val fixtures: List<SportsFixture>,
)

/**
 * Unified Sports hub combining `Matches` (live/upcoming/recent fixtures with sport pop-out filter)
 * and `Channels` (live sports channel EPG grid).
 */
@Composable
fun SportsFixturesView(
    store: LiveTvStore,
    favourites: Set<String>,
    epgVersion: Long,
    hideSportsScores: Boolean,
    nowMs: Long,
    contentFocusRequester: FocusRequester,
    onMoveToRail: () -> Unit,
    onFocusFixture: (SportsFixture, List<ResolvedFixtureStream>) -> Unit,
    onFocusSlot: (GuideChannelRow, EpgProgramme?) -> Unit,
    onOpenCatchup: (CanonicalChannel) -> Unit,
    onEmptyFocus: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val fixtures by store.sportsFixtures.collectAsState()
    val loading by store.sportsLoading.collectAsState()
    val error by store.sportsError.collectAsState()
    val resolvedById by store.resolvedFixtureStreams.collectAsState()
    val sportsLiveRows by store.sportsLiveRows.collectAsState()

    var mode by remember { mutableStateOf(SportsMode.MATCHES) }
    var selectedSport by remember { mutableStateOf(SportFilter.ALL) }
    var sportFilterOpen by remember { mutableStateOf(false) }
    val channelsFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        if (fixtures.isEmpty() && !loading) {
            store.loadSportsFixtures()
        }
    }

    val filteredFixtures = remember(fixtures, selectedSport) {
        if (selectedSport == SportFilter.ALL) fixtures else fixtures.filter { matchesSport(it, selectedSport) }
    }

    val groups = remember(filteredFixtures, nowMs) {
        groupFixturesByTime(filteredFixtures, nowMs)
    }

    LaunchedEffect(mode, filteredFixtures.firstOrNull()?.id, resolvedById, loading) {
        if (mode != SportsMode.MATCHES) return@LaunchedEffect
        val first = filteredFixtures.firstOrNull()
        if (first != null) {
            onFocusFixture(first, resolvedById[first.id].orEmpty())
        } else if (!loading) {
            onEmptyFocus(
                "Sports",
                error ?: "No fixtures available for ${selectedSport.label}",
            )
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Unified segmented mode bar (Matches | Channels) + pop-out sport filter
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Dx.Background)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier
                        .background(LivePalette.GlassFill, RoundedCornerShape(50))
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SportsMode.entries.forEachIndexed { idx, entry ->
                        val mod = if (idx == 0) {
                            Modifier
                                .then(if (mode == SportsMode.MATCHES && filteredFixtures.isEmpty()) Modifier.focusRequester(contentFocusRequester) else Modifier)
                                .onPreviewKeyEvent { ev ->
                                    if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionLeft) {
                                        onMoveToRail()
                                        true
                                    } else false
                                }
                        } else Modifier
                        LiveChip(
                            label = entry.label,
                            selected = mode == entry,
                            onClick = { mode = entry },
                            modifier = mod,
                        )
                    }
                }

                if (mode == SportsMode.MATCHES) {
                    LiveChip(
                        label = "Sport: ${selectedSport.label}",
                        selected = sportFilterOpen || selectedSport != SportFilter.ALL,
                        onClick = { sportFilterOpen = !sportFilterOpen },
                        trailing = {
                            Icon(
                                imageVector = if (sportFilterOpen) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                        },
                    )
                    Spacer(Modifier.weight(1f))
                    LiveChip(
                        label = if (loading) "Updating…" else "Refresh",
                        selected = false,
                        onClick = { store.loadSportsFixtures() },
                        leading = {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                        },
                    )
                }
            }

            AnimatedVisibility(
                visible = mode == SportsMode.MATCHES && sportFilterOpen,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    itemsIndexed(SportFilter.entries, key = { _, f -> f.name }) { idx, filter ->
                        LiveChip(
                            label = filter.label,
                            selected = selectedSport == filter,
                            onClick = {
                                selectedSport = filter
                                sportFilterOpen = false
                            },
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

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(LivePalette.Hairline),
        )

        if (mode == SportsMode.CHANNELS) {
            LiveEpgGrid(
                rows = sportsLiveRows,
                store = store,
                favourites = favourites,
                epgVersion = epgVersion,
                isSportsMode = true,
                contentFocusRequester = channelsFocusRequester,
                onMoveToRail = onMoveToRail,
                onFocusSlot = onFocusSlot,
                onOpenCatchup = onOpenCatchup,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            return@Column
        }

        when {
            loading && filteredFixtures.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Loading live & upcoming sports fixtures…", fontSize = 15.sp, color = Dx.Muted)
                }
            }
            error != null && filteredFixtures.isEmpty() -> {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(error ?: "Unable to load sports fixtures", fontSize = 15.sp, color = Color(0xFFFF7A7E))
                }
            }
            groups.isEmpty() -> {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("No ${selectedSport.label} fixtures scheduled right now.", fontSize = 15.sp, color = Dx.Muted)
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    groups.forEachIndexed { groupIdx, group ->
                        item(key = "hdr:${group.title}") {
                            LiveSectionHeading(
                                title = group.title,
                                subtitle = "${group.fixtures.size} event${if (group.fixtures.size == 1) "" else "s"}",
                            )
                        }
                        val rows = group.fixtures.chunked(2)
                        itemsIndexed(rows, key = { _, pair -> "row:${group.title}:${pair.joinToString(":") { it.id }}" }) { rowIdx, pair ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                pair.forEachIndexed { colIdx, fixture ->
                                    val streams = resolvedById[fixture.id].orEmpty()
                                    val isFirstCard = groupIdx == 0 && rowIdx == 0 && colIdx == 0
                                    val cardMod = Modifier
                                        .weight(1f)
                                        .then(if (isFirstCard) Modifier.focusRequester(contentFocusRequester) else Modifier)
                                        .then(
                                            if (colIdx == 0) {
                                                Modifier.onPreviewKeyEvent { ev ->
                                                    if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionLeft) {
                                                        onMoveToRail()
                                                        true
                                                    } else false
                                                }
                                            } else Modifier,
                                        )
                                    SportsFixtureCard(
                                        fixture = fixture,
                                        resolvedStreams = streams,
                                        hideSportsScores = hideSportsScores,
                                        nowMs = nowMs,
                                        onFocus = { onFocusFixture(fixture, streams) },
                                        onClick = { store.playFixture(context, fixture) },
                                        modifier = cardMod,
                                    )
                                }
                                if (pair.size == 1) {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Shared Apple TV glass sports fixture card with light-backed crests and UK broadcaster priority. */
@Composable
internal fun SportsFixtureCard(
    fixture: SportsFixture,
    resolvedStreams: List<ResolvedFixtureStream>,
    hideSportsScores: Boolean,
    nowMs: Long,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    preferDateOverUsBroadcaster: Boolean = false,
) {
    val topStream = resolvedStreams.firstOrNull()
    val hasPlayable = topStream != null
    val matchLabel = when {
        fixture.status == FixtureStatus.FINAL -> {
            val dateLabel = Dates.formatShortDay(fixture.startMs)
            fixture.ukChannelName?.takeIf { it.isNotBlank() }?.let { "$dateLabel · $it" } ?: dateLabel
        }
        topStream?.reason == MatchReason.DIRECT_EVENT -> "Live Event · ${topStream.matchedLabel}"
        topStream != null -> topStream.matchedLabel
        !fixture.ukChannelName.isNullOrBlank() -> fixture.ukChannelName!!
        preferDateOverUsBroadcaster -> LiveTimeFormat.formatFixtureWhen(fixture.startMs, nowMs)
        fixture.broadcasts.isNotEmpty() -> fixture.broadcasts.first()
        else -> LiveTimeFormat.formatFixtureWhen(fixture.startMs, nowMs)
    }

    val (statusText, statusBg) = when (fixture.status) {
        FixtureStatus.LIVE -> {
            val clock = fixture.clock?.takeIf { it.isNotBlank() } ?: fixture.statusDetail?.takeIf { it.isNotBlank() }
            (if (clock != null) "LIVE · $clock" else "LIVE") to LivePalette.LiveRed
        }
        FixtureStatus.FINAL -> {
            val detail = fixture.statusDetail?.takeIf { it.isNotBlank() } ?: "FT"
            "${Dates.formatShortDay(fixture.startMs)} · $detail" to Color(0xFF33415C)
        }
        FixtureStatus.UPCOMING -> {
            LiveTimeFormat.formatFixtureWhen(fixture.startMs, nowMs) to Dx.Blue.copy(alpha = 0.35f)
        }
    }

    val home = fixture.home
    val away = fixture.away
    val fill = if (fixture.status == FixtureStatus.LIVE) LivePalette.CellLiveBg else LivePalette.GlassFill
    val border = if (fixture.status == FixtureStatus.LIVE) Dx.Blue.copy(alpha = 0.4f) else LivePalette.GlassBorder

    GlassTile(
        onClick = onClick,
        onFocus = onFocus,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        fillColor = fill,
        focusedFillColor = Color.White,
        borderColor = border,
        focusedScale = 1.02f,
    ) { focused, ink ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = fixture.leagueName,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = ink.copy(alpha = 0.68f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                HeroPillBadge(
                    text = statusText,
                    containerColor = statusBg,
                    contentColor = Color.White,
                )
            }

            if (home != null && away != null) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FixtureTeamRow(
                        logoUrl = home.logoUrl,
                        name = home.shortName ?: home.name,
                        score = if (fixture.status == FixtureStatus.UPCOMING) null else if (hideSportsScores) "••" else home.score,
                        winner = !hideSportsScores && home.winner,
                        ink = ink,
                    )
                    FixtureTeamRow(
                        logoUrl = away.logoUrl,
                        name = away.shortName ?: away.name,
                        score = if (fixture.status == FixtureStatus.UPCOMING) null else if (hideSportsScores) "••" else away.score,
                        winner = !hideSportsScores && away.winner,
                        ink = ink,
                    )
                }
            } else {
                Text(
                    text = fixture.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .background(
                            when {
                                focused && hasPlayable -> Color.Black.copy(alpha = 0.1f)
                                hasPlayable -> Dx.Blue.copy(alpha = 0.24f)
                                focused -> Color.Black.copy(alpha = 0.08f)
                                else -> Color.White.copy(alpha = 0.08f)
                            },
                            RoundedCornerShape(6.dp),
                        )
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = matchLabel,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (hasPlayable) ink else ink.copy(alpha = 0.62f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                topStream?.stream?.quality?.let { q ->
                    formatCompactQuality(q)?.let { badge ->
                        Text(
                            text = badge,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = ink.copy(alpha = 0.72f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FixtureTeamRow(
    logoUrl: String?,
    name: String,
    score: String?,
    winner: Boolean,
    ink: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CrestBadge(logoUrl = logoUrl, name = name, size = 24.dp)
        Text(
            text = name,
            fontSize = 14.sp,
            fontWeight = if (winner) FontWeight.Bold else FontWeight.SemiBold,
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!score.isNullOrBlank()) {
            Text(
                text = score,
                fontSize = 15.sp,
                fontWeight = FontWeight.ExtraBold,
                color = ink,
            )
        }
    }
}

private fun matchesSport(fixture: SportsFixture, filter: SportFilter): Boolean {
    val s = fixture.sport.lowercase()
    val l = fixture.league.lowercase()
    val ln = fixture.leagueName.lowercase()
    return when (filter) {
        SportFilter.ALL -> true
        SportFilter.FOOTBALL -> (s == "soccer" || l.contains("soccer") || l.contains("eng.") ||
            l.contains("uefa") || l.contains("esp.") || l.contains("ger.") ||
            l.contains("ita.") || l.contains("fra.") || l.contains("sco.") ||
            ln.contains("premier league") || ln.contains("champions league")) && !l.contains("nfl")
        SportFilter.NFL -> l.contains("nfl") || (s == "football" && (ln.contains("nfl") || l.contains("american")))
        SportFilter.NBA -> s.contains("basketball") || l.contains("nba")
        SportFilter.NHL -> s.contains("hockey") || l.contains("nhl")
        SportFilter.MLB -> s.contains("baseball") || l.contains("mlb")
        SportFilter.F1 -> s.contains("racing") || s.contains("motorsport") || l.contains("f1") || ln.contains("formula")
        SportFilter.UFC -> s.contains("mma") || s.contains("ufc") || l.contains("ufc")
        SportFilter.GOLF -> s.contains("golf") || l.contains("pga")
        SportFilter.TENNIS -> s.contains("tennis") || l.contains("atp") || l.contains("wta")
    }
}

private fun groupFixturesByTime(fixtures: List<SportsFixture>, nowMs: Long): List<FixtureGroup> {
    val todayCal = Calendar.getInstance().apply { timeInMillis = nowMs }
    val todayYear = todayCal.get(Calendar.YEAR)
    val todayDay = todayCal.get(Calendar.DAY_OF_YEAR)

    val live = mutableListOf<SportsFixture>()
    val today = mutableListOf<SportsFixture>()
    val upcoming = mutableListOf<SportsFixture>()
    val past = mutableListOf<SportsFixture>()

    for (f in fixtures) {
        when (f.status) {
            FixtureStatus.LIVE -> live += f
            FixtureStatus.FINAL -> past += f
            FixtureStatus.UPCOMING -> {
                val fc = Calendar.getInstance().apply { timeInMillis = f.startMs }
                if (fc.get(Calendar.YEAR) == todayYear && fc.get(Calendar.DAY_OF_YEAR) == todayDay) {
                    today += f
                } else {
                    upcoming += f
                }
            }
        }
    }

    return buildList {
        if (live.isNotEmpty()) add(FixtureGroup("Live Now", live.sortedBy { it.startMs }))
        if (today.isNotEmpty()) add(FixtureGroup("Later Today", today.sortedBy { it.startMs }))
        if (upcoming.isNotEmpty()) add(FixtureGroup("Upcoming", upcoming.sortedBy { it.startMs }))
        if (past.isNotEmpty()) add(FixtureGroup("Recent Results", past.sortedByDescending { it.startMs }))
    }
}
