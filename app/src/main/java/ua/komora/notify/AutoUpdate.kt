package ua.komora.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import ua.komora.R
import ua.komora.catalog.Catalog
import ua.komora.catalog.CatalogApp
import ua.komora.catalog.Sources
import ua.komora.install.ApkVerifier
import ua.komora.install.Installer
import ua.komora.net.GitHub
import ua.komora.net.RateLimitException
import ua.komora.net.Rules
import ua.komora.ui.AppLanguage
import ua.komora.ui.MainActivity
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Automatic updates, off unless the owner turns them on. Once a day, when the phone allows (over Wi-Fi
 * only, or any connection, as chosen; on the charger if asked), Komora updates the apps it installed
 * itself, with exactly the same checks as a tap on "Update": the pinned key, the package, a newer
 * version. Android lets it do so without asking only for apps Komora installed; any other app, and
 * Komora itself, is left for the owner, who updates them in Komora as before.
 */
object AutoUpdate {
    private const val FILE = "auto_update"
    private const val JOB_ID = 2
    private const val CHANNEL = "auto_updates"
    private const val NOTICE_DONE = 2
    private const val NOTICE_ASK = 3

    data class Settings(val enabled: Boolean = false, val wifiOnly: Boolean = true, val charging: Boolean = false)

    fun settings(context: Context): Settings = try {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        Settings(p.getBoolean("enabled", false), p.getBoolean("wifi_only", true), p.getBoolean("charging", false))
    } catch (_: Exception) {
        Settings()
    }

