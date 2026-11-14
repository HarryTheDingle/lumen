package com.notdxbi.dxbi.tv.lumenphone

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notdxbi.dxbi.tv.phone.Stremio
import com.notdxbi.dxbi.tv.phone.Title
import com.notdxbi.dxbi.tv.phone.objects
import com.notdxbi.dxbi.tv.phone.text
import org.json.JSONObject

/** Their initials on grey, with their photo over it once it's there (a cast member's face, here and on a title's page) */
@Composable
fun Face(name: String, photo: String?, size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(Dx.Raised), contentAlignment = Alignment.Center) {
        Text(name.split(" ").mapNotNull { it.firstOrNull() }.take(2).joinToString(""), color = Dx.Text, fontSize = (size.value * 0.36f).sp, fontWeight = FontWeight.SemiBold)
        if (photo != null) Art(photo, Modifier.size(size))
    }
}

/**
 * Someone from a title's cast (iOS PersonView): their photo and name, who they are, a short biography that
 * opens out, then what they're known for and all their films and shows, each a poster that opens its page.
 * The data is TMDB's (a key in Lumen settings); without one the cast row searches for the name instead and
 * this page isn't reached.
 */
@Composable
fun PersonPage(stremio: Stremio, layout: AppLayout, page: Page.Person, bottomSpace: androidx.compose.ui.unit.Dp, onOpen: (Title) -> Unit, onBack: () -> Unit) {
    var person by remember(page.id) { mutableStateOf<JSONObject?>(null) }
    var loaded by remember(page.id) { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    LaunchedEffect(page.id) {
        person = stremio.person(page.id, page.name)
        loaded = true
    }
    val wide = !layout.compact
    Box(Modifier.fillMaxSize().background(Dx.Background)) {
        LazyColumn(contentPadding = PaddingValues(top = 64.dp, bottom = bottomSpace + 40.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            item {
                val size = if (wide) 200.dp else 128.dp
                val p = person
                Column(Modifier.padding(horizontal = layout.gutter), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(if (wide) 32.dp else 16.dp), verticalAlignment = if (wide) Alignment.Top else Alignment.CenterVertically) {
                        Face(page.name, p?.text("photo") ?: page.photo, size)
                        Column(Modifier.weight(1f).widthIn(max = Dx.Readable), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(p?.text("name") ?: page.name, color = Dx.Text, style = MaterialTheme.typography.headlineMedium)
                            facts(p).forEach { Text(it, color = Dx.Muted, style = MaterialTheme.typography.bodyMedium) }
                            if (wide) Bio(p?.text("bio"), more, 6, 420) { more = !more }
                        }
                    }
                    if (!wide) Bio(p?.text("bio"), more, 4, 200) { more = !more }
                }
            }
            val p = person
            if (p != null) {
                shelf("Known For", p, "known", layout, onOpen)
                shelf("Films", p, "films", layout, onOpen)
                shelf("TV Shows", p, "shows", layout, onOpen)
            } else item {
                if (loaded) Column(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Nothing to show for ${page.name} right now.", color = Dx.Muted)
                } else Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Dx.Text) }
            }
        }
        PageBack(onBack, Modifier.align(Alignment.TopStart))
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.shelf(name: String, person: JSONObject, key: String, layout: AppLayout, onOpen: (Title) -> Unit) {
    val titles = person.optJSONArray(key).objects().map { j ->
        Title(id = j.optString("id"), type = j.optString("type"), name = j.optString("name"), poster = j.text("poster"), year = j.text("year"))
    }
    if (titles.isEmpty()) return
    item(key = "shelf:$key") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeader(name, layout.gutter)
            LazyRow(contentPadding = PaddingValues(horizontal = layout.gutter), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(titles, key = { it.id }) { t ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        PosterCard(t, layout.poster, onClick = { onOpen(t) })
                        t.year?.let { Text(it, color = Dx.Muted, style = MaterialTheme.typography.labelMedium) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Bio(bio: String?, open: Boolean, lines: Int, longerThan: Int, toggle: () -> Unit) {
    if (bio == null) return
    Column(Modifier.clickable(onClick = toggle)) {
        Text(bio, color = Dx.Text, style = MaterialTheme.typography.bodyMedium, maxLines = if (open) Int.MAX_VALUE else lines, overflow = TextOverflow.Ellipsis)
        if (!open && bio.length > longerThan) Text("MORE", color = Dx.Muted, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
    }
}

/** What they do, when and where they were born (and died) */
private fun facts(p: JSONObject?): List<String> {
    p ?: return emptyList()
    val out = mutableListOf<String>()
    p.text("department")?.let { out += if (it == "Acting") "Actor" else it }
    val born = date(p.text("born"))
    val age = p.text("age")?.let { " ($it)" } ?: ""
    val place = p.text("place")
    if (born != null) {
        val died = date(p.text("died"))
        if (died != null) {
            out += "Born $born" + (place?.let { " in $it" } ?: "")
            out += "Died $died$age"
        } else out += "Born $born$age" + (place?.let { " in $it" } ?: "")
    } else if (place != null) out += "Born in $place"
    return out
}

private fun date(iso: String?): String? = runCatching {
    val parsed = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(iso ?: return null) ?: return null
    java.text.SimpleDateFormat("d MMMM yyyy", java.util.Locale.getDefault()).format(parsed)
}.getOrNull()
