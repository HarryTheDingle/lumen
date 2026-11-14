@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
package com.notdxbi.dxbi.tv.lumen

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.notdxbi.dxbi.tv.phone.Load
import com.notdxbi.dxbi.tv.phone.Stremio
import com.notdxbi.dxbi.tv.phone.Title
import kotlinx.coroutines.delay

/**
 * Search, as the Apple TV app's: its own letters down the left (no TV keyboard to bring up; OK on the box still opens it,
 * for the remote's voice typing), the box across the top, and as each letter goes in: suggestions (the names it's
 * finding; resting on one searches for it, moving off goes back to what was typed), Top Results / TV Shows / Movies, and
 * the results as frosted tiles in three rows. Moving right into them, the letters slide away; left brings them back.
 * Nothing typed: the genres, to browse.
 */
@Composable
fun SearchScreen(stremio: Stremio, nav: Nav) {
    val results by stremio.search.collectAsState()
    var text by remember { mutableStateOf(nav.query) }
    // a name chosen on a title's page
    LaunchedEffect(nav.query) { if (nav.query.isNotEmpty()) text = nav.query }
    /** A suggestion the remote is resting on: its results show, the box says it */
    var previewing by remember { mutableStateOf<String?>(null) }
    val asked = (previewing ?: text).trim()
    // looks a moment after the typing stops (a suggestion, once the remote's rested on it)
    LaunchedEffect(asked) {
        if (asked.length < 2) return@LaunchedEffect
        if (asked != nav.query) delay(if (previewing != null) 350 else 450)
        stremio.search(asked)
    }
    val rows = if (asked.length < 2) emptyList() else results.filter { it.type !in LEFT_OUT }
    // the addons that haven't answered after a few seconds aren't waited for
    var patient by remember { mutableStateOf(true) }
    LaunchedEffect(asked) { patient = true; delay(6000); patient = false }
    val loading = patient && rows.any { it.state == Load.Loading }

    // what's found, as one list for each tab: Top Results takes each row's best in turn
    val ready = rows.filter { it.state == Load.Ready }
    val shows = ready.filter { it.type == "series" || it.type == "anime" }.flatMap { it.items }.distinctBy { it.id }
    val films = ready.filter { it.type == "movie" }.flatMap { it.items }.distinctBy { it.id }
    val top = buildList {
        val lists = ready.map { it.items }
        for (i in 0 until (lists.maxOfOrNull { it.size } ?: 0)) lists.forEach { l -> l.getOrNull(i)?.let(::add) }
    }.distinctBy { it.id }
    // suggestions: what it's finding, by name (kept while one is being looked at, so the row doesn't change under the remote)
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(top, text, previewing) {
        if (previewing != null) return@LaunchedEffect
        suggestions = if (text.trim().length < 2) emptyList() else top.map { it.name.lowercase() }.distinct().take(8)
    }
    var tab by remember { mutableStateOf(0) }
    val tabs = listOfNotNull("Top Results", "TV Shows".takeIf { shows.isNotEmpty() }, "Movies".takeIf { films.isNotEmpty() })
    val shown = when (tabs.getOrNull(tab)) { "TV Shows" -> shows; "Movies" -> films; else -> top }

    // the letters slide away while the remote is over on the right, and come back when it moves left onto them
    var inLetters by remember { mutableStateOf(true) }
    val lettersWidth by animateDpAsState(if (inLetters) 300.dp else 0.dp, tween(320), label = "letters")
    val firstLetter = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    // Search chosen from the menu: the remote starts on the letters
    LaunchedEffect(Unit) { delay(300); if (nav.query.isEmpty()) runCatching { firstLetter.requestFocus() } }

    Box(Modifier.fillMaxSize().background(Dx.Background)) {
        Column(Modifier.fillMaxSize()) {
            ScreenTag(nav)
            // the box: what's typed, or the suggestion being looked at; OK opens the TV's keyboard (and its voice typing)
            Field(
                "Movies, shows and more", previewing ?: text, { previewing = null; text = it }, KeyboardType.Text,
                Modifier.padding(start = Dx.Gutter, end = Dx.Gutter, top = 6.dp).fillMaxWidth(),
                shape = RoundedCornerShape(50),
                leading = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(22.dp)) },
            )
            Row(Modifier.fillMaxSize().padding(top = 18.dp)) {
                // the letters
                Box(
                    Modifier.width(lettersWidth).fillMaxHeight().clipToBounds()
                        .onFocusChanged { if (it.hasFocus) inLetters = true },
                ) {
                    Letters(
                        firstLetter,
                        onType = { previewing = null; text += it },
                        onDelete = { previewing = null; text = text.dropLast(1) },
                        onClear = { previewing = null; text = "" },
                        modifier = Modifier.width(300.dp).padding(start = Dx.Gutter - 8.dp, end = 12.dp),
                    )
                }
                // what's found (or the genres): left off its start goes back to the letters
                Box(
                    Modifier.fillMaxSize()
                        .onFocusChanged { if (it.hasFocus) inLetters = false }
                        .onPreviewKeyEvent {
                            if (it.key != Key.DirectionLeft || it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            if (focusManager.moveFocus(FocusDirection.Left)) return@onPreviewKeyEvent true
                            inLetters = true
                            runCatching { firstLetter.requestFocus() }
                            true
                        },
                ) {
                    if (asked.length < 2 && text.trim().length < 2) {
                        CompositionLocalProvider(LocalBringIntoViewSpec provides CentredScrolling) {
                            LazyColumn(contentPadding = PaddingValues(bottom = 40.dp)) {
                                item(key = "hint") {
                                    Text("Browse", fontSize = 15.sp, color = Dx.Muted, modifier = Modifier.padding(horizontal = Dx.Gutter, vertical = 4.dp))
                                }
                                genresItem(nav, inChristmasSeason(), hasKitsu = false)
                            }
                        }
                    } else {
                        Found(
                            typed = text.trim(), suggestions = suggestions, previewing = previewing,
                            onPreview = { previewing = it }, onChoose = { previewing = null; text = it },
                            tabs = tabs, tab = tab.coerceIn(0, (tabs.size - 1).coerceAtLeast(0)), onTab = { tab = it },
                            titles = shown, loading = loading, onOpen = { nav.open(it) },
                        )
                    }
                }
            }
        }
    }
}

