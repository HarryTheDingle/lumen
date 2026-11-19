package lumos.engine

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What the screens talk to. Every source is asked at the same time and answers as soon as it can:
 * nothing waits for a web page to start, and one slow addon never holds up the others.
 */
class Engine(
    addonUrls: List<String>,
    torBoxKey: String? = null,
    val http: HttpClient = defaultHttp(),
) {
    val addons: List<Addon> = addonUrls.map { Addon(it, http) }
    val torBox: TorBox? = torBoxKey?.takeIf { it.isNotBlank() }?.let { TorBox(it, http) }

    /** Each addon's manifest (the ones that answer), fetched together */
    suspend fun manifests(): List<Pair<Addon, Manifest>> = coroutineScope {
        addons.map { a -> async { withTimeoutOrNull(MANIFEST_MS) { runCatching { a to a.manifest() }.getOrNull() } } }.awaitAll().filterNotNull()
    }

    /** Every catalog every addon offers without needing a search or genre, in addon order */
    suspend fun homeCatalogs(): List<Pair<Addon, Catalog>> =
        manifests().flatMap { (a, m) -> m.catalogs.filter { it.requiredExtras.isEmpty() }.map { a to it } }

    /** Home's rows, all fetched at once; a row that doesn't answer in time is left out */
    suspend fun home(): List<Pair<Catalog, List<MetaPreview>>> = coroutineScope {
        homeCatalogs().map { (a, c) ->
            async { withTimeoutOrNull(CATALOG_MS) { runCatching { c to a.catalog(c.type, c.id) }.getOrNull() } }
        }.awaitAll().filterNotNull().filter { it.second.isNotEmpty() }
    }

    /** A title's details from the first addon that has them */
    suspend fun meta(type: String, id: String): Meta? {
        for ((a, m) in manifests()) if (m.answers("meta", type, id)) {
            withTimeoutOrNull(CATALOG_MS) { runCatching { a.meta(type, id) }.getOrNull() }?.let { return it }
        }
        return null
    }

    /**
     * Streams for a movie, or an episode (`tt0944947:1:1`), as each addon that offers streams answers.
     * Each emission is one addon's list. (TorBox retired its own search in 2026: finding is the addons'
     * job, TorBox plays what they find.)
     */
    fun streams(type: String, id: String): Flow<List<Stream>> = channelFlow {
        for (a in addons) launch {
            val m = withTimeoutOrNull(MANIFEST_MS) { runCatching { a.manifest() }.getOrNull() } ?: return@launch
            if (!m.answers("stream", type, id)) return@launch
            withTimeoutOrNull(STREAMS_MS) { runCatching { a.streams(type, id) }.getOrNull() }
                ?.takeIf { it.isNotEmpty() }?.let { send(it) }
        }
    }

    companion object {
        const val MANIFEST_MS = 6_000L
        const val CATALOG_MS = 10_000L
        const val STREAMS_MS = 15_000L

        fun defaultHttp() = HttpClient {
            install(HttpTimeout) { connectTimeoutMillis = 6_000; requestTimeoutMillis = 20_000 }
            expectSuccess = false
        }
    }
}

/** Waits for the flow to finish, for tests and simple callers */
suspend fun Flow<List<Stream>>.all(): List<Stream> {
    val out = mutableListOf<Stream>()
    collect { out += it }
    return out
}
