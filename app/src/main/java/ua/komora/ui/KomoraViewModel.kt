package ua.komora.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ua.komora.catalog.Catalog
import ua.komora.catalog.CatalogApp
import ua.komora.install.ApkVerifier
import ua.komora.install.InstallFailed
import ua.komora.install.InstallResult
import ua.komora.install.Installer
import ua.komora.install.VerifyError
import ua.komora.net.GitHub
import ua.komora.net.NoReleaseException
import ua.komora.net.RateLimitException
import ua.komora.net.Release
import ua.komora.net.Rules
import java.io.File

enum class Problem { Network, RateLimit, NoRelease, BadRelease, Unreadable, WrongPackage, WrongSigner, NotNewer, OtherKey, Install }

/** Problems from an install attempt; a later update check keeps them on screen. */
private val installProblems = setOf(
    Problem.Unreadable, Problem.WrongPackage, Problem.WrongSigner, Problem.NotNewer, Problem.OtherKey, Problem.Install,
)

sealed class Phase {
    object Idle : Phase()
    object Checking : Phase()
    data class Downloading(val progress: Float) : Phase()
    /** [sessionId] is null while the APK is being handed to Android. */
    data class Installing(val sessionId: Int? = null) : Phase()
}

data class AppRow(
    val app: CatalogApp,
    val installedVersion: String? = null,
    val latest: Release? = null,
    val phase: Phase = Phase.Idle,
    val problem: Problem? = null,
) {
    val isInstalled get() = installedVersion != null
    val hasUpdate get() = latest != null && installedVersion != null &&
        Rules.normalizeVersion(latest.tag) != Rules.normalizeVersion(installedVersion)
}

class KomoraViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()

    private val catalog: List<CatalogApp> =
        Catalog.parse(app.assets.open("catalog.json").bufferedReader().use { it.readText() })

    private val _rows = MutableStateFlow(catalog.map { AppRow(it) })
    val rows: StateFlow<List<AppRow>> = _rows

    /** One download or install at a time keeps things simple and predictable. Touched only on the main thread. */
    private var active: Active? = null
    private class Active(val pkg: String, var sessionId: Int? = null)

    private var checkJob: Job? = null

    init {
        refreshInstalled()
        checkUpdates()
        viewModelScope.launch {
            Installer.results.collect { result ->
                // Only the answer for the session started here counts; a late one from an older session is ignored
                val a = active ?: return@collect
                if (a.sessionId != result.sessionId) return@collect
                active = null
                set(a.pkg) {
                    it.copy(phase = Phase.Idle, problem = if (result is InstallResult.Failure) Problem.Install else null)
                }
                refreshInstalled()
            }
        }
    }

    /** Stops the install that is waiting (e.g. its confirmation screen was closed without an answer). */
    fun cancelInstall() {
        val id = active?.sessionId ?: return
        Installer.cancel(ctx, id)
    }

    fun refreshInstalled() {
        val pm = ctx.packageManager
        _rows.update { rows ->
            rows.map { row ->
                // An installed app with no version name still counts as installed
                val info = ApkVerifier.installedInfo(pm, row.app.packageName)
                row.copy(installedVersion = info?.let { it.versionName ?: "?" })
            }
        }
    }

    fun checkUpdates() {
        // One check at a time: GitHub allows only 60 requests an hour without an account
        if (checkJob?.isActive == true) return
        checkJob = viewModelScope.launch {
            for (app in catalog) {
                set(app.packageName) { if (it.phase == Phase.Idle) it.copy(phase = Phase.Checking) else it }
                val result = withContext(Dispatchers.IO) { catching { GitHub.latestRelease(app.repo, app.assetPrefix) } }
                set(app.packageName) { row ->
                    val phase = if (row.phase == Phase.Checking) Phase.Idle else row.phase
                    result.fold(
                        onSuccess = { row.copy(latest = it, phase = phase, problem = row.problem.takeIf { p -> p in installProblems }) },
                        onFailure = { row.copy(phase = phase, problem = problemOf(it)) },
                    )
                }
                // After a rate limit, the rest would fail the same way
                if (result.exceptionOrNull() is RateLimitException) break
            }
        }
    }

    fun install(row: AppRow) {
        val release = row.latest ?: return
        if (active != null) return
        val pkg = row.app.packageName
        val current = Active(pkg)
        active = current
        viewModelScope.launch {
            set(pkg) { it.copy(phase = Phase.Downloading(0f), problem = null) }
            val prepared = withContext(Dispatchers.IO) {
                val dir = File(ctx.noBackupFilesDir, "apk").apply { mkdirs() }
                // A new file each time, so two attempts can never share bytes
                val file = File.createTempFile(pkg, ".apk", dir)
                try {
                    catching {
                        var shown = -1
                        GitHub.download(release.apk, file) { p ->
                            val percent = (p * 100).toInt()
                            if (percent != shown) {
                                shown = percent
                                set(pkg) { it.copy(phase = Phase.Downloading(p)) }
                            }
                        }
                        ensureActive()
                        ApkVerifier.verify(ctx, file, row.app)
                        set(pkg) { it.copy(phase = Phase.Installing()) }
                        Installer.prepare(ctx, file, pkg)
                    }
                } finally {
                    // The session holds its own copy now
                    file.delete()
                }
            }
            val id = prepared.getOrElse { e ->
                active = null
                set(pkg) { it.copy(phase = Phase.Idle, problem = problemOf(e)) }
                return@launch
            }
            // Known before Android can answer, so no answer is missed
            current.sessionId = id
            set(pkg) { it.copy(phase = Phase.Installing(id)) }
            withContext(Dispatchers.IO) { catching { Installer.commit(ctx, id) } }.onFailure {
                active = null
                set(pkg) { it.copy(phase = Phase.Idle, problem = Problem.Install) }
            }
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

    private fun problemOf(e: Throwable): Problem = when (e) {
        is RateLimitException -> Problem.RateLimit
        is NoReleaseException -> Problem.NoRelease
        is VerifyError.Unreadable -> Problem.Unreadable
        is VerifyError.WrongPackage -> Problem.WrongPackage
        is VerifyError.WrongSigner -> Problem.WrongSigner
        is VerifyError.NotNewer -> Problem.NotNewer
        is VerifyError.InstalledByOtherKey -> Problem.OtherKey
        is InstallFailed -> Problem.Install
        // The release is there, but without exactly one correctly named APK
        is IllegalArgumentException -> Problem.BadRelease
        else -> Problem.Network
    }
}
