package com.notdxbi.dxbi.tv.lumen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import lumos.engine.Sync
import org.json.JSONObject

/**
 * A card's one label (sync/labels.js): In Cinemas, New Movie, New Show, New Season, New Episode, Returning, or its place
 * in Top 10 Today; most cards have none. Asked for in batches as cards come on screen, kept for twelve hours.
 */
object Labels {
    private const val KEEP_MS = 12L * 3_600_000
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var sync: Sync? = null
    private val queue = LinkedHashSet<String>()
    private var flushing = false
    private val asked = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** tt → its label ("" for none), as known so far: cards read it, and show it when it comes */
    val known = mutableStateMapOf<String, String>()

    fun start(sync: Sync) { this.sync = sync }

    /**
     * A film's TMDB dates as the server keeps them ({kind, cinema, digital, physical} as "2026-11-18"), the same ones its
     * label comes from: a film's page goes by these for whether it's out. null when the server can't say
     */
    suspend fun facts(tt: String): JSONObject? {
        val s = sync?.takeIf { it.session != null } ?: return null
        val answer = runCatching { JSONObject(s.labels(listOf(tt), facts = true)) }.getOrNull() ?: return null
        answer.optJSONObject("labels")?.let { l -> if (l.has(tt)) kotlinx.coroutines.withContext(Dispatchers.Main) { known[tt] = if (l.isNull(tt)) "" else l.optString(tt) } }
        return answer.optJSONObject("facts")?.optJSONObject(tt)
    }

    /** A card wants [tt]'s label */
    fun want(tt: String) {
        if (!Regex("""tt\d+""").matches(tt)) return
        val at = asked[tt]
        if (at != null && System.currentTimeMillis() - at < KEEP_MS) return
        asked[tt] = System.currentTimeMillis()
        scope.launch {
            lock.withLock {
                queue += tt
                if (!flushing) { flushing = true; scope.launch { flush() } }
            }
        }
    }

    private suspend fun flush() {
        // (the cards that come on screen together, asked for together)
        delay(400)
        while (true) {
            val ids = lock.withLock {
                val take = queue.take(60)
                queue.removeAll(take.toSet())
                if (take.isEmpty()) flushing = false
                take
            }
            if (ids.isEmpty()) return
            val s = sync?.takeIf { it.session != null }
            val answer = s?.let { runCatching { JSONObject(it.labels(ids)) }.getOrNull() }
            if (answer == null) { ids.forEach { asked.remove(it) }; continue }
            val labels = answer.optJSONObject("labels") ?: JSONObject()
            val got = labels.keys().asSequence().associateWith { if (labels.isNull(it)) "" else labels.optString(it) }
            kotlinx.coroutines.withContext(Dispatchers.Main) { known.putAll(got) }
            val pending = answer.optJSONArray("pending")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            if (pending.isNotEmpty()) { lock.withLock { queue.addAll(pending) }; delay(1500) }
        }
    }
}

/**
 * The card's label, if it has one: a glass tab hanging from the middle of its top edge, the same width on every card. In
 * the Top 10 row ([inTopTen]) its "#3 Today" isn't: the row's order says it
 */
@Composable
fun BoxScope.CardLabel(id: String, cardWidth: Dp, inTopTen: Boolean = false) {
    LaunchedEffect(id) { Labels.want(id) }
    val label = Labels.known[id]?.takeIf { it.isNotEmpty() } ?: return
    // (the Top 10's own order says its place: everything else shows there as anywhere)
    if (inTopTen && label.startsWith("#")) return
    // (nor what the row's own name says: New Movie in New to Stream, In Cinemas in In Cinemas…)
    if (LocalRowSays.current.any { label.startsWith(it) }) return
    // (one flat colour, see-through, the corners only just rounded: Harry's call)
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(bottomStart = 3.dp, bottomEnd = 3.dp)
    Box(
        Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth(0.74f)
            .height(labelBand(cardWidth))
            .background(Color.Black.copy(alpha = 0.55f), shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = (cardWidth.value * 0.072f).coerceIn(10f, 12.5f).sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1)
    }
}

/** How tall a card's label is (the Top 10's numbers sit just under it) */
fun labelBand(cardWidth: Dp): Dp = (cardWidth.value * 0.15f).coerceIn(17f, 24f).dp

/** Labels the row a card's in already says by its name (Home sets it per list), left off its cards */
val LocalRowSays = androidx.compose.runtime.staticCompositionLocalOf<Set<String>> { emptySet() }

/** What each of Lumos's lists says by its name */
fun rowSays(key: String): Set<String> = when (key.removePrefix("lumos:")) {
    "new" -> setOf("New Movie", "New Show")
    "cinemas" -> setOf("In Cinemas")
    "comingSoon" -> setOf("Coming")
    "top10" -> setOf("#")
    else -> emptySet()
}
