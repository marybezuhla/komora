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

/** A signing key, as people compare it: 64 hex digits in groups of four, on four lines. */
fun formatKey(sha256: String): String =
    sha256.uppercase().chunked(4).chunked(4).joinToString("\n") { it.joinToString(" ") }

/** "Add a source": a GitHub link or owner/name; the steps after it come from [state]. */
@Composable
fun AddSourceDialog(state: AddState, onAdd: (String) -> Unit, onChoose: (String, String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    if (state is AddState.Choose) {
        ChooseApkDialog(state, onChoose, onDismiss)
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
                    onValueChange = { text = it.take(200) },
                    label = { Text(stringResource(R.string.add_source_field)) },
                    placeholder = { Text("github.com/owner/name") },
                    singleLine = true,
                    enabled = !working,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state is AddState.Failed) {
                    Spacer(Modifier.height(8.dp))
                    Text(addErrorText(state.reason), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
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
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = if (mono) MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** "Details": where an app comes from and the key it must be signed with, so anyone can check. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailsSheet(row: AppRow, lang: String, pinnedKey: String?, onRemove: (() -> Unit)?, onDismiss: () -> Unit) {
    val uri = LocalUriHandler.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            Text(row.app.name(lang), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(if (row.app.added) R.string.details_added else R.string.details_verified),
                style = MaterialTheme.typography.bodyMedium,
                color = if (row.app.added) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                    .padding(12.dp),
            )
            Spacer(Modifier.height(8.dp))
            Field(stringResource(R.string.field_source), "github.com/${row.app.repo}")
            if (row.app.packageName.isNotEmpty()) Field(stringResource(R.string.field_package), row.app.packageName)
            row.installedVersion?.takeIf { it.isNotEmpty() }?.let { Field(stringResource(R.string.field_installed), it) }
            row.latestVersion?.let { Field(stringResource(R.string.field_latest), it) }
            Field(
                stringResource(R.string.field_key),
                pinnedKey?.let { formatKey(it) } ?: stringResource(R.string.key_unknown_yet),
                mono = pinnedKey != null,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { runCatching { uri.openUri("https://github.com/${row.app.repo}") } }) {
                    Text(stringResource(R.string.open_repository))
                }
                if (onRemove != null) {
                    TextButton(onClick = onRemove) { Text(stringResource(R.string.remove_source), color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}
