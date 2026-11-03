package ua.komora.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ua.komora.catalog.Catalog
import ua.komora.catalog.CatalogApp
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
import java.io.File

enum class Problem {
    Network, RateLimit, NoRelease, BadRelease, Download, NoSpace,
    Unreadable, WrongPackage, WrongSigner, NotNewer, OtherKey, Install,
}

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

    private val catalog: List<CatalogApp> =
        Catalog.parse(app.assets.open("catalog.json").bufferedReader().use { it.readText() })

    private val _rows = MutableStateFlow(catalog.map { AppRow(it) })
    val rows: StateFlow<List<AppRow>> = _rows

    /** True while one app downloads or installs: one at a time keeps things simple and predictable. */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    /** The one install in progress. Touched only on the main thread. */
    private var active: Active? = null
    private class Active(val pkg: String) {
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
                set(a.pkg) { it.copy(phase = Phase.Idle, problem = problem) }
                refreshInstalled()
                // "Update all": the next one, unless the owner cancelled
                if (result is InstallResult.Cancelled) queue.clear()
                startNextQueued()
            }
        }
    }

    fun refreshInstalled() {
        val pm = ctx.packageManager
        _rows.update { rows ->
            rows.map { row ->
                val info = runCatching { ApkVerifier.installedInfo(pm, row.app.packageName) }.getOrNull()
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
            for ((index, app) in catalog.withIndex()) {
                set(app.packageName) { if (it.phase == Phase.Idle) it.copy(phase = Phase.Checking) else it }
                val result = withContext(Dispatchers.IO) { catching { GitHub.latestRelease(app.repo, app.assetPrefix) } }
                set(app.packageName) { row ->
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
                // Without a connection or after the rate limit, the rest would fail the same way: say so for all
                val e = result.exceptionOrNull() ?: continue
                val p = problemOf(e)
                if (p == Problem.RateLimit || p == Problem.Network) {
                    for (rest in catalog.drop(index + 1)) {
                        set(rest.packageName) {
                            if (it.isBusy || (it.problem != null && it.problem !in checkProblems)) it else it.copy(problem = p)
                        }
                    }
                    break
                }
            }
        }
    }

    fun install(row: AppRow) {
        val release = row.latest ?: return
        if (active != null) return
        val pkg = row.app.packageName
        val current = Active(pkg)
        active = current
        _busy.value = true
        current.job = viewModelScope.launch {
            set(pkg) { it.copy(phase = Phase.Downloading(0, release.apk.size), problem = null) }
            val prepared = withContext(Dispatchers.IO) {
                var file: File? = null
                try {
                    catching {
                        val dir = apkDir.apply { mkdirs() }
                        // The APK, plus Android's own copy while installing
                        if (dir.usableSpace < release.apk.size * 2) throw NotEnoughSpace()
                        // A new file each time, so two attempts can never share bytes
                        val f = File.createTempFile(pkg, ".apk", dir).also { file = it }
                        var shown = -1L
                        GitHub.download(release.apk, f, isCancelled = { !isActive }) { bytes ->
                            // About 200 updates per download, whatever its size
                            val step = bytes * 200 / release.apk.size
                            if (step != shown) {
                                shown = step
                                set(pkg) { it.copy(phase = Phase.Downloading(bytes, release.apk.size)) }
                            }
                        }
                        ApkVerifier.verify(ctx, f, row.app)
                        set(pkg) { it.copy(phase = Phase.Installing()) }
                        ensureActive()
                        val id = Installer.prepare(ctx, f, pkg)
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
                set(pkg) { it.copy(phase = Phase.Idle, problem = problemOf(e)) }
                // One app failing does not stop "Update all"
                startNextQueued()
                return@launch
            }
            // Known before Android can answer, so no answer is missed
            current.sessionId = id
            set(pkg) { it.copy(phase = Phase.Installing(id)) }
            withContext(Dispatchers.IO) { catching { Installer.commit(ctx, id) } }.onFailure {
                if (active === current) {
                    finish(current)
                    set(pkg) { it.copy(phase = Phase.Idle, problem = Problem.Install) }
                    startNextQueued()
                }
            }
        }
        // A cancelled download returns the row to where it was, with no error
        current.job?.invokeOnCompletion { cause ->
            if (cause is CancellationException) viewModelScope.launch {
                if (active === current) {
                    finish(current)
                    set(pkg) { it.copy(phase = Phase.Idle) }
                }
            }
        }
    }

    /** Stops the download, or the install that waits for the owner's answer. */
    /** Apps waiting their turn after "Update all"; installed one after another. */
    private val queue = ArrayDeque<String>()

    /** Updates every app with a newer release, one at a time (Android may ask about each). */
    fun updateAll() {
        queue.clear()
        _rows.value.filter { it.hasUpdate && !it.foreignKey && it.latest != null }
            // Komora itself last: updating it closes it
            .sortedBy { it.app.isSelf }
            .forEach { queue.addLast(it.app.packageName) }
        startNextQueued()
    }

    private fun startNextQueued() {
        while (active == null) {
            val pkg = queue.removeFirstOrNull() ?: return
            val row = _rows.value.firstOrNull { it.app.packageName == pkg } ?: continue
            if (row.hasUpdate && row.latest != null) install(row)
        }
    }

    fun cancelInstall() {
        queue.clear()
        val a = active ?: return
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

    private fun set(pkg: String, change: (AppRow) -> AppRow) {
        _rows.update { rows -> rows.map { if (it.app.packageName == pkg) change(it) else it } }
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
        is InstallFailed -> Problem.Install
        else -> Problem.Network
    }
}
