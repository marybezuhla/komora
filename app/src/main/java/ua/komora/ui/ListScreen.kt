package ua.komora.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.AdaptiveIconDrawable
import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.border
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ua.komora.R
import androidx.compose.ui.graphics.Brush
import androidx.compose.material3.CenterAlignedTopAppBar
import ua.komora.install.Confirm
import ua.komora.ui.theme.CardTitle
import ua.komora.ui.theme.Ornament

internal const val SOURCE_URL = "https://github.com/Marybezu/komora"
internal const val LICENSE_URL = "$SOURCE_URL/blob/main/LICENSE"

/** The three groups of the list, in the order shown: what needs doing first. */
private enum class Group(val title: Int) {
    UPDATES(R.string.group_updates), INSTALLED(R.string.group_installed), AVAILABLE(R.string.group_available);

    companion object {
        fun of(row: AppRow): Group = when {
            row.hasUpdate && !row.foreignKey -> UPDATES
            row.isInstalled -> INSTALLED
            else -> AVAILABLE
        }
    }
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
    listState: LazyListState = rememberLazyListState(),
    onUpdateAll: (List<String>) -> Unit = {},
    sourcesEnabled: Boolean = false,
    onAddSource: () -> Unit = {},
    onDetails: (AppRow) -> Unit = {},
    // Two tabs once other sources are turned on: Komora's own list, and what the owner added.
    // Kept by the caller, so the tab and each tab's place in the list survive a visit to Settings.
    addedTab: Boolean = false,
    onTab: (Boolean) -> Unit = {},
    addedListState: LazyListState = rememberLazyListState(),
) {
    val showAdded = sourcesEnabled && addedTab
    val verifiedRows = rows.filter { !it.app.added }
    val addedRows = rows.filter { it.app.added }
    // Same language as the rest of the screen (Android picks the strings; the catalog follows them)
    val lang = stringResource(R.string.catalog_lang)
    val checking = rows.any { it.phase == Phase.Checking }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.background)) {
                // The name in the middle, over the motif
                CenterAlignedTopAppBar(
                    title = {
                        Text(stringResource(R.string.app_name), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    },
                    actions = {
                        // A round, framed button, like a chip
                        OutlinedIconButton(
                            onClick = onLook,
                            colors = IconButtonDefaults.outlinedIconButtonColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.padding(end = 8.dp),
                        ) {
                            Icon(painterResource(R.drawable.ic_settings), contentDescription = stringResource(R.string.look))
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
                Ornament(Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
            }
        },
    ) { padding ->
        // One column on a phone; two on an unfolded Galaxy Fold / Pixel Fold or an upright tablet;
        // three on a tablet held sideways
        // On a big screen the list keeps a comfortable width, in the middle
        val columns = columnsFor(minOf(LocalConfiguration.current.screenWidthDp, MAX_LIST_WIDTH))
        val wide = columns > 1
        // Pull the list down to check for updates
        PullToRefreshBox(
            isRefreshing = checking, onRefresh = onCheck,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                state = if (showAdded) addedListState else listState,
                modifier = Modifier.fillMaxHeight().widthIn(max = MAX_LIST_WIDTH.dp).fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (sourcesEnabled) {
                    item { Tabs(verifiedRows.size, addedRows.size, addedTab, onTab) }
                }
                if (!canInstall) item { AllowCard(actions.allowInstalls) }
                val shown = if (showAdded) addedRows else verifiedRows
                if (showAdded) item { AddedIntro(onAddSource) }
                // How this tab's apps stand; "Update all" covers only what is shown
                if (!showAdded || shown.isNotEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Box(Modifier.widthIn(max = 560.dp)) {
                                Summary(shown, checking, busy, canInstall, onCheck) { onUpdateAll(shown.map { it.app.key }) }
                            }
                        }
                    }
                }
                if (wide) {
                    // Side by side, one even grid in the same order (updates first): no half-empty rows;
                    // each card's label already says where it stands
                    val ordered = Group.entries.flatMap { g -> shown.filter { Group.of(it) == g } }
                    if (ordered.isNotEmpty()) cards(ordered, columns, lang, busy, confirm, canInstall, actions, onCheck, onInstall, onCancel, onDetails)
                } else for (group in Group.entries) {
                    val inGroup = shown.filter { Group.of(it) == group }
                    if (inGroup.isEmpty()) continue
                    item(key = "title-" + group.name) { GroupTitle(stringResource(group.title), inGroup.size) }
                    cards(inGroup, columns, lang, busy, confirm, canInstall, actions, onCheck, onInstall, onCancel, onDetails)
                }
                if (!showAdded) item { About() }
            }
        }
    }
}

