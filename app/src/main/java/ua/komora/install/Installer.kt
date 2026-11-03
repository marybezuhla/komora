package ua.komora.install

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File

/** What happened to one install session. */
sealed class InstallResult {
    abstract val sessionId: Int
    data class Success(override val sessionId: Int) : InstallResult()
    data class Failure(override val sessionId: Int, val noSpace: Boolean = false) : InstallResult()
    data class Cancelled(override val sessionId: Int) : InstallResult()
}

/** Android's confirmation screen for a session; kept until that session ends, so it can be opened again. */
data class Confirm(val sessionId: Int, val intent: Intent, val shown: Boolean)

/** Android refused to take or start the install (not a network or signature problem). */
class InstallFailed(cause: Throwable) : Exception(cause)

object Installer {
    private val _results = MutableSharedFlow<InstallResult>(replay = 1, extraBufferCapacity = 8)
    val results: SharedFlow<InstallResult> = _results

    private val _confirm = MutableStateFlow<Confirm?>(null)
    val confirm: StateFlow<Confirm?> = _confirm

    internal fun report(result: InstallResult) {
        _confirm.update { if (it?.sessionId == result.sessionId) null else it }
        _results.tryEmit(result)
    }

    internal fun askToConfirm(sessionId: Int, intent: Intent) {
        _confirm.value = Confirm(sessionId, intent, shown = false)
    }

    fun markShown(sessionId: Int) {
        _confirm.update { if (it?.sessionId == sessionId) it.copy(shown = true) else it }
    }

    /**
     * Step 1: copies an already verified APK into a new install session and returns its id.
     * Nothing is installed yet; the APK file may be deleted afterwards.
     */
    fun prepare(context: Context, apk: File, packageName: String): Int = wrap {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(packageName)
            setSize(apk.length())
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            // With UPDATE_PACKAGES_WITHOUT_USER_ACTION: updates to apps Komora installed need no extra tap
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            if (Build.VERSION.SDK_INT >= 33) setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE)
            // With ENFORCE_UPDATE_OWNERSHIP (Android 14+): another installer must ask the owner before updating
            if (Build.VERSION.SDK_INT >= 34) setRequestUpdateOwnership(true)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { out ->
                        input.copyTo(out, 64 * 1024)
                        session.fsync(out)
                    }
                }
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            throw e
        }
        id
    }

    /** Step 2: asks Android to install. The answer comes to [InstallReceiver]. */
    fun commit(context: Context, sessionId: Int) = wrap {
        val installer = context.packageManager.packageInstaller
        try {
            installer.openSession(sessionId).use { session ->
                val intent = Intent(context, InstallReceiver::class.java).setPackage(context.packageName)
                // Mutable is required: Android fills in the result. The intent is explicit, to Komora's own receiver.
                val pending = PendingIntent.getBroadcast(
                    context, sessionId, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            throw e
        }
    }

    /**
     * Stops a session that is waiting for the owner's answer. Reported as cancelled only if Android really
     * dropped it; a session Android already holds finishes and reports by itself.
     */
    fun cancel(context: Context, sessionId: Int) {
        val dropped = runCatching { context.packageManager.packageInstaller.abandonSession(sessionId) }.isSuccess
        if (dropped || context.packageManager.packageInstaller.getSessionInfo(sessionId) == null) {
            report(InstallResult.Cancelled(sessionId))
        }
    }

    /** Drops sessions Komora prepared but never handed to Android (e.g. it was closed in between). */
    fun dropUncommitted(context: Context) {
        val installer = context.packageManager.packageInstaller
        installer.mySessions.filter { !it.isCommitted }.forEach { runCatching { installer.abandonSession(it.sessionId) } }
    }

    private inline fun <T> wrap(block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        throw InstallFailed(e)
    }
}

/** Receives the installer's answer. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        if (id == -1) return
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm != null && confirm.action in CONFIRM_ACTIONS) {
                    // Opened by MainActivity while it is in front: Android blocks opening it from the background
                    Installer.askToConfirm(id, confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } else {
                    // Anything else is never opened; the session is dropped
                    runCatching { context.packageManager.packageInstaller.abandonSession(id) }
                    Installer.report(InstallResult.Failure(id))
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Installer.report(InstallResult.Success(id))
            PackageInstaller.STATUS_FAILURE_ABORTED -> Installer.report(InstallResult.Cancelled(id))
            PackageInstaller.STATUS_FAILURE_STORAGE -> Installer.report(InstallResult.Failure(id, noSpace = true))
            else -> Installer.report(InstallResult.Failure(id))
        }
    }

    private companion object {
        // The installer screens Android uses (the second one on some older versions)
        val CONFIRM_ACTIONS = setOf(
            "android.content.pm.action.CONFIRM_INSTALL",
            "android.content.pm.action.CONFIRM_PERMISSIONS",
        )
    }
}
