package ua.komora

import org.junit.Assert.assertTrue
import org.junit.Test
import ua.komora.ui.Palette
import ua.komora.ui.Tones
import kotlin.math.pow

/** Komora's colours stay easy to read, by day and by night: text at least 4.5:1, shapes at least 3:1 (WCAG). */
class PaletteTest {
    private fun lum(argb: Long): Double {
        fun f(c: Long): Double {
            val x = c / 255.0
            return if (x <= 0.03928) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * f((argb shr 16) and 0xFF) + 0.7152 * f((argb shr 8) and 0xFF) + 0.0722 * f(argb and 0xFF)
    }

    private fun ratio(a: Long, b: Long): Double {
        val (hi, lo) = listOf(lum(a), lum(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun check(name: String, tones: Tones) {
        for ((pair, colors) in tones.readablePairs) {
            val r = ratio(colors.first, colors.second)
            assertTrue("$name $pair is $r", r >= 4.5)
        }
        for ((pair, colors) in tones.visiblePairs) {
            val r = ratio(colors.first, colors.second)
            assertTrue("$name $pair is $r", r >= 3.0)
        }
    }

    @Test
    fun light() = check("light", Palette.light)

    @Test
    fun dark() = check("dark", Palette.dark)
}
