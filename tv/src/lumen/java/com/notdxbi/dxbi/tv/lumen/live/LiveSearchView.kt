package com.notdxbi.dxbi.tv.lumen.live

import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clipToBounds
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
        // (your teams' coming matches too, so "tottenham game" finds the next one and where it's on)
        launch { store.refreshSavedTeamSchedules() }
        if (sportsFixtures.isEmpty() && !sportsLoading) store.loadSportsFixtures()
    }

    val schedules by store.teamSchedules.collectAsState()
    LaunchedEffect(query, channels, sportsFixtures, epgVersion, schedules) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            bundle = null
            return@LaunchedEffect
        }
        delay(160)
        // ("tottenham game", "spurs v arsenal", "where's the match": the words that say what, not how it's asked)
        val words = trimmed.lowercase().split(Regex("[^a-z0-9&']+")).filter { it.isNotBlank() && it !in SEARCH_FILLER }
        val core = words.joinToString(" ").ifBlank { trimmed }
        bundle = withContext(Dispatchers.Default) {
            val epgResults = store.search(core, System.currentTimeMillis())
            val pool = (sportsFixtures + schedules.flatMap { it.live + it.upcoming }).distinctBy { it.id }
            val matchingFixtures = if (words.isEmpty()) emptyList() else pool.filter { f ->
                val hay = listOfNotNull(f.name, f.leagueName, f.sport, f.home?.name, f.away?.name, f.ukChannelName, teamNick(f.home?.name), teamNick(f.away?.name))
                    .plus(f.broadcasts).joinToString(" ").lowercase()
                words.all { it in hay }
            }.sortedBy { it.startMs }
            ScopedLiveSearchBundle(epg = epgResults, fixtures = matchingFixtures)
        }
    }

    // what's on now, in the chosen category
    val browse = remember(mainGuideRows, channels, scope) {
        if (scope == SearchScope.CATCHUP) channels.filter { it.inScope(scope) }.take(30)
        else mainGuideRows.filter { it.isPlayable && it.linkedChannel != null }.mapNotNull { it.linkedChannel }.filter { it.inScope(scope) }
    }
    val onLeft = Modifier.onPreviewKeyEvent { ev ->
        if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionLeft) { onMoveToRail(); true } else false
    }

    // laid out as Lumen's Search: the microphone and a round box along the top, the letters down the left (they slide
    // away while the remote is over on the right, and come back as it moves left onto them), what's found beside them
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val firstLetter = remember { FocusRequester() }
    var inLetters by remember { mutableStateOf(true) }
    val lettersWidth by androidx.compose.animation.core.animateDpAsState(
        if (inLetters) 300.dp else 0.dp, androidx.compose.animation.core.tween(Dx.Med, easing = Dx.Ease), label = "letters",
    )
    Column(modifier = modifier.fillMaxSize().padding(top = 4.dp, bottom = 12.dp)) {
        val voice = com.notdxbi.dxbi.tv.phone.rememberVoiceTyping { said -> query = said }
        Row(
            Modifier.padding(horizontal = Dx.Gutter).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (voice.available) com.notdxbi.dxbi.tv.lumen.MicButton(voice.listening, { if (voice.listening) voice.stop() else voice.start() }, onLeft)
            Field(
                label = if (voice.listening) "Listening…" else "Channels, shows, teams and matches",
                value = if (voice.listening) voice.heard else query,
                onChange = { query = it },
                type = KeyboardType.Text,
                downWhenDone = true,
                modifier = Modifier.weight(1f).focusRequester(contentFocusRequester).then(if (voice.available) Modifier else onLeft),
                shape = RoundedCornerShape(50),
                leading = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(22.dp)) },
            )
        }
        Row(Modifier.fillMaxSize().padding(top = 18.dp)) {
            Box(
                Modifier.width(lettersWidth).fillMaxHeight().clipToBounds()
                    .onFocusChanged { if (it.hasFocus) inLetters = true }
                    // (Left off the letters' left edge: Lumen's menu)
                    .onPreviewKeyEvent { ev ->
                        if (ev.type != KeyEventType.KeyDown || ev.key != Key.DirectionLeft) return@onPreviewKeyEvent false
                        if (focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Left)) true else { onMoveToRail(); true }
                    },
            ) {
                com.notdxbi.dxbi.tv.lumen.Letters(
                    firstLetter,
                    onUpFromTop = { runCatching { contentFocusRequester.requestFocus() } },
                    onType = { query += it },
                    onDelete = { query = query.dropLast(1) },
                    onClear = { query = "" },
                    modifier = Modifier.width(300.dp).padding(start = Dx.Gutter - 8.dp, end = 12.dp),
                )
            }
            androidx.compose.foundation.layout.BoxWithConstraints(
                Modifier.fillMaxSize()
                    // (the same margin both sides once the letters have slid away, so the grid sits in the middle)
                    .padding(start = (Dx.Gutter - lettersWidth).coerceAtLeast(0.dp), end = Dx.Gutter)
                    .onFocusChanged { if (it.hasFocus) inLetters = false }
                    .onPreviewKeyEvent {
                        if (it.key != Key.DirectionLeft || it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        if (focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Left)) return@onPreviewKeyEvent true
                        inLetters = true
                        runCatching { firstLetter.requestFocus() }
                        true
                    },
            ) {
                val perRow = ((maxWidth + 20.dp) / 176.dp).toInt().coerceAtLeast(2)
                androidx.compose.runtime.CompositionLocalProvider(LocalPerRow provides perRow) {
                val current = bundle
                when {
                    query.isBlank() -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        val favs = favourites.filter { it.streams.isNotEmpty() }
                        if (favs.isNotEmpty()) {
                            item(key = "h:fav") { TileHeading("Favourite channels") }
                            tileRows("fav", favs, nowNextMap, nowMs, perRow) { store.playLive(context, it) }
                        }
                        item(key = "h:all") { TileHeading("All channels") }
                        if (browse.isEmpty()) item(key = "none") { Text("Your channels are still loading.", fontSize = 14.sp, color = Dx.Muted) }
                        tileRows("all", browse, nowNextMap, nowMs, perRow) { store.playLive(context, it) }
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
                        } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (chans.isNotEmpty()) {
                                item(key = "h:ch") { TileHeading("Channels") }
                                tileRows("ch", chans.take(30), nowNextMap, nowMs, perRow) { store.playLive(context, it) }
                            }
                            if (fixtures.isNotEmpty()) {
                                item(key = "h:sp") { TileHeading("Matches") }
                                items(fixtures.take(12).chunked(2), key = { "sf:" + it.first().id }) { pair ->
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                        pair.forEach { f ->
                                        // (where to see it: the UK channel showing it, or that none is listed yet)
                                        androidx.compose.foundation.layout.Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            MatchPanel(f, nowMs, hideSportsScores, mine = false, onPick = { store.playFixture(context, f) }, modifier = Modifier.fillMaxWidth())
                                            val where = f.ukChannelName?.takeIf { it.isNotBlank() } ?: f.broadcasts.firstOrNull { it.isNotBlank() }
                                            Text(
                                                if (where != null) "On $where" else "No UK channel listed yet",
                                                fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                                color = if (where != null) Color.White else Dx.Muted,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.padding(start = 6.dp),
                                            )
                                        }
                                    }
                                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                            fun programmes(key: String, title: String, list: List<Pair<CanonicalChannel, EpgProgramme>>, tag: (EpgProgramme) -> String, click: (CanonicalChannel, EpgProgramme) -> Unit) {
                                if (list.isEmpty()) return
                                item(key = "h:$key") { LiveSectionHeading(title, "${list.size}") }
                                items(list.take(24).chunked(2), key = { "$key:${it.first().first.id}:${it.first().second.startMs}" }) { pair ->
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                        pair.forEach { (ch, prog) ->
                                            SearchRow(null, prog.title, "${ch.name} · ${Dates.formatShortDay(prog.startMs)} ${LiveTimeFormat.formatRange12h(prog.startMs, prog.endMs)}", tag(prog), { click(ch, prog) }, Modifier.weight(1f))
                                        }
                                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                            programmes("now", "On now", onNow, { "Live" }) { ch, _ -> store.playLive(context, ch) }
                            programmes("cu", "Catch-up", catchup, { "Catch-up" }) { ch, prog -> store.playCatchup(context, ch, prog) }
                            programmes("up", "Coming up", upcoming, { LiveTimeFormat.formatTime24h(it.startMs) }) { ch, prog ->
                                Toast.makeText(context, "${prog.title} airs ${LiveTimeFormat.formatDayHeader(prog.startMs)} at ${LiveTimeFormat.formatTime12h(prog.startMs)} on ${ch.name}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                }
            }
        }
    }
}

/** A result: a number, two lines and a small tag, on a solid box with a soft rim */
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
        // (solid, with a soft glass rim: the see-through fill looked grey)
        fillColor = Color(0xFF17171A),
        borderColor = Color.White.copy(alpha = 0.12f),
        sheen = false,
        focusedScale = Dx.FocusRow,
    ) { _, ink ->
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (number != null) Text(number, style = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ink.copy(alpha = 0.55f), fontFeatureSettings = "tnum"), modifier = Modifier.width(30.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = ink.copy(alpha = 0.62f), fontFeatureSettings = "tnum"), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (tag != null) Text(tag, style = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = ink.copy(alpha = 0.7f), fontFeatureSettings = "tnum"), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}


@Composable
private fun TileHeading(text: String) {
    // (over the grid's first column: the grid is centred, so the heading is too, at the grid's width)
    val perRow = LocalPerRow.current
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(156.dp * perRow + 20.dp * (perRow - 1)).padding(top = 4.dp))
    }
}

