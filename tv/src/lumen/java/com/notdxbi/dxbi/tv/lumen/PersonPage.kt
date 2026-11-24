package com.notdxbi.dxbi.tv.lumen

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.notdxbi.dxbi.tv.phone.Stremio
import com.notdxbi.dxbi.tv.phone.Title
import com.notdxbi.dxbi.tv.phone.objects
import com.notdxbi.dxbi.tv.phone.text
import org.json.JSONObject

/**
 * Someone from a title's cast, the Apple TV way (as the iPad's PersonView.swift): their photo and name, who
 * they are, a biography that opens out, then what they're known for and all their films and shows, each a
 * poster that opens its page. The data is TMDB's (Tmdb.kt); when nothing's found it still opens, with their name
 * and initials and a short message, never the search.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PersonPage(stremio: Stremio, nav: Nav, page: Page.Person) {
    var person by remember { mutableStateOf<JSONObject?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var moreText by remember { mutableStateOf(false) }
    LaunchedEffect(page.id) {
        person = Tmdb.person(page.id, page.name)  // (by id, else by name; null: nothing found)
        loaded = true
    }
    val first = remember { FocusRequester() }
    val p = person
    val bio = p?.text("bio")
    // the remote starts on the biography's button (or the first poster, when there's none)
    LaunchedEffect(loaded) {
        if (loaded) {
            kotlinx.coroutines.delay(120)
            runCatching { first.requestFocus() }
        }
    }

    fun titles(key: String): List<Title> = p?.optJSONArray(key).objects().map {
        Title(id = it.optString("id"), type = it.optString("type"), name = it.optString("name"), poster = it.text("poster"), year = it.text("year"))
    }
    val rows = listOf("Known For" to titles("known"), "Films" to titles("films"), "TV Shows" to titles("shows")).filter { it.second.isNotEmpty() }
    val firstRow = rows.firstOrNull()?.first

    PageWindow(onBack = { nav.back() }, home = first) {
        Box(Modifier.fillMaxSize().background(Dx.Background)) {
            // (as a title's page: what they're best known for, blurred, under the whole page)
            val known = titles("known").firstOrNull() ?: titles("films").firstOrNull() ?: titles("shows").firstOrNull()
            if (known != null) BlurredArt(known.backdrop, Modifier.fillMaxSize(), dim = 0.55f)
            // (the page moves as every other does: the row the remote goes into glides to the middle in one move; with
            // Compose's own scrolling it chased the card as it lifted and settled in two steps)
            PageColumn(contentPadding = PaddingValues(top = Dx.Gutter, bottom = pageBottomPad()), verticalArrangement = Arrangement.spacedBy(26.dp)) {
                item(key = "header") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = Dx.Gutter), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                        val photo = p?.text("photo") ?: page.photo
                        var waiting by remember(photo) { mutableStateOf(photo != null || !loaded) }
                        var failed by remember(photo) { mutableStateOf(false) }
                        Box(Modifier.size(190.dp).clip(CircleShape).background(Dx.Raised).skeleton(waiting && !failed, CircleShape), contentAlignment = Alignment.Center) {
                            if (failed || (photo == null && loaded)) Text(page.name.split(' ').mapNotNull { it.firstOrNull() }.take(2).joinToString(""), fontSize = 60.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                            photo?.let {
                                AsyncImage(
                                    model = it, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                    onSuccess = { waiting = false }, onError = { waiting = false; failed = true },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(p?.text("name") ?: page.name, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            facts(p).forEach { Text(it, fontSize = 15.sp, color = Dx.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                            when {
                                // (skeleton lines while it loads, as the rest of the app)
                                !loaded -> {
                                    Box(Modifier.size(200.dp, 14.dp).skeleton(shape = RoundedCornerShape(Dx.RCard)))
                                    Spacer(Modifier.height(4.dp))
                                    repeat(3) { Box(Modifier.then(if (it == 2) Modifier.width(420.dp) else Modifier.fillMaxWidth().widthIn(max = 760.dp)).height(14.dp).skeleton(shape = RoundedCornerShape(Dx.RCard))) }
                                }
                                p == null -> Text("No details found for ${page.name}.", fontSize = 15.sp, color = Dx.Muted)
                            }
                            if (bio != null) {
                                Spacer(Modifier.height(4.dp))
                                // (OK opens the whole biography out, and again closes it)
                                androidx.tv.material3.Surface(
                                    onClick = { moreText = !moreText },
                                    modifier = Modifier.focusRequester(first).fillMaxWidth().widthIn(max = 760.dp),
                                    // (the same see-through glass box as a title page's About, brightening a little when chosen)
                                    shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(Dx.RPanel)),
                                    colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                                        containerColor = Glass.Fill,
                                        contentColor = Color.White,
                                        focusedContainerColor = Color.White.copy(alpha = 0.32f),
                                        focusedContentColor = Color.White,
                                    ),
                                    border = androidx.tv.material3.ClickableSurfaceDefaults.border(border = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(1.dp, Glass.Edge), shape = androidx.compose.foundation.shape.RoundedCornerShape(Dx.RPanel))),
                                    scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = Dx.FocusRow),
                                ) {
                                    Text(
                                        bio,
                                        fontSize = 14.sp,
                                        lineHeight = 20.sp,
                                        color = Color.White.copy(alpha = 0.9f),
                                        maxLines = if (moreText) Int.MAX_VALUE else 4,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                // (while it loads, the rows are skeletons too)
                if (!loaded) {
                    item(key = "loading") { LoadingShelf("Known For") }
                    item(key = "loading2") { LoadingShelf("Films") }
                }
                rows.forEach { (name, list) ->
                    item(key = name) {
                        // (with no biography to start on, the remote starts on the first row)
                        Shelf(name, if (name == firstRow && bio == null && p != null) Modifier.focusRequester(first) else Modifier) {
                            items(list, key = { "$name-${it.id}" }) { t ->
                                PreviewPosterCard(t, onClick = { nav.open(t) })
                            }
                        }
                    }
                }
            }
        }
    }
}

/** What they do, when and where they were born (and died) */
private fun facts(p: JSONObject?): List<String> {
    p ?: return emptyList()
    fun day(iso: String?): String? {
        val date = iso?.let(com.notdxbi.dxbi.tv.phone.Dates::parseLocalDay) ?: return null
        return com.notdxbi.dxbi.tv.phone.Dates.formatLongDay(date)
    }
    val out = mutableListOf<String>()
    p.text("department")?.let { out += if (it == "Acting") "Actor" else it }
    val place = p.text("place")?.let { " in $it" } ?: ""
    val age = if (p.has("age") && !p.isNull("age")) " (${p.optInt("age")})" else ""
    val born = day(p.text("born"))
    val died = day(p.text("died"))
    when {
        born != null && died != null -> { out += "Born $born$place"; out += "Died $died$age" }
        born != null -> out += "Born $born$age$place"
        p.text("place") != null -> out += "Born$place"
    }
    return out
}
