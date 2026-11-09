package com.notdxbi.dxbi.tv.lumenphone

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.notdxbi.dxbi.tv.phone.Load
import com.notdxbi.dxbi.tv.phone.Stremio
import com.notdxbi.dxbi.tv.phone.Title
import kotlinx.coroutines.delay

/** Types the lists leave out: Stremio's live channels and TV (as Home does) */
private val LEFT_OUT = setOf("channel", "tv")

/**
 * Search (iOS: SearchView): a field, and as they type (after a pause) what each addon finds, one row per
 * catalog with a See All on it. With nothing typed: their recent searches, and the Genres as tiles, as Apple
 * TV's Search page has them. The keyboard goes as the results are scrolled, and its height is left clear at
 * the foot so the last row can be reached.
 */
@Composable
fun SearchScreen(stremio: Stremio, layout: AppLayout, bottomSpace: Dp, onOpen: (Title) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("lumen.search", 0) }
    var query by rememberSaveable { mutableStateOf("") }
    var searched by rememberSaveable { mutableStateOf("") }
    var recent by remember { mutableStateOf(prefs.getString("recent", "")!!.split("\n").filter { it.isNotEmpty() }) }
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    val results by stremio.search.collectAsState()
    val focus = LocalFocusManager.current
    val listState = rememberLazyListState()

    fun noteRecent(q: String) {
        recent = (listOf(q) + recent.filter { it != q }).take(8)
        prefs.edit().putString("recent", recent.joinToString("\n")).apply()
    }

    fun run(text: String) {
        val q = text.trim()
        if (q.isEmpty()) return
        query = q
        searched = q
        stremio.search(q)
        noteRecent(q)
        focus.clearFocus()
    }

    // As they type, after a moment's pause (a name or two is too little to look for)
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.isEmpty()) { searched = ""; return@LaunchedEffect }
        if (q.length < 3) return@LaunchedEffect
        delay(600)
        if (q != searched) { searched = q; stremio.search(q) }
    }
    // Scrolling the results puts the keyboard away
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { if (it) focus.clearFocus() }
    }

    val ime = with(LocalDensity.current) { WindowInsets.ime.getBottom(this).toDp() }
    // (rows that gave nothing, and live TV)
    val rows = results.filter { it.items.isNotEmpty() && it.type !in LEFT_OUT }
    // A row whose addon hasn't answered after a few seconds goes, so a stuck one isn't a spinner for good
    var patient by remember(searched) { mutableStateOf(true) }
    LaunchedEffect(searched) { patient = true; delay(6000); patient = false }

    BrowseHost(page, { page = it }, stremio, layout, bottomSpace, onOpen) {
        Box(Modifier.fillMaxSize().background(Dx.Background)) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(top = statusBarTop() + 8.dp, bottom = maxOf(bottomSpace, ime + 16.dp) + 20.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                item(key = "head") {
                    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("Search", modifier = Modifier.padding(horizontal = layout.gutter), color = Dx.Text, style = MaterialTheme.typography.headlineMedium)
                        SearchField(query, layout, onChange = { query = it }, onSearch = { run(query) }, onClear = { query = ""; searched = "" })
                    }
                }
                if (searched.isEmpty()) {
                    if (recent.isNotEmpty()) item(key = "recent") {
                        androidx.compose.foundation.layout.Column(Modifier.padding(horizontal = layout.gutter), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Recent Searches", modifier = Modifier.weight(1f), color = Dx.Text, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Clear", color = Dx.Muted, style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.clickable { recent = emptyList(); prefs.edit().remove("recent").apply() }.padding(vertical = 4.dp, horizontal = 4.dp),
                                )
                            }
                            recent.forEach { r ->
                                Row(
                                    Modifier.fillMaxWidth().clickable { run(r) }.padding(vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Icon(Icons.Filled.History, contentDescription = null, tint = Dx.Muted, modifier = Modifier.size(20.dp))
                                    Text(r, color = Dx.Text, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                                }
                            }
                        }
                    }
                    // (nothing typed: the genres, as Apple TV's Search page has them)
                    item(key = "genres") {
                        GenresSection(stremio, layout, onGenre = { page = BrowsePage.Genre(it).encode() }, onChristmas = { page = BrowsePage.Christmas.encode() })
                    }
                } else {
                    rows.forEach { row ->
                        item(key = "row:${row.key}:${row.index}") {
                            BrowseShelf(row.heading, row.items, layout, onSeeAll = row.discover?.let { link -> { page = BrowsePage.All(link, row.heading).encode() } }, onOpen = onOpen)
                        }
                    }
                    if (rows.isEmpty()) item(key = "state") {
                        if (patient && (results.isEmpty() || results.any { it.state == Load.Loading })) BrowseSpinner()
                        else BrowseEmpty("No Results for \"$searched\"", "Check the spelling or try a new search.")
                    }
                }
            }
        }
    }
}

/** The box to type in: a rounded grey field with a magnifier, and a cross to clear it (iOS: .searchable) */
@Composable
private fun SearchField(query: String, layout: AppLayout, onChange: (String) -> Unit, onSearch: () -> Unit, onClear: () -> Unit) {
    val focus = remember { FocusRequester() }
    Row(
        Modifier.padding(horizontal = layout.gutter).fillMaxWidth().height(44.dp).clip(RoundedCornerShape(14.dp)).background(Color(0x1FFFFFFF)).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = Dx.Muted, modifier = Modifier.size(20.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text("Films, shows, people", color = Dx.Faint, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            BasicTextField(
                query, onChange,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Dx.Text),
                cursorBrush = SolidColor(Dx.Text),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            )
        }
        if (query.isNotEmpty()) Icon(
            Icons.Filled.Close, contentDescription = "Clear", tint = Dx.Muted,
            modifier = Modifier.size(20.dp).clip(androidx.compose.foundation.shape.CircleShape).clickable(onClick = onClear),
        )
    }
}
