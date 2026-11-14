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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
 * poster that opens its page. The data is TMDB's (a key in Lumen settings); without one the cast row
 * searches for the name instead and this page isn't reached.
 */
@Composable
fun PersonPage(stremio: Stremio, nav: Nav, page: Page.Person) {
    var person by remember { mutableStateOf<JSONObject?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var moreText by remember { mutableStateOf(false) }
    LaunchedEffect(page.id) {
        person = stremio.person(page.id, page.name)
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
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            // (as a title's page: what they're best known for, blurred, under the whole page)
            val known = titles("known").firstOrNull() ?: titles("films").firstOrNull() ?: titles("shows").firstOrNull()
            if (known != null) BlurredArt(known.backdrop, Modifier.fillMaxSize(), dim = 0.55f)
            LazyColumn(contentPadding = PaddingValues(top = 56.dp, bottom = 60.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
                item(key = "header") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = Dx.Gutter), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                        Box(Modifier.size(190.dp).clip(CircleShape).background(Color(0xFF444444)), contentAlignment = Alignment.Center) {
                            Text(page.name.split(' ').mapNotNull { it.firstOrNull() }.take(2).joinToString(""), fontSize = 60.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                            (p?.text("photo") ?: page.photo)?.let {
                                AsyncImage(model = it, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(p?.text("name") ?: page.name, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            facts(p).forEach { Text(it, fontSize = 15.sp, color = Dx.Muted) }
                            when {
                                !loaded -> Text("Loading…", fontSize = 15.sp, color = Dx.Muted)
                                p == null -> {
                                    Text("Nothing to show for ${page.name} right now.", fontSize = 15.sp, color = Dx.Muted)
                                    Pill("Search for ${page.name}", selected = false, onClick = { nav.search(page.name) }, modifier = Modifier.focusRequester(first))
                                }
                            }
                            if (bio != null) {
                                Spacer(Modifier.height(4.dp))
                                // (OK opens the whole biography out, and again closes it)
                                androidx.tv.material3.Surface(
                                    onClick = { moreText = !moreText },
                                    modifier = Modifier.focusRequester(first).width(760.dp),
                                    shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)),
                                    colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                                        containerColor = Color.Transparent,
                                        contentColor = Color.White.copy(alpha = 0.85f),
                                        focusedContainerColor = Color.White,
                                        focusedContentColor = Color.Black,
                                    ),
                                    scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
                                ) {
                                    Text(
                                        bio,
                                        fontSize = 14.sp,
                                        lineHeight = 20.sp,
                                        maxLines = if (moreText) Int.MAX_VALUE else 4,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(12.dp),
                                    )
                                }
                            }
                        }
                    }
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
        val date = iso?.let { runCatching { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(it) }.getOrNull() } ?: return null
        return java.text.SimpleDateFormat("d MMMM yyyy", java.util.Locale.getDefault()).format(date)
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
