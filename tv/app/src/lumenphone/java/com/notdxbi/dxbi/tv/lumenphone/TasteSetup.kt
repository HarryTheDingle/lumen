package com.notdxbi.dxbi.tv.lumenphone

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.notdxbi.dxbi.tv.R
import com.notdxbi.dxbi.tv.phone.ListRow
import com.notdxbi.dxbi.tv.phone.Stremio
import com.notdxbi.dxbi.tv.phone.Title
import com.notdxbi.dxbi.tv.phone.objects
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * What a profile likes, told once when it first opens (TasteSetup) so Home has suggestions before there's any
 * watching to go on: the genres they chose, and a few titles they've enjoyed. Kept on this phone, one record for
 * each person (profile) of each account, and with the account too (the plugin syncs it between its devices): the
 * same record iOS and the TV keep (ios/Dxbi/Engine/Taste.swift). Once real recommendations exist they take Home's
 * place; the chosen titles only stand in until then.
 */
data class Taste(val genres: List<String> = emptyList(), val titles: List<Seed> = emptyList(), val skipped: Boolean = false) {
    /** A title picked as liked: enough to draw it and to find more like it */
    data class Seed(val id: String, val type: String, val name: String, val poster: String?, val genres: List<String>) {
        constructor(t: Title) : this(t.id, t.type, t.name, t.poster, t.genres)
    }

    val isEmpty: Boolean get() = genres.isEmpty() && titles.isEmpty()

    fun toJson(): JSONObject = JSONObject()
        .put("genres", JSONArray(genres))
        .put("skipped", skipped)
        .put("titles", JSONArray(titles.map { JSONObject().put("id", it.id).put("type", it.type).put("name", it.name).put("poster", it.poster).put("genres", JSONArray(it.genres)) }))

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

/** A genre card in the setup: its name, how Cinemeta spells it (and a second one shown with it), and its picture */
private class TasteGenre(val name: String, val key: String, val picture: Int, val also: String? = null)

/** iOS's Genre.all without Anime (it needs its own addon), in the same order */
private val TASTE_GENRES = listOf(
    TasteGenre("Action", "Action", R.drawable.genre_action),
    TasteGenre("Comedy", "Comedy", R.drawable.genre_comedy),
    TasteGenre("Crime", "Crime", R.drawable.genre_crime),
    TasteGenre("Drama", "Drama", R.drawable.genre_drama),
    TasteGenre("Documentary", "Documentary", R.drawable.genre_documentary),
    TasteGenre("Animation", "Animation", R.drawable.genre_animation),
    TasteGenre("Family", "Family", R.drawable.genre_family),
    TasteGenre("Sci-Fi", "Sci-Fi", R.drawable.genre_scifi),
    TasteGenre("Romance", "Romance", R.drawable.genre_romance),
    TasteGenre("Thriller & Horror", "Thriller", R.drawable.genre_thrillerhorror, also = "Horror"),
)

/** Cinemeta's popular titles straight from the internet (not through Stremio's page), kept for a few hours */
private object TasteFeed {
    private val memory = ConcurrentHashMap<String, Pair<Long, List<Title>>>()

    suspend fun popular(type: String, genre: String? = null): List<Title> = withContext(Dispatchers.IO) {
        val extra = genre?.let { "/genre=" + URLEncoder.encode(it, "UTF-8").replace("+", "%20") } ?: ""
        val url = "https://v3-cinemeta.strem.io/catalog/$type/top$extra.json"
        memory[url]?.let { (at, list) -> if (System.currentTimeMillis() - at < 6 * 3_600_000L) return@withContext list }
        val body = runCatching {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 12_000
            c.readTimeout = 15_000
            try { if (c.responseCode in 200..299) c.inputStream.bufferedReader().use { it.readText() } else null } finally { c.disconnect() }
        }.getOrNull() ?: return@withContext memory[url]?.second.orEmpty()
        // (the first sixty are plenty: a genre's catalog is hundreds)
        val list = runCatching { JSONObject(body).optJSONArray("metas").objects().take(60).map(Title::from) }.getOrDefault(emptyList())
        memory[url] = System.currentTimeMillis() to list
        list
    }
}

/**
 * The current profile's taste and the rows Home can show from it, and whether the setup is open. Home reads
 * [rows] ("Picked for You", "Because you liked …") while the recommender has nothing watched to go on; the setup
 * opens when [tuning] is true: set by the root when a profile has nothing watched and no taste yet (as iOS's
 * offerTuning does), and by Profile → Tune Suggestions.
 */
class TasteState(context: Context, private val stremio: Stremio) {
    private val prefs = context.getSharedPreferences("lumen-taste", Context.MODE_PRIVATE)
    var current by mutableStateOf<Taste?>(null)
        private set
    var rows by mutableStateOf<List<ListRow>>(emptyList())
        private set
    /** Whose it is (account and profile): the root asks only once this matches who's watching */
    var loadedFor by mutableStateOf<String?>(null)
        private set
    var tuning by mutableStateOf(false)
    private var key = ""
    private var viewerOf: String? = null

