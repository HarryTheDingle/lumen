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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.filled.*
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

/** What the chips under the field narrow channels to */
private enum class SearchScope(val label: String) {
    ALL("All"), CATCHUP("Catch-up"), ENTERTAINMENT("Entertainment"), SPORTS("Sports"), CINEMA("Cinema"), NEWS("News"), KIDS("Kids")
}

private fun CanonicalChannel.inScope(scope: SearchScope): Boolean {
    val cat = category.label.lowercase()
    return when (scope) {
        SearchScope.ALL -> true
        SearchScope.CATCHUP -> hasCatchup
        SearchScope.ENTERTAINMENT -> "entertain" in cat || "terrestrial" in cat || "general" in cat
        SearchScope.SPORTS -> "sport" in cat
        SearchScope.CINEMA -> "cinema" in cat || "movie" in cat || "film" in cat
        SearchScope.NEWS -> "news" in cat
        SearchScope.KIDS -> "kid" in cat || "child" in cat
    }
}

/**
 * Live TV search: the field, the categories and sports to browse, and what's on now. Typing narrows channels, shows,
 * catch-up and fixtures (and the chips narrow them to a category).
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
    onOpenSport: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val channels by store.channels.collectAsState()
    val mainGuideRows by store.mainGuideRows.collectAsState()
    val nowNextMap by store.nowNextByChannel.collectAsState()
    val favourites by store.favourites.collectAsState()
    val sportsFixtures by store.sportsFixtures.collectAsState()
    val sportsLoading by store.sportsLoading.collectAsState()
    val epgVersion by store.epgVersion.collectAsState()

    var query by remember { mutableStateOf("") }
    var scope by remember { mutableStateOf(SearchScope.ALL) }
    var bundle by remember { mutableStateOf<ScopedLiveSearchBundle?>(null) }
    val favTeamIds = remember { emptySet<String>() }

    LaunchedEffect(Unit) {
        if (sportsFixtures.isEmpty() && !sportsLoading) store.loadSportsFixtures()
    }

    LaunchedEffect(query, channels, sportsFixtures, epgVersion) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            bundle = null
            return@LaunchedEffect
        }
        delay(160)
        val qLower = trimmed.lowercase()
        bundle = withContext(Dispatchers.Default) {
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
    }

    // what's on now, in the chosen category
    val browse = remember(mainGuideRows, channels, scope) {
        if (scope == SearchScope.CATCHUP) channels.filter { it.inScope(scope) }.take(30)
        else mainGuideRows.filter { it.isPlayable && it.linkedChannel != null }.mapNotNull { it.linkedChannel }.filter { it.inScope(scope) }.take(30)
    }
    val onLeft = Modifier.onPreviewKeyEvent { ev ->
        if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionLeft) { onMoveToRail(); true } else false
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(start = 40.dp, top = 4.dp, bottom = 12.dp, end = 40.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val voice = com.notdxbi.dxbi.tv.phone.rememberVoiceTyping { said -> query = said }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Field(
                label = "Search channels, shows, catch-up, sports…",
                value = query,
                onChange = { query = it },
                type = KeyboardType.Text,
                downWhenDone = true,
                modifier = Modifier.weight(1f).focusRequester(contentFocusRequester).then(onLeft),
                leading = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
            if (voice.available) com.notdxbi.dxbi.tv.lumen.MicButton(voice.listening, { if (voice.listening) voice.stop() else voice.start() })
            if (query.isNotEmpty()) LiveChip(label = "Clear", selected = false, onClick = { query = "" })
        }

        val current = bundle
        when {
            query.isBlank() -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                val favs = favourites.filter { it.streams.isNotEmpty() }
                if (favs.isNotEmpty()) {
                    item(key = "h:fav") { TileHeading("Favourite channels") }
                    tileRows("fav", favs, nowNextMap, nowMs) { store.playLive(context, it) }
                }
                item(key = "h:all") { TileHeading("All channels") }
                if (browse.isEmpty()) item(key = "none") { Text("Your channels are still loading.", fontSize = 14.sp, color = Dx.Muted) }
                tileRows("all", browse, nowNextMap, nowMs) { store.playLive(context, it) }
            }
            current == null -> Text("Searching…", fontSize = 14.sp, color = Dx.Muted)
            else -> {
                val chans = current.epg.channels.filter { it.inScope(scope) }
                val onNow = current.epg.onNow.filter { it.first.inScope(scope) }
                val catchup = current.epg.catchup.filter { it.first.inScope(scope) }
                val upcoming = current.epg.upcoming.filter { it.first.inScope(scope) }
                val fixtures = if (scope == SearchScope.ALL || scope == SearchScope.SPORTS) current.fixtures else emptyList()
                if (chans.isEmpty() && onNow.isEmpty() && catchup.isEmpty() && upcoming.isEmpty() && fixtures.isEmpty()) {
                    Text("No results for “${query.trim()}”. Try a channel, show or team name.", fontSize = 14.sp, color = Dx.Muted)
                } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (chans.isNotEmpty()) {
                        item(key = "h:ch") { TileHeading("Channels") }
                        tileRows("ch", chans.take(30), nowNextMap, nowMs) { store.playLive(context, it) }
                    }
                    if (fixtures.isNotEmpty()) {
                        item(key = "h:sp") { LiveSectionHeading("Matches", "${fixtures.size}") }
                        items(fixtures.take(12).chunked(2), key = { "sf:" + it.first().id }) { pair ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                pair.forEach { f -> MatchPanel(f, nowMs, hideSportsScores, mine = false, onPick = { store.playFixture(context, f) }, modifier = Modifier.weight(1f)) }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                    fun programmes(key: String, title: String, list: List<Pair<CanonicalChannel, EpgProgramme>>, tag: (EpgProgramme) -> String, click: (CanonicalChannel, EpgProgramme) -> Unit) {
                        if (list.isEmpty()) return
                        item(key = "h:$key") { LiveSectionHeading(title, "${list.size}") }
                        items(list.take(24).chunked(2), key = { "$key:${it.first().first.id}:${it.first().second.startMs}" }) { pair ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                pair.forEach { (ch, prog) ->
                                    SearchRow(null, prog.title, "${ch.name} · ${Dates.formatShortDay(prog.startMs)} ${LiveTimeFormat.formatRange12h(prog.startMs, prog.endMs)}", tag(prog), { click(ch, prog) }, Modifier.weight(1f))
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                    programmes("now", "On now", onNow, { "Live" }) { ch, _ -> store.playLive(context, ch) }
                    programmes("cu", "Catch-up", catchup, { "Catch-up" }) { ch, prog -> store.playCatchup(context, ch, prog) }
                    programmes("up", "Coming up", upcoming, { Dates.formatTime(Date(it.startMs)) }) { ch, prog ->
                        Toast.makeText(context, "${prog.title} airs ${LiveTimeFormat.formatDayHeader(prog.startMs)} at ${LiveTimeFormat.formatTime12h(prog.startMs)} on ${ch.name}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}

/** A result: a number, two lines and a small tag, on glass (no logos) */
@Composable
private fun SearchRow(
    number: String?,
    title: String,
    subtitle: String,
    tag: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassTile(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        shape = RoundedCornerShape(Dx.RCard),
        fillColor = LivePalette.GlassFill,
        sheen = true,
        focusedScale = Dx.FocusRow,
    ) { _, ink ->
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (number != null) Text(number, style = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ink.copy(alpha = 0.55f), fontFeatureSettings = "tnum"), modifier = Modifier.width(30.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, fontSize = 12.sp, color = ink.copy(alpha = 0.62f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (tag != null) Text(tag, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = ink.copy(alpha = 0.7f), maxLines = 1)
        }
    }
}


@Composable
private fun TileHeading(text: String) {
    Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(top = 4.dp))
}

