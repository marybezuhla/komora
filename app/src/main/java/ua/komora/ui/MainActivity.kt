package ua.komora.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import ua.komora.R
import ua.komora.install.Installer
import ua.komora.net.Rules

private const val SOURCE_URL = "https://github.com/Marybezu/komora"

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
                        runCatching { startActivity(c.intent) }
                    }
                }
            }
        }
        setContent {
            val dark = isSystemInDarkTheme()
            val ctx = LocalContext.current
            MaterialTheme(colorScheme = if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)) {
                KomoraScreen(
                    vm = vm,
                    canInstall = canInstall.value,
                    onAllow = {
                        startActivity(
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")),
                        )
                    },
                    onOpen = { pkg -> packageManager.getLaunchIntentForPackage(pkg)?.let(::startActivity) },
                    onContinue = { intent -> runCatching { startActivity(intent) } },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        canInstall.value = packageManager.canRequestPackageInstalls()
        vm.refreshInstalled()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KomoraScreen(
    vm: KomoraViewModel,
    canInstall: Boolean,
    onAllow: () -> Unit,
    onOpen: (String) -> Unit,
    onContinue: (Intent) -> Unit,
) {
    val rows by vm.rows.collectAsState()
    val confirm by Installer.confirm.collectAsState()
    val lang = remember { if (java.util.Locale.getDefault().language == "uk") "uk" else "en" }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = vm::checkUpdates) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
                },
            )
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
                            Text(stringResource(R.string.allow_title), style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(stringResource(R.string.allow_text), style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = onAllow) { Text(stringResource(R.string.allow_button)) }
                        }
                    }
                }
            }
            items(rows, key = { it.app.packageName }) { row ->
                val waiting = (row.phase as? Phase.Installing)?.sessionId?.let { id -> confirm?.takeIf { it.sessionId == id } }
                AppCard(
                    row, lang, canInstall,
                    onInstall = { vm.install(row) },
                    onOpen = { onOpen(row.app.packageName) },
                    onContinue = waiting?.let { c -> { onContinue(c.intent) } },
                    onCancel = vm::cancelInstall,
                )
            }
            item {
                val uri = LocalUriHandler.current
                Column(Modifier.padding(top = 8.dp)) {
                    Text(
                        stringResource(R.string.footer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.about_title), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.about_text),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { runCatching { uri.openUri(SOURCE_URL) } }) { Text(stringResource(R.string.source_code)) }
                }
            }
        }
    }
}

@Composable
private fun AppCard(
    row: AppRow,
    lang: String,
    canInstall: Boolean,
    onInstall: () -> Unit,
    onOpen: () -> Unit,
    onContinue: (() -> Unit)?,
    onCancel: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(row.app.name(lang), style = MaterialTheme.typography.titleMedium)
            Text(row.app.summary(lang), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Text(statusText(row), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            row.problem?.let {
                Spacer(Modifier.height(4.dp))
                Text(problemText(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(12.dp))
            when (val phase = row.phase) {
                is Phase.Downloading -> {
                    LinearProgressIndicator(progress = { phase.progress }, modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.downloading, (phase.progress * 100).toInt()), style = MaterialTheme.typography.bodySmall)
                }
                is Phase.Installing -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.installing), style = MaterialTheme.typography.bodySmall)
                    if (phase.sessionId != null) {
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Android is waiting for the owner's answer on its own screen
                            onContinue?.let { Button(onClick = it) { Text(stringResource(R.string.continue_install)) } }
                            OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                        }
                    }
                }
                Phase.Checking, Phase.Idle -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val canGet = canInstall && row.latest != null
                    when {
                        !row.isInstalled -> Button(onClick = onInstall, enabled = canGet) { Text(stringResource(R.string.install)) }
                        row.hasUpdate -> Button(onClick = onInstall, enabled = canGet) { Text(stringResource(R.string.update)) }
                    }
                    if (row.isInstalled && !row.app.isSelf) {
                        OutlinedButton(onClick = onOpen) { Text(stringResource(R.string.open)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun statusText(row: AppRow): String {
    val installed = row.installedVersion
    val latest = row.latest?.let { Rules.normalizeVersion(it.tag) }
    return when {
        row.phase == Phase.Checking -> stringResource(R.string.checking)
        installed == null && latest == null -> stringResource(R.string.not_installed)
        installed == null -> stringResource(R.string.not_installed) + " · " + stringResource(R.string.available_version, latest!!)
        row.hasUpdate -> stringResource(R.string.installed_version, installed) + " · " + stringResource(R.string.available_version, latest!!)
        latest != null -> stringResource(R.string.installed_version, installed) + " · " + stringResource(R.string.up_to_date)
        else -> stringResource(R.string.installed_version, installed)
    }
}

@Composable
private fun problemText(p: Problem): String = stringResource(
    when (p) {
        Problem.Network -> R.string.err_network
        Problem.RateLimit -> R.string.err_rate_limit
        Problem.NoRelease -> R.string.err_no_release
        Problem.BadRelease -> R.string.err_bad_release
        Problem.Unreadable -> R.string.err_unreadable
        Problem.WrongPackage -> R.string.err_wrong_package
        Problem.WrongSigner -> R.string.err_wrong_signer
        Problem.NotNewer -> R.string.err_not_newer
        Problem.OtherKey -> R.string.err_other_key
        Problem.Install -> R.string.err_install
    },
)
