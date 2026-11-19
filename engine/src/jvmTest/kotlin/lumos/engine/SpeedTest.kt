package lumos.engine

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class SpeedTest {
    /** Real network: Cloudflare's speed test and this network's id. Run with -Dlive=true */
    @Test fun liveSpeed() = runBlocking {
        if (System.getProperty("live") != "true") return@runBlocking
        val speed = Speed(Engine.defaultHttp()) { System.currentTimeMillis() }
        val t0 = System.currentTimeMillis()
        val mbps = speed.get()
        println("speed: ${"%.1f".format(mbps)} Mbit/s in ${System.currentTimeMillis() - t0} ms; network ${speed.currentId().take(6)}…")
        // (the second ask is answered from the reading just taken)
        val t1 = System.currentTimeMillis()
        speed.get()
        println("again: ${System.currentTimeMillis() - t1} ms")
        println(speed.list().replace(Regex("\"id\":\"[^\"]+\""), "\"id\":\"…\""))
        assertTrue(mbps > 0)
    }
}
