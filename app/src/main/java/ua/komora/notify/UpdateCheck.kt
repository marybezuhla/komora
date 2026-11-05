package ua.komora.notify

import android.Manifest
import android.app.Notification
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
import ua.komora.Stores
import ua.komora.catalog.Catalog
import ua.komora.catalog.Sources
import ua.komora.install.ApkVerifier
import ua.komora.net.GitHub
import ua.komora.net.NoReleaseException
import ua.komora.net.RateLimitException
import ua.komora.net.Rules
import ua.komora.ui.AppLanguage
import ua.komora.ui.MainActivity
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Update notices, off unless the owner turns them on: once a day, in the background, Komora asks
 * GitHub about every app in its own list (the same questions as when it is opened, so they say nothing
 * about what is installed) and about added sources whose app is on this phone and says when an app on this phone has a newer version. It only tells;
 * installing still happens in Komora, with every check.
 */
object UpdateCheck {
    private const val FILE = "updates"
    private const val JOB_ID = 1
    private const val CHANNEL = "updates"
    private const val NOTICE_ID = 1

    /** Off by default; once a day, or once a week for those who want it quieter. */
    data class Settings(val enabled: Boolean = false, val wifiOnly: Boolean = false, val weekly: Boolean = false)

    fun settings(context: Context): Settings = try {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        Settings(p.getBoolean("enabled", false), p.getBoolean("wifi_only", false), p.getBoolean("weekly", false))
    } catch (_: Exception) {
        // Unreadable preferences: everything off, as by default
        Settings()
    }

    /**
     * Starts or stops the daily check to match [s], then saves it. Returns false when Android refused
     * to schedule the check; the notices then stay off, rather than looking on while doing nothing.
     */
    fun apply(context: Context, s: Settings): Boolean {
        val jobs = context.getSystemService(JobScheduler::class.java) ?: return false
        val saved = if (!s.enabled) {
            jobs.cancel(JOB_ID)
            s
        } else {
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, UpdateJob::class.java))
                // Android picks the moment within the last part of each period, when it suits the phone
                .setPeriodic(
                    TimeUnit.DAYS.toMillis(if (s.weekly) 7 else 1),
                    if (s.weekly) TimeUnit.DAYS.toMillis(1) else TimeUnit.HOURS.toMillis(6),
                )
                .setRequiredNetworkType(if (s.wifiOnly) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY)
                // After a lost connection, try again in an hour, then less often
                .setBackoffCriteria(TimeUnit.HOURS.toMillis(1), JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                // Kept after the phone restarts
                .setPersisted(true)
                .build()
            val ok = try { jobs.schedule(job) == JobScheduler.RESULT_SUCCESS } catch (_: Exception) { false }
            if (ok) s else s.copy(enabled = false)
        }
        try {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putBoolean("enabled", saved.enabled).putBoolean("wifi_only", saved.wifiOnly)
                .putBoolean("weekly", saved.weekly).apply()
        } catch (_: Exception) {
            // Only a preference
        }
        return saved == s
    }

    /** On every start of Komora: if turned on, make sure the daily check is there (e.g. after an update). */
    fun ensure(context: Context) {
        val s = settings(context)
        if (s.enabled && context.getSystemService(JobScheduler::class.java)?.getPendingJob(JOB_ID) == null) apply(context, s)
    }

    /** Whether a notice from Komora would actually be seen: allowed by Android and not silenced. */
    fun canNotify(context: Context, channelId: String = CHANNEL): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        if (!nm.areNotificationsEnabled()) return false
        val channel = nm.getNotificationChannel(channelId)
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    /**
     * The check itself, run by [UpdateJob] off the main thread. Returns false only when the
     * connection failed, so Android tries again later; anything else waits for the next day.
     */
    fun run(base: Context, stopped: () -> Boolean): Boolean {
        // In the language chosen in Komora (before Android 13 the job would follow the phone)
        val context = AppLanguage.wrap(base)
        // Notices turned off in Android: no point asking GitHub at all
        if (!canNotify(context)) return true
        val builtIn = Catalog.parse(context.assets.open("catalog.json").bufferedReader().use { it.readText() })
        // Added sources only when shown, and only those trusted and on this phone: a repository the owner
        // chose is personal, so it is not asked about in the background unless the app is actually installed
        val added = if (!Sources.enabled(context)) emptyList() else Sources.load(context)
            .filter { it.trusted && builtIn.none { b -> b.packageName == it.packageName } && it.packageName != context.packageName }
            .filter { ApkVerifier.installedInfo(context.packageManager, it.packageName) != null }
            .map { it.toApp() }
        val catalog = builtIn + added
        val lang = context.getString(R.string.catalog_lang)
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val updates = mutableListOf<Triple<String, String, String>>() // key, version, "Name 1.2.3"
        for (app in catalog) {
            if (stopped()) return true
            val release = try {
                GitHub.latestRelease(app, Stores.releases(context))
            } catch (_: RateLimitException) {
                // Only part of the answers: no notice from that; tomorrow again
                return true
            } catch (_: NoReleaseException) {
                continue
            } catch (e: IOException) {
                // One added repository that answers oddly does not hold up the rest
                if (app.added) continue
                return false
            } catch (_: Exception) {
                // Anything else about one app (a bad release, say) does not stop the others
                continue
            }
            // Asked about every app; only those on this phone are reported
            val info = ApkVerifier.installedInfo(context.packageManager, app.packageName) ?: continue
            val installed = info.versionName ?: continue
            // Signed with another key: Komora could not update it, so it is not offered
            if (!ApkVerifier.installedMatchesPin(context, app, info)) continue
            val latest = Rules.normalizeVersion(release.tag)
            if ((Rules.compareVersions(latest, installed) ?: 0) > 0) {
                updates += Triple("told_" + app.packageName, latest, "${app.name(lang)} $latest")
            }
        }
        // Everything up to date: an old notice is taken away
        if (updates.isEmpty()) {
            dismiss(context)
            return true
        }
        // A notice only when something is new since the last one; it then lists every update waiting
        val anyNew = updates.any { (key, version, _) -> prefs.getString(key, null) != version }
        if (anyNew && !stopped() && notify(context, updates.map { it.third })) {
            val edit = prefs.edit()
            for ((key, version, _) in updates) edit.putString(key, version)
            edit.apply()
        }
        return true
    }

    /** Takes the "Updates available" notice away (nothing left to update, or Komora was opened). */
    fun dismiss(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(NOTICE_ID) }
    }

    /** Forgets what was last told about [packageName] (when its source is removed). */
    fun forget(context: Context, packageName: String) {
        try {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().remove("told_$packageName").apply()
        } catch (_: Exception) {
            // Only a note
        }
    }

    /** The channel, named in the current language; refreshed whenever Komora opens. */
    fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.notification_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    private fun notify(context: Context, apps: List<String>): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        createChannel(context)
        // Opens Komora; an explicit, unchangeable intent
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = context.getString(R.string.notification_title)
        val text = apps.joinToString(", ")
        // On a locked screen: only that there are updates, not which apps
        val public = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .build()
        val notice = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(apps.joinToString("\n")))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .build()
        return runCatching { nm.notify(NOTICE_ID, notice) }.isSuccess
    }
}
