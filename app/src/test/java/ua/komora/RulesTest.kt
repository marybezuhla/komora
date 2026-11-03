package ua.komora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.komora.net.Rules
import ua.komora.net.Rules.Asset

class RulesTest {
    private val repo = "Marybezu/tarilka"
    private fun asset(name: String, url: String = "https://github.com/$repo/releases/download/v1/$name", size: Long = 1000) =
        Asset(name, url, size, null)

    @Test
    fun onlyGitHubOverHttps() {
        assertTrue(Rules.isAllowedUrl("https://api.github.com/repos/a/b/releases/latest"))
        assertTrue(Rules.isAllowedUrl("https://release-assets.githubusercontent.com/x?y=1"))
        assertFalse(Rules.isAllowedUrl("http://github.com/a"))
        assertFalse(Rules.isAllowedUrl("https://github.com.evil.example/a"))
        assertFalse(Rules.isAllowedUrl("https://evil.example/github.com"))
        assertFalse(Rules.isAllowedUrl("https://user@github.com/a"))
        assertFalse(Rules.isAllowedUrl("https://github.com:8443/a"))
        assertFalse(Rules.isAllowedUrl("file:///sdcard/a.apk"))
        assertFalse(Rules.isAllowedUrl("not a url"))
    }

    @Test
    fun picksTheOneNamedApk() {
        val picked = Rules.pickApk(listOf(asset("tarilka-1.2.apk"), asset("notes.txt"), asset("other-1.2.apk")), "tarilka", repo)
        assertEquals("tarilka-1.2.apk", picked.name)
    }

    @Test
    fun refusesWhenUnclear() {
        assertThrows(IllegalArgumentException::class.java) { Rules.pickApk(listOf(asset("notes.txt")), "tarilka", repo) }
        assertThrows(IllegalArgumentException::class.java) {
            Rules.pickApk(listOf(asset("tarilka-1.apk"), asset("tarilka-2.apk")), "tarilka", repo)
        }
        // A similar prefix is not the same app
        assertThrows(IllegalArgumentException::class.java) { Rules.pickApk(listOf(asset("tarilkax-1.apk")), "tarilka", repo) }
    }

    @Test
    fun refusesLinksOutsideTheRepository() {
        assertThrows(IllegalArgumentException::class.java) {
            Rules.pickApk(listOf(asset("tarilka-1.apk", url = "https://github.com/someone/else/releases/download/v1/tarilka-1.apk")), "tarilka", repo)
        }
    }

    @Test
    fun refusesImpossibleSizes() {
        assertThrows(IllegalArgumentException::class.java) { Rules.pickApk(listOf(asset("tarilka-1.apk", size = 0)), "tarilka", repo) }
        assertThrows(IllegalArgumentException::class.java) {
            Rules.pickApk(listOf(asset("tarilka-1.apk", size = Rules.MAX_APK_BYTES + 1)), "tarilka", repo)
        }
    }

    @Test
    fun digests() {
        val hex = "a".repeat(64)
        assertEquals(hex, Rules.parseDigest("sha256:$hex"))
        assertEquals(hex, Rules.parseDigest("sha256:${hex.uppercase()}"))
        assertNull(Rules.parseDigest("md5:abc"))
        assertNull(Rules.parseDigest("sha256:xyz"))
        assertNull(Rules.parseDigest(null))
    }

    @Test
    fun versions() {
        assertEquals("0.1.42", Rules.normalizeVersion("v0.1.42"))
        assertEquals("0.1.42", Rules.normalizeVersion("0.1.42"))
    }
}
