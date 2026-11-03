package ua.komora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.komora.catalog.Source
import ua.komora.catalog.Sources
import ua.komora.net.BadReleaseException
import ua.komora.net.Rules

class SourcesTest {
    private val key = "a".repeat(64)

    @Test
    fun readsRepositoryLinks() {
        assertEquals("owner/name", Sources.parseRepo("owner/name"))
        assertEquals("owner/name", Sources.parseRepo(" https://github.com/owner/name/ "))
        assertEquals("owner/name", Sources.parseRepo("github.com/owner/name.git"))
        assertEquals("owner/name", Sources.parseRepo("https://github.com/owner/name/releases/latest"))
        assertEquals("Some-One/app.android", Sources.parseRepo("http://github.com/Some-One/app.android"))
    }

    @Test
    fun refusesAnythingElse() {
        for (bad in listOf("", "owner", "https://gitlab.com/owner/name", "https://github.com.evil.io/owner/name",
            "owner/../x", "-owner/name", "owner/.hidden", "own er/name", "ftp://github.com/owner/name")) {
            assertNull(bad, Sources.parseRepo(bad))
        }
    }

    @Test
    fun keepsOnlyValidEntries() {
        val json = """[
            {"repo":"a/one","apkPattern":"one-[0-9]+\\.apk"},
            {"repo":"a/two","apkPattern":"two-[0-9]+\\.apk","packageName":"org.two","certSha256":"$key"},
            {"repo":"a/three","apkPattern":"three\\.zip"},
            {"repo":"a/four","apkPattern":"four\\.apk","packageName":"org.four"},
            {"repo":"a/five","apkPattern":"five\\.apk","packageName":"org.five","certSha256":"XYZ"},
            {"repo":"bad repo","apkPattern":"x\\.apk"},
            {"repo":"A/ONE","apkPattern":"dup\\.apk"},
            7
        ]"""
        val list = Sources.parse(json)
        assertEquals(listOf("a/one", "a/two"), list.map { it.repo })
        assertFalse(list[0].trusted)
        assertTrue(list[1].trusted)
    }

    @Test
    fun neverMoreThanTheLimit() {
        val json = (1..30).joinToString(",", "[", "]") { """{"repo":"a/r$it","apkPattern":"r\\.apk"}""" }
        assertEquals(Sources.MAX, Sources.parse(json).size)
    }

    @Test
    fun addedAppsAreMarked() {
        val app = Source("owner/name", "x\\.apk").toApp()
        assertTrue(app.added)
        assertTrue(app.needsTrust)
        assertEquals("src:owner/name", app.key)
        val trusted = Source("owner/name", "x\\.apk", "org.x", key).toApp()
        assertFalse(trusted.needsTrust)
        assertEquals("org.x", trusted.key)
    }

    @Test
    fun patternFollowsLaterVersions() {
        val p = Rules.patternFor("app-1.2.3-universal.apk")
        assertTrue(Sources.valid(Source("a/b", p)))
        val r = Regex(p)
        assertTrue(r.matches("app-1.2.3-universal.apk"))
        assertTrue(r.matches("app-1.10.0-universal.apk"))
        assertFalse(r.matches("app-1.2.3-arm64.apk"))
        assertFalse(r.matches("app-1x2.3-universal.apk"))
        assertFalse(r.matches("app-1.2.3-universal.apk.sig"))
    }

    @Test
    fun patternPicksOnlyFromTheRepository() {
        val repo = "a/b"
        fun asset(name: String, url: String = "https://github.com/$repo/releases/download/v2/$name") = Rules.Asset(name, url, 1000, null)
        val p = Rules.patternFor("app-1.0-universal.apk")
        assertEquals("app-2.0-universal.apk", Rules.pickByPattern(listOf(asset("app-2.0-universal.apk"), asset("app-2.0-arm64.apk")), p, repo).name)
        assertThrows(BadReleaseException::class.java) {
            Rules.pickByPattern(listOf(asset("app-2.0-universal.apk", "https://github.com/other/x/releases/download/v2/a.apk")), p, repo)
        }
        assertThrows(BadReleaseException::class.java) { Rules.pickByPattern(emptyList(), p, repo) }
    }
}
