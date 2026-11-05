package ua.komora

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ua.komora.net.GitHub
import ua.komora.net.ReleaseStore
import ua.komora.net.Rules
import ua.komora.net.ScreenshotStore

class StoresTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun readsTheTagFromTheLatestPage() {
        assertEquals("v1.0.1", Rules.tagFromRedirect("https://github.com/marybezuhla/komora/releases/tag/v1.0.1"))
        assertEquals("1.2", Rules.tagFromRedirect("https://GitHub.com/a/b/releases/tag/1.2"))
        assertEquals("v1+build", Rules.tagFromRedirect("https://github.com/a/b/releases/tag/v1%2Bbuild"))
        for (bad in listOf(
            "https://github.com/a/b/releases", "https://github.com/login?return_to=x",
            "http://github.com/a/b/releases/tag/v1", "https://github.com.evil.io/a/b/releases/tag/v1",
            "https://github.com/a/.b/releases/tag/v1", "https://github.com/a/b/releases/tag/v1?x=1",
            "https://github.com/a/b/releases/tag/", "not a link",
        )) assertNull(bad, Rules.tagFromRedirect(bad))
    }

    @Test
    fun aRecentCheckHoldsForHalfAnHour() {
        val now = 10_000_000L
        assertTrue(Rules.stillFresh(now - 60_000, now))
        assertFalse(Rules.stillFresh(now - Rules.CHECK_FRESH_MS, now))
        // The clock turned back: not trusted
        assertFalse(Rules.stillFresh(now + 60_000, now))
    }

    @Test
    fun gitNamesContentLikeGit() {
        // `printf 'hello\n' | git hash-object --stdin`
        assertEquals("ce013625030ba8dba906f756967f9e9ca394464a", Rules.gitBlobSha("hello\n".toByteArray()))
        assertEquals("e69de29bb2d1d6434b8b29ae775ad8c2e48c5391", Rules.gitBlobSha(ByteArray(0)))
    }

    @Test
    fun keepsReleaseAnswersPerRepository() {
        val store = ReleaseStore(tmp.newFolder("r"))
        assertNull(store.get("a/b"))
        store.put("a/b", "v1", "{\"x\":1}".toByteArray(), 100)
        val e = store.get("A/B")!!
        assertEquals("v1", e.tag)
        assertEquals(100L, e.checkedAt)
        assertArrayEquals("{\"x\":1}".toByteArray(), e.body)
        store.touch("a/b", e, 500)
        assertEquals(500L, store.get("a/b")!!.checkedAt)
        store.forget("a/b")
        assertNull(store.get("a/b"))
    }

    @Test
    fun aDamagedAnswerIsAskedAnew() {
        val dir = tmp.newFolder("r")
        val store = ReleaseStore(dir)
        store.put("a/b", "v1", "{}".toByteArray(), 1)
        dir.listFiles()!!.single().writeText("{half")
        assertNull(store.get("a/b"))
        assertEquals(0, dir.listFiles()!!.size)
    }

    private fun shot(name: String, bytes: ByteArray) = GitHub.Shot(name, "https://raw.githubusercontent.com/a/b/main/$name", bytes.size.toLong(), Rules.gitBlobSha(bytes))

    @Test
    fun screenshotsAreFetchedOnlyWhenChanged() {
        val store = ScreenshotStore(tmp.newFolder("s"))
        val one = "one".toByteArray(); val two = "two".toByteArray(); val twoNew = "two, new".toByteArray()
        val fetched = mutableListOf<String>()
        val fetch = { s: GitHub.Shot -> fetched += s.name; mapOf("1.png" to one, "2.png" to if (s.sha == Rules.gitBlobSha(two)) two else twoNew)[s.name] }

        assertNull(store.get("a/b", "uk", "v1"))
        store.update("a/b", "uk", "v1", listOf(shot("1.png", one), shot("2.png", two)), fetch)
        assertEquals(listOf("1.png", "2.png"), fetched)
        // Same release: from the phone, nothing asked
        assertEquals(listOf("one", "two"), store.get("a/b", "uk", "v1")!!.map { String(it) })
        assertNull(store.get("a/b", "uk", "v2"))
        assertEquals(2, store.get("a/b", "uk", null)!!.size)

        // A new release: only the changed picture is fetched; a removed one goes
        fetched.clear()
        val shots = store.update("a/b", "uk", "v2", listOf(shot("2.png", twoNew)), fetch)
        assertEquals(listOf("2.png"), fetched)
        assertEquals(listOf("two, new"), shots.map { String(it) })
        assertEquals(listOf("two, new"), store.get("a/b", "uk", "v2")!!.map { String(it) })
        // In another language, kept apart
        assertNull(store.get("a/b", "en", null))
    }

    @Test
    fun aPictureThatFailedIsTriedAgain() {
        val store = ScreenshotStore(tmp.newFolder("s"))
        val one = "one".toByteArray()
        val shots = store.update("a/b", "uk", "v1", listOf(shot("1.png", one), shot("2.png", "x".toByteArray()))) { s ->
            if (s.name == "1.png") one else null
        }
        assertEquals(1, shots.size)
        // Not marked as done for v1, so the next look asks again; the older pictures still show offline
        assertNull(store.get("a/b", "uk", "v1"))
        assertEquals(1, store.get("a/b", "uk", null)!!.size)
    }

    @Test
    fun aDamagedPictureIsNotShown() {
        val root = tmp.newFolder("s")
        val store = ScreenshotStore(root)
        val one = "one".toByteArray()
        store.update("a/b", "uk", "v1", listOf(shot("1.png", one))) { one }
        root.walk().first { it.isFile && it.name == Rules.gitBlobSha(one) }.writeText("changed")
        assertNull(store.get("a/b", "uk", "v1"))
    }

    @Test
    fun oldestPicturesGoWhenFull() {
        val root = tmp.newFolder("s")
        val store = ScreenshotStore(root, maxBytes = 2500)
        val big = { c: Char -> ByteArray(1000) { c.code.toByte() } }
        store.update("a/one", "uk", "v1", listOf(shot("1.png", big('a')))) { big('a') }
        root.listFiles()!!.single().setLastModified(1_000)
        store.update("a/two", "uk", "v1", listOf(shot("1.png", big('b')))) { big('b') }
        store.update("a/three", "uk", "v1", listOf(shot("1.png", big('c')))) { big('c') }
        assertNull(store.get("a/one", "uk", null))
        assertEquals(1, store.get("a/three", "uk", "v1")!!.size)
    }
}
