package ua.komora.screens

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import ua.komora.catalog.Source
import ua.komora.install.ApkVerifier
import ua.komora.ui.AddError
import ua.komora.ui.AddSourceDialog
import ua.komora.ui.AddState
import ua.komora.ui.DetailsSheet
import ua.komora.ui.TrustDialog
import ua.komora.ui.TrustRequest
import ua.komora.catalog.Catalog
import ua.komora.install.Confirm
import ua.komora.net.Release
import ua.komora.net.Rules
import ua.komora.ui.Actions
import ua.komora.ui.AppRow
import ua.komora.ui.KomoraList
import ua.komora.ui.LookPage
import ua.komora.ui.AboutPage
import ua.komora.ui.SourcesWarning
import ua.komora.ui.Phase
import ua.komora.ui.Problem
import ua.komora.ui.theme.KomoraTheme
import ua.komora.ui.theme.Look
import ua.komora.ui.theme.Theme
import ua.komora.notify.UpdateCheck
import ua.komora.notify.AutoUpdate
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import java.io.File

/**
 * Draws Komora's screens with made-up states, in several themes, on the computer.
 * A screen that crashes fails the build; the pictures go to build/screens for review.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "uk-w400dp-h1600dp-xhdpi")
class ScreensTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val catalog = Catalog.parse(File("src/main/assets/catalog.json").readText())

    private fun release(prefix: String, version: String, mb: Long) = Release(
        "v$version",
        Rules.Asset("$prefix-$version.apk", "https://github.com/x/$prefix/releases/download/v$version/$prefix-$version.apk", mb * 1024 * 1024, null),
        notes = "Що змінилось\n• Нові теми й кольори\n• Швидше відкривається\n• Виправлено дрібні помилки",
    )

    /** Every state a card can be in, one app each. */
    private val rows: List<AppRow> by lazy {
        val by = catalog.associateBy { it.packageName }
        listOf(
            AppRow(by.getValue("ua.komora"), installedVersion = "0.1.2", latest = release("komora", "0.1.2", 2)),
            AppRow(by.getValue("ua.tarilka"), installedVersion = "1.2.3", latest = release("tarilka", "1.3.0", 48)),
            AppRow(by.getValue("ua.mandat"), latest = release("mandat", "0.1.90", 31),
                phase = Phase.Downloading(12L * 1024 * 1024, 31L * 1024 * 1024)),
            AppRow(by.getValue("ua.sprotyv"), latest = release("sprotyv", "1.0.4", 9)),
            AppRow(by.getValue("ua.pysanka"), installedVersion = "2.1.0", foreignKey = true, latest = release("pysanka", "2.1.0", 120)),
            AppRow(by.getValue("ua.svitlyna"), problem = Problem.Network),
        )
    }

    private val noActions = Actions({}, {}, { false }, {}, {})

    @Before fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        // The empty activity that hosts each screen (only known to this test, never to the app)
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
    }

    private fun shot(name: String, theme: Theme, manualClock: Boolean = false, afterIdle: () -> Unit = {}, content: @Composable () -> Unit) =
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            // A text field keeps the screen busy for ever: then time is moved on by hand
            if (manualClock) compose.mainClock.autoAdvance = false
            scenario.onActivity { it.setContent { KomoraTheme(theme) { content() } } }
            // Frames played by hand, without waiting for a quiet moment that never comes
            fun settle(times: Int) = repeat(times) {
                if (manualClock) {
                    repeat(30) { compose.mainClock.advanceTimeByFrame() }
                    shadowOf(Looper.getMainLooper()).idle()
                } else {
                    shadowOf(Looper.getMainLooper()).idle()
                    compose.waitForIdle()
                }
            }
            settle(6)
            afterIdle()
            settle(4)
            var bitmap: Bitmap? = null
            scenario.onActivity { activity ->
                val view = activity.window.decorView
                bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { b ->
                    val canvas = android.graphics.Canvas(b)
                    view.draw(canvas)
                    // A dialog or sheet is a window of its own: drawn on top, over a dimmed screen
                    ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView?.let { d ->
                        canvas.drawColor(0x66000000)
                        canvas.save()
                        canvas.translate(((b.width - d.width) / 2).toFloat().coerceAtLeast(0f), ((b.height - d.height) / 2).toFloat().coerceAtLeast(0f))
                        d.draw(canvas)
                        canvas.restore()
                    }
                }
            }
            val dir = File("build/screens").apply { mkdirs() }
            File(dir, "$name.png").outputStream().use { bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
            Unit
        }

    private fun list(
        name: String, theme: Theme, canInstall: Boolean = true, confirm: Confirm? = null, rows: List<AppRow> = this.rows,
        sources: Boolean = false, addedTab: Boolean = false,
    ) = shot(name, theme) {
        KomoraList(rows, busy = rows.any { it.isBusy }, confirm = confirm, canInstall = canInstall, onLook = {},
            actions = noActions, onCheck = {}, onInstall = {}, onCancel = {},
            sourcesEnabled = sources, addedTab = addedTab)
    }

    @Test fun listPhone() = list("01-list-phone-colours", Theme.SYSTEM)
    @Config(qualifiers = "+night")
    @Test fun listPhoneDark() = list("02-list-phone-colours-dark", Theme.SYSTEM_DARK)
    @Test fun listLinen() = list("03-list-linen", Theme.LINEN)
    @Test fun listGlyna() = list("04-list-glyna", Theme.GLYNA)
    @Config(qualifiers = "+night")
    @Test fun listMak() = list("05-list-mak", Theme.MAK)
    @Config(qualifiers = "+night")
    @Test fun listZhar() = list("06-list-zhar", Theme.ZHAR)
    @Test fun firstStart() = list("07-first-start-allow", Theme.SYSTEM, canInstall = false)

    @Test fun waitingForConfirm() {
        val by = catalog.associateBy { it.packageName }
        val waiting = AppRow(by.getValue("ua.tarilka"), installedVersion = "1.2.3", latest = release("tarilka", "1.3.0", 48),
            phase = Phase.Installing(sessionId = 7))
        list("08-waiting-for-confirm", Theme.SYSTEM, confirm = Confirm(7, Intent(), shown = true), rows = listOf(waiting))
    }

    @Test fun look() = shot("09-look", Theme.SYSTEM) { LookPage(Look(), onBack = {}) {} }
    @Config(qualifiers = "+night")
    @Test fun lookDark() = shot("10-look-dark", Theme.SMEREKA) {
        LookPage(Look(day = Theme.GLYNA, night = Theme.SMEREKA), onBack = {}) {}
    }

    @Config(qualifiers = "en-w400dp-h1600dp-xhdpi")
    @Test fun listEnglish() = list("11-list-english", Theme.NEBO)

    @Test fun whatsNewOpen() {
        val by = catalog.associateBy { it.packageName }
        val row = AppRow(by.getValue("ua.tarilka"), installedVersion = "1.2.3", latest = release("tarilka", "1.3.0", 48))
        // "What's new" is in the details now, opened by tapping the app's row
        shot("12-whats-new", Theme.SYSTEM) { DetailsSheet(row, "uk", row.app.certSha256, onRemove = null, onDismiss = {}) }
    }

    @Test fun settingsWithUpdates() = shot("13-settings-updates-on", Theme.SYSTEM) {
        LookPage(Look(), onBack = {}, updates = UpdateCheck.Settings(enabled = true, wifiOnly = true)) {}
    }

    // A narrow phone, and one with a larger font: nothing may wrap badly or hide
    @Config(qualifiers = "uk-w340dp-h1600dp-xhdpi")
    @Test fun lookNarrow() = shot("14-settings-narrow", Theme.SYSTEM) { LookPage(Look(), onBack = {}) {} }

    @Config(qualifiers = "uk-w340dp-h1800dp-xhdpi", fontScale = 1.3f)
    @Test fun listNarrowLargeFont() = list("15-list-narrow-large-font", Theme.SYSTEM)

    // An unfolded Galaxy Fold or Pixel Fold, and a tablet held sideways
    @Config(qualifiers = "uk-w673dp-h841dp-xhdpi")
    @Test fun foldableOpen() = list("16-foldable-open", Theme.SYSTEM)

    @Config(qualifiers = "uk-w1280dp-h800dp-mdpi")
    @Test fun tablet() = list("17-tablet-landscape", Theme.GLYNA)

    @Config(qualifiers = "uk-w1280dp-h800dp-mdpi")
    @Test fun tabletSettings() = shot("18-tablet-settings", Theme.SYSTEM) { LookPage(Look(), onBack = {}) {} }

    @Config(qualifiers = "uk-w800dp-h1280dp-mdpi")
    @Test fun tabletUpright() = list("19-tablet-upright", Theme.MAK)

    /** A calm day: two updates waiting, nothing downloading, the rest up to date or available. */
    @Test fun calmWithUpdates() {
        val by = catalog.associateBy { it.packageName }
        val calm = listOf(
            AppRow(by.getValue("ua.komora"), installedVersion = "0.1.7", latest = release("komora", "0.1.7", 2)),
            AppRow(by.getValue("ua.tarilka"), installedVersion = "1.2.3", latest = release("tarilka", "1.3.0", 48)),
            AppRow(by.getValue("ua.mandat"), installedVersion = "0.1.80", latest = release("mandat", "0.1.90", 31)),
            AppRow(by.getValue("ua.sprotyv"), installedVersion = "1.0.4", latest = release("sprotyv", "1.0.4", 9)),
            AppRow(by.getValue("ua.pysanka"), latest = release("pysanka", "2.1.0", 120)),
            AppRow(by.getValue("ua.svitlyna"), latest = release("svitlyna", "0.6.1", 54)),
        )
        list("20-calm-two-updates", Theme.SYSTEM, rows = calm)
    }

    @Config(qualifiers = "+night")
    @Test fun calmDark() {
        val by = catalog.associateBy { it.packageName }
        val calm = listOf(
            AppRow(by.getValue("ua.komora"), installedVersion = "0.1.7", latest = release("komora", "0.1.7", 2)),
            AppRow(by.getValue("ua.tarilka"), installedVersion = "1.3.0", latest = release("tarilka", "1.3.0", 48)),
            AppRow(by.getValue("ua.sprotyv"), latest = release("sprotyv", "1.0.4", 9)),
        )
        list("21-calm-dark-mak", Theme.MAK, rows = calm)
    }

    // Other sources: two added apps, one trusted and installed, one not installed yet
    private val key = "3f9a0c7be15d42a8" + "9c0e5b71d26fa4c3" + "e8b07a2d915c6f40" + "b3d8e1a7c42f9065"
    private val addedRows: List<AppRow> by lazy {
        val trusted = Source("someone/notes-app", "notes-[0-9]+\\.[0-9]+\\.apk", "org.example.notes", key, "Notes").toApp()
        val fresh = Source("other-dev/weather", "weather-[0-9]+\\.apk").toApp()
        listOf(
            AppRow(trusted, installedVersion = "2.4", latest = release("notes", "2.5", 7)),
            AppRow(fresh, latest = release("weather", "1.0", 12)),
        )
    }

    @Test fun sourcesVerifiedTab() = list("22-sources-verified-tab", Theme.SYSTEM, rows = rows + addedRows, sources = true)

    @Test fun sourcesAddedTab() = list("23-sources-added-tab", Theme.SYSTEM, rows = rows + addedRows, sources = true, addedTab = true)

    @Config(qualifiers = "+night")
    @Test fun sourcesAddedTabDark() = list("24-sources-added-dark", Theme.GLYNA, rows = rows + addedRows, sources = true, addedTab = true)

    @Test fun trustQuestion() = shot("25-trust-question", Theme.SYSTEM) {
        KomoraList(rows + addedRows, busy = false, confirm = null, canInstall = true, onLook = {}, actions = noActions,
            onCheck = {}, onInstall = {}, onCancel = {}, sourcesEnabled = true, addedTab = true)
        TrustDialog(TrustRequest("other-dev/weather", ApkVerifier.Identity("org.example.weather", key, "Weather", "1.0"))) {}
    }

    @Test fun addSourceFailed() = shot("26-add-source-error", Theme.SYSTEM, manualClock = true) {
        AddSourceDialog(AddState.Failed(AddError.NoApk), onAdd = {}, onChoose = { _, _ -> }) {}
    }

    @Test fun chooseApk() = shot("27-add-source-choose", Theme.SYSTEM) {
        AddSourceDialog(
            AddState.Choose("other-dev/weather", listOf("weather-1.0-arm64-v8a.apk", "weather-1.0-armeabi-v7a.apk", "weather-1.0-universal.apk")),
            onAdd = {}, onChoose = { _, _ -> },
        ) {}
    }

    @Test fun detailsAdded() = shot("28-details-added", Theme.SYSTEM) {
        DetailsSheet(addedRows[0], "uk", key, onRemove = {}, onDismiss = {})
    }

    @Test fun detailsVerified() = shot("29-details-verified", Theme.MAK) {
        DetailsSheet(rows[1], "uk", rows[1].app.certSha256, onRemove = null, onDismiss = {})
    }

    @Test fun settingsWithSources() = shot("30-settings-sources-on", Theme.SYSTEM) {
        LookPage(Look(), onBack = {}, sourcesEnabled = true) {}
    }

    @Test fun sourcesWarning() = shot("31-sources-warning", Theme.SYSTEM) {
        LookPage(Look(), onBack = {}) {}
        SourcesWarning(onAgree = {}, onDismiss = {})
    }

    @Config(qualifiers = "+night")
    @Test fun sourcesWarningDark() = shot("32-sources-warning-dark", Theme.SYSTEM_DARK) {
        SourcesWarning(onAgree = {}, onDismiss = {})
    }

    // "About Komora": the author's word, support links, facts; tall, to see all of it
    @Config(qualifiers = "uk-w400dp-h3600dp-xhdpi")
    @Test fun about() = shot("33-about", Theme.SYSTEM) { AboutPage(onBack = {}) }

    @Config(qualifiers = "en-w400dp-h3600dp-xhdpi")
    @Test fun aboutEnglish() = shot("34-about-english", Theme.MAK) { AboutPage(onBack = {}) }

    @Config(qualifiers = "uk-w400dp-h1200dp-xhdpi")
    @Test fun detailsWithReport() = shot("35-details-report", Theme.SYSTEM) {
        DetailsSheet(rows[1], "uk", rows[1].app.certSha256, onRemove = null, onDismiss = {})
    }

    @Test fun settingsAboutRow() = shot("36-settings-about-row", Theme.SYSTEM) {
        LookPage(Look(), onBack = {}, sourcesEnabled = true) {}
    }

    // The new pair: Viburnum by day, Starry night by night
    @Test fun listKalyna() = list("37-list-kalyna", Theme.KALYNA)

    @Config(qualifiers = "+night")
    @Test fun listZorianaNich() = list("38-list-zoriana-nich", Theme.ZORIANA_NICH)

    @Test fun listNeboHoney() = list("39-list-nebo-honey", Theme.NEBO)

    @Test fun aboutTitle() = shot("40-about-title", Theme.SYSTEM) { AboutPage(onBack = {}) }

    @Config(qualifiers = "uk-w400dp-h2400dp-xhdpi")
    @Test fun settingsWeekly() = shot("41-settings-weekly", Theme.SYSTEM) {
        LookPage(Look(), onBack = {}, updates = UpdateCheck.Settings(enabled = true, weekly = true)) {}
    }

    @Config(qualifiers = "uk-w400dp-h2600dp-xhdpi")
    @Test fun settingsAutoUpdate() = shot("42-settings-auto-update", Theme.SYSTEM) {
        LookPage(Look(), onBack = {}, updates = UpdateCheck.Settings(enabled = true),
            auto = AutoUpdate.Settings(enabled = true, wifiOnly = false)) {}
    }
}