/** Rows of cards; cards side by side share one height, with their buttons at the bottom. */
private fun LazyListScope.cards(
    rows: List<AppRow>,
    columns: Int,
    lang: String,
    busy: Boolean,
    confirm: Confirm?,
    canInstall: Boolean,
    actions: Actions,
    onCheck: () -> Unit,
    onInstall: (AppRow) -> Unit,
    onCancel: () -> Unit,
    onDetails: (AppRow) -> Unit,
) {
    items(rows.chunked(columns), key = { chunk -> chunk.first().app.key }) { chunk ->
        // Cards side by side get one height; a single card keeps its own (asking a card for its smallest
        // height can come out too small when its text wraps)
        val rowModifier = if (columns > 1) Modifier.fillMaxWidth().height(IntrinsicSize.Min) else Modifier.fillMaxWidth()
        Row(rowModifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (row in chunk) {
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
                    onDetails = { onDetails(row) },
                    modifier = if (columns > 1) Modifier.weight(1f).fillMaxHeight() else Modifier.weight(1f),
                    stretch = columns > 1,
                )
            }
            // An incomplete last row keeps the same card width
            repeat(columns - chunk.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/** The one thing to do first: framed in the accent. */
@Composable
private fun AllowCard(onAllow: () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.allow_title), style = CardTitle, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.allow_text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Button(onClick = onAllow, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.allow_button)) }
        }
    }
}

/** One line on top: how things stand, with "Update all" when there is more than one update. */
@Composable
private fun Summary(rows: List<AppRow>, checking: Boolean, busy: Boolean, canInstall: Boolean, onCheck: () -> Unit, onUpdateAll: () -> Unit) {
    val updates = rows.count { it.hasUpdate && !it.foreignKey && it.latest != null }
    val scheme = MaterialTheme.colorScheme
    val (text, tint) = when {
        checking -> stringResource(R.string.summary_checking) to scheme.onSurfaceVariant
        updates > 0 -> stringResource(R.string.summary_updates, updates) to scheme.primary
        rows.any { it.isInstalled } && rows.none { it.problem in setOf(Problem.Network, Problem.RateLimit) } ->
            stringResource(R.string.summary_all_current) to scheme.onSurface
        else -> stringResource(R.string.summary_tap_check) to scheme.onSurfaceVariant
    }
    // In the middle: how things stand, and under it what can be done
    Column(
        Modifier.fillMaxWidth()
            .background(scheme.surface, RoundedCornerShape(20.dp))
            .border(1.dp, scheme.outlineVariant, RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = tint,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), modifier = Modifier.fillMaxWidth()) {
            // TalkBack reads the full label
            val full = stringResource(R.string.refresh)
            OutlinedButton(
                onClick = onCheck,
                enabled = !checking,
                modifier = Modifier.weight(1f, fill = updates > 1).heightIn(min = 48.dp).semantics { contentDescription = full },
            ) { Text(stringResource(R.string.check), fontWeight = FontWeight.SemiBold, maxLines = 1) }
            if (updates > 1) {
                Button(onClick = onUpdateAll, enabled = canInstall && !busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.update_all), fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun GroupTitle(title: String, count: Int) {
    val line = MaterialTheme.colorScheme.outlineVariant
    // In the middle, between two thin lines, like the motif under the page title
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(Brush.horizontalGradient(listOf(line.copy(alpha = 0f), line))))
        Text(
            "$title · $count",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp).semantics { heading() },
        )
        Box(Modifier.weight(1f).height(1.dp).background(Brush.horizontalGradient(listOf(line, line.copy(alpha = 0f)))))
    }
}

