package lumos.engine

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.TimeSource

/**
 * Match my internet speed, as Lumen's plugin does it (netSpeed and netMemory): a short download from Cloudflare's speed
 * test (rechecked every 10 minutes, never waited for when there's a reading from before), and what each network is known
 * to do, from the test, from the player filling its buffer, and from buffering. Networks are told apart by the internet
 * address's range (no permission needed); each can have its own quality cap. Kept on this device only.
 */
class Speed(private val http: HttpClient, private val now: () -> Long) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var mbps = 0.0
    private var at = 0L
    private var pending: Deferred<Double>? = null

    /** network id → {name, cap, at, samples: [{mbps, from, t}]} */
    private val networks = mutableMapOf<String, Network>()
    private var id = ""
    private var idAt = 0L

    data class Sample(val mbps: Double, val from: String, val t: Long)
    data class Network(var name: String = "", var cap: Int = 0, var at: Long = 0, var samples: List<Sample> = emptyList())

    /** The connection's speed in Mbit/s for a pick now (0: not known). A reading from before goes at once; the test runs behind it */
    suspend fun get(): Double {
        if (at > 0 && now() - at < 10 * 60_000) return mbps
        val before = mbps.takeIf { it > 0 } ?: estimate()
        if (before > 0) { refresh(); return before }
        return withTimeoutOrNull(6000) { refresh().await() } ?: 0.0
    }

    /** As the app starts: a test a little later, so the first Play has a speed to go on */
    fun warm() { scope.async { kotlinx.coroutines.delay(20_000); refresh().await() } }

    private fun refresh(): Deferred<Double> = pending ?: scope.async {
        val m = measure()
        if (m > 0) add(m, "test")
        val known = estimate()
        val result = if (known > 0) known else m
        if (result > 0) { mbps = result; at = now() }
        pending = null
        result.takeIf { it > 0 } ?: mbps
    }.also { pending = it }

    /** Reads for up to 4 seconds; the first half second is left out, while the connection gets going */
    private suspend fun measure(): Double = runCatching {
        withTimeoutOrNull(5500) {
            http.prepareGet("https://speed.cloudflare.com/__down?bytes=50000000&t=${now()}").execute { r ->
                if (!r.status.isSuccess()) return@execute 0.0
                val ch = r.bodyAsChannel()
                val buf = ByteArray(64 * 1024)
                val start = TimeSource.Monotonic.markNow()
                var fromMs = -1L
                var bytes = 0L
                var total = 0L
                while (true) {
                    val n = ch.readAvailable(buf, 0, buf.size)
                    if (n < 0) break
                    val ms = start.elapsedNow().inWholeMilliseconds
                    total += n
                    if (fromMs < 0 && ms >= 500) fromMs = ms else if (fromMs >= 0) bytes += n
                    if (ms >= 4000) break
                }
                val end = start.elapsedNow().inWholeMilliseconds
                // (a very fast line finishes the whole download before the half second is up)
                if (fromMs >= 0 && end - fromMs >= 500) bytes * 8.0 / ((end - fromMs) / 1000.0) / 1e6
                else if (end > 0) total * 8.0 / (end / 1000.0) / 1e6 else 0.0
            }
        } ?: 0.0
    }.getOrDefault(0.0)

    /** "v6:2a04:201:7dc3" (a /48) or "v4:82.13.45" (a /24): the same at home from day to day; "" offline */
    suspend fun currentId(): String {
        if (id.isNotEmpty() && now() - idAt < 120_000) return id
        runCatching {
            withTimeoutOrNull(4000) {
                val r = http.get("https://speed.cloudflare.com/cdn-cgi/trace")
                val ip = Regex("""(?m)^ip=(.+)$""").find(r.bodyAsText())?.groupValues?.get(1)?.trim().orEmpty()
                id = when {
                    ip.contains(':') -> "v6:" + ip.split(':').take(3).joinToString(":")
                    ip.isNotEmpty() -> "v4:" + ip.split('.').take(3).joinToString(".")
                    else -> ""
                }
                idAt = now()
            }
        }
        return id
    }

    // What the line can do: the test's reading, or the player's when it filled faster; less only when it buffered recently
    private fun speedOf(n: Network): Double {
        fun middle(list: List<Double>) = if (list.isEmpty()) 0.0 else list.sorted()[(list.size - 1) / 2]
        fun of(test: (Sample) -> Boolean) = n.samples.filter(test).map { it.mbps }
        val line = maxOf(middle(of { it.from == "test" }), middle(of { it.from == "fill" || it.from == "play" }))
        val stalls = of { it.from == "stall" && now() - it.t < 3 * 86_400_000L }
        return if (stalls.isNotEmpty()) minOf(if (line > 0) line else Double.MAX_VALUE, middle(stalls)) else line
    }

    // (a slow reading, which would hold quality back, isn't trusted until it's been seen three times)
    private fun trusted(n: Network): Boolean {
        val m = speedOf(n)
        return m > 0 && now() - n.at < 30 * 86_400_000L && (if (m >= 10) n.samples.isNotEmpty() else n.samples.size >= 3)
    }

    private suspend fun estimate(): Double {
        val n = networks[currentId()] ?: return 0.0
        return if (trusted(n)) speedOf(n) else 0.0
    }

    /** A reading: "test", "fill" (the player filling its buffer) or "stall" (it buffered: what it really got) */
    suspend fun add(reading: Double, from: String) {
        if (reading <= 0) return
        val key = currentId().ifEmpty { return }
        val n = networks.getOrPut(key) { Network() }
        // (much faster than remembered: the connection's better now, or the first reading was off)
        if (n.samples.isNotEmpty() && reading > speedOf(n) * 2) n.samples = emptyList()
        n.samples = (n.samples + Sample(kotlin.math.round(reading * 10) / 10, from, now())).takeLast(8)
        n.at = now()
        // (the 20 networks used last)
        networks.entries.sortedByDescending { it.value.at }.drop(20).forEach { networks.remove(it.key) }
        at = 0
        if (from == "stall") mbps = minOf(if (mbps > 0) mbps else Double.MAX_VALUE, speedOf(n))
    }

    /** This network's own quality cap (0: none) */
    suspend fun cap(): Int = networks[currentId()]?.cap ?: 0

    /** Settings → Networks */
    suspend fun list(): String {
        val current = currentId()
        return JsonArray(networks.entries.sortedByDescending { it.value.at }.map { (k, n) ->
            buildJsonObject {
                put("id", k); put("name", n.name); put("cap", n.cap); put("current", k == current); put("trusted", trusted(n))
                put("mbps", kotlin.math.round(speedOf(n) * 10) / 10); put("readings", n.samples.size); put("at", n.at)
            }
        }).toString()
    }

    fun set(key: String, name: String?, cap: Int?, relearn: Boolean) {
        val n = networks[key] ?: return
        name?.let { n.name = it.trim().take(40) }
        cap?.let { n.cap = if (it in listOf(0, 2160, 1080, 720)) it else 0 }
        if (relearn) n.samples = emptyList()
        at = 0
    }

    fun forget(key: String) { networks.remove(key); at = 0 }

    // ---- Kept with the rest of the engine's state ----

    fun state(): JsonObject = JsonObject(networks.mapValues { (_, n) ->
        buildJsonObject {
            put("name", n.name); put("cap", n.cap); put("at", n.at)
            put("samples", JsonArray(n.samples.map { s -> buildJsonObject { put("mbps", s.mbps); put("from", s.from); put("t", s.t) } }))
        }
    })

    fun restore(o: JsonObject?) {
        o?.forEach { (k, v) ->
            val n = v.obj() ?: return@forEach
            networks[k] = Network(
                n.str("name") ?: "", n.int("cap") ?: 0, n.long("at") ?: 0,
                n["samples"].arr().mapNotNull { it.obj() }.mapNotNull { s -> Sample(s.double("mbps") ?: return@mapNotNull null, s.str("from") ?: "test", s.long("t") ?: 0) },
            )
        }
    }

    companion object {
        /** A stream fits when it needs at most this share of the measured speed: speeds dip */
        const val HEADROOM = 0.75
    }
}
