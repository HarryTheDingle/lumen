package com.notdxbi.dxbi.tv.lumen

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.notdxbi.dxbi.tv.phone.ListRow
import com.notdxbi.dxbi.tv.phone.Title
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/**
 * What a profile likes, told once when it first opens (TasteSetup) so Home has suggestions before there's any
 * watching to go on: the genres they chose and a few titles they've enjoyed. Kept on this TV, one record for
 * each person (profile) of each account (as on the iPad). Once real recommendations exist they take Home's
 * place; the chosen titles only stand in until then.
 */
data class Taste(val genres: List<String> = emptyList(), val titles: List<Seed> = emptyList(), val skipped: Boolean = false) {
    /** A title picked as liked: enough to draw it and to find more like it */
    data class Seed(val id: String, val type: String, val name: String, val poster: String?, val genres: List<String>) {
        constructor(t: Title) : this(t.id, t.type, t.name, t.poster, t.genres)
    }

    val isEmpty: Boolean get() = genres.isEmpty() && titles.isEmpty()

    fun toJson(): String = JSONObject()
        .put("genres", JSONArray(genres))
        .put("skipped", skipped)
        .put("titles", JSONArray(titles.map { JSONObject().put("id", it.id).put("type", it.type).put("name", it.name).put("poster", it.poster).put("genres", JSONArray(it.genres)) }))
        .toString()

    companion object {
        fun fromJson(text: String?): Taste? = runCatching {
            val o = JSONObject(text ?: return null)
            Taste(
                genres = o.optJSONArray("genres")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
                titles = o.optJSONArray("titles")?.let { a ->
                    (0 until a.length()).map { i ->
                        val t = a.getJSONObject(i)
                        Seed(t.getString("id"), t.optString("type"), t.optString("name"), t.optString("poster").takeIf { it.isNotEmpty() && it != "null" }, t.optJSONArray("genres")?.let { g -> (0 until g.length()).map { g.getString(it) } }.orEmpty())
                    }
                }.orEmpty(),
                skipped = o.optBoolean("skipped"),
            )
        }.getOrNull()
    }
}

/** The current profile's taste and the Home rows made from it, and whether the setup is open */
class TasteState(context: Context, private val feed: DiscoverFeed, private val stremio: com.notdxbi.dxbi.tv.phone.Stremio) {
    private val prefs = context.getSharedPreferences("lumen-taste", Context.MODE_PRIVATE)
    var current by mutableStateOf<Taste?>(null)
        private set
    var rows by mutableStateOf<List<ListRow>>(emptyList())
        private set
    /** Whose it is (account and profile): Home asks only once this matches who's watching */
    var loadedFor by mutableStateOf<String?>(null)
        private set
    var tuning by mutableStateOf(false)
    private var key = ""
    private var viewerOf: String? = null

    private fun keyOf(account: String?, viewer: String?) = "taste.${account ?: "guest"}.${viewer ?: "main"}"

    suspend fun load(account: String?, viewer: String?) {
        key = keyOf(account, viewer)
        viewerOf = viewer
        current = Taste.fromJson(prefs.getString(key, null))
        rows = emptyList()
        loadedFor = key
        current?.takeIf { !it.isEmpty }?.let { rows = rowsFor(it) }
        // then the account's copy: the plugin syncs it between the account's devices (a copy only this TV has goes up)
        if (viewer == null) return
        val remote = runCatching { stremio.taste(viewer) }.getOrNull()?.let { Taste.fromJson(it.toString()) }
        if (key != keyOf(account, viewer)) return
        if (remote != null) {
            if (remote != current) {
                current = remote
                prefs.edit().putString(key, remote.toJson()).apply()
                rows = if (remote.isEmpty) emptyList() else rowsFor(remote)
            }
        } else current?.let { runCatching { stremio.setTaste(viewer, JSONObject(it.toJson())) } }
    }

    suspend fun finish(taste: Taste) {
        current = taste
        prefs.edit().putString(key, taste.toJson()).apply()
        viewerOf?.let { runCatching { stremio.setTaste(it, JSONObject(taste.toJson())) } }
        tuning = false
        rows = if (taste.isEmpty) emptyList() else rowsFor(taste)
    }

