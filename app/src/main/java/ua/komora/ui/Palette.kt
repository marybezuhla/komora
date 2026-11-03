package ua.komora.ui

/**
 * Komora's colours, in the family of Pysanka's: the icon's deep red, its gold, and warm cream
 * surfaces. Plain numbers (ARGB), so a unit test can check every text colour against its background.
 */
class Tones(
    val primary: Long,
    val onPrimary: Long,
    val primaryContainer: Long,
    val onPrimaryContainer: Long,
    val secondary: Long,
    val onSecondary: Long,
    val secondaryContainer: Long,
    val onSecondaryContainer: Long,
    val tertiary: Long,
    val background: Long,
    val surface: Long,
    val onSurface: Long,
    val surfaceVariant: Long,
    val onSurfaceVariant: Long,
    val outline: Long,
    val outlineVariant: Long,
    val error: Long,
    val onError: Long,
    /** The ornament under the title: red and gold on the background. */
    val ornamentRed: Long,
    val ornamentGold: Long,
) {
    /** Pairs of (text, background) that must stay easy to read: at least 4.5:1 (WCAG AA). */
    val readablePairs: List<Pair<String, Pair<Long, Long>>>
        get() = listOf(
            "onPrimary/primary" to (onPrimary to primary),
            "onPrimaryContainer/primaryContainer" to (onPrimaryContainer to primaryContainer),
            "onSecondaryContainer/secondaryContainer" to (onSecondaryContainer to secondaryContainer),
            "onSurface/surface" to (onSurface to surface),
            "onSurface/background" to (onSurface to background),
            "onSurfaceVariant/surface" to (onSurfaceVariant to surface),
            "primary/surface" to (primary to surface),
            "error/surface" to (error to surface),
            "onError/error" to (onError to error),
        )

    /** Shapes that must be seen (borders of buttons, the ornament): at least 3:1. */
    val visiblePairs: List<Pair<String, Pair<Long, Long>>>
        get() = listOf(
            "outline/surface" to (outline to surface),
            "ornamentRed/background" to (ornamentRed to background),
            "ornamentGold/background" to (ornamentGold to background),
        )
}

object Palette {
    val light = Tones(
        primary = 0xFF9A2A20,
        onPrimary = 0xFFFFFFFF,
        primaryContainer = 0xFFF8E3DE,
        onPrimaryContainer = 0xFF7A231B,
        secondary = 0xFF7A5900,
        onSecondary = 0xFFFFFFFF,
        secondaryContainer = 0xFFFBE7C2,
        onSecondaryContainer = 0xFF4A3300,
        tertiary = 0xFF7A5900,
        background = 0xFFFBF6EE,
        surface = 0xFFFFFBF7,
        onSurface = 0xFF241A16,
        surfaceVariant = 0xFFF2E6DA,
        onSurfaceVariant = 0xFF56443C,
        outline = 0xFF87736A,
        outlineVariant = 0xFFDCC8BE,
        error = 0xFFB3261E,
        onError = 0xFFFFFFFF,
        ornamentRed = 0xFF9A2A20,
        ornamentGold = 0xFF9A6E00,
    )

    val dark = Tones(
        primary = 0xFFFFB4A8,
        onPrimary = 0xFF5F150F,
        primaryContainer = 0xFF3A2320,
        onPrimaryContainer = 0xFFF3C7BE,
        secondary = 0xFFF0C05A,
        onSecondary = 0xFF3F2E00,
        secondaryContainer = 0xFF4A3608,
        onSecondaryContainer = 0xFFFBDFA6,
        tertiary = 0xFFF0C05A,
        background = 0xFF1A1311,
        surface = 0xFF211916,
        onSurface = 0xFFF1DFDA,
        surfaceVariant = 0xFF3B2F2B,
        onSurfaceVariant = 0xFFD8C2BB,
        outline = 0xFFA08C87,
        outlineVariant = 0xFF534340,
        error = 0xFFFFB4AB,
        onError = 0xFF690005,
        ornamentRed = 0xFFE07A6C,
        ornamentGold = 0xFFF0C05A,
    )
}
