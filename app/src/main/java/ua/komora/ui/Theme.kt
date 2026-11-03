package ua.komora.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private fun Tones.scheme(dark: Boolean): ColorScheme {
    fun c(v: Long) = Color(v)
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = c(primary), onPrimary = c(onPrimary),
        primaryContainer = c(primaryContainer), onPrimaryContainer = c(onPrimaryContainer),
        secondary = c(secondary), onSecondary = c(onSecondary),
        secondaryContainer = c(secondaryContainer), onSecondaryContainer = c(onSecondaryContainer),
        tertiary = c(tertiary),
        background = c(background), onBackground = c(onSurface),
        surface = c(surface), onSurface = c(onSurface),
        surfaceVariant = c(surfaceVariant), onSurfaceVariant = c(onSurfaceVariant),
        surfaceContainerLowest = c(surface), surfaceContainerLow = c(surface),
        surfaceContainer = c(surface), surfaceContainerHigh = c(surfaceVariant), surfaceContainerHighest = c(surfaceVariant),
        outline = c(outline), outlineVariant = c(outlineVariant),
        error = c(error), onError = c(onError),
    )
}

/** Komora's own colours (not the phone's), light or dark with the phone. */
@Composable
fun KomoraTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val tones = if (dark) Palette.dark else Palette.light
    MaterialTheme(colorScheme = tones.scheme(dark), content = content)
}

/** The current palette, for the few things Material's scheme does not name (the ornament). */
@Composable
fun tones(): Tones = if (isSystemInDarkTheme()) Palette.dark else Palette.light

/** An app's name in its card header: larger and bolder than anything inside, as in Pysanka. */
val CardTitle: TextStyle
    @Composable get() = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)

/**
 * A narrow embroidered band, like the edge of a rushnyk: red rhombuses with a gold dot between them.
 * Decoration only: hidden from TalkBack.
 */
@Composable
fun Ornament(modifier: Modifier = Modifier) {
    val t = tones()
    val red = Color(t.ornamentRed)
    val gold = Color(t.ornamentGold)
    Canvas(modifier.fillMaxWidth().height(12.dp).clearAndSetSemantics { }) {
        val h = size.height
        val step = h * 1.6f
        val count = (size.width / step).toInt().coerceAtLeast(1)
        // Centred, so both ends look the same
        val start = (size.width - count * step) / 2f
        val half = h / 2f
        for (i in 0 until count) {
            val cx = start + step * i + step / 2f
            val rhombus = Path().apply {
                moveTo(cx, 0f)
                lineTo(cx + half * 0.9f, half)
                lineTo(cx, h)
                lineTo(cx - half * 0.9f, half)
                close()
            }
            drawPath(rhombus, red)
            // A small cream-coloured heart of the rhombus is left as background: the "eye" of the motif
            drawCircle(Color(t.background), radius = h * 0.12f, center = Offset(cx, half))
            if (i < count - 1) drawCircle(gold, radius = h * 0.13f, center = Offset(cx + step / 2f, half))
        }
    }
}