    private fun keyOf(account: String?, viewer: String?) = "taste.${account ?: "guest"}.${viewer ?: "main"}"

    /** This phone's copy first (Home has it at once), then the account's: a copy only this phone has goes up to the account */
    suspend fun load(account: String?, viewer: String?) {
        key = keyOf(account, viewer)
        viewerOf = viewer
        current = Taste.fromJson(prefs.getString(key, null))
        rows = emptyList()
        loadedFor = key
        current?.takeIf { !it.isEmpty }?.let { rows = rowsFor(it) }
        if (viewer == null) return
        val remote = runCatching { stremio.taste(viewer) }.getOrNull()?.let { Taste.fromJson(it.toString()) }
        // (someone else is watching by now)
        if (key != keyOf(account, viewer)) return
        if (remote != null) {
            if (remote != current) {
                current = remote
                prefs.edit().putString(key, remote.toJson().toString()).apply()
                rows = if (remote.isEmpty) emptyList() else rowsFor(remote)
            }
        } else current?.let { runCatching { stremio.setTaste(viewer, it.toJson()) } }
    }

    suspend fun finish(taste: Taste) {
        current = taste
        prefs.edit().putString(key, taste.toJson().toString()).apply()
        viewerOf?.let { runCatching { stremio.setTaste(it, taste.toJson()) } }
        tuning = false
        rows = if (taste.isEmpty) emptyList() else rowsFor(taste)
    }

    /**
     * Picked for You: popular films and series in the chosen genres, taking turns; then Because you liked … for up
     * to three chosen titles, popular in that title's own genre (Cinemeta has no "similar to"), the titles
     * chosen left out. (iOS's Stremio.tasteRows)
     */
    private suspend fun rowsFor(t: Taste): List<ListRow> {
        val chosen = t.titles.map { it.id }.toSet()
        val out = ArrayList<ListRow>()
        val genres = t.genres.mapNotNull { n -> TASTE_GENRES.firstOrNull { it.name == n } }
        if (genres.isNotEmpty()) {
            val lists = ArrayList<List<Title>>()
            for (g in genres.take(4)) for (k in listOfNotNull(g.key, g.also)) {
                lists.add(TasteFeed.popular("movie", k))
                lists.add(TasteFeed.popular("series", k))
            }
            val seen = HashSet<String>(chosen)
            val picked = ArrayList<Title>()
            for (i in 0 until (lists.maxOfOrNull { it.size } ?: 0)) for (l in lists) if (i < l.size && seen.add(l[i].id)) picked.add(l[i])
            if (picked.size >= 4) out.add(ListRow("Picked for You", picked.take(30)))
        }
        for (seed in t.titles.take(3)) {
            val genre = seed.genres.firstOrNull() ?: continue
            val list = TasteFeed.popular(if (seed.type == "movie") "movie" else "series", genre).filter { it.id !in chosen }.take(25)
            if (list.size >= 4) out.add(ListRow("Because you liked ${seed.name}", list))
        }
        return out
    }