/** Apple TV's letters: a to z six across (or the numbers), then Space, delete and Clear */
@Composable
private fun Letters(first: FocusRequester, onType: (String) -> Unit, onDelete: () -> Unit, onClear: () -> Unit, modifier: Modifier = Modifier) {
    var numbers by remember { mutableStateOf(false) }
    val keys = if (numbers) "1234567890".map { it.toString() } else ('a'..'z').map { it.toString() }
    // (left off the letters goes nowhere: it went up to the corner, which opened the menu)
    Column(
        modifier.focusRestorer(first).focusProperties { onExit = { if (requestedFocusDirection == FocusDirection.Left) cancelFocusChange() } },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // abc / 123
        Row(Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(50)).padding(3.dp)) {
            listOf("abc", "123").forEachIndexed { i, label ->
                val on = (i == 1) == numbers
                KeyButton(label, Modifier.weight(1f).height(32.dp), selected = on, small = true) { numbers = i == 1 }
            }
        }
        keys.chunked(6).forEachIndexed { r, line ->
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                line.forEachIndexed { c, k ->
                    KeyButton(k, Modifier.size(42.dp).then(if (r == 0 && c == 0) Modifier.focusRequester(first) else Modifier)) { onType(k) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            KeyButton("SPACE", Modifier.width(92.dp).height(38.dp), small = true) { onType(" ") }
            KeyButton("⌫", Modifier.width(70.dp).height(38.dp), icon = true) { onDelete() }
            KeyButton("CLEAR", Modifier.width(92.dp).height(38.dp), small = true) { onClear() }
        }
    }
}

/** One key: plain grey type, a white rounded square with dark type when the remote's on it */
@Composable
private fun KeyButton(label: String, modifier: Modifier, selected: Boolean = false, small: Boolean = false, icon: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Color.White.copy(alpha = 0.2f) else Color.Transparent,
            contentColor = Color.White.copy(alpha = if (selected) 1f else 0.7f),
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.12f),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (icon) Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = "Delete", modifier = Modifier.size(20.dp))
            else Text(label, fontSize = if (small) 13.sp else 22.sp, fontWeight = if (small) FontWeight.SemiBold else FontWeight.Medium)
        }
    }
}