/** How many channels fit across beside the letters (the headings line up with the grid) */
private val LocalPerRow = androidx.compose.runtime.compositionLocalOf { 5 }

/** Channels five to a row: each its logo tile, its name and what's on */
private fun androidx.compose.foundation.lazy.LazyListScope.tileRows(
    key: String,
    channels: List<CanonicalChannel>,
    nowNext: Map<String, Pair<EpgProgramme?, EpgProgramme?>>,
    nowMs: Long,
    perRow: Int,
    onPick: (CanonicalChannel) -> Unit,
) {
    // (the grid sits in the middle of the room beside the letters, however many fit across; a short last row starts
    // under the first column)
    val gridWidth = 156.dp * perRow + 20.dp * (perRow - 1)
    items(channels.chunked(perRow), key = { "$key:" + it.first().id }) { row ->
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Row(Modifier.width(gridWidth), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                row.forEach { ch -> ChannelCard(ch, nowNext[ch.id]?.first?.title, onClick = { onPick(ch) }) }
            }
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
            shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(Dx.RCard)),
            colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent),
            scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = Dx.FocusButton),
            border = androidx.tv.material3.ClickableSurfaceDefaults.border(
                focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(2.dp, Color.White), shape = RoundedCornerShape(Dx.RCard)),
            ),
        ) {
            ChannelTile(logoUrl = logo, name = ch.name, width = 156.dp, height = 88.dp, corner = Dx.RCard)
        }
        Spacer(Modifier.size(3.dp))
        Text(ch.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (now != null) Text(now, fontSize = 12.sp, color = Dx.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}


/** Words that ask rather than say what: "tottenham game" searches for Tottenham */
private val SEARCH_FILLER = setOf(
    "game", "games", "match", "matches", "fixture", "fixtures", "v", "vs", "versus", "the", "on", "watch", "where", "is", "whats",
    "what's", "when", "today", "tonight", "live", "play", "playing", "football", "footy", "highlights", "next", "a", "and", "to",
)

/** The names people use for a club ("spurs" for Tottenham Hotspur), so they find it too */
private fun teamNick(name: String?): String? {
    val n = name?.lowercase() ?: return null
    return when {
        "tottenham" in n -> "spurs"
        "manchester united" in n || n == "man utd" -> "man utd united"
        "manchester city" in n -> "man city"
        "wolverhampton" in n -> "wolves"
        "brighton" in n -> "seagulls"
        "west ham" in n -> "hammers"
        "newcastle" in n -> "toon magpies"
        "arsenal" in n -> "gunners"
        else -> null
    }
}
