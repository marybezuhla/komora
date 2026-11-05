package ua.komora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.komora.catalog.Catalog
import java.io.File

class CatalogTest {
    private val catalogJson = File("src/main/assets/catalog.json").readText()
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    @Test
    fun shippedCatalogIsValid() {
        val apps = Catalog.parse(catalogJson)
        assertTrue(apps.isNotEmpty())
        assertEquals("Only Komora itself is pinned to its own key", listOf("ua.komora"), apps.filter { it.isSelf }.map { it.packageName })
    }

    @Test
    fun manifestNamesExactlyTheCatalogApps() {
        val inCatalog = Catalog.parse(catalogJson).filterNot { it.isSelf }.map { it.packageName }.toSet()
        val inManifest = Regex("<package android:name=\"([^\"]+)\"").findAll(manifest).map { it.groupValues[1] }.toSet()
        assertEquals(inCatalog, inManifest)
    }

    @Test
    fun badCertificateIsRefused() {
        val bad = catalogJson.replace("64eaf973ec74aa88ab62a6b32b9552cc8713042385fa3d749f66698bcdb25c60", "64EAF973")
        assertThrows(IllegalArgumentException::class.java) { Catalog.parse(bad) }
    }

    @Test
    fun secondSelfIsRefused() {
        val bad = catalogJson.replace("64eaf973ec74aa88ab62a6b32b9552cc8713042385fa3d749f66698bcdb25c60", "self")
        assertThrows(IllegalArgumentException::class.java) { Catalog.parse(bad) }
    }
    @Test
    fun otherVersionIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { Catalog.parse(catalogJson.replace("\"version\": 1", "\"version\": 2")) }
    }

    @Test
    fun duplicatePackageIsRefused() {
        val bad = catalogJson.replace("\"packageName\": \"ua.mandat\"", "\"packageName\": \"ua.tarilka\"")
        assertThrows(IllegalArgumentException::class.java) { Catalog.parse(bad) }
    }

    @Test
    fun missingEnglishIsRefused() {
        val bad = catalogJson.replace("\"en\": \"Tarilka\"", "\"pl\": \"Tarilka\"")
        assertThrows(IllegalArgumentException::class.java) { Catalog.parse(bad) }
    }

    @Test
    fun badRepoIsRefused() {
        val bad = catalogJson.replace("\"marybezuhla/tarilka\"", "\"marybezuhla/tarilka/../x\"")
        assertThrows(IllegalArgumentException::class.java) { Catalog.parse(bad) }
    }

    @Test
    fun badBadgeColourIsRefused() {
        val bad = catalogJson.replace("\"badge\": \"#3E7B57\"", "\"badge\": \"green\"")
        assertThrows(IllegalArgumentException::class.java) { Catalog.parse(bad) }
    }

    @Test
    fun badgesAreOpaqueColours() {
        for (app in Catalog.parse(catalogJson)) assertEquals(0xFFL, app.badge ushr 24)
    }

    @Test
    fun everyAppHasItsIcon() {
        val ui = File("src/main/java/ua/komora/ui/ListScreen.kt").readText()
        for (app in Catalog.parse(catalogJson)) {
            assertTrue("${app.packageName} has no icon", ui.contains("\"${app.packageName}\" to R.drawable.app_icon_${app.assetPrefix}"))
            assertTrue("missing picture for ${app.assetPrefix}", File("src/main/res/drawable-nodpi/app_icon_${app.assetPrefix}.png").exists())
        }
    }
}
