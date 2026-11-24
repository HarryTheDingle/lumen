package com.notdxbi.dxbi.tv.lumen

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import lumos.engine.Sync
import org.json.JSONObject

/**
 * Checked pictures (sync/art.js): every title's metahub picture (what the addons and Cinemeta hand over, by IMDb id) is
 * swapped as it loads for TMDB's, once the sync server has checked TMDB's title is the same one. metahub files some IMDb
 * ids under another title's pictures; this puts every screen right at once, with nothing on the screens changed. Where
 * there's no checked picture (TMDB hasn't got it, its title didn't match, signed out, offline), metahub's is used.
 *
 * Asked for in batches (the cards on screen come together), kept on this TV for a month. Wrong Picture (a card's hold
 * menu) tells the server, which never uses those pictures for that title again, and the screens load the next best.
 */
object CheckedArt {
    data class Pics(val poster: String?, val background: String?, val logo: String?) {
        fun of(kind: String) = when (kind) { "poster" -> poster; "background" -> background; else -> logo }
    }

    private val METAHUB = Regex("""^https://images\.metahub\.space/(poster|background|logo)/\w+/(tt\d+)/img""")
    private const val KEEP_MS = 30L * 86_400_000
    private const val BATCH = 60

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var sync: Sync? = null
    private var prefs: SharedPreferences? = null

    /** tt → its checked pictures, or null for none (known either way) */
    private val known = java.util.concurrent.ConcurrentHashMap<String, Pics?>()
    private val noneMark = Pics(null, null, null)
    private val waiting = HashMap<String, CompletableDeferred<Pics?>>()
    private val queue = LinkedHashSet<String>()
    private var flushing = false

    /** A title's pictures put right this session: its screens' requests change, so they load again */
    val versions = mutableStateMapOf<String, Int>()

    fun start(context: Context, sync: Sync) {
        this.sync = sync
        val p = context.getSharedPreferences("lumos-art", Context.MODE_PRIVATE)
        prefs = p
        scope.launch {
            val now = System.currentTimeMillis()
            val old = ArrayList<String>()
            for ((tt, v) in p.all) {
                val parts = (v as? String)?.split('|') ?: continue
                val at = parts.lastOrNull()?.toLongOrNull() ?: 0
                if (now - at > KEEP_MS || parts.size != 5) { old += tt; continue }
                known[tt] = if (parts[0] == "-") noneMark else Pics(parts[0].ifEmpty { null }, parts[1].ifEmpty { null }, parts[2].ifEmpty { null })
            }
            if (old.isNotEmpty()) p.edit().apply { old.forEach(::remove) }.apply()
        }
    }

    /** The IMDb id a metahub picture is of, and which picture */
    private fun parse(url: String): Pair<String, String>? = METAHUB.find(url)?.let { it.groupValues[2] to it.groupValues[1] }

    /** The checked picture for a metahub [url], else null (load it as it is) */
    suspend fun resolve(url: String): String? {
        val (tt, kind) = parse(url) ?: return null
        val pics = withTimeoutOrNull(6000) { get(tt) } ?: return null
        return pics.of(kind)
    }

    private suspend fun get(tt: String): Pics? {
        known[tt]?.let { return it.takeIf { p -> p !== noneMark } }
        if (known.containsKey(tt)) return null
        val wait = lock.withLock {
            waiting[tt] ?: CompletableDeferred<Pics?>().also { d ->
                waiting[tt] = d
                queue += tt
                if (!flushing) { flushing = true; scope.launch { flush() } }
            }
        }
        return wait.await()
    }

