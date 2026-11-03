package ua.komora.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.Image
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.activity.compose.BackHandler
import androidx.activity.SystemBarStyle
import android.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import ua.komora.R
import ua.komora.install.Confirm
import ua.komora.install.Installer
import ua.komora.ui.theme.CardTitle
import ua.komora.ui.theme.KomoraTheme
import ua.komora.ui.theme.Look
import ua.komora.ui.theme.LookSettings
import ua.komora.ui.theme.LookStore
import ua.komora.ui.theme.Ornament

private const val SOURCE_URL = "https://github.com/Marybezu/komora"
private const val LICENSE_URL = "$SOURCE_URL/blob/main/LICENSE"

class MainActivity : ComponentActivity() {
    private val vm: KomoraViewModel by viewModels()

    /** Re-read on every return, e.g. after the owner allowed installs in Settings. */
    private val canInstall = mutableStateOf(false)

    /** Light or dark, and the day and night themes; remembered on this phone only. */
    private val look = mutableStateOf(Look())

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        look.value = LookStore.load(this)
        // Android's confirmation screen opens only while Komora is in front
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                // Opened once by itself; if it gets lost, the "Continue" button opens it again
                Installer.confirm.collect { c ->
                    if (c != null && !c.shown) {
                        Installer.markShown(c.sessionId)
                        start(c.intent)
                    }
                }
            }
        }
        setContent {
            val theme = look.value.current(isSystemInDarkTheme())
            // Status and navigation bar icons follow the chosen theme, not only the phone
            LaunchedEffect(theme.dark) {
                val bars = if (theme.dark) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            }
            var showLook by rememberSaveable { mutableStateOf(false) }
            BackHandler(enabled = showLook) { showLook = false }
            KomoraTheme(theme) {
                if (showLook) {
                    LookPage(
                        look.value,
                        onBack = { showLook = false },
                        language = AppLanguage.current(this),
                        onLanguage = { AppLanguage.set(this, it) },
                    ) { changed ->
                        look.value = changed
                        LookStore.save(this, changed)
                    }
                } else {
                    KomoraScreen(
                        vm = vm,
                        canInstall = canInstall.value,
                        onLook = { showLook = true },
                        actions = Actions(
                            allowInstalls = {
                                start(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                            },
                            open = { pkg -> packageManager.getLaunchIntentForPackage(pkg)?.let(::start) },
                            canOpen = { pkg -> packageManager.getLaunchIntentForPackage(pkg) != null },
                            appSettings = { pkg ->
                                start(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
                            },
                            start = ::start,
                        ),
                    )
                }
            }
        }
    }

    /** Some phones lack a screen another app expects; that must never crash Komora. */
    private fun start(intent: Intent) {
        runCatching { startActivity(intent) }
    }

    override fun onResume() {
        super.onResume()
        canInstall.value = packageManager.canRequestPackageInstalls()
        vm.refreshInstalled()
    }
}

internal class Actions(
    val allowInstalls: () -> Unit,
    val open: (String) -> Unit,
    val canOpen: (String) -> Boolean,
    val appSettings: (String) -> Unit,
    val start: (Intent) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KomoraScreen(vm: KomoraViewModel, canInstall: Boolean, onLook: () -> Unit, actions: Actions) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val confirm by Installer.confirm.collectAsStateWithLifecycle()
    KomoraList(
        rows, busy, confirm, canInstall, onLook, actions,
        onCheck = vm::checkUpdates,
        onInstall = vm::install,
        onCancel = vm::cancelInstall,
    )
}