/** Channels five to a row: each its logo tile, its name and what's on */
private fun androidx.compose.foundation.lazy.LazyListScope.tileRows(
    key: String,
    channels: List<CanonicalChannel>,
    nowNext: Map<String, Pair<EpgProgramme?, EpgProgramme?>>,
    nowMs: Long,
    onPick: (CanonicalChannel) -> Unit,
) {
    items(channels.chunked(5), key = { "$key:" + it.first().id }) { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            row.forEach { ch -> ChannelCard(ch, nowNext[ch.id]?.first?.title, onClick = { onPick(ch) }) }
        }
    }
}

/** A channel in search: its logo tile (which lifts with a white edge when chosen), its name and what's on now */
@Composable
private fun ChannelCard(ch: CanonicalChannel, now: String?, onClick: () -> Unit) {
    val logo = remember(ch.id) { tileLogoFor(ch) }
    Column(Modifier.width(156.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        androidx.tv.material3.Surface(
            onClick = onClick,
            modifier = Modifier.size(156.dp, 88.dp),
            shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
            colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent),
            scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
            border = androidx.tv.material3.ClickableSurfaceDefaults.border(
                focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(2.dp, Color.White), shape = RoundedCornerShape(10.dp)),
            ),
        ) {
            ChannelTile(logoUrl = logo, name = ch.name, width = 156.dp, height = 88.dp, corner = 10.dp)
        }
        Spacer(Modifier.size(3.dp))
        Text(ch.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (now != null) Text(now, fontSize = 11.sp, color = Dx.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
