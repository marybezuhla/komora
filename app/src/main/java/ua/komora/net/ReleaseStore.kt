package ua.komora.net

import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * GitHub's last answer about each repository's newest release, kept on the phone (in a folder Android
 * never backs up), so the limited API is asked again only when a new release is out. Plain files, no
 * Android code: tested on a computer.
 */
class ReleaseStore(private val dir: File) {
    /** [tag] as [Rules.tagKey] makes it; [body] is GitHub's answer as it came. */
    class Entry(val tag: String, val checkedAt: Long, val body: ByteArray)

    @Synchronized
    fun get(repo: String): Entry? {
        val f = fileFor(repo)
        if (!f.isFile) return null
        return try {
            if (f.length() > Rules.MAX_KEPT_RELEASE_FILE_BYTES) throw IOException("Too large")
            val o = JSONObject(f.readText(Charsets.UTF_8))
            val body = o.getString("body").toByteArray(Charsets.UTF_8)
            if (body.size > Rules.MAX_JSON_BYTES) throw IOException("Too large")
            Entry(o.getString("tag"), o.getLong("checkedAt"), body)
        } catch (_: IOException) {
            // Unreadable or too large: dropped, so GitHub is simply asked anew
            f.delete(); null
        } catch (_: JSONException) {
            f.delete(); null
        } catch (_: StackOverflowError) {
            f.delete(); null
        }
    }

    @Synchronized
    fun put(repo: String, tag: String, body: ByteArray, now: Long) {
        write(repo, Entry(tag, now, body))
        trim()
    }

    /**
     * The release page still names [tag]: the kept answer counts as checked [now]. Only if it is still
     * about that tag, so a check that saw an older answer never puts it back over a newer one.
     */
    @Synchronized
    fun touch(repo: String, tag: String, now: Long) {
        val current = get(repo) ?: return
        if (current.tag == tag) write(repo, Entry(tag, now, current.body))
    }

    /** The kept answer is wrong or no longer wanted (a failed download, a removed source): asked anew next time. */
    @Synchronized
    fun forget(repo: String) {
        fileFor(repo).delete()
    }

    /** Removes every kept answer that is not about one of [repos]. */
    @Synchronized
    fun keepOnly(repos: Set<String>) {
        val wanted = repos.map { fileFor(it).name }.toSet()
        dir.listFiles { f -> f.name.endsWith(".json") && f.name !in wanted }?.forEach { it.delete() }
    }

    private fun write(repo: String, e: Entry) {
        val text = JSONObject().put("tag", e.tag).put("checkedAt", e.checkedAt)
            .put("body", String(e.body, Charsets.UTF_8)).toString()
        try {
            dir.mkdirs()
            WholeFile.write(fileFor(repo), text.toByteArray(Charsets.UTF_8))
        } catch (_: IOException) {
            // Not kept only means asking GitHub again next time, so nobody needs to be told
        }
    }

    /** Sources removed long ago leave their answers behind: only the newest ones are kept. */
    private fun trim() {
        WholeFile.dropLeftovers(dir)
        val files = dir.listFiles { f -> f.name.endsWith(".json") } ?: return
        if (files.size <= Rules.MAX_KEPT_RELEASES) return
        files.sortedBy { it.lastModified() }.take(files.size - Rules.MAX_KEPT_RELEASES).forEach { it.delete() }
    }

    // Names on GitHub ignore case, and so does the hash
    private fun fileFor(repo: String) = File(dir, Rules.storeName(repo) + ".json")
}
