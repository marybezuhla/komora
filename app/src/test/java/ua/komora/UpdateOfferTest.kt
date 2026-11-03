package ua.komora

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.komora.catalog.Catalog
import ua.komora.net.Release
import ua.komora.net.Rules
import ua.komora.ui.AppRow
import java.io.File

/** "Update" is offered only for a clearly newer release: never for the same, an older or an unclear one. */
class UpdateOfferTest {
    private val app = Catalog.parse(File("src/main/assets/catalog.json").readText()).first { !it.isSelf }

    private fun row(installed: String?, tag: String?) = AppRow(
        app = app,
        installedVersion = installed,
        latest = tag?.let { Release(it, Rules.Asset("x-1.apk", "https://github.com/x/x/releases/download/v1/x-1.apk", 1, null)) },
    )

    @Test
    fun newerIsOffered() {
        assertTrue(row("0.1.41", "v0.1.42").hasUpdate)
        assertTrue(row("0.1.9", "0.1.10").hasUpdate)
    }

    @Test
    fun sameOlderOrUnclearIsNot() {
        assertFalse(row("0.1.42", "v0.1.42").hasUpdate)
        assertFalse(row("0.2.0", "v0.1.9").hasUpdate)
        assertFalse(row("1.2.0-debug", "1.2.0").hasUpdate)
        assertFalse(row("", "1.2.0").hasUpdate)
        assertFalse(row("1.0.0", "nightly").hasUpdate)
        assertFalse(row(null, "1.0.0").hasUpdate)
        assertFalse(row("1.0.0", null).hasUpdate)
    }

}
