package ua.komora.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ua.komora.R
import ua.komora.Stores
import ua.komora.catalog.Catalog
import ua.komora.catalog.CatalogApp
import ua.komora.catalog.Source
import ua.komora.catalog.Sources
import ua.komora.install.ApkRejected
import ua.komora.install.ApkVerifier
import ua.komora.install.InstallFailed
import ua.komora.install.InstallResult
import ua.komora.install.Installer
import ua.komora.net.BadReleaseException
import ua.komora.net.DownloadException
import ua.komora.net.GitHub
import ua.komora.net.InterruptedDownload
import ua.komora.net.NoReleaseException
import ua.komora.net.RateLimitException
import ua.komora.net.Release
import ua.komora.net.Rules
import ua.komora.notify.UpdateCheck
import java.io.File
import java.io.IOException

enum class Problem {
    Network, RateLimit, NoRelease, BadRelease, Download, NoSpace,
    Unreadable, WrongPackage, WrongSigner, NotNewer, OtherKey, Install,
    /** Older than the lowest version Komora's list allows for this app. */
    TooOld,
    /** An added source's APK claims to be an app Komora already has. */
    AlreadyInKomora,
}

/** Adding a source, step by step, for the dialog. */
sealed class AddState {
    object Idle : AddState()
    object Working : AddState()
    /** Everything asked for is added: the dialog closes. */
    object Done : AddState()
    /** The release has several APKs: the owner picks one, once. */
    /** [names] are shown; [all] are every APK in the release, so the pattern can be checked against them. */
    data class Choose(val repo: String, val names: List<String>, val all: List<String> = names) : AddState()
    /** [repos]: when several were added at once, the ones that could not be. */
    data class Failed(val reason: AddError, val repos: List<String> = emptyList()) : AddState()
}

enum class AddError { BadLink, Exists, InKomora, TooMany, NoRelease, NoApk, Network, RateLimit, Storage, SomeFailed }

/** Shown before the first install from an added source: what the file is, and whether to trust it. */
data class TrustRequest(val repo: String, val identity: ApkVerifier.Identity)

/** Problems from an update check; anything else came from an install attempt. */
private val checkProblems = setOf(Problem.Network, Problem.RateLimit, Problem.NoRelease, Problem.BadRelease)

sealed class Phase {
    object Idle : Phase()
    object Checking : Phase()
    data class Downloading(val bytes: Long, val total: Long) : Phase() {
        val fraction get() = if (total > 0) bytes.toFloat() / total else 0f
    }
    /** [sessionId] is null while the APK is being handed to Android. */
    data class Installing(val sessionId: Int? = null) : Phase()
}

data class AppRow(
    val app: CatalogApp,
    val installedVersion: String? = null,
    /**
     * Installed with a key other than the pinned one: from another source, or from before the author
     * changed keys. Only the downloaded APK can tell which (its signed key history), so an update is still tried.
     */
    val foreignKey: Boolean = false,
    val latest: Release? = null,
    val phase: Phase = Phase.Idle,
    val problem: Problem? = null,
) {
    val isInstalled get() = installedVersion != null
    val latestVersion get() = latest?.let { Rules.normalizeVersion(it.tag) }

    /** Only when the release is clearly newer; unclear or older versions never offer an update. */
    val hasUpdate: Boolean get() {
        val l = latestVersion ?: return false
        val i = installedVersion ?: return false
        return (Rules.compareVersions(l, i) ?: return false) > 0
    }

    val isBusy get() = phase is Phase.Downloading || phase is Phase.Installing
}

class KomoraViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()

    /** Komora's own list, inside its signed APK. */
    private val verified: List<CatalogApp> =
        Catalog.parse(app.assets.open("catalog.json").bufferedReader().use { it.readText() })

    /** The sources the owner added; shown only while the switch in Settings is on. */
    private var sources: List<Source> = Sources.load(app).let { all ->
        // A source pinned to a package that is now in Komora's own list (or is Komora) is dropped:
        // Komora's own key always wins, and a hidden source would hold a place nobody could free
        val taken = verified.map { it.packageName }.toSet() + app.packageName
        val kept = all.filter { it.packageName !in taken }
        if (kept.size != all.size) Sources.save(app, kept)
        kept
    }
    private val _sourcesEnabled = MutableStateFlow(Sources.enabled(app))
    val sourcesEnabled: StateFlow<Boolean> = _sourcesEnabled

    private val _sourcePictures = MutableStateFlow(Sources.pictures(app))
    /** Added apps' screenshots and icons shown (the owner turned them on after a warning). */
    val sourcePictures: StateFlow<Boolean> = _sourcePictures

    /**
     * Komora's own list, then the added sources. A source whose pinned package is (now) in Komora's own
     * list, or is Komora itself, is left out: Komora's own key always wins.
     */
    private fun apps(): List<CatalogApp> {
        if (!_sourcesEnabled.value) return verified
        val taken = verified.map { it.packageName }.toSet() + ctx.packageName
        return verified + sources.filter { it.packageName !in taken }.map { it.toApp() }
    }

    private val _rows = MutableStateFlow(apps().map { AppRow(it) })
    val rows: StateFlow<List<AppRow>> = _rows

    /** True while one app downloads or installs: one at a time keeps things simple and predictable. */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _adding = MutableStateFlow<AddState>(AddState.Idle)
    val adding: StateFlow<AddState> = _adding

    private val _trust = MutableStateFlow<TrustRequest?>(null)
    val trust: StateFlow<TrustRequest?> = _trust
    private var trustAnswer: CompletableDeferred<Boolean>? = null
    private var addJob: Job? = null

    /** The one install in progress. Touched only on the main thread. */
    private var active: Active? = null
    private class Active(var key: String) {
        var sessionId: Int? = null
        var job: Job? = null
    }

    private var checkJob: Job? = null

    private val apkDir get() = File(ctx.noBackupFilesDir, "apk")

    init {
        // Taken here, on the main thread, where the list is changed
        val listed = (verified.map { it.repo } + sources.map { it.repo }).toSet()
        val noSources = sources.isEmpty()
        viewModelScope.launch {
            // Leftovers from a time Komora was closed mid-install
            withContext(Dispatchers.IO) {
                // (except the start of an interrupted download, for a while, so it can still continue)
                runCatching {
                    val now = System.currentTimeMillis()
                    apkDir.listFiles()?.filter { f ->
                        !f.name.startsWith("part-") || now - f.lastModified() !in 0..Rules.MAX_PARTIAL_AGE_MS
                    }?.forEach { it.delete() }
                }
                runCatching { Installer.dropUncommitted(ctx) }
                // Release answers about anything no longer in the list (a source removed while Komora was
                // closed before its undo ran out) go at the next start
                Stores.releases(ctx).keepOnly(listed)
                // Added apps' pictures with no added source left, or with their pictures off: none kept
                if (noSources || !Sources.pictures(ctx)) Stores.forgetSourcePictures(ctx)
            }
        }
        refreshInstalled()
        checkUpdates()
        viewModelScope.launch {
            Installer.results.collect { result ->
                // Only the answer for the session started here counts; a late one from an older session is ignored
                val a = active ?: return@collect
                if (a.sessionId != result.sessionId) return@collect
                finish(a)
                val problem = when (result) {
                    is InstallResult.Failure -> if (result.noSpace) Problem.NoSpace else Problem.Install
                    else -> null
                }
                set(a.key) { it.copy(phase = Phase.Idle, problem = problem) }
                refreshInstalled()
                // "Update all": the next one, unless the owner cancelled
                if (result is InstallResult.Cancelled) queue.clear()
                startNextQueued()
            }
        }
    }

    /** The rows again after the list of apps changed, keeping what is known about each. */
    private fun rebuildRows() {
        val known = _rows.value.associateBy { it.app.key }
        _rows.value = apps().map { app -> known[app.key]?.copy(app = app) ?: AppRow(app) }
        refreshInstalled()
    }

    fun refreshInstalled() {
        val pm = ctx.packageManager
        _rows.update { rows ->
            rows.map { row ->
                val info = if (row.app.packageName.isEmpty()) null
                else runCatching { ApkVerifier.installedInfo(pm, row.app.packageName) }.getOrNull()
                val version = info?.let { it.versionName ?: "" }
                val foreign = info != null && !ApkVerifier.installedMatchesPin(ctx, row.app, info)
                // An install problem is about the copy that was there; once that changes, it no longer applies
                val keep = row.problem in checkProblems || version == row.installedVersion
                row.copy(installedVersion = version, foreignKey = foreign, problem = row.problem.takeIf { keep })
            }
        }
    }

    /** Pulled down while a check was running: once it ends, everything is asked about again. */
    private var forceAgain = false

    /** [force]: pulled down by the owner, so GitHub is asked now even if it was asked a moment ago. */
    fun checkUpdates(force: Boolean = false) {
        // One check at a time: GitHub allows only 60 requests an hour without an account
        if (checkJob?.isActive == true) {
            if (force) forceAgain = true
            return
        }
        checkJob = viewModelScope.launch {
            var forced = force
            do {
                forceAgain = false
                checkAll(forced)
                forced = true
            } while (forceAgain)
            prefetchScreenshots()
        }
    }

    private var prefetchJob: Job? = null
    // Asked for while one was running (it may have seen the list before a check finished): one more round
    private var prefetchAgain = false
    // Removing added apps' pictures, when they were turned off: turning them on waits for it
    private var cleanupJob: Job? = null

    /**
     * The screenshots of every app in Komora's own list, for its newest release, fetched ahead (on any
     * network), so its details open with them at once. Only what is missing: once a release's pictures
     * are kept, GitHub is not asked again until a new release. Added apps (only with their pictures
     * turned on) get just their icon here, once per source and a few per round, so GitHub's hourly
     * limit stays for update checks; their screenshots come when their details open. Stops at the first
     * failure (no connection, GitHub's limit): tried again next check.
     */
    private fun prefetchScreenshots() {
        if (prefetchJob?.isActive == true) {
            prefetchAgain = true
            return
        }
        prefetchAgain = false
        prefetchJob = viewModelScope.launch(Dispatchers.IO) {
            val lang = ctx.getString(R.string.catalog_lang)
            val store = Stores.screenshots(ctx)
            var icons = 0
            try {
                for (row in _rows.value) {
                    val latest = row.latest ?: continue
                    // As the details ask for them: the release's own repository (after a rename), else the listed one
                    val repo = latest.repo ?: row.app.repo
                    val done = try {
                        if (row.app.added) {
                            if (!_sourcePictures.value || icons >= ICONS_PER_ROUND) continue
                            val iconStore = Stores.sourceIcons(ctx)
                            if (iconStore.get(repo, SourcePictures.ICON, SourcePictures.ICON_ONCE) != null) continue
                            icons++
                            val icon = GitHub.iconList(repo, lang) { !isActive } ?: break
                            iconStore.update(repo, SourcePictures.ICON, SourcePictures.ICON_ONCE, icon) { shot -> if (isActive) GitHub.screenshot(shot) else null }
                            true
                        } else {
                            if (store.get(repo, lang, latest.tagKey) != null) continue
                            val listed = GitHub.screenshotList(repo, lang) { !isActive }
                            if (listed == null) false
                            else store.update(repo, lang, latest.tagKey, listed) { shot -> if (isActive) GitHub.screenshot(shot) else null }.complete
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Pictures are only a help: never a problem shown for them
                        false
                    }
                    if (!done) break
                }
            } finally {
                // Icons read again once, when the round is over
                if (icons > 0) SourcePictures.changed()
            }
        }.also { job ->
            // Another round if one was asked for meanwhile (on the main thread, where it was asked)
            // (also after a cancel: what the list asked for meanwhile still comes)
            job.invokeOnCompletion {
                viewModelScope.launch { if (prefetchAgain) prefetchScreenshots() }
            }
        }
    }

    private suspend fun checkAll(force: Boolean) {
        val list = apps()
        for ((index, app) in list.withIndex()) {
            // Sources switched off or removed meanwhile: not asked about any more
            if (apps().none { it.key == app.key }) continue
            if (!check(app, force)) {
                // Without a connection or after the rate limit, the rest would fail the same way: say so for all
                val p = _rows.value.firstOrNull { it.app.key == app.key }?.problem ?: return
                for (rest in list.drop(index + 1)) {
                    set(rest.key) {
                        if (it.isBusy || (it.problem != null && it.problem !in checkProblems)) it else it.copy(problem = p)
                    }
                }
                return
            }
        }
    }

    /** Asks GitHub about one app. False when the connection or GitHub's limit stops the whole check. */
    private suspend fun check(app: CatalogApp, force: Boolean = false): Boolean {
        set(app.key) { if (it.phase == Phase.Idle) it.copy(phase = Phase.Checking) else it }
        val result = withContext(Dispatchers.IO) { catching { GitHub.latestRelease(app, Stores.releases(ctx), force) } }
        set(app.key) { row ->
            val phase = if (row.phase == Phase.Checking) Phase.Idle else row.phase
            result.fold(
                onSuccess = {
                    val problem = row.problem.takeIf { p -> p !in checkProblems }
                    row.copy(latest = it, phase = phase, problem = problem)
                },
                // A check never covers an install in progress or its result
                onFailure = {
                    if (row.isBusy || (row.problem != null && row.problem !in checkProblems)) row.copy(phase = phase)
                    else row.copy(phase = phase, problem = problemOf(it))
                },
            )
        }
        val p = problemOf(result.exceptionOrNull() ?: return true)
        return p != Problem.RateLimit && p != Problem.Network
    }

    // ---- Added sources ----

    /** Added apps' pictures on (after the warning) or off: off removes every one of them from the phone. */
    fun setSourcePictures(on: Boolean) {
        Sources.setPictures(ctx, on)
        _sourcePictures.value = on
        if (on) {
            // After any removal still going on, so it cannot delete what is fetched now
            val cleaning = cleanupJob
            viewModelScope.launch { cleaning?.join(); prefetchScreenshots() }
        } else {
            // Gone from the screen at once; from storage once anything being fetched has stopped
            SourcePictures.changed()
            val running = prefetchJob
            cleanupJob = viewModelScope.launch(Dispatchers.IO) {
                running?.cancelAndJoin()
                Stores.forgetSourcePictures(ctx)
                SourcePictures.changed()
            }
        }
    }

    fun setSourcesEnabled(on: Boolean) {
        // Without added sources, their pictures go too (and stay off until asked for again)
        if (!on && _sourcePictures.value) setSourcePictures(false)
        Sources.setEnabled(ctx, on)
        _sourcesEnabled.value = on
        rebuildRows()
        if (on) {
            // One check at a time: after one already running, which did not include the added apps
            val previous = checkJob
            checkJob = viewModelScope.launch {
                previous?.join()
                for (app in apps().filter { it.added }) {
                    if (apps().none { it.key == app.key }) continue
                    if (!check(app)) break
                }
                // Pulled down meanwhile: everything is asked about now, as the pull asked
                while (forceAgain) {
                    forceAgain = false
                    checkAll(force = true)
                }
                // The added apps' icons, if their pictures are on
                prefetchScreenshots()
            }
        }
    }

    /** Several repositories added at once (a shared list): the ones still to do, and those that failed. */
    private val pendingAdds = ArrayDeque<String>()
    private val failedAdds = mutableListOf<String>()
    private var currentAdd: String? = null
    private var batch = false

    /**
     * Step 1: a link or "owner/name", or several (one per line, as "Share the list" gives them); finds the
     * APKs in each newest release, one repository after another.
     */
    fun addSource(input: String) {
        val words = input.split('\n', ',', ' ', '\t').map { it.trim() }.filter { it.isNotEmpty() }
        pendingAdds.clear()
        failedAdds.clear()
        // Only what reads as a repository; the same one twice counts once
        val repos = words.mapNotNull { Sources.parseRepo(it) }.distinctBy { it.lowercase() }
        // Words that look like links but are not a repository are said, not dropped silently
        val bad = words.filter { "/" in it && Sources.parseRepo(it) == null }.map { it.take(60) }
        batch = repos.size > 1 || (repos.isNotEmpty() && bad.isNotEmpty())
        if (!batch) {
            val repo = repos.singleOrNull() ?: return run { _adding.value = AddState.Failed(AddError.BadLink) }
            addOne(repo)
            return
        }
        failedAdds += bad
        repos.forEach { pendingAdds.addLast(it) }
        nextAdd()
    }

    /** The next repository of several, or the end: Idle, or which ones could not be added. */
    private fun nextAdd() {
        val repo = pendingAdds.removeFirstOrNull()
        if (repo != null) return addOne(repo)
        currentAdd = null
        batch = false
        _adding.value = if (failedAdds.isEmpty()) AddState.Done else AddState.Failed(AddError.SomeFailed, failedAdds.toList())
    }

    /** "Cancel" on the choice of APK: in a list, only that repository is skipped. */
    fun skipChoice() {
        if (!batch) return dismissAdding()
        currentAdd?.let { failedAdds += it }
        nextAdd()
    }

    /** One repository finished (added, failed, or needing a choice): go on with the rest, if several. */
    private fun finishOne(result: AddState) {
        if (!batch || result is AddState.Choose) {
            _adding.value = result
            return
        }
        // Already added counts as done when moving a list over
        if (result is AddState.Failed && result.reason != AddError.Exists && result.reason != AddError.InKomora) currentAdd?.let { failedAdds += it }
        nextAdd()
    }

    private fun addOne(repo: String) {
        currentAdd = repo
        when {
            // Already there first: moving a list over counts those as done, even when the list is full
            sources.any { it.repo.equals(repo, ignoreCase = true) } -> return finishOne(AddState.Failed(AddError.Exists))
            verified.any { it.repo.equals(repo, ignoreCase = true) } -> return finishOne(AddState.Failed(AddError.InKomora))
            sources.size >= Sources.MAX -> return finishOne(AddState.Failed(AddError.TooMany))
        }
        _adding.value = AddState.Working
        addJob?.cancel()
        addJob = viewModelScope.launch {
            val found = withContext(Dispatchers.IO) { catching { GitHub.latestApks(repo) } }
            // Closed or started again meanwhile: this answer is no longer wanted
            ensureActive()
            finishOne(found.fold(
                onSuccess = { (canonical, apks) ->
                    val names = apks.map { it.name }
                    when {
                        names.isEmpty() -> AddState.Failed(AddError.NoApk)
                        // As GitHub spells it (owner and name may differ in case, or the repository was renamed)
                        sources.any { it.repo.equals(canonical, ignoreCase = true) } -> AddState.Failed(AddError.Exists)
                        verified.any { it.repo.equals(canonical, ignoreCase = true) } -> AddState.Failed(AddError.InKomora)
                        names.size == 1 -> saveSource(canonical, names.single(), names)
                        else -> AddState.Choose(canonical, names.take(12), names)
                    }
                },
                onFailure = { e ->
                    AddState.Failed(
                        when (e) {
                            is RateLimitException -> AddError.RateLimit
                            is NoReleaseException, is BadReleaseException -> AddError.NoRelease
                            else -> AddError.Network
                        },
                    )
                },
            ))
        }
    }

    /** The added sources as links, one per line, to move them to another phone. */
    fun sourcesAsText(): String = sources.joinToString("\n") { Rules.repoUrl(it.repo) }

    /** Step 2, when there were several APKs: the one to take from now on. */
    fun chooseApk(repo: String, name: String) {
        val all = (_adding.value as? AddState.Choose)?.all ?: listOf(name)
        val result = saveSource(repo, name, all)
        // One of several: on with the rest
        if (batch) {
            if (result is AddState.Failed) currentAdd?.let { failedAdds += it }
            nextAdd()
        } else {
            _adding.value = result
        }
    }

    fun dismissAdding() {
        addJob?.cancel()
        addJob = null
        pendingAdds.clear()
        failedAdds.clear()
        currentAdd = null
        batch = false
        _adding.value = AddState.Idle
    }

    /** Saves the source; Idle when done (the dialog closes), or why not. */
    private fun saveSource(repo: String, apkName: String, allNames: List<String>): AddState {
        if (sources.size >= Sources.MAX) return AddState.Failed(AddError.TooMany)
        val source = Source(repo = repo, apkPattern = Rules.patternFor(apkName, allNames), addedAt = System.currentTimeMillis())
        if (!Sources.valid(source)) return AddState.Failed(AddError.NoApk)
        val next = sources + source
        if (!Sources.save(ctx, next)) return AddState.Failed(AddError.Storage)
        sources = next
        rebuildRows()
        viewModelScope.launch { check(source.toApp()) }
        return AddState.Done
    }

    /** The source just removed, kept (with its key) until the owner's chance to undo has passed. */
    private var lastRemoved: List<Source> = emptyList()
    // Where the removed sources live now (after a rename on GitHub): their pictures are kept under that name
    private var lastRemovedNow: Map<String, String> = emptyMap()
    private val _removed = MutableStateFlow<String?>(null)
    /** The name of the source just removed, for "Removed · Undo"; null when there is nothing to undo. */
    val removed: StateFlow<String?> = _removed

    /** Removes an added source from Komora (the app itself, if installed, stays on the phone). */
    fun removeSource(repo: String) {
        if (active?.key?.let { k -> _rows.value.any { it.app.key == k && it.app.repo == repo } } == true) return
        val gone = sources.filter { it.repo == repo }
        if (gone.isEmpty()) return
        val next = sources - gone.toSet()
        if (!Sources.save(ctx, next)) return
        // An earlier removal not undone is final now
        forgetRemoved()
        sources = next
        lastRemoved = gone
        lastRemovedNow = _rows.value.filter { it.app.repo == repo }.mapNotNull { r -> r.latest?.repo?.let { repo to it } }.toMap()
        _removed.value = gone.first().let { it.name.ifEmpty { it.repo } }
        rebuildRows()
    }

    /** "Undo": the source comes back as it was, with the same key, at the same place in the list. */
    fun undoRemove() {
        val back = lastRemoved.filter { r -> sources.none { it.repo.equals(r.repo, ignoreCase = true) } }
        lastRemoved = emptyList()
        lastRemovedNow = emptyMap()
        _removed.value = null
        if (back.isEmpty() || sources.size + back.size > Sources.MAX) return
        val next = (sources + back).sortedBy { it.addedAt }
        if (!Sources.save(ctx, next)) return
        sources = next
        rebuildRows()
    }

    /** The chance to undo has passed: what Komora kept about the source goes, even the last version told about. */
    fun forgetRemoved() {
        val gone = lastRemoved
        // Not what was added again meanwhile
        val back = sources.map { it.repo.lowercase() }.toSet() + verified.map { it.repo.lowercase() }
        val drop = gone.filter { it.repo.lowercase() !in back }
        for (s in drop) if (s.packageName.isNotEmpty()) UpdateCheck.forget(ctx, s.packageName)
        // GitHub's last answer about it too (a file, so not on the main thread)
        val now = lastRemovedNow
        viewModelScope.launch(Dispatchers.IO) {
            drop.forEach {
                Stores.releases(ctx).forget(it.repo)
                // Its pictures, under the name it was added with and the one it has now
                Stores.forgetSourcePictures(ctx, it.repo)
                now[it.repo]?.let { current -> Stores.forgetSourcePictures(ctx, current) }
            }
        }
        lastRemoved = emptyList()
        _removed.value = null
    }

    /** The owner's answer, counted only for the question it was given to. */
    fun answerTrust(request: TrustRequest?, yes: Boolean) {
        if (request == null || request === _trust.value) trustAnswer?.complete(yes)
    }

    private class TrustDeclined : Exception()
    private class AlreadyInKomora : Exception()

    /**
     * Before the first install from an added source: the APK's package must be new to Komora, and an
     * installed copy must have the same key; then the owner sees the name, package and key and decides.
     * On yes, the package and key are pinned, and the row moves to its package.
     */
    private suspend fun trustFirst(app: CatalogApp, apk: File): CatalogApp {
        val id = ApkVerifier.identity(ctx, apk)
        val taken = verified.map { it.packageName } + ctx.packageName +
            sources.filter { it.repo != app.repo }.map { it.packageName }
        if (id.packageName in taken) throw AlreadyInKomora()
        val installed = ApkVerifier.installedSigner(ctx, id.packageName)
        if (installed != null && installed != id.certSha256) throw ApkRejected.InstalledByOtherKey
        val answer = CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main) {
            trustAnswer = answer
            _trust.value = TrustRequest(app.repo, id)
        }
        val yes = try { answer.await() } finally {
            // Also when the install is cancelled meanwhile: the question never stays on screen
            withContext(NonCancellable + Dispatchers.Main) {
                if (trustAnswer === answer) { _trust.value = null; trustAnswer = null }
            }
        }
        if (!yes) throw TrustDeclined()
        return withContext(Dispatchers.Main) {
            // Removed while the question was open: nothing to pin
            val source = sources.firstOrNull { it.repo == app.repo } ?: throw TrustDeclined()
            val pinned = source.copy(packageName = id.packageName, certSha256 = id.certSha256, name = id.label)
            val next = sources.map { if (it.repo == app.repo) pinned else it }
            if (!Sources.save(ctx, next)) throw IOException("Could not save the source")
            sources = next
            val newApp = pinned.toApp()
            _rows.update { rows -> rows.map { if (it.app.key == app.key) it.copy(app = newApp) else it } }
            newApp
        }
    }

    // ---- Installing ----

    fun install(row: AppRow) {
        val release = row.latest ?: return
        if (active != null) return
        val current = Active(row.app.key)
        active = current
        _busy.value = true
        Installer.foregroundBusy = true
        current.job = viewModelScope.launch {
            set(current.key) { it.copy(phase = Phase.Downloading(0, release.apk.size), problem = null) }
            val prepared = withContext(Dispatchers.IO) {
                var file: File? = null
                var keep = false
                try {
                    catching {
                        val dir = apkDir.apply { mkdirs() }
                        // The APK, plus Android's own copy while installing
                        if (dir.usableSpace < release.apk.size * 2) throw NotEnoughSpace()
                        // Named after exactly this file: an interrupted try of it continues where it stopped
                        val f = File(dir, Rules.partialName(release.apk)).also { file = it }
                        // The start of any other file (an older release, a replaced file) is of no use now
                        dir.listFiles()?.filter { it.name.startsWith("part-") && it.name != f.name }?.forEach { it.delete() }
                        var shown = -1L
                        GitHub.download(release.apk, f, isCancelled = { !isActive }) { bytes ->
                            // About 200 updates per download, whatever its size
                            val step = bytes * 200 / release.apk.size
                            if (step != shown) {
                                shown = step
                                set(current.key) { it.copy(phase = Phase.Downloading(bytes, release.apk.size)) }
                            }
                        }
                        // An added source installed for the first time: the owner decides whether to trust it
                        var app = row.app
                        if (app.needsTrust) {
                            app = trustFirst(app, f)
                            withContext(Dispatchers.Main) { current.key = app.key }
                        }
                        ApkVerifier.verify(ctx, f, app)
                        set(current.key) { it.copy(phase = Phase.Installing()) }
                        ensureActive()
                        val id = Installer.prepare(ctx, f, app.packageName)
                        // Cancelled while the APK was being handed over: drop that copy at once
                        if (!isActive) {
                            Installer.dropUncommitted(ctx)
                            throw CancellationException("Cancelled")
                        }
                        id
                    }.also { result -> keep = result.exceptionOrNull() is InterruptedDownload }
                } finally {
                    // The session holds its own copy now. Only a broken connection keeps what arrived, for the next try
                    if (!keep) file?.delete()
                }
            }
            val id = prepared.getOrElse { e ->
                if (active === current) finish(current)
                // The kept answer about this release may be out of date: GitHub is asked anew right away,
                // so the next tap does not try the same file again
                if (e is DownloadException && e !is InterruptedDownload) viewModelScope.launch {
                    withContext(Dispatchers.IO) { Stores.releases(ctx).forget(row.app.repo) }
                    check(row.app, force = true)
                }
                // Saying no to an unknown app is not an error
                set(current.key) { it.copy(phase = Phase.Idle, problem = if (e is TrustDeclined) null else problemOf(e)) }
                // One app failing does not stop "Update all"
                startNextQueued()
                return@launch
            }
            // Known before Android can answer, so no answer is missed
            current.sessionId = id
            set(current.key) { it.copy(phase = Phase.Installing(id)) }
            withContext(Dispatchers.IO) { catching { Installer.commit(ctx, id) } }.onFailure {
                if (active === current) {
                    finish(current)
                    set(current.key) { it.copy(phase = Phase.Idle, problem = Problem.Install) }
                    startNextQueued()
                }
            }
        }
        // A cancelled download returns the row to where it was, with no error
        current.job?.invokeOnCompletion { cause ->
            // Directly, not in a new coroutine: when the screen is gone for good, that one would never run
            if (cause is CancellationException && active === current) Installer.foregroundBusy = false
            if (cause is CancellationException) viewModelScope.launch {
                answerTrust(null, false)
                if (active === current) {
                    finish(current)
                    set(current.key) { it.copy(phase = Phase.Idle) }
                }
            }
        }
    }

    /** Apps waiting their turn after "Update all"; installed one after another. */
    private val queue = ArrayDeque<String>()

    /** Updates every app with a newer release, one at a time (Android may ask about each). */
    fun updateAll(keys: List<String>) {
        queue.clear()
        _rows.value.filter { it.app.key in keys && it.hasUpdate && !it.foreignKey && it.latest != null }
            // Komora itself last: updating it closes it
            .sortedBy { it.app.isSelf }
            .forEach { queue.addLast(it.app.key) }
        startNextQueued()
    }

    private fun startNextQueued() {
        while (active == null) {
            val key = queue.removeFirstOrNull() ?: return
            val row = _rows.value.firstOrNull { it.app.key == key } ?: continue
            if (row.hasUpdate && row.latest != null) install(row)
        }
    }

    /** Stops the download, or the install that waits for the owner's answer. */
    fun cancelInstall() {
        queue.clear()
        val a = active ?: return
        answerTrust(null, false)
        val id = a.sessionId
        if (id == null) a.job?.cancel() else Installer.cancel(ctx, id)
    }

    override fun onCleared() {
        // The screen is gone: automatic updates may run again, even if Android's question was left unanswered
        active?.let { a ->
            Installer.foregroundBusy = false
            a.sessionId?.let(Installer::forgetConfirm)
        }
        super.onCleared()
    }

    private fun finish(a: Active) {
        if (active === a) {
            active = null
            _busy.value = false
            Installer.foregroundBusy = false
        }
    }

    /** Like runCatching, but never swallows cancellation. */
    private inline fun <T> catching(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private fun set(key: String, change: (AppRow) -> AppRow) {
        _rows.update { rows -> rows.map { if (it.app.key == key) change(it) else it } }
    }

    private class NotEnoughSpace : Exception()

    private fun problemOf(e: Throwable): Problem = when (e) {
        is RateLimitException -> Problem.RateLimit
        is NoReleaseException -> Problem.NoRelease
        is BadReleaseException -> Problem.BadRelease
        is DownloadException -> Problem.Download
        is NotEnoughSpace -> Problem.NoSpace
        is ApkRejected.Unreadable -> Problem.Unreadable
        is ApkRejected.WrongPackage -> Problem.WrongPackage
        is ApkRejected.WrongSigner -> Problem.WrongSigner
        is ApkRejected.NotNewer -> Problem.NotNewer
        is ApkRejected.TooOld -> Problem.TooOld
        is ApkRejected.InstalledByOtherKey -> Problem.OtherKey
        is AlreadyInKomora -> Problem.AlreadyInKomora
        is InstallFailed -> Problem.Install
        else -> Problem.Network
    }
}

/** At most this many added apps' icons are looked up per round (each may take a few of GitHub's 60 hourly requests). */
private const val ICONS_PER_ROUND = 3
