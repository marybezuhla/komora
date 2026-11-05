package ua.komora

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ua.komora.net.ReleaseStore
import ua.komora.net.Rules
import ua.komora.net.ScreenshotStore
import ua.komora.net.WholeFile
import java.io.File

class StoresTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun keepsReleaseAnswersPerRepository() {
        val store = ReleaseStore(tmp.newFolder("r"))
        assertNull(store.get("a/b"))
        store.put("a/b", "v1", "{\"x\":1}".toByteArray(), 100)
        val e = store.get("A/B")!!
        assertEquals("v1", e.tag)
        assertEquals(100L, e.checkedAt)
        assertArrayEquals("{\"x\":1}".toByteArray(), e.body)
        store.touch("a/b", "v1", 500)
        assertEquals(500L, store.get("a/b")!!.checkedAt)
        store.forget("a/b")
        assertNull(store.get("a/b"))
    }

    @Test
    fun anOlderCheckNeverPutsBackAnOlderAnswer() {
        val store = ReleaseStore(tmp.newFolder("r"))
        store.put("a/b", "v2", "new".toByteArray(), 100)
        // A check that saw v1 a moment ago finishes now
        store.touch("a/b", "v1", 200)
        val e = store.get("a/b")!!
        assertEquals("v2", e.tag)
        assertEquals(100L, e.checkedAt)
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

    @Test
    fun onlyTheNewestAnswersAreKept() {
        val dir = tmp.newFolder("r")
        val store = ReleaseStore(dir)
        for (i in 0..Rules.MAX_KEPT_RELEASES + 5) {
            store.put("a/app$i", "v1", "{}".toByteArray(), i.toLong())
        }
        assertTrue(dir.listFiles()!!.size <= Rules.MAX_KEPT_RELEASES)
    }

    @Test
    fun leftoversOfCutOffWritesGo() {
        val dir = tmp.newFolder("w")
        val old = File(dir, "komora1.tmp").apply { writeText("x"); setLastModified(0) }
        val fresh = File(dir, "komora2.tmp").apply { writeText("x") }
        WholeFile.dropLeftovers(dir)
        assertFalse(old.exists())
        assertTrue(fresh.exists())
    }

    private fun shot(name: String, bytes: ByteArray) =
        Rules.Shot(name, "https://raw.githubusercontent.com/a/b/main/$name", bytes.size.toLong(), Rules.gitBlobSha(bytes))

    @Test
    fun screenshotsAreFetchedOnlyWhenChanged() {
        val store = ScreenshotStore(tmp.newFolder("s"))
        val one = "one".toByteArray(); val two = "two".toByteArray(); val twoNew = "two, new".toByteArray()
        val bySha = listOf(one, two, twoNew).associateBy { Rules.gitBlobSha(it) }
        val fetched = mutableListOf<String>()
        val fetch = { s: Rules.Shot -> fetched += s.name; bySha[s.sha] }

        assertNull(store.get("a/b", "uk", "v1"))
        val first = store.update("a/b", "uk", "v1", listOf(shot("1.png", one), shot("2.png", two)), fetch)
        assertTrue(first.complete)
        assertEquals(listOf("1.png", "2.png"), fetched)
        // Same release: from the phone, nothing asked
        assertEquals(listOf("one", "two"), store.get("a/b", "uk", "v1")!!.map { String(it) })
        assertNull(store.get("a/b", "uk", "v2"))
        assertEquals(2, store.get("a/b", "uk", null)!!.size)

        // A new release: only the changed picture is fetched; a removed one goes
        fetched.clear()
        val next = store.update("a/b", "uk", "v2", listOf(shot("2.png", twoNew)), fetch)
        assertEquals(listOf("2.png"), fetched)
        assertEquals(listOf("two, new"), next.pictures.map { String(it) })
        assertEquals(listOf("two, new"), store.get("a/b", "uk", "v2")!!.map { String(it) })
        // In another language, kept apart
        assertNull(store.get("a/b", "en", null))
    }

    @Test
    fun aPictureThatFailedIsTriedAgainAndTheOldOneStays() {
        val store = ScreenshotStore(tmp.newFolder("s"))
        val one = "one".toByteArray(); val oneNew = "one, new".toByteArray()
        store.update("a/b", "uk", "v1", listOf(shot("1.png", one))) { one }
        // v2 changes the picture, but it cannot be fetched now
        val r = store.update("a/b", "uk", "v2", listOf(shot("1.png", oneNew))) { null }
        assertFalse(r.complete)
        assertEquals(listOf("one"), r.pictures.map { String(it) })
        // Not marked as done for v2, so the next look asks again; the older picture still shows offline
        assertNull(store.get("a/b", "uk", "v2"))
        assertEquals(listOf("one"), store.get("a/b", "uk", null)!!.map { String(it) })
        val again = store.update("a/b", "uk", "v2", listOf(shot("1.png", oneNew))) { oneNew }
        assertTrue(again.complete)
        assertEquals(listOf("one, new"), store.get("a/b", "uk", "v2")!!.map { String(it) })
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
