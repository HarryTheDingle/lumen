package com.notdxbi.dxbi.tv.lumen

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.ui.draw.drawWithContent
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
import androidx.compose.ui.graphics.graphicsLayer
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

    /**
     * Candidates for the second step: well-known films and series from every decade, in the chosen genres (TMDB's most
     * popular and best loved, with good posters), so what's picked tells us something. Without a TMDB key, or if it
     * doesn't answer, Cinemeta's popular ones in those genres, mixed (all popular ones if none chosen)
     */
    suspend fun candidates(genreNames: List<String>): List<Title> {
        val chosen = genreNames.mapNotNull { n -> GENRES.firstOrNull { it.name == n } }.filter { it.cinemeta.isNotEmpty() }
        val wanted = chosen.map { it.cinemeta } + chosen.mapNotNull { it.also }
        // (TMDB's ids for each of our genres: a series has no Romance or Horror of its own, so those add films only)
        val movieIds = chosen.take(4).map { g -> TMDB_MOVIE[g.name].orEmpty() }.filter { it.isNotEmpty() }
        val seriesIds = chosen.take(4).map { g -> TMDB_SERIES[g.name].orEmpty() }.filter { it.isNotEmpty() }
        val tmdb = if (chosen.isNotEmpty() && movieIds.isEmpty() && seriesIds.isEmpty()) emptyList() else runCatching { Tmdb.picks(movieIds, seriesIds, 20) }.getOrDefault(emptyList())
        if (tmdb.size >= 10) {
            // (a chosen genre of the title first: "Because you liked" looks up the first, and one they chose says more)
            return tmdb.map { t -> t.copy(genres = t.genres.sortedByDescending { it in wanted }) }
        }
        val lists = ArrayList<List<Title>>()
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
        return out.take(20)
    }

    private companion object {
        val TMDB_MOVIE = mapOf(
            "Action" to listOf(28), "Animation" to listOf(16), "Comedy" to listOf(35), "Crime" to listOf(80), "Documentary" to listOf(99),
            "Drama" to listOf(18), "Family" to listOf(10751), "Romance" to listOf(10749), "Sci-Fi" to listOf(878), "Thriller & Horror" to listOf(53, 27),
        )
        val TMDB_SERIES = mapOf(
            "Action" to listOf(10759), "Animation" to listOf(16), "Comedy" to listOf(35), "Crime" to listOf(80), "Documentary" to listOf(99),
            "Drama" to listOf(18), "Family" to listOf(10751), "Sci-Fi" to listOf(10765), "Thriller & Horror" to listOf(9648, 80),
        )
    }
}

val LocalTaste = staticCompositionLocalOf<TasteState?> { null }

/**
 * Tune Suggestions, for the remote: the first time a profile opens with nothing watched (and from Settings whenever),
 * two choices: the genres they like, then a few titles they've enjoyed. OK chooses or clears one; Skip and Back leave it.
 * Everything here moves slowly and softly (about half a second, no snaps): the steps drift across into each other, a
 * chosen tile settles into its tint and tick, and the progress and buttons come in a moment after the page.
 */