    /**
     * Picked for You: popular films and series in the chosen genres, taking turns; then Because you liked … for up
     * to three chosen titles, popular in that title's own genre (Cinemeta has no "similar to"), the titles
     * chosen left out.
     */
    private suspend fun rowsFor(t: Taste): List<ListRow> {
        val chosen = t.titles.map { it.id }.toSet()
        val out = ArrayList<ListRow>()
        val genres = t.genres.mapNotNull { n -> GENRES.firstOrNull { it.name == n } }.filter { it.cinemeta.isNotEmpty() && it.name != "Anime" }
        if (genres.isNotEmpty()) {
            val lists = ArrayList<List<Title>>()
            for (g in genres.take(4)) for (key in listOfNotNull(g.cinemeta, g.also)) {
                lists.add(feed.popular("movie", key).map { it.title })
                lists.add(feed.popular("series", key).map { it.title })
            }
            val seen = HashSet<String>(chosen)
            val picked = ArrayList<Title>()
            for (i in 0 until (lists.maxOfOrNull { it.size } ?: 0)) for (l in lists) if (i < l.size && seen.add(l[i].id)) picked.add(l[i])
            if (picked.size >= 4) out.add(ListRow("Picked for You", picked.take(30)))
        }
        for (seed in t.titles.take(3)) {
            val genre = seed.genres.firstOrNull() ?: continue
            val list = feed.popular(if (seed.type == "movie") "movie" else "series", genre).map { it.title }.filter { it.id !in chosen }.take(25)
            if (list.size >= 4) out.add(ListRow("Because you liked ${seed.name}", list))
        }
        return out
    }

    /** Candidates for the second step: films and series in the chosen genres, mixed (all popular ones if none) */
    suspend fun candidates(genreNames: List<String>): List<Title> {
        val lists = ArrayList<List<Title>>()
        val chosen = genreNames.mapNotNull { n -> GENRES.firstOrNull { it.name == n } }.filter { it.cinemeta.isNotEmpty() }
        if (chosen.isEmpty()) {
            lists.add(feed.popular("movie").map { it.title })
            lists.add(feed.popular("series").map { it.title })
        } else {
            for (g in chosen.take(4)) {
                lists.add(feed.popular("movie", g.cinemeta).take(10).map { it.title })
                lists.add(feed.popular("series", g.cinemeta).take(10).map { it.title })
            }
        }
        val seen = HashSet<String>()
        val out = ArrayList<Title>()
        for (i in 0 until (lists.maxOfOrNull { it.size } ?: 0)) for (l in lists) {
            if (i < l.size && l[i].art != null && l[i].genres.isNotEmpty() && seen.add(l[i].id)) out.add(l[i])
        }
        return out.take(24)
    }
}

val LocalTaste = staticCompositionLocalOf<TasteState?> { null }

/**
 * Tune Suggestions, for the remote: the first time a profile opens with nothing watched (and from Settings whenever),
 * two choices: the genres they like, then a few titles they've enjoyed. OK chooses or clears one; Skip and Back leave it.
 */
