package ua.komora.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ua.komora.R
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.clickable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.text.style.Hyphens
import ua.komora.net.ReleaseNotes
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.HorizontalDivider
import android.os.Build
import java.net.URLEncoder

/** A new issue in [repo] with a title and the text already there; nothing is sent until the owner does. */
fun issueUrl(repo: String, name: String, version: String, body: String): String {
    fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    return "https://github.com/$repo/issues/new?title=${enc("$name $version: ")}&body=${enc(body)}"
}

/** A signing key, as people compare it: 64 hex digits in groups of four, on four lines. */
fun formatKey(sha256: String): String =
    sha256.uppercase().chunked(4).chunked(4).joinToString("\n") { it.joinToString(" ") }

/** "Add a source": a GitHub link or owner/name; the steps after it come from [state]. */
@Composable
fun AddSourceDialog(
    state: AddState,
    onAdd: (String) -> Unit,
    onChoose: (String, String) -> Unit,
    onSkipChoice: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    if (state is AddState.Choose) {
        // Closing the choice skips only this repository when several are being added
        ChooseApkDialog(state, onChoose, onSkipChoice)
        return
    }
    val working = state is AddState.Working
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_source)) },
        text = {
            Column {
                Text(stringResource(R.string.add_source_text), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(2000) },
                    label = { Text(stringResource(R.string.add_source_field)) },
                    placeholder = { Text("github.com/owner/name") },
                    // One link, or several pasted from "Share the list"
                    singleLine = false,
                    maxLines = 6,
                    enabled = !working,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state is AddState.Failed) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (state.reason == AddError.SomeFailed) stringResource(R.string.add_err_some, state.repos.joinToString(", "))
                        else addErrorText(state.reason),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
                if (working) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.padding(start = 8.dp))
                        Text(stringResource(R.string.add_source_looking), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onAdd(text) }, enabled = !working && text.isNotBlank()) { Text(stringResource(R.string.add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** The release has several APKs: pick the one for this phone (usually "universal" or "arm64"). */
@Composable
private fun ChooseApkDialog(state: AddState.Choose, onChoose: (String, String) -> Unit, onDismiss: () -> Unit) {
    var chosen by rememberSaveable(state.repo) { mutableStateOf(state.names.firstOrNull { "universal" in it } ?: state.names.first()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.choose_apk)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
                // Which repository this is about (several may be added one after another)
                Text("github.com/${state.repo}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.choose_apk_text), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                for (name in state.names) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(name == chosen, role = Role.RadioButton) { chosen = name },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = name == chosen, onClick = null)
                        Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onChoose(state.repo, chosen) }) { Text(stringResource(R.string.add)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun addErrorText(e: AddError): String = stringResource(
    when (e) {
        AddError.BadLink -> R.string.add_err_link
        AddError.Exists -> R.string.add_err_exists
        AddError.InKomora -> R.string.add_err_in_komora
        AddError.TooMany -> R.string.add_err_too_many
        AddError.NoRelease -> R.string.add_err_no_release
        AddError.NoApk -> R.string.add_err_no_apk
        AddError.Network -> R.string.err_network
        AddError.RateLimit -> R.string.err_rate_limit
        AddError.Storage -> R.string.add_err_storage
        AddError.SomeFailed -> R.string.add_err_link
    },
)

/**
 * Before the first install from an added source: the app's name, package and key, as the file says,
 * with a plain warning; nothing is installed unless the owner says yes.
 */
@Composable
fun TrustDialog(request: TrustRequest, onAnswer: (Boolean) -> Unit) {
    val id = request.identity
    AlertDialog(
        onDismissRequest = { onAnswer(false) },
        icon = { Icon(painterResource(R.drawable.ic_warning), contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(stringResource(R.string.trust_title, id.label.ifBlank { id.packageName })) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.trust_text), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                Field(stringResource(R.string.field_source), "github.com/${request.repo}")
                Field(stringResource(R.string.field_package), id.packageName)
                Field(stringResource(R.string.field_key), formatKey(id.certSha256), mono = true)
                id.versionName?.let { Field(stringResource(R.string.field_version), it) }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.trust_after), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { Button(onClick = { onAnswer(true) }) { Text(stringResource(R.string.trust_yes)) } },
        dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text(stringResource(R.string.trust_no)) } },
    )
}

@Composable
private fun Field(label: String, value: String, mono: Boolean = false) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = if (mono) MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * "Details": where an app comes from and the key it must be signed with, as a table (label on the
 * left, value on the right), what is new in its latest version, and the links.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailsSheet(row: AppRow, lang: String, pinnedKey: String?, onRemove: (() -> Unit)?, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) { DetailsContent(row, lang, pinnedKey, onRemove) }
}

/** What the details sheet holds; on its own so the store pictures can show it whole. */
@Composable
internal fun DetailsContent(row: AppRow, lang: String, pinnedKey: String?, onRemove: (() -> Unit)?) {
    val uri = LocalUriHandler.current
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(row.app.name(lang), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        // 1. What the app is
        Spacer(Modifier.height(12.dp))
        Text(row.app.summary(lang), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface,
            modifier = Modifier.fillMaxWidth())
        // How it looks: only for Komora's own list (pictures from unknown sources are not opened)
        if (!row.app.added) {
            Spacer(Modifier.height(12.dp))
            Screenshots(row.app.repo, lang)
        }
        // 2. What is new in the latest version, in the language shown
        val notes = ReleaseNotes.forLanguage(row.latest?.notes.orEmpty(), lang)
        if (notes.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.whats_new) + (row.latestVersion?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                notes,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
                modifier = Modifier.fillMaxWidth().background(scheme.surfaceVariant, RoundedCornerShape(16.dp)).padding(16.dp),
            )
        }
        // 3. The technical facts, one per line; the signing key folded until asked for
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.details_technical), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(if (row.app.added) R.string.details_added else R.string.details_verified),
            style = MaterialTheme.typography.bodyMedium,
            color = if (row.app.added) scheme.error else scheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, scheme.outlineVariant, RoundedCornerShape(16.dp)),
        ) {
            val lines = buildList {
                add(stringResource(R.string.field_source) to row.app.repo)
                if (row.app.packageName.isNotEmpty()) add(stringResource(R.string.field_package) to row.app.packageName)
                row.installedVersion?.takeIf { it.isNotEmpty() }?.let { add(stringResource(R.string.field_installed) to it) }
                row.latestVersion?.let { add(stringResource(R.string.field_latest) to it) }
            }
            lines.forEachIndexed { i, (label, value) ->
                if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = scheme.outlineVariant)
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, style = MaterialTheme.typography.bodyMedium.copy(textAlign = TextAlign.Start), color = scheme.onSurfaceVariant)
                    Spacer(Modifier.width(16.dp).weight(1f))
                    Text(value, style = MaterialTheme.typography.bodyMedium.copy(textAlign = TextAlign.End, hyphens = Hyphens.None),
                        fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = scheme.outlineVariant)
            var showKey by rememberSaveable(row.app.key) { mutableStateOf(false) }
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button) { showKey = !showKey }
                    .heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.field_key), style = MaterialTheme.typography.bodyMedium.copy(textAlign = TextAlign.Start),
                    color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(stringResource(if (showKey) R.string.hide_key else R.string.show_key),
                    style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = scheme.primary)
            }
            AnimatedVisibility(visible = showKey) {
                // In full, in groups of four, to compare by eye
                Text(
                    pinnedKey?.let { formatKey(it) } ?: stringResource(R.string.key_unknown_yet),
                    style = if (pinnedKey != null) MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, textAlign = TextAlign.Start, hyphens = Hyphens.None)
                    else MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurface,
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        // The links, one under the other, the same width
        val name = row.app.name(lang)
        val version = row.installedVersion?.takeIf { it.isNotEmpty() } ?: row.latestVersion ?: "?"
        val android = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        val body = stringResource(R.string.report_bug_body, name, version, android)
        OutlinedButton(
            onClick = { runCatching { uri.openUri("https://github.com/${row.app.repo}") } },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.open_repository), fontWeight = FontWeight.SemiBold) }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { runCatching { uri.openUri(issueUrl(row.app.repo, name, version, body)) } },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.report_bug), fontWeight = FontWeight.SemiBold) }
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.report_bug_note), style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
        if (onRemove != null) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onRemove, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.remove_source), color = scheme.error, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