@Composable
fun TasteSetup(state: TasteState, who: String?, onDone: suspend (Taste) -> Unit) {
    var step by remember { mutableStateOf(0) }
    val genres = remember { mutableStateListOf<String>().also { l -> state.current?.let { l.addAll(it.genres) } } }
    val picked = remember { mutableStateListOf<Taste.Seed>().also { l -> state.current?.let { l.addAll(it.titles) } } }
    var candidates by remember { mutableStateOf<List<Title>>(emptyList()) }
    // (the genres the candidates are for: going Back and forward again with the same ones doesn't fetch them again)
    var candidatesFor by remember { mutableStateOf<List<String>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var finishing by remember { mutableStateOf<Taste?>(null) }
    // (one for each step: during the cross-fade both steps are on screen, and a requester can only be on one thing)
    val firstGenre = remember { FocusRequester() }
    val firstTitle = remember { FocusRequester() }
    val buttons = remember { FocusRequester() }
    LaunchedEffect(step, candidates.size, loading) {
        // (after the steps have crossed over, so focus lands on the new one and not the one leaving)
        delay(260)
        if (step == 0) runCatching { firstGenre.requestFocus() }
        else if (!loading) runCatching { (if (candidates.isNotEmpty()) firstTitle else buttons).requestFocus() }
    }
    LaunchedEffect(finishing) { finishing?.let { onDone(it) } }
    // (the progress and the buttons arrive a little after the page does)
    var arrived by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(250); arrived = true }
    val arrival by animateFloatAsState(if (arrived) 1f else 0f, tween(Dx.Slow, easing = Dx.Ease), label = "arrival")

    fun finish(skip: Boolean) {
        finishing = Taste(genres.toList(), picked.toList(), skipped = skip && genres.isEmpty() && picked.isEmpty())
    }
    fun next() {
        step = 1
    }
    LaunchedEffect(step) {
        if (step == 1) {
            val asked = genres.toList()
            if (candidatesFor != asked) {
                loading = true
                candidates = state.candidates(asked)
                candidatesFor = asked
                // (a pick made for other genres is still theirs: it stays picked even if it isn't offered again)
                loading = false
            }
        }
    }

    PageWindow(onBack = { if (step == 1) step = 0 else finish(skip = true) }) {
        Column(Modifier.fillMaxSize().background(Dx.Background).padding(top = 48.dp, bottom = 32.dp)) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AnimatedContent(
                    targetState = step,
                    transitionSpec = {
                        // (forward drifts left, back drifts right: a twelfth of the width, slower in than out so they overlap softly)
                        val dir = if (targetState > initialState) 1 else -1
                        (fadeIn(tween(480, delayMillis = 120, easing = Dx.Ease)) + slideInHorizontally(tween(520, easing = Dx.Ease)) { dir * it / 12 }) togetherWith
                            (fadeOut(tween(Dx.Med, easing = Dx.Ease)) + slideOutHorizontally(tween(520, easing = Dx.Ease)) { -dir * it / 12 })
                    },
                    label = "step",
                ) { s ->
                    Column(Modifier.fillMaxSize()) {
                        Column(Modifier.padding(horizontal = SIDE).graphicsLayer { alpha = arrival }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("${if (who.isNullOrBlank()) "" else "$who · "}${s + 1} of 2", fontSize = 14.sp, color = Dx.Faint)
                            Text(if (s == 0) "What do you like to watch?" else "Which of these have you enjoyed?", fontSize = 32.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                            Text(
                                if (s == 0) "Choose a few that appeal. You can change this at any time in Settings." else "Pick any you've loved, and your suggestions will feel more like you.",
                                fontSize = 17.sp, color = Dx.Muted,
                            )
                        }
                        if (s == 0) {
                            val list = GENRES.filter { it.cinemeta.isNotEmpty() && it.name != "Anime" }
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(4),
                                modifier = Modifier.underHeading(),
                                contentPadding = PaddingValues(horizontal = SIDE, vertical = 36.dp),
                                horizontalArrangement = Arrangement.spacedBy(28.dp),
                                verticalArrangement = Arrangement.spacedBy(28.dp),
                            ) {
                                items(list, key = { it.name }) { g ->
                                    val on = g.name in genres
                                    val mod = if (g === list.first()) Modifier.focusRequester(firstGenre) else Modifier
                                    FocusCard(onClick = { if (on) genres.remove(g.name) else genres.add(g.name) }, modifier = mod.fillMaxWidth().aspectRatio(16f / 9f)) {
                                        Box(Modifier.fillMaxSize()) {
                                            Picture(genreDrawable(g.name), Modifier.fillMaxSize())
                                            Chosen(on)
                                            Text(g.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, modifier = Modifier.align(Alignment.BottomStart).padding(start = 14.dp, bottom = 11.dp))
                                        }
                                    }
                                }
                            }
                        } else {
                            Crossfade(targetState = loading, animationSpec = tween(Dx.Med, easing = Dx.Ease), label = "loading") { busy ->
                                if (busy) {
                                    Text("Finding titles you'll know…", fontSize = 17.sp, color = Dx.Muted, modifier = Modifier.padding(horizontal = SIDE, vertical = 36.dp))
                                } else {
                                    LazyVerticalGrid(
                                        modifier = Modifier.underHeading(),
                                        columns = GridCells.Fixed(5),
                                        contentPadding = PaddingValues(horizontal = SIDE, vertical = 36.dp),
                                        horizontalArrangement = Arrangement.spacedBy(28.dp),
                                        verticalArrangement = Arrangement.spacedBy(28.dp),
                                    ) {
                                        items(candidates, key = { it.id }) { t ->
                                            val on = picked.any { it.id == t.id }
                                            val mod = if (t.id == candidates.first().id) Modifier.focusRequester(firstTitle) else Modifier
                                            FocusCard(onClick = { if (on) picked.removeAll { it.id == t.id } else picked.add(Taste.Seed(t)) }, modifier = mod.fillMaxWidth().aspectRatio(2f / 3f)) {
                                                Box(Modifier.fillMaxSize()) {
                                                    Art(t.art, Modifier.fillMaxSize())
                                                    Chosen(on)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Row(Modifier.padding(horizontal = SIDE).graphicsLayer { alpha = arrival }, horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { if (step == 0) finish(skip = true) else step = 0 },
                    colors = ButtonDefaults.colors(containerColor = Color.White.copy(alpha = 0.1f), contentColor = Color.White, focusedContainerColor = Color.White, focusedContentColor = Color.Black),
                ) { Crossfade(step, animationSpec = tween(Dx.Med, easing = Dx.Ease), label = "back") { Text(if (it == 0) "Skip" else "Back", fontSize = 16.sp, fontWeight = FontWeight.Medium) } }
                // (Continue appears once there's something chosen on the first step, easing in: nothing to press until it means something)
                val count = if (step == 0) genres.size else picked.size
                val showContinue = step == 1 || genres.isNotEmpty()
                val continueAlpha by animateFloatAsState(if (showContinue) 1f else 0f, tween(Dx.Slow, easing = Dx.Ease), label = "continue")
                if (continueAlpha > 0.01f) Button(
                    onClick = { if (step == 0) next() else finish(skip = false) },
                    colors = ButtonDefaults.colors(containerColor = Dx.Accent.copy(alpha = 0.85f), contentColor = Dx.OnAccent, focusedContainerColor = Dx.Accent, focusedContentColor = Dx.OnAccent),
                    modifier = Modifier.focusRequester(buttons).graphicsLayer { alpha = continueAlpha },
                ) { Crossfade(step, animationSpec = tween(Dx.Med, easing = Dx.Ease), label = "continue label") { Text(if (it == 0) "Continue" else "Start Watching", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) } }
                Text(
                    if (count == 0) (if (step == 0) "Or skip if you'd rather not" else "Nothing chosen yet, and that's fine") else "$count chosen",
                    fontSize = 14.sp, color = Dx.Faint,
                )
            }
        }
    }
}

/** Left and right margins of the setup: roomier than the rest of the app, so the choices sit calmly in the middle */
private val SIDE = 72.dp

/**
 * A chosen tile's mark: it darkens a little and a white tick settles into the corner, both over about 0.4 s
 * (animated, so choosing and un-choosing never snap). Nothing is drawn while it isn't chosen.
 */
@Composable
private fun Chosen(on: Boolean) {
    val amount by animateFloatAsState(if (on) 1f else 0f, tween(Dx.Med, easing = Dx.Ease), label = "chosen")
    if (amount <= 0.001f) return
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f * amount)))
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = "Chosen",
            tint = Color.White,
            modifier = Modifier.padding(8.dp).size(26.dp).graphicsLayer { alpha = amount; scaleX = 0.7f + 0.3f * amount; scaleY = 0.7f + 0.3f * amount }.background(Color.Black.copy(alpha = 0.5f), CircleShape),
        )
    }
}

/**
 * A grid under the step's heading: a gap from it, and its top edge fading, so a row scrolled half under it slips out of
 * sight softly rather than being cut off against the words
 */
private fun Modifier.underHeading(): Modifier = this
    .padding(top = 14.dp)
    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            androidx.compose.ui.graphics.Brush.verticalGradient(0f to Color.Transparent, (28.dp.toPx() / size.height).coerceIn(0f, 1f) to Color.Black),
            blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
        )
    }