@Composable
fun TasteSetup(state: TasteState, who: String?, onDone: suspend (Taste) -> Unit) {
    var step by remember { mutableStateOf(0) }
    val genres = remember { mutableStateListOf<String>().also { l -> state.current?.let { l.addAll(it.genres) } } }
    val picked = remember { mutableStateListOf<Taste.Seed>().also { l -> state.current?.let { l.addAll(it.titles) } } }
    var candidates by remember { mutableStateOf<List<Title>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var finishing by remember { mutableStateOf<Taste?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(step, candidates.size, loading) {
        delay(120)
        runCatching { first.requestFocus() }
    }
    LaunchedEffect(finishing) { finishing?.let { onDone(it) } }

    fun finish(skip: Boolean) {
        finishing = Taste(genres.toList(), picked.toList(), skipped = skip && genres.isEmpty() && picked.isEmpty())
    }
    fun next() {
        step = 1
        loading = true
    }
    LaunchedEffect(step) {
        if (step == 1) {
            candidates = state.candidates(genres.toList())
            loading = false
        }
    }

    PageWindow(onBack = { if (step == 1) step = 0 else finish(skip = true) }) {
        Column(Modifier.fillMaxSize().background(Dx.Background).padding(top = 40.dp, bottom = 24.dp)) {
            Column(Modifier.padding(horizontal = Dx.Gutter), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${if (who.isNullOrBlank()) "" else "$who · "}${step + 1} of 2", fontSize = 14.sp, color = Dx.Faint)
                Text(if (step == 0) "What do you like to watch?" else "Pick a few you've enjoyed", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text(if (step == 0) "Choose any. You can change this later in Settings." else "Three or more is plenty.", fontSize = 16.sp, color = Dx.Muted)
            }
            Spacer(Modifier.height(10.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (step == 0) {
                    val list = GENRES.filter { it.cinemeta.isNotEmpty() && it.name != "Anime" }
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(210.dp),
                        contentPadding = PaddingValues(horizontal = Dx.Gutter, vertical = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        items(list, key = { it.name }) { g ->
                            val on = g.name in genres
                            val mod = if (g === list.first()) Modifier.focusRequester(first) else Modifier
                            FocusCard(onClick = { if (on) genres.remove(g.name) else genres.add(g.name) }, modifier = mod.fillMaxWidth().aspectRatio(16f / 9f)) {
                                Box(Modifier.fillMaxSize()) {
                                    androidx.compose.foundation.Image(painterResource(genreDrawable(g.name)), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                    Text(g.name, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 9.dp))
                                    if (on) Tick(Modifier.align(Alignment.TopEnd))
                                }
                            }
                        }
                    }
                } else if (loading) {
                    Text("Finding titles…", fontSize = 16.sp, color = Dx.Muted, modifier = Modifier.padding(horizontal = Dx.Gutter, vertical = 30.dp))
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(140.dp),
                        contentPadding = PaddingValues(horizontal = Dx.Gutter, vertical = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        items(candidates, key = { it.id }) { t ->
                            val on = picked.any { it.id == t.id }
                            val mod = if (t.id == candidates.first().id) Modifier.focusRequester(first) else Modifier
                            FocusCard(onClick = { if (on) picked.removeAll { it.id == t.id } else picked.add(Taste.Seed(t)) }, modifier = mod.fillMaxWidth().aspectRatio(2f / 3f)) {
                                Box(Modifier.fillMaxSize()) {
                                    Art(t.art, Modifier.fillMaxSize())
                                    if (on) Tick(Modifier.align(Alignment.TopEnd))
                                }
                            }
                        }
                    }
                }
            }
            Row(Modifier.padding(horizontal = Dx.Gutter), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { if (step == 0) finish(skip = true) else step = 0 },
                    colors = ButtonDefaults.colors(containerColor = Color.White.copy(alpha = 0.12f), contentColor = Color.White, focusedContainerColor = Color.White, focusedContentColor = Color.Black),
                ) { Text(if (step == 0) "Skip" else "Back", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                Button(
                    onClick = { if (step == 0) next() else finish(skip = false) },
                    colors = ButtonDefaults.colors(containerColor = Dx.Accent.copy(alpha = 0.8f), contentColor = Dx.OnAccent, focusedContainerColor = Dx.Accent, focusedContentColor = Dx.OnAccent),
                ) { Text(if (step == 0) "Next" else "Start Watching", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                if (step == 0 && genres.isEmpty()) Text("Skip if you'd rather not", fontSize = 14.sp, color = Dx.Faint)
            }
        }
    }
}

@Composable
private fun Tick(modifier: Modifier) {
    Icon(
        Icons.Filled.CheckCircle,
        contentDescription = "Chosen",
        tint = Color.White,
        modifier = modifier.padding(6.dp).size(22.dp).background(Color.Black, CircleShape),
    )
}
