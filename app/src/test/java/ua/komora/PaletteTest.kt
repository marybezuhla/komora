package ua.komora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.komora.ui.theme.Look
import ua.komora.ui.theme.Mode
import ua.komora.ui.theme.Shade
import ua.komora.ui.theme.Theme
import ua.komora.ui.theme.contrast
import ua.komora.ui.theme.mix

/** Every theme stays easy to read: text at least 4.5:1, shapes that must be seen at least 3:1 (WCAG AA). */
class PaletteTest {
    private val themes = Theme.entries.filter { it.palette != null }

    @Test
    fun textIsReadable() {
        for (t in themes) {
            val p = t.palette!!
            val primaryContainer = mix(p.surface, p.primary, 0.2)
            val secondaryContainer = mix(p.surface, p.secondary, 0.25)
            val pairs = mapOf(
                "onSurface/surface" to (p.onSurface to p.surface),
                "onSurface/background" to (p.onSurface to p.background),
                "onSurfaceVariant/surface" to (p.onSurfaceVariant to p.surface),
                "onSurfaceVariant/background" to (p.onSurfaceVariant to p.background),
                "onSurfaceVariant/surfaceVariant" to (p.onSurfaceVariant to p.surfaceVariant),
                "onPrimary/primary" to (p.onPrimary to p.primary),
                "primary/surface" to (p.primary to p.surface),
                "primary/background" to (p.primary to p.background),
                "error/surface" to (p.error to p.surface),
                "onSurface/primaryContainer" to (p.onSurface to primaryContainer),
                "onSurface/secondaryContainer" to (p.onSurface to secondaryContainer),
            )
            for ((name, c) in pairs) {
                val r = contrast(c.first, c.second)
                assertTrue("${t.key} $name is $r", r >= 4.5)
            }
        }
    }

    @Test
    fun textOnGroupsIsReadable() {
        for (t in themes) {
            val p = t.palette!!
            for (shade in Shade.entries) {
                val fill = mix(p.surface, if (shade.second) p.secondary else p.primary, shade.amount(t.dark).toDouble())
                for ((name, text) in listOf("primary" to p.primary, "onSurface" to p.onSurface,
                    "onSurfaceVariant" to p.onSurfaceVariant, "error" to p.error)) {
                    val r = contrast(text, fill)
                    assertTrue("${t.key} $name on ${shade.name} is $r", r >= 4.5)
                }
            }
            // Problems: dark red text on its pale red box, and white on the error colour
            val errorContainer = mix(p.surface, p.error, if (t.dark) 0.25 else 0.12)
            assertTrue("${t.key} onSurface/errorContainer", contrast(p.onSurface, errorContainer) >= 4.5)
            assertTrue("${t.key} error/errorContainer", contrast(p.error, errorContainer) >= 4.5)
        }
    }

    @Test
    fun shapesAreVisible() {
        for (t in themes) {
            val p = t.palette!!
            // The ornament and the progress bar
            assertTrue("${t.key} secondary/background", contrast(p.secondary, p.background) >= 3.0)
            assertTrue("${t.key} primary/background", contrast(p.primary, p.background) >= 3.0)
        }
    }

    @Test
    fun dayAndNightAreSeparate() {
        assertTrue(Theme.DAY.none { it.dark })
        assertTrue(Theme.NIGHT.all { it.dark })
        assertEquals(Theme.DAY.size, Theme.NIGHT.size)
    }

    @Test
    fun lookPicksByMode() {
        val look = Look(day = Theme.GLYNA, night = Theme.ZHAR)
        assertEquals(Theme.GLYNA, look.current(phoneIsDark = false))
        assertEquals(Theme.ZHAR, look.current(phoneIsDark = true))
        assertEquals(Theme.GLYNA, look.copy(mode = Mode.LIGHT).current(phoneIsDark = true))
        assertEquals(Theme.ZHAR, look.copy(mode = Mode.DARK).current(phoneIsDark = false))
        assertEquals(Theme.LINEN, Theme.of("unknown", Theme.LINEN))
    }

    @Test
    fun phoneColoursByDefault() {
        val look = Look()
        assertEquals(Mode.AUTO, look.mode)
        assertEquals(Theme.SYSTEM, look.day)
        assertEquals(Theme.SYSTEM_DARK, look.night)
        assertEquals(Theme.SYSTEM, Theme.DAY.first())
        assertEquals(Theme.SYSTEM_DARK, Theme.NIGHT.first())
    }

    @Test
    fun contrastMatchesWcag() {
        assertEquals(21.0, contrast(0xFF000000, 0xFFFFFFFF), 0.01)
        assertEquals(1.0, contrast(0xFF777777, 0xFF777777), 0.001)
    }
}
