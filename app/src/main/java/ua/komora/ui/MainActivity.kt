package ua.komora.ui

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
import ua.komora.install.Installer

private const val SOURCE_URL = "https://github.com/Marybezu/komora"
private const val LICENSE_URL = "$SOURCE_URL/blob/main/LICENSE"

class MainActivity : ComponentActivity() {
    private val vm: KomoraViewModel by viewModels()

    /** Re-read on every return, e.g. after the owner allowed installs in Settings. */
    private val canInstall = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
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
            KomoraTheme {
                KomoraScreen(
                    vm = vm,
                    canInstall = canInstall.value,
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

private class Actions(
    val allowInstalls: () -> Unit,
    val open: (String) -> Unit,
    val canOpen: (String) -> Boolean,
    val appSettings: (String) -> Unit,
    val start: (Intent) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KomoraScreen(vm: KomoraViewModel, canInstall: Boolean, actions: Actions) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val confirm by Installer.confirm.collectAsStateWithLifecycle()
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
                        IconButton(onClick = vm::checkUpdates) {
                            Icon(painterResource(R.drawable.ic_refresh), contentDescription = stringResource(R.string.refresh))
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
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!canInstall) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                stringResource(R.string.allow_title),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.semantics { heading() },
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(stringResource(R.string.allow_text), style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = actions.allowInstalls) { Text(stringResource(R.string.allow_button)) }
                        }
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
                    onInstall = { vm.install(row) },
                    onOpen = { actions.open(row.app.packageName) },
                    onAppSettings = { actions.appSettings(row.app.packageName) },
                    onRetry = vm::checkUpdates,
                    onContinue = waiting?.let { c -> { actions.start(c.intent) } },
                    onCancel = vm::cancelInstall,
                )
            }
            item { About() }
        }
    }
}

@Composable
private fun About() {
    val uri = LocalUriHandler.current
    Column(Modifier.padding(top = 8.dp)) {
        Text(
            stringResource(R.string.footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.about_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(R.string.about_text),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRowCompat {
            TextButton(onClick = { runCatching { uri.openUri(SOURCE_URL) } }) { Text(stringResource(R.string.source_code)) }
            TextButton(onClick = { runCatching { uri.openUri(LICENSE_URL) } }) { Text(stringResource(R.string.license)) }
        }
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
    // As Pysanka's blocks: a coloured header band with the name, and a thin frame of the same colour
    Card(
        colors = CardDefaults.cardColors(containerColor = scheme.surface),
        border = BorderStroke(1.dp, scheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        // One swipe reads one app: name, what it is, and where it stands
        Column(Modifier.semantics(mergeDescendants = true) {}) {
            Row(
                Modifier.fillMaxWidth().background(scheme.primaryContainer).heightIn(min = 52.dp)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(name, style = CardTitle, color = scheme.onPrimaryContainer, modifier = Modifier.semantics { heading() })
            }
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
                Text(row.app.summary(lang), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    statusText(row),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }

        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {

            val problem = if (row.foreignKey) Problem.OtherKey else row.problem
            problem?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    problemText(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                )
            }

            Spacer(Modifier.height(12.dp))
            when (val phase = row.phase) {
                is Phase.Downloading -> {
                    LinearProgressIndicator(progress = { phase.fraction }, modifier = Modifier.fillMaxWidth())
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
                Phase.Checking, Phase.Idle -> FlowRowCompat {
                    val installLabel = stringResource(R.string.install)
                    val updateLabel = stringResource(R.string.update)
                    val openLabel = stringResource(R.string.open)
                    when {
                        row.foreignKey -> OutlinedButton(onClick = onAppSettings) { Text(stringResource(R.string.app_settings)) }
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
                    if (canOpen) {
                        OutlinedButton(
                            onClick = onOpen,
                            modifier = Modifier.semantics { contentDescription = "$openLabel $name" },
                        ) { Text(openLabel) }
                    }
                    if (problem == Problem.Network || problem == Problem.RateLimit) {
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
