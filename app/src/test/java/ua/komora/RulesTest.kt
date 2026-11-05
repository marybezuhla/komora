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
    private val repo = "marybezuhla/tarilka"
    private fun asset(name: String, url: String = "https://github.com/$repo/releases/download/v1/$name", size: Long = 1000) =
        Asset(name, url, size, null)

    @Test
    fun onlyGitHubOverHttps() {
        assertTrue(Rules.allowedUri("https://api.github.com/repos/a/b/releases/latest") != null)
        assertTrue(Rules.allowedUri("https://release-assets.githubusercontent.com/x?y=1") != null)
        assertTrue(Rules.allowedUri("https://GitHub.com/a") != null)
        assertFalse(Rules.allowedUri("http://github.com/a") != null)
        assertFalse(Rules.allowedUri("https://github.com.evil.example/a") != null)
        assertFalse(Rules.allowedUri("https://github.com./a") != null)
        assertFalse(Rules.allowedUri("https://evil.example/github.com") != null)
        assertFalse(Rules.allowedUri("https://user@github.com/a") != null)
        assertFalse(Rules.allowedUri("https://github.com:8443/a") != null)
        assertFalse(Rules.allowedUri("file:///sdcard/a.apk") != null)
        assertFalse(Rules.allowedUri("not a url") != null)
        // Pictures only for screenshots: an app file can never come from there
        assertFalse(Rules.allowedUri("https://raw.githubusercontent.com/a/b/main/x.apk") != null)
        assertTrue(Rules.allowedUri("https://raw.githubusercontent.com/a/b/main/x.png", Rules.SCREENSHOT_HOSTS) != null)
        assertFalse(Rules.allowedUri("https://github.com/a/b/releases/download/v1/x.apk", Rules.SCREENSHOT_HOSTS) != null)
    }

    @Test
    fun screenshotsOnlyFromTheRepository() {
        val ok = "https://raw.githubusercontent.com/marybezuhla/sprotyv/main/fastlane/1.png"
        assertTrue(Rules.isScreenshot("1.png", ok, 1000, "marybezuhla/sprotyv"))
        assertFalse(Rules.isScreenshot("1.png", "https://raw.githubusercontent.com/other/x/main/1.png", 1000, "marybezuhla/sprotyv"))
        assertFalse(Rules.isScreenshot("1.svg", ok, 1000, "marybezuhla/sprotyv"))
        assertFalse(Rules.isScreenshot("1.png", "https://raw.githubusercontent.com/marybezuhla/sprotyv-evil/main/1.png", 1000, "marybezuhla/sprotyv"))
        assertFalse(Rules.isScreenshot("1.png", "https://raw.githubusercontent.com/marybezuhla/sprotyv/../x/1.png", 1000, "marybezuhla/sprotyv"))
        assertFalse(Rules.isScreenshot("../1.png", ok, 1000, "marybezuhla/sprotyv"))
        assertFalse(Rules.isScreenshot("1.png", ok, Rules.MAX_IMAGE_BYTES + 1L, "marybezuhla/sprotyv"))
        assertEquals("fastlane/metadata/android/uk/images/phoneScreenshots", Rules.screenshotDirs("uk").first())
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
        // Test versions come before the final one, then by kind and number
        assertEquals(1, Rules.compareVersions("v1.0.0", "1.0.0-beta"))
        assertEquals(-1, Rules.compareVersions("1.0.0-rc.1", "1.0.0"))
        assertEquals(1, Rules.compareVersions("1.0.0-rc.2", "1.0.0-rc.1"))
        assertEquals(1, Rules.compareVersions("1.0.0-rc1", "1.0.0-beta3"))
        assertEquals(1, Rules.compareVersions("1.0.0-preview", "1.0.0-pre.9"))
        assertEquals(0, Rules.compareVersions("1.0.0-BETA", "1.0.0-beta.0"))
        assertEquals(1, Rules.compareVersions("1.0.1-alpha", "1.0.0"))
    }

    @Test
    fun automaticUpdatesWait() {
        val now = 10L * Rules.AUTO_UPDATE_WAIT_MS
        assertFalse(Rules.oldEnough(null, now))
        assertFalse(Rules.oldEnough(now - Rules.AUTO_UPDATE_WAIT_MS + 1, now))
        assertTrue(Rules.oldEnough(now - Rules.AUTO_UPDATE_WAIT_MS, now))
        // A date in the future (a wrong clock) is not old enough either
        assertFalse(Rules.oldEnough(now + 1000, now))
    }
}