@Composable
private fun About() {
    val uri = LocalUriHandler.current
    val note = MaterialTheme.colorScheme.onSurfaceVariant
    // On a wide screen the notes keep a readable line length, in the middle
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 600.dp).fillMaxWidth().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.footer), style = MaterialTheme.typography.bodyMedium, color = note, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.footer_permissions), style = MaterialTheme.typography.bodyMedium, color = note, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.about_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.about_text), style = MaterialTheme.typography.bodyMedium, color = note, textAlign = TextAlign.Center)
            FlowRowCompat {
                TextButton(onClick = { runCatching { uri.openUri(SOURCE_URL) } }) { Text(stringResource(R.string.source_code)) }
                TextButton(onClick = { runCatching { uri.openUri(LICENSE_URL) } }) { Text(stringResource(R.string.license)) }
            }
        }
    }
}

/**
 * An installed app's icon, drawn round like the bundled ones: the two layers of an adaptive icon are
 * drawn in full and the middle cut out as a circle, so every launcher style looks the same here.
 */
private fun installedIcon(context: Context, packageName: String, px: Int): ImageBitmap? = try {
    val drawable = context.packageManager.getApplicationIcon(packageName)
    val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val circle = android.graphics.Path().apply { addCircle(px / 2f, px / 2f, px / 2f, android.graphics.Path.Direction.CW) }
    canvas.clipPath(circle)
    if (drawable is AdaptiveIconDrawable) {
        // A layer is 108 dp, of which the middle 72 dp is the visible icon
        val full = px * 1.5f
        val offset = ((px - full) / 2f).toInt()
        for (layer in listOfNotNull(drawable.background, drawable.foreground)) {
            layer.setBounds(offset, offset, offset + full.toInt(), offset + full.toInt())
            layer.draw(canvas)
        }
    } else {
        drawable.setBounds(0, 0, px, px)
        drawable.draw(canvas)
    }
    bitmap.asImageBitmap()
} catch (_: Exception) {
    null
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

private val ICON = 56.dp

/**
 * The app's icon. Once installed, it comes from the app itself on this phone, so a new icon shows as
 * soon as the app is updated; before that, the copy bundled in Komora. An app with neither gets a
 * small square with its first letters. Decoration only.
 */
@Composable
private fun AppIcon(packageName: String, name: String, color: Long, installedVersion: String?) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { ICON.roundToPx() }
    // Read again whenever the installed version changes (an update may bring a new icon)
    val installed = remember(packageName, installedVersion) {
        if (installedVersion == null) null else installedIcon(context, packageName, px)
    }
    if (installed != null) {
        Image(installed, contentDescription = null, modifier = Modifier.size(ICON).clearAndSetSemantics { })
        return
    }
    val icon = APP_ICONS[packageName]
    if (icon != null) {
        Image(painterResource(icon), contentDescription = null, modifier = Modifier.size(ICON).clip(CircleShape).clearAndSetSemantics { })
        return
    }
    Box(
        Modifier.size(ICON).background(Color(color), CircleShape).clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Text(name.take(2).replaceFirstChar { it.uppercase() }, color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

/** Where an app stands, as a small coloured label: seen at a glance. */
@Composable
private fun StatusPill(row: AppRow) {
    val scheme = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val size = row.latest?.let { Formatter.formatShortFileSize(ctx, it.apk.size) }
    val latest = row.latestVersion
    val (text, bg, fg) = when {
        row.phase == Phase.Checking -> Triple(stringResource(R.string.checking), scheme.surfaceVariant, scheme.onSurfaceVariant)
        row.hasUpdate && latest != null && size != null ->
            Triple(stringResource(R.string.pill_update, latest, size), scheme.primaryContainer, scheme.onPrimaryContainer)
        row.isInstalled && latest != null ->
            Triple("✓ " + stringResource(R.string.pill_current, row.installedVersion.orEmpty().ifEmpty { latest }), scheme.secondaryContainer, scheme.onSecondaryContainer)
        row.isInstalled -> Triple(
            if (row.installedVersion.isNullOrEmpty()) stringResource(R.string.installed) else stringResource(R.string.pill_installed, row.installedVersion),
            scheme.surfaceVariant, scheme.onSurfaceVariant,
        )
        size != null -> Triple(stringResource(R.string.pill_available, latest.orEmpty(), size), scheme.surfaceVariant, scheme.onSurfaceVariant)
        else -> Triple(stringResource(R.string.not_installed), scheme.surfaceVariant, scheme.onSurfaceVariant)
    }
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = fg,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.background(bg, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** Wider than this, the list stays this wide, in the middle. */
private const val MAX_LIST_WIDTH = 1040

private fun columnsFor(width: Int) = when {
    width >= 1000 -> 3
    width >= 600 -> 2
    else -> 1
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
    onDetails: () -> Unit = {},
    modifier: Modifier = Modifier,
    /** In a row of cards of one height: the lower part goes to the bottom. */
    stretch: Boolean = false,
) {
    val name = row.app.name(lang)
    val scheme = MaterialTheme.colorScheme
    val idle = row.phase == Phase.Idle || row.phase == Phase.Checking
    // A copy from another source: said at once when there is nothing to update; otherwise after a try
    val problem = row.problem ?: Problem.OtherKey.takeIf { row.foreignKey && !row.hasUpdate }
    val retry = problem == Problem.Network || problem == Problem.RateLimit
    // The release's description, before installing or updating
    val notes = row.latest?.notes.orEmpty()
    val offerNotes = notes.isNotBlank() && (!row.isInstalled || row.hasUpdate)
    var showNotes by rememberSaveable(row.app.key) { mutableStateOf(false) }
    val added = row.app.added

    // Komora's own apps: a plain thin frame. Added by the owner: a dashed frame in the warning colour, never mistaken
    val frame = scheme.error
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surface),
        border = if (added) null else BorderStroke(1.dp, scheme.outlineVariant),
        modifier = modifier.fillMaxWidth().then(if (added) Modifier.dashedBorder(frame, 16.dp) else Modifier),
    ) {
        // Top: the icon beside the name and where the app stands. Then what it is, across the whole card.
        // Then the buttons, side by side and the same width, so the card is even from edge to edge.
        val details = stringResource(R.string.details)
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 14.dp)) {
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClickLabel = details, onClick = onDetails)
                    .semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIcon(row.app.packageName, name, row.app.badge, row.installedVersion)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (added) {
                        // Said in words too, not only by the frame
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(painterResource(R.drawable.ic_warning), contentDescription = null, tint = scheme.error, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.added_by_you), style = MaterialTheme.typography.labelMedium,
                                color = scheme.error, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Text(name, style = CardTitle, color = scheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() })
                    StatusPill(row)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                row.app.summary(lang),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                // An added app's line is its repository: one line, cut at the end, never broken at "/"
                maxLines = if (row.app.added) 1 else 3,
                overflow = TextOverflow.Ellipsis,
            )
            val hasAction = !row.isInstalled || row.hasUpdate || canOpen
            val notesButton = offerNotes && idle
            if (idle && (hasAction || notesButton)) {
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (notesButton) {
                        OutlinedButton(
                            onClick = { showNotes = !showNotes },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp),
                        ) {
                            Text(stringResource(if (showNotes) R.string.hide_whats_new else R.string.whats_new), maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (hasAction) {
                        MainAction(row, name, canGet, canOpen, problem, onInstall, onOpen, Modifier.weight(1f).heightIn(min = 48.dp))
                    }
                }
            }
        }

        val secondary = retry || problem == Problem.OtherKey
        val notesOpen = offerNotes && showNotes
        val below = problem != null || !idle || secondary || notesOpen
        if (stretch) Spacer(Modifier.weight(1f))
        if (!below) return@Card

        // Everything below uses the whole width, like the text above
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
            problem?.let {
                // A block of its own across the card: a sign as well as the colour, since in red themes
                // the error and the accent are close
                Row(
                    Modifier.fillMaxWidth().background(scheme.errorContainer, RoundedCornerShape(12.dp)).padding(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_warning),
                        contentDescription = null,
                        tint = scheme.error,
                        modifier = Modifier.padding(top = 2.dp, end = 10.dp).size(18.dp),
                    )
                    Text(problemText(it), style = MaterialTheme.typography.bodyMedium, color = scheme.onErrorContainer,
                        modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
                }
                Spacer(Modifier.height(8.dp))
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
                        style = MaterialTheme.typography.bodyMedium,
                        // Read aloud only every 10%, not on every change
                        modifier = Modifier.semantics {
                            contentDescription = spoken
                            liveRegion = LiveRegionMode.Polite
                        },
                    )
                    Text(stringResource(R.string.keep_open), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                }
                is Phase.Installing -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(if (row.app.isSelf) R.string.updating_self else R.string.installing), style = MaterialTheme.typography.bodyMedium)
                    // Android is waiting for the owner's answer on its own screen
                    if (onContinue != null) {
                        Spacer(Modifier.height(6.dp))
                        FlowRowCompat {
                            Button(onClick = onContinue) { Text(stringResource(R.string.continue_install)) }
                            OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                        }
                    }
                }
                Phase.Checking, Phase.Idle -> {
                    AnimatedVisibility(visible = offerNotes && showNotes) {
                        Text(
                            notes,
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurface,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                                .background(scheme.surfaceVariant, RoundedCornerShape(12.dp))
                                .padding(12.dp),
                        )
                    }
                    // The way out of a problem: a full-width button, like the ones above
                    if (secondary) Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (problem == Problem.OtherKey) {
                            OutlinedButton(onClick = onAppSettings, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.app_settings), fontWeight = FontWeight.SemiBold)
                            }
                        }
                        if (retry) OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.retry), fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