    /** Films and series in the chosen genres, mixed (all popular ones if none were chosen): the second step's pictures */
    suspend fun candidates(genreNames: List<String>): List<Title> {
        val lists = ArrayList<List<Title>>()
        val chosen = genreNames.mapNotNull { n -> TASTE_GENRES.firstOrNull { it.name == n } }
        if (chosen.isEmpty()) {
            lists.add(TasteFeed.popular("movie"))
            lists.add(TasteFeed.popular("series"))
        } else {
            for (g in chosen.take(4)) {
                lists.add(TasteFeed.popular("movie", g.key).take(10))
                lists.add(TasteFeed.popular("series", g.key).take(10))
            }
        }
        val seen = HashSet<String>()
        val out = ArrayList<Title>()
        for (i in 0 until (lists.maxOfOrNull { it.size } ?: 0)) for (l in lists) {
            if (i < l.size && (l[i].poster ?: l[i].background) != null && l[i].genres.isNotEmpty() && seen.add(l[i].id)) out.add(l[i])
        }
        return out.take(24)
    }
}

/** The root's [TasteState], for the screens under it (Profile's Tune Suggestions; Home's stand-in rows) */
val LocalTaste = compositionLocalOf<TasteState?> { null }

/**
 * Tune Suggestions (iOS TasteView): two quick choices so Home has suggestions on day one: the genres they like
 * (pictures to tap), then a few titles they've enjoyed. Both can be skipped. A white ring and a tick mark what's
 * chosen. Full screen, over everything.
 */
@Composable
fun TasteSetup(state: TasteState, who: String?, layout: AppLayout, onDone: suspend (Taste) -> Unit) {
    var step by remember { mutableStateOf(0) }
    val genres = remember { mutableStateListOf<String>().also { l -> state.current?.let { l.addAll(it.genres) } } }
    val picked = remember { mutableStateListOf<Taste.Seed>().also { l -> state.current?.let { l.addAll(it.titles) } } }
    var candidates by remember { mutableStateOf<List<Title>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var finishing by remember { mutableStateOf<Taste?>(null) }
    LaunchedEffect(finishing) { finishing?.let { onDone(it) } }

    fun finish(skipped: Boolean) {
        if (finishing != null) return
        finishing = Taste(genres.toList(), picked.toList(), skipped = skipped && genres.isEmpty() && picked.isEmpty())
    }
    LaunchedEffect(step) {
        if (step == 1) {
            loading = true
            candidates = state.candidates(genres.toList())
            loading = false
        }
    }
    // Back: the step before, then out (as Skip)
    BackHandler { if (step == 1) step = 0 else finish(skipped = true) }

    Column(Modifier.fillMaxSize().background(Dx.Background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = layout.gutter).padding(top = 28.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("${if (who.isNullOrBlank()) "" else "$who · "}${step + 1} of 2", color = Dx.Faint, style = MaterialTheme.typography.bodySmall)
            Text(if (step == 0) "What do you like to watch?" else "Pick a few you've enjoyed", color = Dx.Text, style = MaterialTheme.typography.headlineMedium)
            Text(if (step == 0) "Choose any. You can change this later in Profile." else "Three or more is plenty.", color = Dx.Muted, style = MaterialTheme.typography.bodyMedium)
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            if (step == 0) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(if (layout.compact) 100.dp else 150.dp),
                    modifier = Modifier.widthIn(max = 900.dp),
                    contentPadding = PaddingValues(horizontal = layout.gutter, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(TASTE_GENRES, key = { it.name }) { g ->
                        val on = g.name in genres
                        TasteCard(on, dimmed = genres.isNotEmpty() && !on, corner = 14.dp, label = g.name, onClick = { if (on) genres.remove(g.name) else genres.add(g.name) }) {
                            Image(painterResource(g.picture), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            // (so the name reads on any picture)
                            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)))))
                            Text(g.name, color = Dx.Text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.BottomStart).padding(10.dp))
                        }
                    }
                }
            } else if (loading && candidates.isEmpty()) {
                CircularProgressIndicator(color = Dx.Text, modifier = Modifier.padding(top = 60.dp))
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(if (layout.compact) 100.dp else 140.dp),
                    modifier = Modifier.widthIn(max = 900.dp),
                    contentPadding = PaddingValues(horizontal = layout.gutter, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(candidates, key = { it.id }) { t ->
                        val on = picked.any { it.id == t.id }
                        TasteCard(on, dimmed = picked.isNotEmpty() && !on, corner = 12.dp, label = t.name, onClick = { if (on) picked.removeAll { it.id == t.id } else picked.add(Taste.Seed(t)) }) {
                            Art(t.poster ?: t.background, Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = layout.gutter - 6.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ProfileTextButton(if (step == 0) "Skip" else "Back") { if (step == 0) finish(skipped = true) else step = 0 }
            Box(Modifier.weight(1f))
            // (Apple's one white button: the way on)
            val enabled = !(step == 0 && genres.isEmpty())
            Box(
                Modifier.clip(CircleShape).background(if (enabled) Dx.Selected else Dx.Line)
                    .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = null) { if (step == 0) step = 1 else finish(skipped = false) }
                    .padding(horizontal = 26.dp, vertical = 12.dp),
            ) {
                Text(if (step == 0) "Next" else "Start Watching", color = if (enabled) Dx.OnSelected else Dx.Faint, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** A tappable picture (2:3) that's white-ringed with a tick when chosen, and dimmed when others are and it isn't */
@Composable
private fun TasteCard(on: Boolean, dimmed: Boolean, corner: androidx.compose.ui.unit.Dp, label: String, onClick: () -> Unit, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    val shape = RoundedCornerShape(corner)
    Box(
        Modifier.fillMaxWidth().aspectRatio(2f / 3f).alpha(if (dimmed) 0.6f else 1f).clip(shape)
            .border(BorderStroke(if (on) 3.dp else 1.dp, if (on) Dx.Selected else Dx.Line), shape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = label, onClick = onClick),
    ) {
        content()
        if (on) Icon(Icons.Filled.CheckCircle, contentDescription = "Chosen", tint = Dx.Selected, modifier = Modifier.align(Alignment.TopEnd).padding(7.dp).size(24.dp).background(Dx.OnSelected, CircleShape))
    }
}