    /** Starts or stops the daily run to match [s], then saves it; false when Android refused to schedule it. */
    fun apply(context: Context, s: Settings): Boolean {
        val jobs = context.getSystemService(JobScheduler::class.java) ?: return false
        val saved = if (!s.enabled) {
            jobs.cancel(JOB_ID)
            // Nothing half downloaded is left behind
            runCatching { folder(context).deleteRecursively() }
            s
        } else {
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, AutoUpdateJob::class.java))
                .setPeriodic(TimeUnit.DAYS.toMillis(1), TimeUnit.HOURS.toMillis(6))
                .setRequiredNetworkType(if (s.wifiOnly) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY)
                .setRequiresCharging(s.charging)
                // Not when the battery or the storage is low
                .setRequiresBatteryNotLow(true)
                .setRequiresStorageNotLow(true)
                .setBackoffCriteria(TimeUnit.HOURS.toMillis(1), JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .setPersisted(true)
                .build()
            val ok = try { jobs.schedule(job) == JobScheduler.RESULT_SUCCESS } catch (_: Exception) { false }
            if (ok) s else s.copy(enabled = false)
        }
        try {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putBoolean("enabled", saved.enabled).putBoolean("wifi_only", saved.wifiOnly)
                .putBoolean("charging", saved.charging).apply()
        } catch (_: Exception) {
            // Only a preference
        }
        return saved == s
    }

    /** On every start of Komora: if turned on, make sure the daily run is there. */
    fun ensure(context: Context) {
        val s = settings(context)
        if (s.enabled && context.getSystemService(JobScheduler::class.java)?.getPendingJob(JOB_ID) == null) apply(context, s)
    }

    /** The version of [pkg] Android last refused to update without the owner, if any. */
    private fun refused(context: Context, pkg: String): String? =
        runCatching { context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("refused_$pkg", null) }.getOrNull()

    /** Remembers that this version needs the owner, so it is not downloaded again every day. */
    internal fun rememberRefused(context: Context, pkg: String, version: String) {
        if (pkg.isEmpty() || version.isEmpty()) return
        runCatching { context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString("refused_$pkg", version.take(40)).apply() }
    }

    /** Where the run keeps a download; emptied when the run starts and when automatic updates are turned off. */
    private fun folder(context: Context) = File(context.noBackupFilesDir, "auto")

    /** True when Komora is the installer of record, so Android lets it update without asking. */
    private fun installedByKomora(context: Context, pkg: String): Boolean = try {
        context.packageManager.getInstallSourceInfo(pkg).installingPackageName == context.packageName
    } catch (_: Exception) {
        false
    }

    /**
     * The run itself, off the main thread. Returns false only when the connection failed, so Android
     * tries again later. One app at a time; any problem with one app leaves it for the next run.
     */
    fun run(base: Context, stopped: () -> Boolean): Boolean {
        val context = AppLanguage.wrap(base)
        if (!settings(context).enabled) return true
        val pm = context.packageManager
        // Komora open and installing, or waiting for the owner's answer: not now
        if (Installer.foregroundBusy || Installer.confirm.value != null) return true
        // Leftovers of a run stopped half way (never handed to Android)
        runCatching { Installer.dropUncommitted(context) }
        val lang = context.getString(R.string.catalog_lang)
        val builtIn = Catalog.parse(context.assets.open("catalog.json").bufferedReader().use { it.readText() })
        val taken = builtIn.map { it.packageName }.toSet() + context.packageName
        val added = if (!Sources.enabled(context)) emptyList() else Sources.load(context)
            .filter { it.trusted && it.packageName !in taken }.map { it.toApp() }
        val dir = folder(context).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        var waiting = false
        for (app in builtIn + added) {
            if (stopped() || Installer.foregroundBusy) return true
            // Komora itself: updating it would end this very run; the owner does that in Komora
            if (app.isSelf || app.packageName == context.packageName) continue
            val installed = ApkVerifier.installedInfo(pm, app.packageName) ?: continue
            if (!installedByKomora(context, app.packageName)) continue
            // Signed with another key: the update would be refused anyway, so it is not even downloaded
            if (!ApkVerifier.installedMatchesPin(context, app, installed)) continue
            val release = try {
                GitHub.latestRelease(app)
            } catch (_: RateLimitException) {
                return true
            } catch (_: ua.komora.net.NoReleaseException) {
                // No release (yet, or any more): this app only
                continue
            } catch (e: IOException) {
                if (app.added) continue
                return false
            } catch (_: Exception) {
                continue
            }
            val latest = Rules.normalizeVersion(release.tag)
            if ((Rules.compareVersions(latest, installed.versionName ?: continue) ?: 0) <= 0) continue
            // Only a release that has been out for two days: time to notice and pull a bad one
            if (!Rules.oldEnough(release.published, System.currentTimeMillis())) continue
            // Android already refused this very version without the owner: left for the owner, not fetched again
            if (refused(context, app.packageName) == latest) continue
            if (dir.usableSpace < release.apk.size * 2) continue
            val file = File.createTempFile("apk", ".apk", dir)
            try {
                try {
                    GitHub.download(release.apk, file, isCancelled = stopped) { }
                } catch (e: IOException) {
                    if (stopped()) return true
                    continue
                }
                // Every check, as for a tap on "Update"
                try {
                    ApkVerifier.verify(context, file, app)
                } catch (_: Exception) {
                    continue
                }
                if (stopped() || Installer.foregroundBusy) return true
                val id = try { Installer.prepare(context, file, app.packageName, background = true) } catch (_: Exception) { continue }
                try {
                    // The owner started an install meanwhile: theirs goes first
                    if (Installer.foregroundBusy) {
                        runCatching { context.packageManager.packageInstaller.abandonSession(id) }
                        return true
                    }
                    Installer.commitInBackground(context, id, "${app.name(lang)} $latest", app.packageName, latest)
                } catch (_: Exception) {
                    continue
                } finally {
                    Installer.backgroundSessions -= id
                }
                waiting = true
            } finally {
                file.delete()
            }
        }
        return true.also { if (!waiting) dir.delete() }
    }

    private fun channel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.auto_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun openKomora(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 1,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** "Updated automatically: Tarilka 1.3.0, …", one notice that grows during the day. */
    internal fun noticeDone(base: Context, what: String) {
        val context = AppLanguage.wrap(base)
        if (!UpdateCheck.canNotify(context, CHANNEL)) return
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val today = System.currentTimeMillis() / TimeUnit.DAYS.toMillis(1)
        val list = (if (p.getLong("done_day", -1) == today) p.getString("done", "").orEmpty().split('\n') else emptyList())
            .filter { it.isNotBlank() } + what
        p.edit().putLong("done_day", today).putString("done", list.takeLast(20).joinToString("\n")).apply()
        notify(context, NOTICE_DONE, context.getString(R.string.auto_done_title), list.joinToString(", "))
    }

    /** Android wants the owner's own yes for this one: Komora asks them to open it. */
    internal fun noticeAsk(base: Context, what: String) {
        val context = AppLanguage.wrap(base)
        if (!UpdateCheck.canNotify(context, CHANNEL)) return
        // One notice per app, so none hides another
        notify(context, NOTICE_ASK + (what.substringBeforeLast(' ').hashCode() and 0xFFFF), context.getString(R.string.auto_ask_title),
            context.getString(R.string.auto_ask_text, what))
    }

    private fun notify(context: Context, id: Int, title: String, text: String) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        channel(context)
        // On a locked screen: only the title, not which apps
        val public = Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle(title).build()
        val notice = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openKomora(context))
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .build()
        runCatching { nm.notify(id, notice) }
    }
}

/** Android runs this once a day while automatic updates are on; the work happens on its own thread. */
class AutoUpdateJob : JobService() {
    @Volatile private var stopped = false

    override fun onStartJob(params: JobParameters): Boolean {
        stopped = false
        Thread {
            val done = try {
                AutoUpdate.run(applicationContext) { stopped }
            } catch (_: Throwable) {
                // Never a crash in the background; tried again tomorrow
                true
            }
            if (!stopped) jobFinished(params, !done)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        stopped = true
        return true
    }
}

/** Android's answer for an update started in the background. */
class AutoInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val what = intent.getStringExtra(Installer.EXTRA_WHAT).orEmpty().take(80)
        val pkg = intent.getStringExtra(Installer.EXTRA_PACKAGE).orEmpty()
        val version = intent.getStringExtra(Installer.EXTRA_VERSION).orEmpty()
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_SUCCESS -> AutoUpdate.noticeDone(context, what)
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Nothing is ever opened from the background: the session is dropped and the owner asked
                if (id != -1) runCatching { context.packageManager.packageInstaller.abandonSession(id) }
                AutoUpdate.rememberRefused(context, pkg, version)
                AutoUpdate.noticeAsk(context, what)
            }
            // This version cannot be installed on this phone: not downloaded again every day
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> AutoUpdate.rememberRefused(context, pkg, version)
            // Anything else (space, a passing conflict) is simply tried again next time
            else -> Unit
        }
    }
}
