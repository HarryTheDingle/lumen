package com.notdxbi.dxbi.tv.lumen

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.notdxbi.dxbi.tv.phone.Stremio
import com.notdxbi.dxbi.tv.phone.Title
import org.json.JSONObject

/**
 * Discover's Franchises, as on the iPad: the app carries a list of its own (assets/franchises.json, the iOS app's list:
 * every title in it was found at Cinemeta under its name and year), a row of wide cards on Discover, each opening its
 * page: its films in the order they came out, then its series.
 */
data class ListedTitle(val id: String, val name: String, val year: Int) {
    fun title(type: String) = Title(id = id, type = type, name = name, year = year.toString())
}

data class Franchise(val id: String, val name: String, val art: String, val films: List<ListedTitle>, val series: List<ListedTitle>) {
    val picture: String get() = "https://images.metahub.space/background/medium/$art/img"
}

object Franchises {
    @Volatile private var loaded: List<Franchise>? = null

    fun all(context: Context): List<Franchise> = loaded ?: synchronized(this) {
        loaded ?: runCatching {
            val root = JSONObject(context.assets.open("franchises.json").bufferedReader().use { it.readText() })
            val list = root.getJSONArray("franchises")
            fun titles(a: org.json.JSONArray?) = (0 until (a?.length() ?: 0)).map { a!!.getJSONObject(it) }.map { ListedTitle(it.getString("id"), it.getString("name"), it.optInt("year")) }
            (0 until list.length()).map { list.getJSONObject(it) }.map { Franchise(it.getString("id"), it.getString("name"), it.optString("art"), titles(it.optJSONArray("films")), titles(it.optJSONArray("series"))) }
        }.getOrDefault(emptyList()).also { loaded = it }
    }

    fun find(context: Context, id: String) = all(context).firstOrNull { it.id == id }
}

/** How far through a franchise this profile is, from the films of it in their library that are marked watched */
private fun note(f: Franchise, watched: Set<String>): String? {
    if (f.films.isEmpty()) return null
    val seen = f.films.count { it.id in watched }
    return when (seen) {
        0 -> "${f.films.size} films"
        f.films.size -> "All $seen watched"
        else -> "$seen of ${f.films.size} watched"
    }
}

/** Discover's Franchises row: a still from each franchise, darkened to the foot, with its name and how far through it they are */
internal fun androidx.compose.foundation.lazy.LazyListScope.franchisesItem(stremio: Stremio, nav: Nav, modifier: Modifier = Modifier) {
    item(key = "franchises") {
        val context = LocalContext.current
        val list = remember { Franchises.all(context) }
        val library by stremio.library.collectAsState()
        val watched = remember(library) { library.filter { it.watched }.map { it.id }.toSet() }
        if (list.isNotEmpty()) {
            Shelf("Franchises", modifier) {
                items(list, key = { it.id }) { f -> FranchiseCard(f, note(f, watched)) { nav.push(Page.Franchise(f.id, f.name)) } }
            }
        }
    }
}

@Composable
private fun FranchiseCard(f: Franchise, note: String?, onClick: () -> Unit) {
    val width = 270.dp
    FocusCard(onClick = onClick, corner = 16.dp, modifier = Modifier.size(width, width * 0.64f)) {
        Box(Modifier.fillMaxSize()) {
            Art(f.picture, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.3f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f))))
            Column(Modifier.align(Alignment.BottomStart).padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(f.name, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                note?.let { Text(it, fontSize = 12.sp, color = Color.White.copy(alpha = 0.75f), maxLines = 1) }
            }
        }
    }
}

/** A franchise's page: its films in the order they came out, then its series, as rows of posters */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun FranchisePage(stremio: Stremio, nav: Nav, id: String, name: String) {
    val context = LocalContext.current
    val franchise = remember(id) { Franchises.find(context, id) }
    val library by stremio.library.collectAsState()
    val watched = remember(library) { library.filter { it.watched }.map { it.id }.toSet() }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(150)
        runCatching { first.requestFocus() }
    }
    PageWindow(onBack = { nav.back() }, home = first) {
        Box(Modifier.fillMaxSize().background(Dx.Background)) {
            if (franchise != null) Art(franchise.picture, Modifier.fillMaxSize().background(Color.Black))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.35f), 0.38f to Dx.Background.copy(alpha = 0.94f), 0.5f to Dx.Background)))
            val state = rememberLazyListState()
            val scope = rememberCoroutineScope()
            val scroller = remember { PageScroller(state, scope) }
            CompositionLocalProvider(LocalBringIntoViewSpec provides CentredScrolling, LocalPageScroller provides scroller) {
                LazyColumn(
                    state = state,
                    modifier = Modifier.onGloballyPositioned { scroller.viewport = it },
                    contentPadding = PaddingValues(top = 48.dp, bottom = pageBottomPad()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item(key = "title") {
                        Column(Modifier.padding(horizontal = Dx.Gutter, vertical = 40.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(name, fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            franchise?.let { note(it, watched) }?.let { Text(it, fontSize = 18.sp, color = Color.White.copy(alpha = 0.75f)) }
                        }
                    }
                    if (franchise == null || (franchise.films.isEmpty() && franchise.series.isEmpty())) {
                        item(key = "empty") { Text("Nothing listed for this one.", fontSize = 16.sp, color = Dx.Muted, modifier = Modifier.padding(horizontal = Dx.Gutter)) }
                    }
                    if (franchise != null && franchise.films.isNotEmpty()) {
                        item(key = "films") {
                            Shelf("Films", Modifier.focusRequester(first)) {
                                items(franchise.films, key = { it.id }) { f -> PreviewPosterCard(f.title("movie").copy(watched = f.id in watched), onClick = { nav.open(f.title("movie")) }) }
                            }
                        }
                    }
                    if (franchise != null && franchise.series.isNotEmpty()) {
                        item(key = "series") {
                            Shelf("Series", if (franchise.films.isEmpty()) Modifier.focusRequester(first) else Modifier) {
                                items(franchise.series, key = { it.id }) { f -> PreviewPosterCard(f.title("series"), onClick = { nav.open(f.title("series")) }) }
                            }
                        }
                    }
                }
            }
        }
    }
}
