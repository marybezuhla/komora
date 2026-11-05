package ua.komora.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import ua.komora.install.ApkVerifier
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.animation.core.tween
import androidx.compose.animation.Crossfade
import ua.komora.notify.UpdateCheck
import ua.komora.notify.AutoUpdate
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.activity.compose.BackHandler
import androidx.activity.SystemBarStyle
import android.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import ua.komora.R
import ua.komora.install.Installer
import ua.komora.ui.theme.KomoraTheme
import ua.komora.ui.theme.Look
import ua.komora.ui.theme.LookStore


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
                        sourcePictures = vm.sourcePictures.collectAsStateWithLifecycle().value,
                        onSourcePictures = vm::setSourcePictures,
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
        onCheck = { vm.checkUpdates(force = true) },
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