/** The suggestions, the tabs and the results */
@Composable
private fun Found(
    typed: String,
    suggestions: List<String>,
    previewing: String?,
    onPreview: (String?) -> Unit,
    onChoose: (String) -> Unit,
    tabs: List<String>,
    tab: Int,
    onTab: (Int) -> Unit,
    titles: List<Title>,
    loading: Boolean,
    onOpen: (Title) -> Unit,
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        CompositionLocalProvider(LocalBringIntoViewSpec provides RowScrolling) {
            // suggestions: resting on one shows its results; moving off them goes back to what was typed
            if (suggestions.isNotEmpty()) {
                LazyRow(
                    Modifier.focusRestorer().onFocusChanged { if (!it.hasFocus) onPreview(null) },
                    contentPadding = PaddingValues(horizontal = Dx.Gutter),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(suggestions, key = { it }) { s -> Suggestion(s, typed, onFocus = { onPreview(s) }, onClick = { onChoose(s) }) }
                }
            }
            // the tabs: the one the remote's on is chosen
            Row(Modifier.padding(horizontal = Dx.Gutter).focusRestorer(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                tabs.forEachIndexed { i, name -> TabPill(name, i == tab, onFocus = { onTab(i) }) }
            }
            if (titles.isEmpty()) {
                Text(if (loading) "Searching…" else "Nothing found for \"${previewing ?: typed}\".", fontSize = 15.sp, color = Dx.Muted, modifier = Modifier.padding(horizontal = Dx.Gutter))
            } else {
                LazyHorizontalGrid(
                    rows = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxWidth().height(328.dp).focusRestorer(),
                    contentPadding = PaddingValues(horizontal = Dx.Gutter, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    gridItems(titles.take(45), key = { it.id }) { t -> ResultTile(t) { onOpen(t) } }
                }
            }
        }
    }
}

/** A suggestion: a small frosted pill, the letters typed bright and the rest softer; white when the remote's on it */
@Composable
private fun Suggestion(text: String, typed: String, onFocus: () -> Unit, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = Modifier.onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() },
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(containerColor = Glass.Fill, contentColor = Color.White, focusedContainerColor = Color.White, focusedContentColor = Color.Black),
        border = ClickableSurfaceDefaults.border(border = androidx.tv.material3.Border(BorderStroke(1.dp, Glass.Edge), shape = shape)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            val lead = typed.lowercase()
            Text(
                buildAnnotatedString {
                    if (lead.isNotEmpty() && text.startsWith(lead)) {
                        append(text.take(lead.length))
                        withStyle(SpanStyle(color = (if (focused) Color.Black else Color.White).copy(alpha = 0.55f))) { append(text.drop(lead.length)) }
                    } else append(text)
                },
                fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            )
        }
    }
}

/** A tab: plain type, a white pill when it's the one shown */
@Composable
private fun TabPill(name: String, chosen: Boolean, onFocus: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Surface(
        onClick = {},
        modifier = Modifier.onFocusChanged { if (it.isFocused) onFocus() },
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (chosen) Color.White.copy(alpha = 0.2f) else Color.Transparent,
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
    ) {
        Text(name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
    }
}

/** A result: a frosted tile with the poster small on the left, the name and "TV Show · Thriller · 2026" */
@Composable
private fun ResultTile(t: Title, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Surface(
        onClick = onClick,
        modifier = Modifier.width(300.dp).height(92.dp),
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.White.copy(alpha = 0.1f), contentColor = Color.White, focusedContainerColor = Color.White.copy(alpha = 0.26f), focusedContentColor = Color.White),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
    ) {
        Row(Modifier.fillMaxSize().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Art(t.art, Modifier.width(48.dp).height(72.dp).background(Color.Transparent, RoundedCornerShape(6.dp)).then(Modifier.alpha(1f)))
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(t.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 18.sp)
                Text(
                    listOfNotNull(typeName(t.type), t.genres.firstOrNull(), t.year?.take(4)).joinToString(" · "),
                    fontSize = 12.sp, color = Dx.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