    // The cards that came on screen together, asked for together: one request of up to 60 (the server answers what it
    // knows at once and looks up 14 more; the rest come back pending and are asked for again). Few requests: the server
    // lets a TV make 60 a minute, and Wrong Picture and syncing need some of those.
    private suspend fun flush() {
        delay(60)
        while (true) {
            val ids = lock.withLock {
                val take = queue.take(BATCH)
                queue.removeAll(take.toSet())
                if (take.isEmpty()) flushing = false
                take
            }
            if (ids.isEmpty()) return
            val s = sync
            val answer: JSONObject? = if (s == null || s.session == null) null else runCatching { JSONObject(s.art(ids)) }.getOrNull()
            if (answer == null) { ids.forEach { done(it, null, keep = false) }; continue }
            val art = answer.optJSONObject("art") ?: JSONObject()
            val answered = art.keys().asSequence().toList()
            for (tt in answered) done(tt, pics(art.optJSONObject(tt)), keep = true)
            val list = answer.optJSONArray("pending")
            val pending = if (list == null) emptyList() else (0 until list.length()).map { list.optString(it) }
            // (anything the server didn't mention: load metahub's this time)
            ids.filter { it !in answered && it !in pending }.forEach { done(it, null, keep = false) }
            if (pending.isNotEmpty()) { lock.withLock { queue.addAll(pending) }; delay(300) }
        }
    }

    private fun pics(o: JSONObject?): Pics? {
        if (o == null) return null
        fun s(k: String) = if (o.isNull(k)) null else o.optString(k).ifEmpty { null }
        return Pics(s("poster"), s("background"), s("logo")).takeIf { it.poster != null || it.background != null || it.logo != null }
    }

    private suspend fun done(tt: String, p: Pics?, keep: Boolean) {
        if (keep) {
            known[tt] = p ?: noneMark
            prefs?.edit()?.putString(tt, if (p == null) "-||||${System.currentTimeMillis()}" else "${p.poster.orEmpty()}|${p.background.orEmpty()}|${p.logo.orEmpty()}|x|${System.currentTimeMillis()}")?.apply()
        }
        lock.withLock { waiting.remove(tt) }?.complete(p)
    }

    /** Wrong Picture: the server's told, and this title's pictures load again (the next best). False if it couldn't be told */
    suspend fun wrong(tt: String): Boolean {
        val s = sync ?: return false
        val answer = runCatching { JSONObject(s.wrongArt(tt)) }.getOrNull() ?: return false
        val p = pics(answer.optJSONObject("art")?.optJSONObject(tt))
        known[tt] = p ?: noneMark
        prefs?.edit()?.putString(tt, if (p == null) "-||||${System.currentTimeMillis()}" else "${p.poster.orEmpty()}|${p.background.orEmpty()}|${p.logo.orEmpty()}|x|${System.currentTimeMillis()}")?.apply()
        kotlinx.coroutines.withContext(Dispatchers.Main) { versions[tt] = (versions[tt] ?: 0) + 1 }
        return true
    }

    /** Wrong Picture from a menu: sent from here, as the menu closes at once (its own jobs go with it), and said when done */
    fun report(context: Context, tt: String, name: String) {
        val app = context.applicationContext
        scope.launch {
            val ok = wrong(tt)
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(app, if (ok) "Thanks. Finding a better picture for $name." else "Can't connect. Try again in a moment.", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Coil's step before loading: a metahub picture swapped for the checked one (kept in memory under its own address) */
    object Swap : Interceptor {
        override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
            val data = chain.request.data as? String ?: return chain.proceed()
            // (the billboard's, marked #big: the checked picture at its original size, which fills a 1080p screen)
            val big = data.endsWith(BIG)
            val to = resolve(data.removeSuffix(BIG))?.let { if (big) it.replace("/t/p/w1280/", "/t/p/original/") else it }
                ?: return if (big) chain.withRequest(chain.request.newBuilder().data(data.removeSuffix(BIG)).memoryCacheKey(data).build()).proceed() else chain.proceed()
            return chain.withRequest(chain.request.newBuilder().data(to).memoryCacheKey(data).build()).proceed()
        }
    }
}

/**
 * A picture's address as screens should ask for it: once its title's pictures have been put right (Wrong Picture), with
 * a mark that makes the request new, so it loads again
 */
@Composable
fun fresh(url: String?): String? = freshUrl(url)

/** [fresh] outside a screen (the poster prefetch) */
fun freshUrl(url: String?): String? {
    if (url == null) return null
    val tt = IMDB_IN_URL.find(url)?.groupValues?.get(1) ?: return url
    val v = CheckedArt.versions[tt] ?: return url
    return "$url#v$v"
}

private val IMDB_IN_URL = Regex("""/(tt\d+)/img""")

/** A picture asked for at its biggest (the billboard's): see [CheckedArt.Swap] */
const val BIG = "#big"
