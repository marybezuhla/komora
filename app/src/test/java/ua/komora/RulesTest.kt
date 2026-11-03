package ua.komora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.komora.net.BadReleaseException
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
        assertTrue(Rules.isAllowedUrl("https://GitHub.com/a"))
        assertFalse(Rules.isAllowedUrl("http://github.com/a"))
        assertFalse(Rules.isAllowedUrl("https://github.com.evil.example/a"))
        assertFalse(Rules.isAllowedUrl("https://github.com./a"))
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
        assertEquals("tarilka-v1.2.0.apk", Rules.pickApk(listOf(asset("tarilka-v1.2.0.apk")), "tarilka", repo).name)
    }

    @Test
    fun unsignedOrDebugFilesAreNotTheApp() {
        val picked = Rules.pickApk(
            listOf(asset("tarilka-1.2.apk"), asset("tarilka-unsigned-1.2.apk"), asset("tarilka-debug-1.2.apk")),
            "tarilka", repo,
        )
        assertEquals("tarilka-1.2.apk", picked.name)
    }

    @Test
    fun refusesWhenUnclear() {
        assertThrows(BadReleaseException::class.java) { Rules.pickApk(listOf(asset("notes.txt")), "tarilka", repo) }
        assertThrows(BadReleaseException::class.java) {
            Rules.pickApk(listOf(asset("tarilka-1.apk"), asset("tarilka-2.apk")), "tarilka", repo)
        }
        // A similar prefix is not the same app
        assertThrows(BadReleaseException::class.java) { Rules.pickApk(listOf(asset("tarilkax-1.apk")), "tarilka", repo) }
    }

    @Test
    fun refusesLinksOutsideTheRepository() {
        assertThrows(BadReleaseException::class.java) {
            Rules.pickApk(listOf(asset("tarilka-1.apk", url = "https://github.com/someone/else/releases/download/v1/tarilka-1.apk")), "tarilka", repo)
        }
    }

    @Test
    fun refusesImpossibleSizes() {
        assertThrows(BadReleaseException::class.java) { Rules.pickApk(listOf(asset("tarilka-1.apk", size = 0)), "tarilka", repo) }
        assertThrows(BadReleaseException::class.java) {
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
        assertEquals(0, Rules.compareVersions("v0.1.42", "0.1.42"))
        assertEquals(1, Rules.compareVersions("0.1.10", "0.1.9"))
        assertEquals(-1, Rules.compareVersions("v0.1.9", "0.2.0"))
        assertEquals(0, Rules.compareVersions("1.2.0", "1.2.0-debug"))
        assertEquals(0, Rules.compareVersions("1.2", "1.2.0"))
        assertNull(Rules.compareVersions("latest", "1.0"))
        assertNull(Rules.compareVersions("1.0", ""))
    }
}
