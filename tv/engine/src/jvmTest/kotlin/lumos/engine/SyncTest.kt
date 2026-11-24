package lumos.engine

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncTest {
    @Test fun mergesTitleByTitle() {
        val mine = buildJsonObject { putJsonObject("tt1") { put("at", 10) ; put("position", 1) }; putJsonObject("tt2") { put("at", 50) } }
        val theirs = buildJsonObject { putJsonObject("tt1") { put("at", 20); put("position", 2) }; putJsonObject("tt2") { put("at", 40) } }
        val m = mergeTitles(mine, theirs)
        assertEquals(2, m["tt1"].obj()!!.int("position"))
        assertEquals(50L, m["tt2"].obj()!!.long("at"))
    }

    /** Lumen and Stremio keep a show whose episode was finished as its next episode at 0: Continue Watching's Up Next */
    @Test fun upNextFromLumenStaysInContinueWatching() {
        val l = Lumos("http://localhost")
        l.saveProgress("tt21906238", "series", "tt21906238:2:10", "Rivals", null, 0.0, 3319.0, 1000)
        l.saveProgress("tt1", "movie", "tt1", "A Film", null, 0.0, 6000.0, 900)
        l.saveProgress("tt2", "series", "tt2:1:3", "Half Way", null, 600.0, 3000.0, 800)
        l.saveProgress("tt3", "series", "tt3:1:1", "Finished", null, 2990.0, 3000.0, 700)
        val cw = l.continueWatching().map { it.id to it.video }
        assertEquals(listOf("tt21906238" to "tt21906238:2:10", "tt2" to "tt2:1:3"), cw)
        // (finishing an episode marks that episode watched, never the whole show)
        assertTrue("tt3:1:1" in l.watchedIds())
        assertTrue("tt3" !in l.watchedIds())
        // (Continue Watching's ✕ takes an Up Next away too)
        l.forget("tt21906238", 2000)
        assertTrue(l.continueWatching().none { it.id == "tt21906238" })
    }

    /** Against the real Worker on this computer (node sync/serve.mjs). Run with -Dsync=http://localhost:8787 */
    @Test fun signsInATvAndSyncs() {
        val server = System.getProperty("sync")?.takeIf { it.startsWith("http") } ?: return
        runBlocking {
            val http = Engine.defaultHttp()
            val ipad = Sync(server, http)
            ipad.createAccount("local-setup-phrase", "iPad")

            val tv = Sync(server, http)
            val pending = tv.startSignIn("Living room TV")
            assertNull(tv.poll(pending))
            assertEquals("Living room TV", ipad.approve(pending.code))
            val session = assertNotNull(tv.poll(pending))
            assertEquals(ipad.session!!.account, session.account)

            val progress = buildJsonObject { putJsonObject("tt0944947") { put("at", System.currentTimeMillis()); put("video", "tt0944947:1:2"); put("position", 600) } }
            val (now, _) = ipad.sync(0, listOf(Entry("progress:main", System.currentTimeMillis(), progress)))
            assertTrue(now > 0)
            val (_, got) = tv.sync(0, emptyList())
            assertEquals(600, got.single { it.k == "progress:main" }.v.obj()!!["tt0944947"].obj()!!.int("position"))
            assertEquals(2, ipad.devices().size)
            println("Signed the TV in with ${pending.code}; resume point synced")
        }
    }
}