/** The card's one main button: Install, Update or Open. */
@Composable
private fun MainAction(
    row: AppRow,
    name: String,
    canGet: Boolean,
    canOpen: Boolean,
    problem: Problem?,
    onInstall: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val padding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
    when {
        !row.isInstalled -> {
            val label = stringResource(R.string.install)
            Button(onClick = onInstall, enabled = canGet, contentPadding = padding,
                modifier = modifier.semantics { contentDescription = "$label $name" }) { Text(label, maxLines = 1, fontWeight = FontWeight.SemiBold) }
        }
        row.hasUpdate -> {
            val label = stringResource(R.string.update)
            Button(onClick = onInstall, enabled = canGet, contentPadding = padding,
                modifier = modifier.semantics { contentDescription = "$label $name" }) { Text(label, maxLines = 1, fontWeight = FontWeight.SemiBold) }
        }
        canOpen -> {
            val label = stringResource(R.string.open)
            OutlinedButton(onClick = onOpen, contentPadding = padding, colors = ButtonDefaults.outlinedButtonColors(),
                modifier = modifier.semantics { contentDescription = "$label $name" }) { Text(label, maxLines = 1, fontWeight = FontWeight.SemiBold) }
        }
    }
}


/** Buttons wrap to a new line when the font is large or the screen is narrow. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowCompat(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) { content() }
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
        Problem.AlreadyInKomora -> R.string.err_already_in_komora
    },
)

/** A dashed frame: the mark of an app the owner added. */
private fun Modifier.dashedBorder(color: Color, radius: androidx.compose.ui.unit.Dp): Modifier = drawBehind {
    val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
        width = 1.5.dp.toPx(),
        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 5.dp.toPx())),
    )
    val r = radius.toPx()
    drawRoundRect(color = color, style = stroke, cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r))
}

