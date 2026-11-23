package lumos.engine

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

    /** A title's details from the first addon that has them */
    suspend fun meta(type: String, id: String): Meta? {
        for ((a, m) in manifests()) if (m.answers("meta", type, id)) {
            withTimeoutOrNull(CATALOG_MS) { runCatching { a.meta(type, id) }.getOrNull() }?.let { return it }
        }
        return null
    }

    companion object {
        const val MANIFEST_MS = 6_000L
        const val CATALOG_MS = 10_000L
        const val STREAMS_MS = 20_000L
        const val STREAMS_FIRST_MS = 8_000L

        fun defaultHttp() = HttpClient {
            install(HttpTimeout) { connectTimeoutMillis = 6_000; socketTimeoutMillis = 35_000; requestTimeoutMillis = 35_000 }
            expectSuccess = false
        }
    }
}
