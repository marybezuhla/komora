package ua.komora.ui.theme

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Material colours from one of Komora's themes; the phone's own (Material You) for the system ones. */
fun colorScheme(theme: Theme, context: Context): ColorScheme {
    val p = theme.palette
        ?: return if (theme.dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    fun c(v: Long) = Color(v)
    val base = if (theme.dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = c(p.primary),
        onPrimary = c(p.onPrimary),
        primaryContainer = c(mix(p.surface, p.primary, 0.2)),
        onPrimaryContainer = c(p.onSurface),
        secondary = c(p.secondary),
        onSecondary = c(p.onPrimary),
        secondaryContainer = c(mix(p.surface, p.secondary, 0.25)),
        onSecondaryContainer = c(p.onSurface),
        tertiary = c(p.secondary),
        onTertiary = c(p.onPrimary),
        background = c(p.background),
        onBackground = c(p.onSurface),
        // Cards are the lighter (or, at night, raised) surface; the top bar sits on the background
        surface = c(p.surface),
        onSurface = c(p.onSurface),
        surfaceVariant = c(p.surfaceVariant),
        onSurfaceVariant = c(p.onSurfaceVariant),
        surfaceContainerLowest = c(p.surface),
        surfaceContainerLow = c(p.surface),
        surfaceContainer = c(p.surface),
        surfaceContainerHigh = c(p.surface),
        surfaceContainerHighest = c(p.surface),
        surfaceBright = c(p.surface),
        surfaceDim = c(p.background),
        outline = c(p.onSurfaceVariant),
        outlineVariant = c(mix(p.surfaceVariant, p.onSurfaceVariant, 0.3)),
        error = c(p.error),
    )
}

@Composable
fun KomoraTheme(theme: Theme, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colorScheme(theme, LocalContext.current), typography = KomoraType, content = content)
}

/**
 * Text a size larger than Android's defaults, so nothing on a card is tiny; Android's own font size
 * setting still scales all of it.
 */
private val KomoraType = androidx.compose.material3.Typography().run {
    copy(
        bodyLarge = bodyLarge.copy(fontSize = 17.sp, lineHeight = 24.sp),
        bodyMedium = bodyMedium.copy(fontSize = 16.sp, lineHeight = 22.sp),
        bodySmall = bodySmall.copy(fontSize = 14.sp, lineHeight = 20.sp),
        labelLarge = labelLarge.copy(fontSize = 15.sp, lineHeight = 20.sp),
        labelMedium = labelMedium.copy(fontSize = 14.sp, lineHeight = 18.sp),
        labelSmall = labelSmall.copy(fontSize = 13.sp, lineHeight = 16.sp),
        titleMedium = titleMedium.copy(fontSize = 18.sp, lineHeight = 24.sp),
    )
}

/** An app's name in its card header: larger and bolder than anything inside, as in Pysanka. */
val CardTitle: TextStyle
    @Composable get() = MaterialTheme.typography.titleMedium.copy(fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold)

/**
 * A narrow embroidered band, like the edge of a rushnyk: rhombuses in the theme's accent with a dot of
 * its second colour between them. Decoration only: hidden from TalkBack.
 */
@Composable
fun Ornament(modifier: Modifier = Modifier) {
    val red = MaterialTheme.colorScheme.primary
    val gold = MaterialTheme.colorScheme.secondary
    val eye = MaterialTheme.colorScheme.background
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
            // The "eye" of the motif: a dot of the background in the middle of each rhombus
            drawCircle(eye, radius = h * 0.12f, center = Offset(cx, half))
            if (i < count - 1) drawCircle(gold, radius = h * 0.13f, center = Offset(cx + step / 2f, half))
        }
    }
}
