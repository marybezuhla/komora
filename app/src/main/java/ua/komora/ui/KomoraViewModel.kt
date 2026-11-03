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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    /** An added source's APK claims to be an app Komora already has. */
    AlreadyInKomora,
}

/** Adding a source, step by step, for the dialog. */
sealed class AddState {
    object Idle : AddState()
    object Working : AddState()
    /** The release has several APKs: the owner picks one, once. */
    /** [names] are shown; [all] are every APK in the release, so the pattern can be checked against them. */
    data class Choose(val repo: String, val names: List<String>, val all: List<String> = names) : AddState()
    data class Failed(val reason: AddError) : AddState()
}

enum class AddError { BadLink, Exists, InKomora, TooMany, NoRelease, NoApk, Network, RateLimit, Storage }

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
    private var sources: List<Source> = Sources.load(app)
    private val _sourcesEnabled = MutableStateFlow(Sources.enabled(app))
    val sourcesEnabled: StateFlow<Boolean> = _sourcesEnabled

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
        viewModelScope.launch {
            // Leftovers from a time Komora was closed mid-install
            withContext(Dispatchers.IO) {
                runCatching { apkDir.listFiles()?.forEach { it.delete() } }
                runCatching { Installer.dropUncommitted(ctx) }
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

    fun checkUpdates() {
        // One check at a time: GitHub allows only 60 requests an hour without an account
        if (checkJob?.isActive == true) return
        checkJob = viewModelScope.launch {
            val list = apps()
            for ((index, app) in list.withIndex()) {
                // Sources switched off or removed meanwhile: not asked about any more
                if (apps().none { it.key == app.key }) continue
                if (!check(app)) {
                    // Without a connection or after the rate limit, the rest would fail the same way: say so for all
                    val p = _rows.value.firstOrNull { it.app.key == app.key }?.problem ?: break
                    for (rest in list.drop(index + 1)) {
                        set(rest.key) {
                            if (it.isBusy || (it.problem != null && it.problem !in checkProblems)) it else it.copy(problem = p)
                        }
                    }
                    break
                }
            }
        }
    }

    /** Asks GitHub about one app. False when the connection or GitHub's limit stops the whole check. */
    private suspend fun check(app: CatalogApp): Boolean {
        set(app.key) { if (it.phase == Phase.Idle) it.copy(phase = Phase.Checking) else it }
        val result = withContext(Dispatchers.IO) { catching { GitHub.latestRelease(app) } }
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

    fun setSourcesEnabled(on: Boolean) {
        Sources.setEnabled(ctx, on)
        _sourcesEnabled.value = on
        rebuildRows()
        if (on) {
            // Through the one check at a time; a check already running is left to finish
            if (checkJob?.isActive == true) return
            checkJob = viewModelScope.launch {
                for (app in apps().filter { it.added }) {
                    if (apps().none { it.key == app.key }) continue
                    if (!check(app)) break
                }
            }
        }
    }

    /** Step 1: a link or "owner/name"; finds the APKs in its newest release. */
    fun addSource(input: String) {
        val repo = Sources.parseRepo(input) ?: return run { _adding.value = AddState.Failed(AddError.BadLink) }
        when {
            sources.size >= Sources.MAX -> return run { _adding.value = AddState.Failed(AddError.TooMany) }
            sources.any { it.repo.equals(repo, ignoreCase = true) } -> return run { _adding.value = AddState.Failed(AddError.Exists) }
            verified.any { it.repo.equals(repo, ignoreCase = true) } -> return run { _adding.value = AddState.Failed(AddError.InKomora) }
        }
        _adding.value = AddState.Working
        addJob?.cancel()
        addJob = viewModelScope.launch {
            val found = withContext(Dispatchers.IO) { catching { GitHub.latestApks(repo) } }
            // Closed or started again meanwhile: this answer is no longer wanted
            ensureActive()
            _adding.value = found.fold(
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
            )
        }
    }

    /** Step 2, when there were several APKs: the one to take from now on. */
    fun chooseApk(repo: String, name: String) {
        val all = (_adding.value as? AddState.Choose)?.all ?: listOf(name)
        _adding.value = saveSource(repo, name, all)
    }

    fun dismissAdding() {
        addJob?.cancel()
        addJob = null
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
        return AddState.Idle
    }

    /** Removes an added source from Komora (the app itself, if installed, stays on the phone). */
    fun removeSource(repo: String) {
        if (active?.key?.let { k -> _rows.value.any { it.app.key == k && it.app.repo == repo } } == true) return
        val gone = sources.filter { it.repo == repo }
        val next = sources - gone.toSet()
        if (!Sources.save(ctx, next)) return
        sources = next
        // Nothing about it is left behind, not even the last version told about
        for (s in gone) if (s.packageName.isNotEmpty()) UpdateCheck.forget(ctx, s.packageName)
        rebuildRows()
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
        current.job = viewModelScope.launch {
            set(current.key) { it.copy(phase = Phase.Downloading(0, release.apk.size), problem = null) }
            val prepared = withContext(Dispatchers.IO) {
                var file: File? = null
                try {
                    catching {
                        val dir = apkDir.apply { mkdirs() }
                        // The APK, plus Android's own copy while installing
                        if (dir.usableSpace < release.apk.size * 2) throw NotEnoughSpace()
                        // A new file each time, so two attempts can never share bytes
                        val f = File.createTempFile("apk", ".apk", dir).also { file = it }
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
                    }
                } finally {
                    // The session holds its own copy now
                    file?.delete()
                }
            }
            val id = prepared.getOrElse { e ->
                if (active === current) finish(current)
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

    private fun finish(a: Active) {
        if (active === a) {
            active = null
            _busy.value = false
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
        is ApkRejected.InstalledByOtherKey -> Problem.OtherKey
        is AlreadyInKomora -> Problem.AlreadyInKomora
        is InstallFailed -> Problem.Install
        else -> Problem.Network
    }
}
