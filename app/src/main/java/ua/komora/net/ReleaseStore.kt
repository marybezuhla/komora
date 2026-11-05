package ua.komora.net

import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * GitHub's last answer about each repository's newest release, kept on the phone (in a folder Android
 * never backs up), so the limited API is asked again only when a new release is out. Plain files, no
 * Android code: tested on a computer.
 */
class ReleaseStore(private val dir: File) {
    class Entry(val tag: String, val checkedAt: Long, val body: ByteArray)

    @Synchronized
    fun get(repo: String): Entry? {
        val f = fileFor(repo)
        if (!f.isFile) return null
        return try {
            if (f.length() > MAX_FILE_BYTES) throw IOException("Too large")
            val o = JSONObject(f.readText(Charsets.UTF_8))
            val body = o.getString("body").toByteArray(Charsets.UTF_8)
            if (body.size > Rules.MAX_JSON_BYTES) throw IOException("Too large")
            Entry(o.getString("tag"), o.getLong("checkedAt"), body)
        } catch (_: IOException) {
            f.delete(); null
        } catch (_: JSONException) {
            // Half written or damaged: asked anew
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

    /** GitHub said nothing changed: the kept answer counts as checked now. */
    @Synchronized
    fun touch(repo: String, entry: Entry, now: Long) = write(repo, Entry(entry.tag, now, entry.body))

    /** The kept answer turned out wrong (its file did not download as described): asked anew next time. */
    @Synchronized
    fun forget(repo: String) {
        fileFor(repo).delete()
    }

    private fun write(repo: String, e: Entry) {
        // Losing what is kept only means asking GitHub again, so a failure here is not shown to anyone
        runCatching {
            dir.mkdirs()
            val text = JSONObject().put("tag", e.tag).put("checkedAt", e.checkedAt)
                .put("body", String(e.body, Charsets.UTF_8)).toString()
            val tmp = File.createTempFile("release", ".tmp", dir)
            try {
                tmp.writeText(text, Charsets.UTF_8)
                // Whole or not at all: a check in the background and one on screen never see half a file
                if (!tmp.renameTo(fileFor(repo))) throw IOException("Not saved")
            } finally {
                tmp.delete()
            }
        }
    }

    /** Sources removed long ago leave their answers behind: only the newest ones are kept. */
    private fun trim() {
        val files = dir.listFiles { f -> f.name.endsWith(".json") } ?: return
        if (files.size <= MAX_ENTRIES) return
        files.sortedBy { it.lastModified() }.take(files.size - MAX_ENTRIES).forEach { it.delete() }
    }

    // Names on GitHub ignore case; the file name is a hash, so no repository name can reach outside the folder
    private fun fileFor(repo: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(repo.lowercase().toByteArray(Charsets.UTF_8))
        return File(dir, Rules.toHex(hash).take(32) + ".json")
    }

    private companion object {
        const val MAX_ENTRIES = 100
        const val MAX_FILE_BYTES = 3L * 1024 * 1024 * 2
    }
}
