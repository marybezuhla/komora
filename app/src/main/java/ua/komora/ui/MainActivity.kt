package ua.komora.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.animation.core.tween
import androidx.compose.animation.Crossfade
import ua.komora.ui.theme.SectionTitle
import ua.komora.ui.theme.Group
import ua.komora.notify.UpdateCheck
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.Bitmap
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
import androidx.compose.ui.platform.LocalConfiguration
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


class MainActivity : ComponentActivity() {
    private val vm: KomoraViewModel by viewModels()

    /** Re-read on every return, e.g. after the owner allowed installs in Settings. */
    private val canInstall = mutableStateOf(false)

    /** Light or dark, and the day and night themes; remembered on this phone only. */
    private val look = mutableStateOf(Look())

    /** Update notices (off by default), and whether Android refused notifications when asked. */
    private val updates = mutableStateOf(UpdateCheck.Settings())
    private val notifyDenied = mutableStateOf(false)

    /** Android's own question about notifications, asked only when the owner turns notices on. */
    private val askNotify = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) setUpdates(updates.value.copy(enabled = true)) else notifyDenied.value = true
    }

    private fun setUpdates(s: UpdateCheck.Settings) {
        if (s.enabled && !UpdateCheck.canNotify(this)) {
            // Android asks only before the first refusal; after that, the note offers its settings
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                notifyDenied.value = true
            }
            return
        }
        notifyDenied.value = false
        // If Android refuses the daily check, the switch stays off
        UpdateCheck.apply(this, s)
        updates.value = UpdateCheck.settings(this)
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        look.value = LookStore.load(this)
        updates.value = UpdateCheck.settings(this)
        UpdateCheck.ensure(this)
        UpdateCheck.createChannel(this)
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
            // Kept while Settings is open, so the list comes back exactly where it was
            val listState = rememberLazyListState()
            KomoraTheme(theme) {
                // A short fade between the list and Settings, instead of a flash
                Crossfade(targetState = showLook, animationSpec = tween(120), label = "page") { settings ->
                if (settings) {
                    LookPage(
                        look.value,
                        onBack = { showLook = false },
                        language = AppLanguage.current(this),
                        onLanguage = { AppLanguage.set(this, it) },
                        updates = updates.value,
                        notifyDenied = notifyDenied.value,
                        onUpdates = ::setUpdates,
                        onNotifySettings = {
                            start(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                        },
                    ) { changed ->
                        look.value = changed
                        LookStore.save(this, changed)
                    }
                } else {
                    KomoraScreen(
                        vm = vm,
                        canInstall = canInstall.value,
                        listState = listState,
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
    }

    /** Some phones lack a screen another app expects; that must never crash Komora. */
    private fun start(intent: Intent) {
        runCatching { startActivity(intent) }
    }

    override fun onResume() {
        super.onResume()
        canInstall.value = packageManager.canRequestPackageInstalls()
        // Notices turned off in Android since: say so next to the switch
        notifyDenied.value = updates.value.enabled && !UpdateCheck.canNotify(this)
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
private fun KomoraScreen(vm: KomoraViewModel, canInstall: Boolean, listState: LazyListState, onLook: () -> Unit, actions: Actions) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val confirm by Installer.confirm.collectAsStateWithLifecycle()
    KomoraList(
        rows, busy, confirm, canInstall, onLook, actions,
        listState = listState,
        onCheck = vm::checkUpdates,
        onInstall = vm::install,
        onCancel = vm::cancelInstall,
        onUpdateAll = vm::updateAll,
    )
}

/** "Look": light or dark, and the day and night themes, with a way back. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LookPage(
    look: Look,
    onBack: () -> Unit,
    language: AppLanguage.Choice = AppLanguage.Choice.AUTO,
    onLanguage: (AppLanguage.Choice) -> Unit = {},
    updates: UpdateCheck.Settings = UpdateCheck.Settings(),
    notifyDenied: Boolean = false,
    onUpdates: (UpdateCheck.Settings) -> Unit = {},
    onNotifySettings: () -> Unit = {},
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
        // On a tablet or an unfolded phone: a readable column in the middle, not stretched edge to edge
        Box(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter,
        ) {
            LookSettings(
                look, onChange, language, onLanguage,
                Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(16.dp),
            ) { UpdateSettings(updates, notifyDenied, onUpdates, onNotifySettings) }
        }
    }
}

/** "Updates": notices on or off, and whether the daily check may use mobile data. */
@Composable
private fun UpdateSettings(
    s: UpdateCheck.Settings,
    denied: Boolean,
    onChange: (UpdateCheck.Settings) -> Unit,
    onNotifySettings: () -> Unit,
) {
    Spacer(Modifier.height(8.dp))
    SectionTitle(stringResource(R.string.updates_title))
    Group {
        Column {
            SwitchRow(
                stringResource(R.string.notify_updates),
                stringResource(R.string.notify_updates_summary),
                s.enabled,
            ) { onChange(s.copy(enabled = it)) }
            if (denied) {
                Text(
                    stringResource(R.string.notify_denied),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
                TextButton(onClick = onNotifySettings) { Text(stringResource(R.string.notify_settings)) }
            }
            if (s.enabled) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SwitchRow(stringResource(R.string.wifi_only), null, s.wifiOnly) { onChange(s.copy(wifiOnly = it)) }
            }
        }
    }
}

/** A setting that is on or off: the whole row is the switch, read as one by TalkBack. */
@Composable
private fun SwitchRow(title: String, summary: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (summary != null) {
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
