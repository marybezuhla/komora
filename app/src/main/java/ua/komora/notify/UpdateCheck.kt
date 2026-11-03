package ua.komora.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import ua.komora.R
import ua.komora.catalog.Catalog
import ua.komora.install.ApkVerifier
import ua.komora.net.GitHub
import ua.komora.net.RateLimitException
import ua.komora.net.Rules
import ua.komora.ui.MainActivity
import java.util.concurrent.TimeUnit

/**
 * Update notices, off unless the owner turns them on: once a day, in the background, Komora asks
 * GitHub about the apps installed on this phone and says when one has a newer version. It only
 * tells; installing still happens in Komora, with every check.
 */
object UpdateCheck {
    private const val FILE = "updates"
    private const val JOB_ID = 1
    private const val CHANNEL = "updates"
    private const val NOTICE_ID = 1

    data class Settings(val enabled: Boolean = false, val wifiOnly: Boolean = false)

    fun settings(context: Context): Settings = try {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        Settings(p.getBoolean("enabled", false), p.getBoolean("wifi_only", false))
    } catch (_: Exception) {
        Settings()
    }

    /** Saves the choice and starts or stops the daily check to match. */
    fun apply(context: Context, s: Settings) {
        try {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putBoolean("enabled", s.enabled).putBoolean("wifi_only", s.wifiOnly).apply()
        } catch (_: Exception) {
            // Only a preference
        }
        val jobs = context.getSystemService(JobScheduler::class.java) ?: return
        if (!s.enabled) {
            jobs.cancel(JOB_ID)
            return
        }
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, UpdateJob::class.java))
            .setPeriodic(TimeUnit.DAYS.toMillis(1), TimeUnit.HOURS.toMillis(6))
            .setRequiredNetworkType(if (s.wifiOnly) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY)
            // Kept after the phone restarts
            .setPersisted(true)
            .build()
        runCatching { jobs.schedule(job) }
    }

    /** On every start of Komora: if turned on, make sure the daily check is there (e.g. after an update). */
    fun ensure(context: Context) {
        val s = settings(context)
        if (s.enabled && context.getSystemService(JobScheduler::class.java)?.getPendingJob(JOB_ID) == null) apply(context, s)
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** The check itself, run by [UpdateJob] off the main thread. False when it should be tried again later. */
    fun run(context: Context): Boolean {
        val catalog = Catalog.parse(context.assets.open("catalog.json").bufferedReader().use { it.readText() })
        val lang = context.getString(R.string.catalog_lang)
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val found = mutableListOf<Pair<String, String>>()
        val names = mutableListOf<String>()
        var complete = true
        for (app in catalog) {
            val installed = ApkVerifier.installedInfo(context.packageManager, app.packageName)?.versionName ?: continue
            val release = try {
                GitHub.latestRelease(app.repo, app.assetPrefix)
            } catch (_: RateLimitException) {
                complete = false
                break
            } catch (_: Exception) {
                complete = false
                continue
            }
            val latest = Rules.normalizeVersion(release.tag)
            if ((Rules.compareVersions(latest, installed) ?: 0) <= 0) continue
            // Each new version is announced once
            val key = "told_" + app.packageName
            if (prefs.getString(key, null) == latest) continue
            found += key to latest
            names += "${app.name(lang)} $latest"
        }
        // Remembered as told only once the notice is really shown
        if (found.isNotEmpty() && canNotify(context) && notify(context, names)) {
            val edit = prefs.edit()
            for ((key, latest) in found) edit.putString(key, latest)
            edit.apply()
        }
        return complete
    }

    private fun notify(context: Context, apps: List<String>): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.notification_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
        // Opens Komora; an explicit, unchangeable intent
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notice = android.app.Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_title))
            .setContentText(apps.joinToString(", "))
            .setContentIntent(open)
            .setAutoCancel(true)
            // App names and versions only, nothing private; still hidden in full on a locked screen
            .setVisibility(android.app.Notification.VISIBILITY_PRIVATE)
            .build()
        return runCatching { nm.notify(NOTICE_ID, notice) }.isSuccess
    }
}
