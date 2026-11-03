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
import ua.komora.catalog.Catalog
import ua.komora.install.Confirm
import ua.komora.net.Release
import ua.komora.net.Rules
import ua.komora.ui.Actions
import ua.komora.ui.AppRow
import ua.komora.ui.KomoraList
import ua.komora.ui.LookPage
import ua.komora.ui.Phase
import ua.komora.ui.Problem
import ua.komora.ui.theme.KomoraTheme
import ua.komora.ui.theme.Look
import ua.komora.ui.theme.Theme
import ua.komora.notify.UpdateCheck
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

    private fun shot(name: String, theme: Theme, afterIdle: () -> Unit = {}, content: @Composable () -> Unit) =
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { KomoraTheme(theme) { content() } } }
            repeat(6) {
                shadowOf(Looper.getMainLooper()).idle()
                compose.waitForIdle()
            }
            afterIdle()
            repeat(4) {
                shadowOf(Looper.getMainLooper()).idle()
                compose.waitForIdle()
            }
            var bitmap: Bitmap? = null
            scenario.onActivity { activity ->
                val view = activity.window.decorView
                bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                    .also { view.draw(android.graphics.Canvas(it)) }
            }
            val dir = File("build/screens").apply { mkdirs() }
            File(dir, "$name.png").outputStream().use { bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
            Unit
        }

    private fun list(name: String, theme: Theme, canInstall: Boolean = true, confirm: Confirm? = null, rows: List<AppRow> = this.rows) =
        shot(name, theme) {
            KomoraList(rows, busy = rows.any { it.isBusy }, confirm = confirm, canInstall = canInstall, onLook = {},
                actions = noActions, onCheck = {}, onInstall = {}, onCancel = {})
        }

    @Test fun listPhone() = list("01-list-phone-colours", Theme.SYSTEM)
    @Config(qualifiers = "+night")
    @Test fun listPhoneDark() = list("02-list-phone-colours-dark", Theme.SYSTEM_DARK)
    @Test fun listKreyda() = list("03-list-kreyda", Theme.KREYDA)
    @Test fun listGlyna() = list("04-list-glyna", Theme.GLYNA)
    @Config(qualifiers = "+night")
    @Test fun listPodillia() = list("05-list-podillia", Theme.PODILLIA)
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
    @Test fun lookDark() = shot("10-look-dark", Theme.HUTSULSHCHYNA) {
        LookPage(Look(day = Theme.GLYNA, night = Theme.HUTSULSHCHYNA), onBack = {}) {}
    }

    @Config(qualifiers = "en-w400dp-h1600dp-xhdpi")
    @Test fun listEnglish() = list("11-list-english", Theme.NEBO)

    @Test fun whatsNewOpen() {
        val by = catalog.associateBy { it.packageName }
        val row = AppRow(by.getValue("ua.tarilka"), installedVersion = "1.2.3", latest = release("tarilka", "1.3.0", 48))
        shot("12-whats-new", Theme.SYSTEM, afterIdle = { compose.onNodeWithText("Що нового").performClick() }) {
            KomoraList(listOf(row), busy = false, confirm = null, canInstall = true, onLook = {},
                actions = noActions, onCheck = {}, onInstall = {}, onCancel = {})
        }
    }

    @Test fun settingsWithUpdates() = shot("13-settings-updates-on", Theme.SYSTEM) {
        LookPage(Look(), onBack = {}, updates = UpdateCheck.Settings(enabled = true, wifiOnly = true)) {}
    }
}
