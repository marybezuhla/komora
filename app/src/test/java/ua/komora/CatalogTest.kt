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
    fun manifestSeesExactlyTheCatalogApps() {
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
}
