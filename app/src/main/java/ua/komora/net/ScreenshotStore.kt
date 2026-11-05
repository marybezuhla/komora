package ua.komora.net

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * An app's screenshots kept on the phone (in Android's cache folder, which it may empty when space runs
 * low), with the release they were fetched for. GitHub is asked again only when a new release is out,
 * and then only changed pictures are downloaded. Plain files, no Android code: tested on a computer.
 */
class ScreenshotStore(private val root: File, private val maxBytes: Long = Rules.MAX_KEPT_SCREENSHOT_BYTES) {
    private class Kept(val tag: String, val files: List<Pair<String, String>>) // name, sha

    /** The pictures kept for this release ([tag]), or for any release when [tag] is null; null when none. */
    @Synchronized
    fun get(repo: String, lang: String, tag: String?): List<ByteArray>? {
        val dir = dirFor(repo, lang)
        val kept = read(dir) ?: return null
        if (tag != null && kept.tag != tag) return null
        val bytes = kept.files.map { (_, sha) -> readPicture(dir, sha) ?: return null }
        // Looked at now: the last to be cleared when space is needed
        dir.setLastModified(System.currentTimeMillis())
        return bytes
    }

    /**
     * The pictures for release [tag] as [listed] now: those already here (same name, same content) are
     * kept, the rest fetched with [fetch], and pictures no longer listed are removed. When one could not
     * be fetched, the ones that could are kept, but not marked as done, so the next look tries again.
     */
    @Synchronized
    fun update(repo: String, lang: String, tag: String, listed: List<GitHub.Shot>, fetch: (GitHub.Shot) -> ByteArray?): List<ByteArray> {
        val dir = dirFor(repo, lang).apply { mkdirs() }
        val before = read(dir)?.files.orEmpty().toMap()
        val pictures = mutableListOf<ByteArray>()
        val saved = mutableListOf<Pair<String, String>>()
        for (shot in listed) {
            val have = if (before[shot.name] == shot.sha) readPicture(dir, shot.sha) else null
            val bytes = have ?: fetch(shot)?.also { savePicture(dir, shot.sha, it) } ?: continue
            pictures += bytes
            saved += shot.name to shot.sha
        }
        val complete = saved.size == listed.size
        write(dir, Kept(if (complete) tag else "", saved))
        // Pictures no longer listed (or left from a failed save) go
        val keep = saved.map { it.second }.toSet()
        dir.listFiles()?.filter { it.name != INDEX && it.name !in keep }?.forEach { it.delete() }
        trim(except = dir)
        return pictures
    }

    private fun readPicture(dir: File, sha: String): ByteArray? {
        val f = File(dir, sha)
        if (!f.isFile || f.length() > Rules.MAX_IMAGE_BYTES) return null
        // A damaged copy is fetched again rather than shown
        return runCatching { f.readBytes() }.getOrNull()?.takeIf { Rules.gitBlobSha(it) == sha }
    }

    private fun savePicture(dir: File, sha: String, bytes: ByteArray) {
        // Not saved only means it is fetched again next time
        runCatching { atomically(File(dir, sha)) { it.writeBytes(bytes) } }
    }

    private fun read(dir: File): Kept? {
        val f = File(dir, INDEX)
        if (!f.isFile || f.length() > MAX_INDEX_BYTES) return null
        return try {
            val o = JSONObject(f.readText(Charsets.UTF_8))
            val a = o.getJSONArray("files")
            val files = (0 until a.length()).map { i ->
                val e = a.getJSONObject(i)
                val sha = e.getString("sha")
                // Only Git's own names are ever used as file names here
                if (!Rules.GIT_SHA_HEX.matches(sha)) throw JSONException("Bad name")
                e.getString("name") to sha
            }
            Kept(o.getString("tag"), files)
        } catch (_: JSONException) {
            null
        } catch (_: IOException) {
            null
        }
    }

    private fun write(dir: File, kept: Kept) {
        val files = JSONArray()
        kept.files.forEach { (name, sha) -> files.put(JSONObject().put("name", name).put("sha", sha)) }
        val text = JSONObject().put("tag", kept.tag).put("files", files).toString()
        runCatching { atomically(File(dir, INDEX)) { it.writeText(text, Charsets.UTF_8) } }
    }

    private fun atomically(target: File, write: (File) -> Unit) {
        val tmp = File.createTempFile("shot", ".tmp", target.parentFile)
        try {
            write(tmp)
            if (!tmp.renameTo(target)) throw IOException("Not saved")
        } finally {
            tmp.delete()
        }
    }

    /** All apps' pictures together stay under [maxBytes]: those looked at longest ago go first. */
    private fun trim(except: File) {
        val dirs = root.listFiles { f -> f.isDirectory }?.toList() ?: return
        fun size(d: File) = d.listFiles()?.sumOf { it.length() } ?: 0L
        var total = dirs.sumOf { size(it) }
        for (d in dirs.filter { it != except }.sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            total -= size(d)
            d.deleteRecursively()
        }
    }

    // Names on GitHub ignore case; the folder name is a hash, so no repository name can reach outside the root
    private fun dirFor(repo: String, lang: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest("${repo.lowercase()}|$lang".toByteArray(Charsets.UTF_8))
        return File(root, Rules.toHex(hash).take(32))
    }

    private companion object {
        const val INDEX = "index.json"
        const val MAX_INDEX_BYTES = 64L * 1024
    }
}