/** Komora's own list, and the apps the owner added: two tabs with their numbers. */
@Composable
private fun Tabs(verified: Int, added: Int, addedTab: Boolean, onChoose: (Boolean) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().background(scheme.surfaceVariant, RoundedCornerShape(50)).padding(4.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for ((isAdded, label) in listOf(false to stringResource(R.string.tab_verified, verified), true to stringResource(R.string.tab_added, added))) {
            val selected = isAdded == addedTab
            Row(
                Modifier.weight(1f).heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) scheme.surface else Color.Transparent, RoundedCornerShape(50))
                    .border(1.dp, if (selected) scheme.outline else Color.Transparent, RoundedCornerShape(50))
                    .selectable(selected, role = Role.Tab) { onChoose(isAdded) }
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) scheme.onSurface else scheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

/** On the "Added by you" tab: what these are, how Komora checks them, and a way to add one. */
@Composable
private fun AddedIntro(onAdd: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    // A warning, plainly: a reddish background, a sign and a red frame (the theme's own error colours)
    Column(
        Modifier.fillMaxWidth()
            .background(scheme.errorContainer, RoundedCornerShape(16.dp))
            .dashedBorder(scheme.error, 16.dp)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_warning), contentDescription = null, tint = scheme.error, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.added_intro_title), style = CardTitle, color = scheme.onErrorContainer,
                modifier = Modifier.semantics { heading() })
        }
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.added_intro_text), style = MaterialTheme.typography.bodyMedium, color = scheme.onErrorContainer)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onAdd, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.add_source)) }
    }
}
