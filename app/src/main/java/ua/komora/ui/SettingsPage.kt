package ua.komora.ui
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.clickable
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.widthIn
import ua.komora.ui.theme.SectionTitle
import ua.komora.ui.theme.Group
import ua.komora.ui.theme.Segments
import ua.komora.notify.UpdateCheck
import ua.komora.notify.AutoUpdate
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ua.komora.R
import ua.komora.ui.theme.Look
import ua.komora.ui.theme.LookSettings
import ua.komora.ui.theme.Ornament

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
    sourcePictures: Boolean = false,
    onSourcePictures: (Boolean) -> Unit = {},
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
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                LookSettings(
                    look, onChange, language, onLanguage,
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp),
                ) {
                    UpdateSettings(updates, notifyDenied, onUpdates, onNotifySettings, auto, onAuto)
                    SourcesSettings(sourcesEnabled, onSources, pictures = sourcePictures, onPictures = onSourcePictures)
                }
                // Last of all, after the look
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) { AboutRow(onAbout) }
            }
        }
    }
}

/** "Updates": notices on or off, and whether the daily check may use mobile data. */
@Composable
internal fun UpdateSettings(
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
internal fun SwitchRow(title: String, summary: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
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
internal fun SourcesSettings(
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
    startAsking: Boolean = false,
    pictures: Boolean = false,
    onPictures: (Boolean) -> Unit = {},
    startAskingPictures: Boolean = false,
) {
    var asking by rememberSaveable { mutableStateOf(startAsking) }
    var askingPictures by rememberSaveable { mutableStateOf(startAskingPictures) }
    Spacer(Modifier.height(8.dp))
    SectionTitle(stringResource(R.string.sources_title))
    Group {
        // One under the other (a group lays its content on top of itself)
        Column {
            // Turning it on only after the warning is read and agreed to; turning it off needs nothing
            SwitchRow(stringResource(R.string.sources_switch), stringResource(R.string.sources_summary), enabled) { on ->
                if (on) asking = true else onChange(false)
            }
        }
    }
    // Added apps' screenshots and icons: only with added sources on, and again only after a warning
    if (enabled) {
        Spacer(Modifier.height(12.dp))
        Group {
            SwitchRow(stringResource(R.string.source_pictures_switch), stringResource(R.string.source_pictures_summary), pictures) { on ->
                if (on) askingPictures = true else onPictures(false)
            }
        }
    }
    if (asking) SourcesWarning(onAgree = { asking = false; onChange(true) }, onDismiss = { asking = false })
    if (askingPictures && enabled) PicturesWarning(onAgree = { askingPictures = false; onPictures(true) }, onDismiss = { askingPictures = false })
}

/** Why pictures from added sources are off unless asked for, and a plain yes or no. */
@Composable
internal fun PicturesWarning(onAgree: () -> Unit, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = scheme.errorContainer,
        iconContentColor = scheme.error,
        titleContentColor = scheme.onErrorContainer,
        textContentColor = scheme.onErrorContainer,
        icon = { Icon(painterResource(R.drawable.ic_warning), contentDescription = null, modifier = Modifier.size(32.dp)) },
        title = { Text(stringResource(R.string.source_pictures_switch)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.source_pictures_warn), style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            Button(
                onClick = onAgree,
                colors = ButtonDefaults.buttonColors(containerColor = scheme.error, contentColor = scheme.onError),
            ) { Text(stringResource(R.string.source_pictures_yes)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = scheme.onErrorContainer)) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/** The way to "About Komora", last in Settings. */
@Composable
internal fun AboutRow(onClick: () -> Unit) {
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
