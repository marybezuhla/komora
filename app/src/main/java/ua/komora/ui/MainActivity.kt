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
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.clickable
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.MutableState
import ua.komora.install.ApkVerifier
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
import ua.komora.ui.theme.Segments
import ua.komora.notify.UpdateCheck
import ua.komora.notify.AutoUpdate
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

    /** Automatic updates (off by default). */
    private val auto = mutableStateOf(AutoUpdate.Settings())

    /** Asked once when automatic updates are turned on, so the owner can hear what was updated; they work either way. */
    private val askNotifyForAuto = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun setAuto(s: AutoUpdate.Settings) {
        val turningOn = s.enabled && !auto.value.enabled
        AutoUpdate.apply(this, s)
        auto.value = AutoUpdate.settings(this)
        if (turningOn && auto.value.enabled && Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askNotifyForAuto.launch(Manifest.permission.POST_NOTIFICATIONS)
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
        auto.value = AutoUpdate.settings(this)
        AutoUpdate.ensure(this)
        UpdateCheck.createChannel(this)
        // Komora is open: its list says the same, so the notice is not needed any more
        UpdateCheck.dismiss(this)
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
            var showAbout by rememberSaveable { mutableStateOf(false) }
            BackHandler(enabled = showLook) { if (showAbout) showAbout = false else showLook = false }
            // Kept while Settings is open, so the list comes back exactly where it was
            val listState = rememberLazyListState()
            val addedListState = rememberLazyListState()
            val addedTab = rememberSaveable { mutableStateOf(false) }
            KomoraTheme(theme) {
                // A short fade between the list and Settings, instead of a flash
                Crossfade(targetState = if (showLook && showAbout) 2 else if (showLook) 1 else 0, animationSpec = tween(120), label = "page") { page ->
                if (page == 2) {
                    AboutPage(onBack = { showAbout = false })
                } else if (page == 1) {
                    LookPage(
                        look.value,
                        onBack = { showLook = false },
                        language = AppLanguage.current(this),
                        onLanguage = { AppLanguage.set(this, it) },
                        updates = updates.value,
                        notifyDenied = notifyDenied.value,
                        onUpdates = ::setUpdates,
                        auto = auto.value,
                        onAuto = ::setAuto,
                        onNotifySettings = {
                            start(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                        },
                        sourcesEnabled = vm.sourcesEnabled.collectAsStateWithLifecycle().value,
                        onSources = vm::setSourcesEnabled,
                        onAbout = { showAbout = true },
                    ) { changed ->
                        look.value = changed
                        LookStore.save(this, changed)
                    }
                } else {
                    KomoraScreen(
                        vm = vm,
                        canInstall = canInstall.value,
                        listState = listState,
                        addedListState = addedListState,
                        addedTab = addedTab,
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
                            uninstall = { pkg -> start(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))) },
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

    /** An app removed or replaced while Komora is open (Android's uninstaller closes before it is done) */
    private val packagesChanged = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // An update sends removed, added and replaced: only "replaced" counts then
            val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
            if (replacing && intent.action != Intent.ACTION_PACKAGE_REPLACED) return
            vm.refreshInstalled()
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        androidx.core.content.ContextCompat.registerReceiver(this, packagesChanged, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStop() {
        runCatching { unregisterReceiver(packagesChanged) }
        super.onStop()
    }
}

internal class Actions(
    val allowInstalls: () -> Unit,
    val open: (String) -> Unit,
    val canOpen: (String) -> Boolean,
    val appSettings: (String) -> Unit,
    val start: (Intent) -> Unit,
    /** Android's own "Uninstall?" question for the app. */
    val uninstall: (String) -> Unit = { },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KomoraScreen(
    vm: KomoraViewModel,
    canInstall: Boolean,
    listState: LazyListState,
    addedListState: LazyListState,
    addedTab: MutableState<Boolean>,
    onLook: () -> Unit,
    actions: Actions,
) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val confirm by Installer.confirm.collectAsStateWithLifecycle()
    val sourcesOn by vm.sourcesEnabled.collectAsStateWithLifecycle()
    val adding by vm.adding.collectAsStateWithLifecycle()
    val trust by vm.trust.collectAsStateWithLifecycle()
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var detailsKey by rememberSaveable { mutableStateOf<String?>(null) }
    // A source removed from the list can be brought back at once, with its key; only then is it gone for good
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    val removed by vm.removed.collectAsStateWithLifecycle()
    val removedText = removed?.let { stringResource(R.string.source_removed, it) }
    val undo = stringResource(R.string.undo)
    LaunchedEffect(removed) {
        val text = removedText ?: return@LaunchedEffect
        val answer = snackbar.showSnackbar(text, actionLabel = undo, withDismissAction = true,
            duration = androidx.compose.material3.SnackbarDuration.Long)
        if (answer == androidx.compose.material3.SnackbarResult.ActionPerformed) vm.undoRemove() else vm.forgetRemoved()
    }
    // The add dialog closes by itself once everything asked for is added
    LaunchedEffect(adding) {
        if (adding is AddState.Done) {
            showAdd = false
            vm.dismissAdding()
        }
    }
    KomoraList(
        rows, busy, confirm, canInstall, onLook, actions,
        listState = listState,
        onCheck = vm::checkUpdates,
        onInstall = vm::install,
        onCancel = vm::cancelInstall,
        onUpdateAll = vm::updateAll,
        sourcesEnabled = sourcesOn,
        onAddSource = { vm.dismissAdding(); showAdd = true },
        onShareSources = {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, vm.sourcesAsText())
            actions.start(Intent.createChooser(send, null))
        },
        onDetails = { detailsKey = it.app.key },
        addedTab = addedTab.value,
        onTab = { addedTab.value = it },
        addedListState = addedListState,
        snackbar = snackbar,
    )
    if (showAdd) {
        AddSourceDialog(adding, onAdd = vm::addSource, onChoose = vm::chooseApk, onSkipChoice = vm::skipChoice) {
            vm.dismissAdding()
            showAdd = false
        }
    }
    trust?.let { request -> TrustDialog(request) { yes -> vm.answerTrust(request, yes) } }
    val context = LocalContext.current
    val lang = stringResource(R.string.catalog_lang)
    rows.firstOrNull { it.app.key == detailsKey }?.let { row ->
        val key = remember(row.app.key, row.app.certSha256) {
            runCatching { ApkVerifier.pinnedCert(context, row.app) }.getOrNull()?.takeIf { it.length == 64 }
        }
        DetailsSheet(
            row, lang, key,
            // Not while it is downloading or installing
            onRemove = if (row.app.added && !row.isBusy) ({ vm.removeSource(row.app.repo); detailsKey = null }) else null,
            onDismiss = { detailsKey = null },
        )
    }
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
    auto: AutoUpdate.Settings = AutoUpdate.Settings(),
    onAuto: (AutoUpdate.Settings) -> Unit = {},
    onNotifySettings: () -> Unit = {},
    sourcesEnabled: Boolean = false,
    onSources: (Boolean) -> Unit = {},
    onAbout: () -> Unit = {},
    onChange: (Look) -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.background)) {
                // Every page has its title in the middle, over the motif
                CenterAlignedTopAppBar(
                    title = { Text(stringResource(R.string.look), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background),
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
            ) {
                UpdateSettings(updates, notifyDenied, onUpdates, onNotifySettings, auto, onAuto)
                SourcesSettings(sourcesEnabled, onSources)
                AboutRow(onAbout)
            }
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
    auto: AutoUpdate.Settings,
    onAuto: (AutoUpdate.Settings) -> Unit,
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
                Text(stringResource(R.string.notify_how_often), style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 8.dp))
                Segments(
                    options = listOf(false to R.string.notify_daily, true to R.string.notify_weekly),
                    chosen = s.weekly,
                ) { onChange(s.copy(weekly = it)) }
                Spacer(Modifier.height(8.dp))
                SwitchRow(stringResource(R.string.wifi_only), null, s.wifiOnly) { onChange(s.copy(wifiOnly = it)) }
            }
        }
    }
    // Automatic updates: a block of its own, under the notices
    Spacer(Modifier.height(12.dp))
    Group {
        Column {
            SwitchRow(stringResource(R.string.auto_update), stringResource(R.string.auto_update_summary), auto.enabled) {
                onAuto(auto.copy(enabled = it))
            }
            if (auto.enabled) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(stringResource(R.string.auto_network), style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 8.dp))
                Segments(
                    options = listOf(true to R.string.auto_wifi, false to R.string.auto_any_network),
                    chosen = auto.wifiOnly,
                ) { onAuto(auto.copy(wifiOnly = it)) }
                Spacer(Modifier.height(8.dp))
                SwitchRow(stringResource(R.string.auto_charging), null, auto.charging) { onAuto(auto.copy(charging = it)) }
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
            // A title: whole words, not justified
            Text(title, style = MaterialTheme.typography.bodyLarge.copy(
                textAlign = androidx.compose.ui.text.style.TextAlign.Start, hyphens = androidx.compose.ui.text.style.Hyphens.None), color = MaterialTheme.colorScheme.onSurface)
            if (summary != null) {
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** "Other sources": off by default; turning it on shows the second tab, with a plain warning. */
@Composable
internal fun SourcesSettings(enabled: Boolean, onChange: (Boolean) -> Unit, startAsking: Boolean = false) {
    var asking by rememberSaveable { mutableStateOf(startAsking) }
    Spacer(Modifier.height(8.dp))
    SectionTitle(stringResource(R.string.sources_title))
    Group {
        // Turning it on only after the warning is read and agreed to; turning it off needs nothing
        SwitchRow(stringResource(R.string.sources_switch), stringResource(R.string.sources_summary), enabled) { on ->
            if (on) asking = true else onChange(false)
        }
    }
    if (asking) SourcesWarning(onAgree = { asking = false; onChange(true) }, onDismiss = { asking = false })
}

/** The way to "About Komora", last in Settings. */
@Composable
private fun AboutRow(onClick: () -> Unit) {
    Spacer(Modifier.height(16.dp))
    Group {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.about_komora), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.about_komora_summary), style = MaterialTheme.typography.bodyMedium.copy(
                    textAlign = androidx.compose.ui.text.style.TextAlign.Start, hyphens = androidx.compose.ui.text.style.Hyphens.None),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = null,
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.graphicsLayer { rotationZ = 180f })
        }
    }
}

/** The risks, plainly, and a box to tick before other sources can be turned on. */
@Composable
internal fun SourcesWarning(onAgree: () -> Unit, onDismiss: () -> Unit) {
    var understood by rememberSaveable { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = scheme.errorContainer,
        iconContentColor = scheme.error,
        titleContentColor = scheme.onErrorContainer,
        textContentColor = scheme.onErrorContainer,
        icon = { Icon(painterResource(R.drawable.ic_warning), contentDescription = null, modifier = Modifier.size(32.dp)) },
        title = { Text(stringResource(R.string.sources_warn_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.sources_warn_text), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.sources_warn_disclaimer), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .toggleable(value = understood, role = Role.Checkbox) { understood = it },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = understood, onCheckedChange = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.sources_warn_check), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onAgree,
                enabled = understood,
                colors = ButtonDefaults.buttonColors(containerColor = scheme.error, contentColor = scheme.onError),
            ) { Text(stringResource(R.string.sources_warn_yes)) }
        },
        // Ordinary text on the warning: the accent is too faint there at night
        dismissButton = {
            TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = scheme.onErrorContainer)) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