/** The list itself, from plain values only: what the app shows, and what the screen tests draw. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun KomoraList(
    rows: List<AppRow>,
    busy: Boolean,
    confirm: Confirm?,
    canInstall: Boolean,
    onLook: () -> Unit,
    actions: Actions,
    onCheck: () -> Unit,
    onInstall: (AppRow) -> Unit,
    onCancel: () -> Unit,
) {
    // Same language as the rest of the screen (Android picks the strings; the catalog follows them)
    val lang = stringResource(R.string.catalog_lang)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.background)) {
                TopAppBar(
                    title = {
                        Text(
                            stringResource(R.string.app_name),
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                    },
                    actions = {
                        // A round, framed button, like a chip
                        OutlinedIconButton(
                            onClick = onLook,
                            colors = IconButtonDefaults.outlinedIconButtonColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.padding(end = 8.dp),
                        ) {
                            Icon(painterResource(R.drawable.ic_palette), contentDescription = stringResource(R.string.look))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
                Ornament(Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!canInstall) {
                item {
                    // The one thing to do first: framed in the accent
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                stringResource(R.string.allow_title),
                                style = CardTitle,
                                modifier = Modifier.semantics { heading() },
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.allow_text),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = actions.allowInstalls, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.allow_button))
                            }
                        }
                    }
                }
            }
            item {
                // "Apps · 6" on the left, the check on the right
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.apps_header, rows.size),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    TextButton(onClick = onCheck) {
                        Text(stringResource(R.string.refresh), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            items(rows, key = { it.app.packageName }) { row ->
                val waiting = (row.phase as? Phase.Installing)?.sessionId?.let { id -> confirm?.takeIf { it.sessionId == id } }
                AppCard(
                    row = row,
                    lang = lang,
                    canGet = canInstall && !busy && row.latest != null,
                    canOpen = row.isInstalled && !row.app.isSelf && actions.canOpen(row.app.packageName),
                    onInstall = { onInstall(row) },
                    onOpen = { actions.open(row.app.packageName) },
                    onAppSettings = { actions.appSettings(row.app.packageName) },
                    onRetry = onCheck,
                    onContinue = waiting?.let { c -> { actions.start(c.intent) } },
                    onCancel = onCancel,
                )
            }
            item { About() }
        }
    }
}

@Composable
private fun About() {
    val uri = LocalUriHandler.current
    val note = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.footer), style = MaterialTheme.typography.bodySmall, color = note, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.footer_permissions), style = MaterialTheme.typography.bodySmall, color = note, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.about_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(R.string.about_text), style = MaterialTheme.typography.bodySmall, color = note, textAlign = TextAlign.Center)
        FlowRowCompat {
            TextButton(onClick = { runCatching { uri.openUri(SOURCE_URL) } }) { Text(stringResource(R.string.source_code)) }
            TextButton(onClick = { runCatching { uri.openUri(LICENSE_URL) } }) { Text(stringResource(R.string.license)) }
        }
    }
}

/**
 * Each app's own icon, copied from its repository into Komora (round, as most launchers show it).
 * Listed by hand, not looked up by name, so the build's resource shrinking keeps them.
 */
private val APP_ICONS = mapOf(
    "ua.komora" to R.drawable.app_icon_komora,
    "ua.tarilka" to R.drawable.app_icon_tarilka,
    "ua.mandat" to R.drawable.app_icon_mandat,
    "ua.sprotyv" to R.drawable.app_icon_sprotyv,
    "ua.pysanka" to R.drawable.app_icon_pysanka,
    "ua.svitlyna" to R.drawable.app_icon_svitlyna,
)

