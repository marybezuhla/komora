package ua.komora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import ua.komora.net.Rules

class ResumeRulesTest {
    @Test fun theRestOfTheFile() {
        assertEquals("bytes 100-999/1000", Rules.contentRange(100, 1000))
    }

    @Test fun onlyTheSameFileContinues() {
        val a = Rules.Asset("x-1.apk", "https://github.com/x/x/releases/download/v1/x-1.apk", 1000, null)
        assertEquals(Rules.partialName(a), Rules.partialName(a.copy()))
        // Another size, link or checksum: another file, started anew
        assertNotEquals(Rules.partialName(a), Rules.partialName(a.copy(size = 1001)))
        assertNotEquals(Rules.partialName(a), Rules.partialName(a.copy(url = a.url.replace("v1", "v2"))))
        assertNotEquals(Rules.partialName(a), Rules.partialName(a.copy(sha256 = "0".repeat(64))))
        // A plain file name, nothing from the link itself
        assert(Rules.partialName(a).matches(Regex("^part-[A-Za-z0-9_-]+\\.apk$")))
    }
}
