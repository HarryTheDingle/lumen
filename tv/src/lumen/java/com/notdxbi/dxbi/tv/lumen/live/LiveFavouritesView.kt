package com.notdxbi.dxbi.tv.lumen.live

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
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
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.notdxbi.dxbi.tv.live.LiveTvStore
import com.notdxbi.dxbi.tv.lumen.Action
import com.notdxbi.dxbi.tv.lumen.ActionMenu
import com.notdxbi.dxbi.tv.lumen.Dx
import com.notdxbi.dxbi.tv.lumen.Field
import com.notdxbi.dxbi.tv.lumen.FocusOnOpen
import com.notdxbi.dxbi.tv.lumen.PageWindow
import com.notdxbi.dxbi.tv.lumen.Pill
import kotlinx.coroutines.delay
import lumos.iptv.CanonicalChannel
import lumos.iptv.EpgProgramme
import lumos.iptv.SavedSportsTeam
import lumos.iptv.TeamScheduleGroup

/**
 * Full-height Favourites hub displaying Saved Channels (instant tune, catch-up, long-press actions)
 * and Saved Sports Teams (live, upcoming, and date-stamped recent results with light-backed crests).
 */
@Composable
fun LiveFavouritesView(
    store: LiveTvStore,
    nowMs: Long,
    miniPlayerActive: Boolean,
    contentFocusRequester: FocusRequester,
    onMoveToRail: () -> Unit,
    onOpenCatchup: (CanonicalChannel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val favourites by store.favourites.collectAsState()
    val nowNextMap by store.nowNextByChannel.collectAsState()
    val prefs by store.prefs.collectAsState()
    val teamSchedules by store.teamSchedules.collectAsState()
    val teamsLoading by store.teamsLoading.collectAsState()
    val resolvedById by store.resolvedFixtureStreams.collectAsState()

    var showAddTeamModal by remember { mutableStateOf(false) }
    var actionChannel by remember { mutableStateOf<CanonicalChannel?>(null) }

    LaunchedEffect(prefs.favouriteTeams) {
        if (prefs.favouriteTeams.isNotEmpty() && teamSchedules.isEmpty()) {
            store.refreshSavedTeamSchedules()
        }
    }

    val scheduleByTeamKey = remember(teamSchedules) {
        teamSchedules.associateBy { "${it.team.league}:${it.team.teamId}" }
    }
    val zapChannelIds = remember(favourites) { favourites.map { it.id } }

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(end = if (miniPlayerActive) 316.dp else 0.dp),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "fav:title") {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Favourites", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(
                        "Saved UK channels and followed sports teams",
                        fontSize = 13.sp,
                        color = Dx.Muted,
                    )
                }
            }

            item(key = "fav:hdr:channels") {
                LiveSectionHeading(
                    title = "Saved Channels",
                    subtitle = if (favourites.isEmpty()) "None yet" else "${favourites.size} channel${if (favourites.size == 1) "" else "s"}",
                )
            }

            if (favourites.isEmpty()) {
                item(key = "fav:empty:channels") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(LivePalette.GlassFill, RoundedCornerShape(14.dp))
                            .border(1.dp, LivePalette.GlassBorder, RoundedCornerShape(14.dp))
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                    ) {
                        Text(
                            text = "No favourite channels saved yet. Hold OK on any channel in the TV Guide to favourite it.",
                            fontSize = 13.sp,
                            color = Dx.Muted,
                        )
                    }
                }
            } else {
                itemsIndexed(favourites, key = { _, ch -> "favch:${ch.id}" }) { idx, channel ->
                    val (nowProg, nextProg) = nowNextMap[channel.id] ?: (null to null)
                    val firstFocusMod = if (idx == 0) Modifier.focusRequester(contentFocusRequester) else Modifier
                    SavedChannelRow(
                        channel = channel,
                        nowProg = nowProg,
                        nextProg = nextProg,
                        nowMs = nowMs,
                        onMoveToRail = onMoveToRail,
                        onPlay = { store.playLive(context, channel, zapChannelIds = zapChannelIds) },
                        onLongClick = { actionChannel = channel },
                        onOpenCatchup = { onOpenCatchup(channel) },
                        onRemoveFav = { store.toggleFavourite(channel.id) },
                        modifier = firstFocusMod,
                    )
                }
            }

            item(key = "fav:hdr:teams") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LiveSectionHeading(
                        title = "Saved Sports Teams",
                        subtitle = if (prefs.favouriteTeams.isEmpty()) "None followed" else "${prefs.favouriteTeams.size} followed",
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (prefs.favouriteTeams.isNotEmpty()) {
                            LiveChip(
                                label = if (teamsLoading) "Updating…" else "Refresh",
                                selected = false,
                                onClick = { store.refreshSavedTeamSchedules() },
                                leading = {
                                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                                },
                            )
                        }
                        val addFocusMod = if (favourites.isEmpty()) Modifier.focusRequester(contentFocusRequester) else Modifier
                        LiveChip(
                            label = "Add a team",
                            selected = true,
                            onClick = { showAddTeamModal = true },
                            leading = {
                                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                            },
                            modifier = addFocusMod.onPreviewKeyEvent { ev ->
                                if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionLeft && favourites.isEmpty()) {
                                    onMoveToRail()
                                    true
                                } else false
                            },
                        )
                    }
                }
            }

            if (prefs.favouriteTeams.isEmpty()) {
                item(key = "fav:empty:teams") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(LivePalette.GlassFill, RoundedCornerShape(14.dp))
                            .border(1.dp, LivePalette.GlassBorder, RoundedCornerShape(14.dp))
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                    ) {
                        Text(
                            text = "Follow Premier League, Champions League, NFL, NBA, NHL, or MLB teams to track live games, recent scores, and upcoming fixtures.",
                            fontSize = 13.sp,
                            color = Dx.Muted,
                        )
                    }
                }
            } else {
                prefs.favouriteTeams.forEach { team ->
                    val group = scheduleByTeamKey["${team.league}:${team.teamId}"] ?: TeamScheduleGroup(team = team)
                    item(key = "team:hdr:${team.league}:${team.teamId}") {
                        TeamHeaderRow(
                            team = team,
                            onMoveToRail = onMoveToRail,
                            onRemove = { store.removeFavouriteTeam(team.teamId) },
                        )
                    }

                    val sections = listOf(
                        "Live" to group.live,
                        "Upcoming" to group.upcoming.take(4),
                        "Recent Results" to group.past.take(4),
                    )
                    val hasAny = sections.any { it.second.isNotEmpty() }
                    if (!hasAny) {
                        item(key = "team:empty:${team.league}:${team.teamId}") {
                            Text(
                                text = if (teamsLoading) "Loading schedule for ${team.name}…" else "No live or scheduled fixtures found for ${team.name}.",
                                fontSize = 12.sp,
                                color = Dx.Muted,
                                modifier = Modifier.padding(start = 12.dp, bottom = 4.dp),
                            )
                        }
                    } else {
                        sections.forEach { (bucketLabel, list) ->
                            if (list.isNotEmpty()) {
                                item(key = "team:sub:${team.league}:${team.teamId}:$bucketLabel") {
                                    Text(
                                        text = bucketLabel.uppercase(),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (bucketLabel == "Live") LivePalette.LiveRed else Dx.Muted,
                                        modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                                    )
                                }
                                val chunked = list.chunked(2)
                                items(chunked, key = { pair -> "tf:${team.teamId}:$bucketLabel:${pair.joinToString(":") { it.id }}" }) { pair ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        pair.forEachIndexed { colIdx, fixture ->
                                            val resolved = resolvedById[fixture.id].orEmpty()
                                            val leftEdgeMod = if (colIdx == 0) {
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
                                                hideSportsScores = prefs.hideSportsScores,
                                                nowMs = nowMs,
                                                preferDateOverUsBroadcaster = true,
                                                onFocus = {},
                                                onClick = { store.playFixture(context, fixture) },
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .then(leftEdgeMod),
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

        actionChannel?.let { selected ->
            val actions = buildList {
                add(Action("Watch Live", Icons.Filled.PlayArrow) {
                    store.playLive(context, selected, zapChannelIds = zapChannelIds)
                })
                if (selected.hasCatchup && selected.maxCatchupDays > 0) {
                    add(Action("Browse Catch-up Archive", Icons.Filled.History) {
                        onOpenCatchup(selected)
                    })
                }
                add(Action("Remove from Favourites", Icons.Filled.DeleteOutline) {
                    store.toggleFavourite(selected.id)
                })
            }
            ActionMenu(
                heading = selected.name,
                actions = actions,
                onDismiss = { actionChannel = null },
            )
        }

        if (showAddTeamModal) {
            AddSportsTeamDialog(
                store = store,
                followedTeams = prefs.favouriteTeams,
                onAddTeam = { store.addFavouriteTeam(it) },
                onRemoveTeam = { store.removeFavouriteTeam(it.teamId) },
                onClose = { showAddTeamModal = false },
            )
        }
    }
}

@Composable
private fun SavedChannelRow(
    channel: CanonicalChannel,
    nowProg: EpgProgramme?,
    nextProg: EpgProgramme?,
    nowMs: Long,
    onMoveToRail: () -> Unit,
    onPlay: () -> Unit,
    onLongClick: () -> Unit,
    onOpenCatchup: () -> Unit,
    onRemoveFav: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val quality = formatCompactQuality(channel.streams.firstOrNull()?.quality)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassTile(
            onClick = onPlay,
            onLongClick = onLongClick,
            modifier = modifier
                .weight(1f)
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
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LandscapeChannelLogo(
                    logoUrl = channel.logoUrl,
                    name = channel.name,
                    width = 58.dp,
                    height = 34.dp,
                    corner = 7.dp,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        channel.number?.let { num ->
                            Text(
                                text = num.toString(),
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
                            Text(
                                text = quality,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = ink.copy(alpha = 0.65f),
                            )
                        }
                    }
                    Text(
                        text = if (nowProg != null) {
                            "${nowProg.title} (${nowProg.remainingMinutesAt(nowMs)}m left)" +
                                (nextProg?.let { " · Next: ${it.title}" } ?: "")
                        } else {
                            "${channel.category.label} · ${channel.streams.size} feed${if (channel.streams.size == 1) "" else "s"}"
                        },
                        fontSize = 12.sp,
                        color = ink.copy(alpha = 0.66f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        if (channel.hasCatchup && channel.maxCatchupDays > 0) {
            Surface(
                onClick = onOpenCatchup,
                modifier = Modifier.size(40.dp),
                shape = ClickableSurfaceDefaults.shape(CircleShape),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Dx.Amber.copy(alpha = 0.16f),
                    contentColor = Dx.Amber,
                    focusedContainerColor = Color.White,
                    focusedContentColor = Color.Black,
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.History, contentDescription = "Catch-up", modifier = Modifier.size(18.dp))
                }
            }
        }

        Surface(
            onClick = onRemoveFav,
            modifier = Modifier.size(40.dp),
            shape = ClickableSurfaceDefaults.shape(CircleShape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color(0xFFFF6B81).copy(alpha = 0.16f),
                contentColor = Color(0xFFFF6B81),
                focusedContainerColor = Color.White,
                focusedContentColor = Color.Black,
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Favorite, contentDescription = "Remove favourite", modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun TeamHeaderRow(
    team: SavedSportsTeam,
    onMoveToRail: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .background(LivePalette.GlassFill, RoundedCornerShape(12.dp))
            .border(1.dp, LivePalette.GlassBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CrestBadge(logoUrl = team.logoUrl, name = team.name, size = 32.dp)
        Column(Modifier.weight(1f)) {
            Text(
                text = team.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (team.leagueName.isNotBlank()) {
                Text(
                    text = team.leagueName,
                    fontSize = 11.sp,
                    color = Dx.Muted,
                    maxLines = 1,
                )
            }
        }
        Surface(
            onClick = onRemove,
            modifier = Modifier.onPreviewKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionLeft) {
                    onMoveToRail()
                    true
                } else false
            },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.White.copy(alpha = 0.08f),
                contentColor = Color.White.copy(alpha = 0.78f),
                focusedContainerColor = Color.White,
                focusedContentColor = Color.Black,
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(Icons.Filled.DeleteOutline, contentDescription = null, modifier = Modifier.size(14.dp))
                Text("Remove", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
internal fun AddSportsTeamDialog(
    store: LiveTvStore,
    followedTeams: List<SavedSportsTeam>,
    onAddTeam: (SavedSportsTeam) -> Unit,
    onRemoveTeam: (SavedSportsTeam) -> Unit,
    onClose: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SavedSportsTeam>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }

    val followedKeys = remember(followedTeams) {
        followedTeams.mapTo(HashSet()) { "${it.league}:${it.teamId}" }
    }

    LaunchedEffect(query) {
        val trimmed = query.trim()
        if (trimmed.length < 2) {
            results = emptyList()
            searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(220)
        results = store.searchSportsTeams(trimmed)
        searching = false
    }

    PageWindow(onBack = onClose, dim = 0.78f, fadeIn = false) {
        FocusOnOpen(firstFocus)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier
                    .width(640.dp)
                    .fillMaxHeight(0.80f)
                    .background(LiveSurface, RoundedCornerShape(22.dp))
                    .border(1.dp, LivePalette.GlassBorder, RoundedCornerShape(22.dp))
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("Add a team", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text("Search UK football, NFL, NBA, NHL, or MLB teams", fontSize = 12.sp, color = Dx.Muted)
                    }
                    Pill(text = "Done", selected = false, onClick = onClose)
                }

                Field(
                    label = "Search team name (e.g. Arsenal, Chiefs, Lakers)…",
                    value = query,
                    onChange = { query = it },
                    type = KeyboardType.Text,
                    downWhenDone = true,
                    modifier = Modifier.focusRequester(firstFocus),
                    leading = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )

                when {
                    searching -> Text("Searching teams…", fontSize = 13.sp, color = Dx.Muted)
                    query.trim().length >= 2 && results.isEmpty() -> Text("No teams match. Try a different name.", fontSize = 13.sp, color = Dx.Muted)
                    else -> {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(bottom = 12.dp),
                        ) {
                            items(results, key = { "${it.league}:${it.teamId}" }) { team ->
                                val isFollowed = "${team.league}:${team.teamId}" in followedKeys
                                Surface(
                                    onClick = { if (isFollowed) onRemoveTeam(team) else onAddTeam(team) },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                                    colors = ClickableSurfaceDefaults.colors(
                                        containerColor = if (isFollowed) Dx.Blue.copy(alpha = 0.18f) else LivePalette.GlassFill,
                                        contentColor = Color.White,
                                        focusedContainerColor = Color.White,
                                        focusedContentColor = Color.Black,
                                    ),
                                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
                                ) {
                                    val ink = LocalContentColor.current
                                    Row(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        CrestBadge(logoUrl = team.logoUrl, name = team.name, size = 34.dp)
                                        Column(Modifier.weight(1f)) {
                                            Text(team.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = ink)
                                            if (team.leagueName.isNotBlank()) {
                                                Text(team.leagueName, fontSize = 12.sp, color = ink.copy(alpha = 0.65f))
                                            }
                                        }
                                        Icon(
                                            imageVector = if (isFollowed) Icons.Filled.Check else Icons.Filled.Add,
                                            contentDescription = if (isFollowed) "Following" else "Follow",
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Text(
                                            text = if (isFollowed) "Following" else "Follow",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
