package ua.komora.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ua.komora.R
import ua.komora.ui.theme.CardTitle
import ua.komora.ui.theme.Ornament

/** One organisation: its name, what it does, and its official site (no tracking marks in the link). */
private class Cause(@StringRes val name: Int, @StringRes val what: Int, @StringRes val url: Int)

private val DEFENCE = listOf(
    Cause(R.string.org_united24, R.string.org_united24_what, R.string.org_united24_url),
    Cause(R.string.org_savelife, R.string.org_savelife_what, R.string.org_savelife_url),
    Cause(R.string.org_prytula, R.string.org_prytula_what, R.string.org_prytula_url),
    // Not a fundraiser: the state's defence-tech cluster, to learn about it
    Cause(R.string.org_brave1, R.string.org_brave1_what, R.string.org_brave1_url),
)

private val HUMANITARIAN = listOf(
    Cause(R.string.org_redcross, R.string.org_redcross_what, R.string.org_redcross_url),
    Cause(R.string.org_voices, R.string.org_voices_what, R.string.org_voices_url),
    Cause(R.string.org_superhumans, R.string.org_superhumans_what, R.string.org_superhumans_url),
)

private val LETTER = listOf(
    R.string.letter_1, R.string.letter_2, R.string.letter_3, R.string.letter_4, R.string.letter_5, R.string.letter_6,
)

/**
 * "About Komora": a word from the author, how to support Ukraine (only links to the organisations'
 * own sites; Komora takes no part in the money), and the plain facts: licence, code, version.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AboutPage(onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val uri = LocalUriHandler.current
    val open = { url: String -> runCatching { uri.openUri(url) }; Unit }
    Scaffold(
        containerColor = scheme.background,
        topBar = {
            Column(Modifier.background(scheme.background)) {
                TopAppBar(
                    title = { Text(stringResource(R.string.about_komora), color = scheme.primary, fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = scheme.background),
                )
                Ornament(Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
            }
        },
    ) { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter,
        ) {
            // A readable line length on a tablet or an unfolded phone
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(16.dp)) {
                // The author's word
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = scheme.surface),
                    border = BorderStroke(1.dp, scheme.outlineVariant),
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        for (p in LETTER) {
                            Text(stringResource(p), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
                        }
                        Text(
                            stringResource(R.string.letter_signature),
                            style = MaterialTheme.typography.bodyLarge,
                            fontStyle = FontStyle.Italic,
                            color = scheme.primary,
                            modifier = Modifier.align(Alignment.End),
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))
                Text(stringResource(R.string.support_title), style = CardTitle, color = scheme.onSurface,
                    modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.support_note), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                CauseGroup(R.string.support_defence, DEFENCE, open)
                Spacer(Modifier.height(16.dp))
                CauseGroup(R.string.support_humanitarian, HUMANITARIAN, open)

                Spacer(Modifier.height(24.dp))
                Text(stringResource(R.string.about_title), style = CardTitle, color = scheme.onSurface,
                    modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.about_text), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                val context = LocalContext.current
                val version = remember {
                    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
                }
                version?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.about_version, it), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                }
                Row {
                    TextButton(onClick = { open(SOURCE_URL) }) { Text(stringResource(R.string.source_code)) }
                    TextButton(onClick = { open(LICENSE_URL) }) { Text(stringResource(R.string.license)) }
                }
            }
        }
    }
}

@Composable
private fun CauseGroup(@StringRes title: Int, causes: List<Cause>, open: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Text(stringResource(title), style = MaterialTheme.typography.titleMedium, color = scheme.primary,
        fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
    Spacer(Modifier.height(8.dp))
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surface),
        border = BorderStroke(1.dp, scheme.outlineVariant),
    ) {
        for ((i, cause) in causes.withIndex()) {
            if (i > 0) Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(scheme.outlineVariant))
            val url = stringResource(cause.url)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(role = Role.Button, onClickLabel = stringResource(R.string.open_site)) { open(url) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(cause.name), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
                    Text(stringResource(cause.what), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                    Text(url.removePrefix("https://").removeSuffix("/"), style = MaterialTheme.typography.labelMedium, color = scheme.primary)
                }
                Spacer(Modifier.width(8.dp))
                Icon(painterResource(R.drawable.ic_open), contentDescription = null, tint = scheme.primary, modifier = Modifier.size(20.dp))
            }
        }
    }
}
