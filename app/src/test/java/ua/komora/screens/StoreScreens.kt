package ua.komora.screens

import android.app.Application
import android.content.ComponentName
import android.graphics.Bitmap
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
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
import ua.komora.catalog.Catalog
import ua.komora.catalog.Source
import ua.komora.net.Release
import ua.komora.net.Rules
import ua.komora.notify.AutoUpdate
import ua.komora.notify.UpdateCheck
import ua.komora.ui.Actions
import ua.komora.ui.AboutPage
import ua.komora.ui.AppLanguage
import ua.komora.ui.AppRow
import ua.komora.ui.DetailsContent
import ua.komora.ui.KomoraList
import ua.komora.ui.LookPage
import ua.komora.ui.Phase
import ua.komora.ui.theme.KomoraTheme
import ua.komora.ui.theme.Look
import ua.komora.ui.theme.LookSettings
import ua.komora.ui.theme.Theme
import java.io.File

/**
 * The eight pictures for the store pages (and for Komora's own details), in Ukrainian and English,
 * at a phone's size (1080 × 2340), with made-up but believable data. Saved as store-<lang>-NN_name.png;
 * they are copied into fastlane/metadata/android/{uk,en-US}/images/phoneScreenshots.
 *
 * The order follows the account's standard: home, the main action, what is special, an overview,
 * the second key feature, the dark home, the themes, About.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "uk-w360dp-h780dp-xxhdpi")
class StoreScreens {
    @get:Rule val compose = createEmptyComposeRule()

    private val catalog = Catalog.parse(File("src/main/assets/catalog.json").readText()).associateBy { it.packageName }

    // The language of this run, as Komora itself picks it
    private fun uk() = ApplicationProvider.getApplicationContext<Application>().getString(ua.komora.R.string.catalog_lang) == "uk"
    private fun lang() = if (uk()) "uk" else "en"

    private fun notes(app: String, version: String) = if (uk()) {
        "$app $version\n• Швидше відкривається на старих телефонах\n• Нові теми: Калина й Зоряна ніч\n• Більший шрифт і кращий контраст\n• Виправлено дрібні помилки"
    } else {
        "$app $version\n• Opens faster on older phones\n• New themes: Viburnum and Starry night\n• Larger text and better contrast\n• Small fixes"
    }

    private fun release(prefix: String, name: String, version: String, mb: Long) = Release(
        "v$version",
        Rules.Asset("$prefix-$version.apk", "https://github.com/x/$prefix/releases/download/v$version/$prefix-$version.apk", mb * 1024 * 1024, null),
        notes = notes(name, version),
    )

    private fun row(pkg: String, installed: String?, latest: String, mb: Long, phase: Phase = Phase.Idle) =
        catalog.getValue(pkg).let { app ->
            AppRow(app, installedVersion = installed, latest = release(pkg.substringAfter('.'), app.name(lang()), latest, mb), phase = phase)
        }

    /** A believable phone: two updates, two current, two to install. */
    private fun home(downloading: Boolean = false) = listOf(
        row("ua.tarilka", "1.2.4", "1.3.0", 48),
        row("ua.mandat", "0.1.185", "0.1.190", 61,
            if (downloading) Phase.Downloading(38L * 1024 * 1024, 61L * 1024 * 1024) else Phase.Idle),
        row("ua.komora", "0.3.1", "0.3.1", 3),
        row("ua.sprotyv", "1.1.2", "1.1.2", 13),
        row("ua.pysanka", null, "0.24.0", 106),
        row("ua.svitlyna", null, "0.6.1", 57),
    )

    private val key = "64eaf973ec74aa88ab62a6b32b9552cc8713042385fa3d749f66698bcdb25c60"

    private val added by lazy {
        listOf(
            AppRow(Source("someone/notes-app", "notes-[0-9]+\\.[0-9]+\\.apk", "org.example.notes", key, "Notes").toApp(),
                installedVersion = "2.4", latest = release("notes", "Notes", "2.5", 7)),
            AppRow(Source("other-dev/weather", "weather-[0-9]+\\.apk").toApp(), latest = release("weather", "Weather", "1.0", 12)),
        )
    }

    // Installed apps can be opened, as on a real phone
    private val actions = Actions({}, {}, { true }, {}, {})

    @Before fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
    }

    private fun shot(name: String, theme: Theme, afterIdle: () -> Unit = {}, content: @Composable () -> Unit) =
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { KomoraTheme(theme) { content() } } }
            repeat(6) { shadowOf(Looper.getMainLooper()).idle(); compose.waitForIdle() }
            afterIdle()
            repeat(4) { shadowOf(Looper.getMainLooper()).idle(); compose.waitForIdle() }
            var bitmap: Bitmap? = null
            scenario.onActivity { activity ->
                val view = activity.window.decorView
                bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { b ->
                    val canvas = android.graphics.Canvas(b)
                    view.draw(canvas)
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
            File(dir, "store-${lang()}-$name.png").outputStream().use { bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
            Unit
        }

    private fun list(name: String, theme: Theme, rows: List<AppRow>, sources: Boolean = false, addedTab: Boolean = false) =
        shot(name, theme) {
            KomoraList(rows, busy = rows.any { it.isBusy }, confirm = null, canInstall = true, onLook = {}, actions = actions,
                onCheck = {}, onInstall = {}, onCancel = {}, sourcesEnabled = sources, addedTab = addedTab)
        }

    // 1. Home: what Komora is for
    private fun s1() = list("01_home", Theme.NEBO, home())

    // 2. The main action, under way
    private fun s2() = list("02_updating", Theme.NEBO, home(downloading = true))

    // 3. What is special: every app checked against its pinned key
    private fun s3() {
        val r = home().first()
        shot("03_details", Theme.NEBO, afterIdle = { runCatching { compose.onNodeWithText(if (uk()) "Показати" else "Show").performClick() } }) {
            // The sheet's content on a full page, as it looks pulled up
            androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                androidx.compose.foundation.layout.Box(Modifier.padding(top = 28.dp)) {
                    DetailsContent(r, lang(), r.app.certSha256, onRemove = null)
                }
            }
        }
    }

    // 4. Overview: updates by themselves, if wanted
    private fun s4() = shot("04_auto_updates", Theme.NEBO) {
        LookPage(Look(), onBack = {}, updates = UpdateCheck.Settings(enabled = true),
            auto = AutoUpdate.Settings(enabled = true, wifiOnly = true)) {}
    }

    // 5. The second key feature: other sources, clearly marked
    private fun s5() = list("05_other_sources", Theme.GLYNA, home() + added, sources = true, addedTab = true)

    // 6. The dark home
    private fun s6() = list("06_starry_night", Theme.ZORIANA_NICH, home())

    // 7. The themes
    private fun s7() = shot("07_themes", Theme.KALYNA) {
        LookSettings(
            Look(day = Theme.KALYNA, night = Theme.PODILLIA), {}, AppLanguage.Choice.AUTO, {},
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState()).padding(16.dp),
        )
    }

    // 8. About: the author's word, support for Ukraine, the licence
    private fun s8() = shot("08_about", Theme.SYSTEM) { AboutPage(onBack = {}) }

    @Test fun uk1() = s1()
    @Test fun uk2() = s2()
    @Test fun uk3() = s3()
    @Test fun uk4() = s4()
    @Test fun uk5() = s5()
    @Config(qualifiers = "+night") @Test fun uk6() = s6()
    @Test fun uk7() = s7()
    @Test fun uk8() = s8()

    @Config(qualifiers = "en-w360dp-h780dp-xxhdpi") @Test fun en1() = s1()
    @Config(qualifiers = "en-w360dp-h780dp-xxhdpi") @Test fun en2() = s2()
    @Config(qualifiers = "en-w360dp-h780dp-xxhdpi") @Test fun en3() = s3()
    @Config(qualifiers = "en-w360dp-h780dp-xxhdpi") @Test fun en4() = s4()
    @Config(qualifiers = "en-w360dp-h780dp-xxhdpi") @Test fun en5() = s5()
    @Config(qualifiers = "en-w360dp-h780dp-night-xxhdpi") @Test fun en6() = s6()
    @Config(qualifiers = "en-w360dp-h780dp-xxhdpi") @Test fun en7() = s7()
    @Config(qualifiers = "en-w360dp-h780dp-xxhdpi") @Test fun en8() = s8()
}
