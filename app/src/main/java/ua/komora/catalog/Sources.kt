package ua.komora.catalog

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import ua.komora.net.Rules
import ua.komora.net.WholeFile
import java.io.File

/**
 * A source the owner added: a GitHub repository and which APK of its releases to take. Its package and
 * signing key are unknown until the first install, when the owner sees them and decides to trust them;
 * from then on they are pinned, exactly like the apps in Komora's own list.
 */
data class Source(
    val repo: String,
    val apkPattern: String,
    val packageName: String = "",
    val certSha256: String = "",
    /** The app's name as the APK states it, once known. */
    val name: String = "",
    val addedAt: Long = 0,
) {
    val trusted: Boolean get() = packageName.isNotEmpty() && certSha256.isNotEmpty()

    fun toApp(): CatalogApp {
        val shown = name.ifBlank { repo.substringAfter('/') }
        return CatalogApp(
            packageName = packageName,
            repo = repo,
            assetPrefix = "",
            certSha256 = certSha256,
            badge = badgeFor(repo),
            names = mapOf("en" to shown),
            summaries = mapOf("en" to repo),
            added = true,
            apkPattern = apkPattern,
        )
    }

    private fun badgeFor(repo: String): Long {
        // A calm colour of its own, the same every time
        val colours = longArrayOf(0xFF4A4744, 0xFF3D5A80, 0xFF3E7B57, 0xFF2F6B6B, 0xFF8E3B5E, 0xFF6B4A2B)
        return colours[Math.floorMod(repo.lowercase().hashCode(), colours.size)]
    }
}

/** The added sources, kept only on this phone (no backup), with the switch that shows them. */
object Sources {
    const val MAX = Rules.MAX_SOURCES
    private const val FILE = "sources.json"
    private const val PREFS = "sources"


    /** "owner/name", "github.com/owner/name" or a full link to it (any page in the repository). */
    fun parseRepo(input: String): String? {
        val t = input.trim().removeSuffix("/").removeSuffix(".git")
        val path = when {
            t.startsWith("https://github.com/") -> t.removePrefix("https://github.com/")
            t.startsWith("http://github.com/") -> t.removePrefix("http://github.com/")
            t.startsWith("github.com/") -> t.removePrefix("github.com/")
            t.contains("://") -> return null
            else -> t
        }
        val parts = path.split('/')
        if (parts.size < 2) return null
        val repo = parts[0] + "/" + parts[1]
        return repo.takeIf { Rules.REPO.matches(it) && !parts[1].startsWith(".") }
    }

    fun enabled(context: Context): Boolean = try {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false)
    } catch (_: Exception) {
        // Unreadable preferences: off, as by default
        false
    }

    fun setEnabled(context: Context, on: Boolean) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", on).apply()
        } catch (_: Exception) {
            // Only a preference
        }
    }

    /**
     * Screenshots and icons of added apps: off unless the owner turns it on after a warning, as their
     * pictures come from repositories Komora never checked.
     */
    fun pictures(context: Context): Boolean = try {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("pictures", false)
    } catch (_: Exception) {
        // Unreadable preferences: off, as by default
        false
    }

    fun setPictures(context: Context, on: Boolean) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("pictures", on).apply()
        } catch (_: Exception) {
            // Only a preference
        }
    }

    /** The list, strictly checked: anything that does not look right is left out, never half-read. */
    fun load(context: Context): List<Source> = try {
        // What cut-off writes left beside the list (and the fixed name Komora 1.0.0 used for it)
        WholeFile.dropLeftovers(context.noBackupFilesDir)
        File(context.noBackupFilesDir, "$FILE.tmp").delete()
        parse(File(context.noBackupFilesDir, FILE).takeIf { it.exists() }?.readText() ?: "[]")
    } catch (_: Exception) {
        // An unreadable list counts as empty; the file stays, so nothing is lost by reading it
        emptyList()
    }

    fun parse(json: String): List<Source> {
        val array = JSONArray(json)
        return (0 until minOf(array.length(), MAX)).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val s = Source(
                repo = o.optString("repo"),
                apkPattern = o.optString("apkPattern"),
                packageName = o.optString("packageName"),
                certSha256 = o.optString("certSha256"),
                name = o.optString("name").take(60),
                addedAt = o.optLong("addedAt"),
            )
            s.takeIf { valid(it) }
        }.distinctBy { it.repo.lowercase() }
    }

    fun valid(s: Source): Boolean =
        Rules.REPO.matches(s.repo) && !s.repo.substringAfter('/').startsWith(".") &&
            s.apkPattern.length in 5..300 && s.apkPattern.endsWith("\\.apk") && runCatching { Regex(s.apkPattern) }.isSuccess &&
            (s.packageName.isEmpty() || Rules.PACKAGE.matches(s.packageName)) &&
            (s.certSha256.isEmpty() || Rules.SHA256_HEX.matches(s.certSha256)) &&
            // Either both known (trusted) or neither (not installed yet)
            (s.packageName.isEmpty() == s.certSha256.isEmpty())

    /** Saves the list; false if it could not be written (the old list then stays as it was). */
    fun save(context: Context, list: List<Source>): Boolean = try {
        write(context, list)
        true
    } catch (_: Exception) {
        // Not saved: the caller keeps the old list and says so
        false
    }

    private fun write(context: Context, list: List<Source>) {
        val array = JSONArray()
        for (s in list.filter { valid(it) }.take(MAX)) {
            array.put(
                JSONObject()
                    .put("repo", s.repo).put("apkPattern", s.apkPattern)
                    .put("packageName", s.packageName).put("certSha256", s.certSha256)
                    .put("name", s.name).put("addedAt", s.addedAt),
            )
        }
        // Written whole to a new file, on the disk, then swapped in: never a half-written or empty list
        WholeFile.write(File(context.noBackupFilesDir, FILE), array.toString().toByteArray(Charsets.UTF_8))
    }
}
