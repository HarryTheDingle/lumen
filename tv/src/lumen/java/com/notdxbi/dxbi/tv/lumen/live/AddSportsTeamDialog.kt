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
                    .liveGlass(RoundedCornerShape(22.dp))
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