/** The app's icon; an app without one gets a small square with its first letters. Decoration only. */
@Composable
private fun AppIcon(packageName: String, name: String, color: Long) {
    val icon = APP_ICONS[packageName]
    if (icon != null) {
        Image(
            painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(44.dp).clip(CircleShape).clearAndSetSemantics { },
        )
        return
    }
    Box(
        Modifier.size(44.dp).background(androidx.compose.ui.graphics.Color(color), RoundedCornerShape(10.dp)).clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.take(2),
            color = androidx.compose.ui.graphics.Color.White,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun AppCard(
    row: AppRow,
    lang: String,
    canGet: Boolean,
    canOpen: Boolean,
    onInstall: () -> Unit,
    onOpen: () -> Unit,
    onAppSettings: () -> Unit,
    onRetry: () -> Unit,
    onContinue: (() -> Unit)?,
    onCancel: () -> Unit,
) {
    val name = row.app.name(lang)
    val scheme = MaterialTheme.colorScheme
    // A light card with a thin frame: the app's square, its name, what it is, and where it stands
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surface),
        border = BorderStroke(1.dp, scheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 14.dp, end = 16.dp, top = 14.dp), verticalAlignment = Alignment.Top) {
            AppIcon(row.app.packageName, name, row.app.badge)
            Spacer(Modifier.width(12.dp))
            // One swipe reads one app
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                Text(name, style = CardTitle, color = scheme.onSurface, modifier = Modifier.semantics { heading() })
                Text(row.app.summary(lang), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Text(
                    statusText(row),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }

        // A copy from another source: said at once when there is nothing to update; otherwise after a try
        val problem = row.problem ?: Problem.OtherKey.takeIf { row.foreignKey && !row.hasUpdate }
        val retry = problem == Problem.Network || problem == Problem.RateLimit
        val idle = row.phase == Phase.Idle || row.phase == Phase.Checking
        val anyAction = !row.isInstalled || row.hasUpdate || problem == Problem.OtherKey || canOpen || retry
        val below = problem != null || !idle || anyAction

        // Everything below lines up with the name, not with the coloured square
        Column(Modifier.padding(start = 70.dp, end = 16.dp, top = if (below) 10.dp else 0.dp, bottom = 14.dp)) {
            problem?.let {
                // A sign as well as the colour: in red themes the error and the accent are close
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        painterResource(R.drawable.ic_warning),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 1.dp, end = 6.dp).size(16.dp),
                    )
                    Text(
                        problemText(it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                if (!idle || anyAction) Spacer(Modifier.height(10.dp))
            }

            when (val phase = row.phase) {
                is Phase.Downloading -> {
                    LinearProgressIndicator(progress = { phase.fraction }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    val ctx = LocalContext.current
                    val percent = (phase.fraction * 100).toInt()
                    val spoken = stringResource(R.string.downloading_short, percent / 10 * 10)
                    Text(
                        stringResource(
                            R.string.downloading, percent,
                            Formatter.formatShortFileSize(ctx, phase.bytes), Formatter.formatShortFileSize(ctx, phase.total),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        // Read aloud only every 10%, not on every change
                        modifier = Modifier.semantics {
                            contentDescription = spoken
                            liveRegion = LiveRegionMode.Polite
                        },
                    )
                    Text(
                        stringResource(R.string.keep_open),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                }
                is Phase.Installing -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(if (row.app.isSelf) R.string.updating_self else R.string.installing),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    // Android is waiting for the owner's answer on its own screen
                    if (onContinue != null) {
                        Spacer(Modifier.height(8.dp))
                        FlowRowCompat {
                            Button(onClick = onContinue) { Text(stringResource(R.string.continue_install)) }
                            OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                        }
                    }
                }
                Phase.Checking, Phase.Idle -> if (anyAction) FlowRowCompat {
                    val installLabel = stringResource(R.string.install)
                    val updateLabel = stringResource(R.string.update)
                    val openLabel = stringResource(R.string.open)
                    when {
                        !row.isInstalled -> Button(
                            onClick = onInstall,
                            enabled = canGet,
                            modifier = Modifier.semantics { contentDescription = "$installLabel $name" },
                        ) { Text(installLabel) }
                        row.hasUpdate -> Button(
                            onClick = onInstall,
                            enabled = canGet,
                            modifier = Modifier.semantics { contentDescription = "$updateLabel $name" },
                        ) { Text(updateLabel) }
                    }
                    if (problem == Problem.OtherKey) {
                        OutlinedButton(onClick = onAppSettings) { Text(stringResource(R.string.app_settings)) }
                    }
                    if (canOpen) {
                        OutlinedButton(
                            onClick = onOpen,
                            modifier = Modifier.semantics { contentDescription = "$openLabel $name" },
                        ) { Text(openLabel) }
                    }
                    if (retry) {
                        TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
                    }
                }
            }
        }
    }
}

/** Buttons wrap to a new line when the font is large or the screen is narrow. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowCompat(content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) { content() }
}

@Composable
private fun statusText(row: AppRow): String {
    if (row.phase == Phase.Checking) return stringResource(R.string.checking)
    val left = when (val v = row.installedVersion) {
        null -> stringResource(R.string.not_installed)
        "" -> stringResource(R.string.installed)
        else -> stringResource(R.string.installed_version, v)
    }
    val latest = row.latestVersion ?: return left
    val size = Formatter.formatShortFileSize(LocalContext.current, row.latest!!.apk.size)
    val right = when {
        !row.isInstalled || row.hasUpdate -> stringResource(R.string.available_version, latest, size)
        else -> stringResource(R.string.up_to_date)
    }
    return "$left, $right"
}

@Composable
private fun problemText(p: Problem): String = stringResource(
    when (p) {
        Problem.Network -> R.string.err_network
        Problem.RateLimit -> R.string.err_rate_limit
        Problem.NoRelease -> R.string.err_no_release
        Problem.BadRelease -> R.string.err_bad_release
        Problem.Download -> R.string.err_download
        Problem.NoSpace -> R.string.err_no_space
        Problem.Unreadable -> R.string.err_unreadable
        Problem.WrongPackage -> R.string.err_wrong_package
        Problem.WrongSigner -> R.string.err_wrong_signer
        Problem.NotNewer -> R.string.err_not_newer
        Problem.OtherKey -> R.string.err_other_key
        Problem.Install -> R.string.err_install
    },
)

/** "Look": light or dark, and the day and night themes, with a way back. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LookPage(
    look: Look,
    onBack: () -> Unit,
    language: AppLanguage.Choice = AppLanguage.Choice.AUTO,
    onLanguage: (AppLanguage.Choice) -> Unit = {},
    onChange: (Look) -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.background)) {
                TopAppBar(
                    title = { Text(stringResource(R.string.look), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
                Ornament(Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
            }
        },
    ) { padding ->
        LookSettings(
            look, onChange, language, onLanguage,
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
        )
    }
}
