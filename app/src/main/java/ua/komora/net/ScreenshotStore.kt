package ua.komora.net

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * An app's screenshots kept on the phone (in Android's cache folder, which it may empty when space runs
 * low), with the release they were fetched for. GitHub is asked again only when a new release is out,
 * and then only changed pictures are downloaded. Plain files, no Android code: tested on a computer.
 */
class ScreenshotStore(
    private val root: File,
    // Smaller only in tests, so filling it does not take 50 MB
    private val maxBytes: Long = Rules.MAX_KEPT_SCREENSHOT_BYTES,
) {
    private class Kept(val tag: String, val files: List<Pair<String, String>>) // name, sha

    /** [complete]: every listed picture is here, so this release need not be asked about again. */
    class Result(val pictures: List<ByteArray>, val complete: Boolean)

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
     * The pictures for release [tag] as [listed] now: those already here (same content) are kept, the rest
     * fetched with [fetch] (outside the lock, so other apps' pictures can be read meanwhile), and pictures
     * no longer listed are removed. A picture that could not be fetched keeps its older copy, if there is
     * one, and the release is not marked as done, so the next look tries again.
     */
    fun update(repo: String, lang: String, tag: String, listed: List<Rules.Shot>, fetch: (Rules.Shot) -> ByteArray?): Result {
        val dir = dirFor(repo, lang)
        val (before, here) = synchronized(this) {
            val kept = read(dir)?.files.orEmpty().toMap()
            kept to listed.mapNotNull { s -> readPicture(dir, s.sha)?.let { s.sha to it } }.toMap()
        }
        val fetched = listed.filter { it.sha !in here }.mapNotNull { s -> fetch(s)?.let { s.sha to it } }.toMap()
        synchronized(this) {
            dir.mkdirs()
            val saved = fetched.filter { (sha, bytes) -> savePicture(dir, sha, bytes) }.keys
            val pictures = mutableListOf<ByteArray>()
            val files = mutableListOf<Pair<String, String>>()
            var complete = true
            for (s in listed) {
                val new = here[s.sha] ?: fetched[s.sha]
                if (new != null) {
                    pictures += new
                    // Still on disk (another look may have cleared this folder in between), or saved again now
                    val stored = s.sha in saved || File(dir, s.sha).isFile || savePicture(dir, s.sha, new)
                    if (stored) files += s.name to s.sha else complete = false
                    continue
                }
                complete = false
                // Not fetched now: the older copy of this picture, if it is still here
                val old = before[s.name]?.let { sha -> readPicture(dir, sha)?.let { sha to it } } ?: continue
                pictures += old.second
                files += s.name to old.first
            }
            write(dir, Kept(if (complete) tag else INCOMPLETE, files))
            val keep = files.map { it.second }.toSet() + INDEX
            dir.listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
            trim(except = dir)
            return Result(pictures, complete)
        }
    }

    private fun readPicture(dir: File, sha: String): ByteArray? {
        val f = File(dir, sha)
        if (!f.isFile || f.length() > Rules.MAX_IMAGE_BYTES) return null
        // A damaged copy is fetched again rather than shown
        val bytes = try { f.readBytes() } catch (_: IOException) { return null }
        return bytes.takeIf { Rules.gitBlobSha(it) == sha }
    }

    private fun savePicture(dir: File, sha: String, bytes: ByteArray): Boolean = try {
        WholeFile.write(File(dir, sha), bytes)
        true
    } catch (_: IOException) {
        // Not saved: shown this time, fetched again next time
        false
    }

    private fun read(dir: File): Kept? {
        val f = File(dir, INDEX)
        if (!f.isFile || f.length() > Rules.MAX_SCREENSHOT_INDEX_BYTES) return null
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
            // Damaged: as if nothing were kept, so the pictures are fetched again
            null
        } catch (_: IOException) {
            null
        } catch (_: StackOverflowError) {
            null
        }
    }

    private fun write(dir: File, kept: Kept) {
        val files = JSONArray()
        kept.files.forEach { (name, sha) -> files.put(JSONObject().put("name", name).put("sha", sha)) }
        val text = JSONObject().put("tag", kept.tag).put("files", files).toString()
        try {
            WholeFile.write(File(dir, INDEX), text.toByteArray(Charsets.UTF_8))
        } catch (_: IOException) {
            // No list: the pictures are fetched again next time, nothing worse
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

    private fun dirFor(repo: String, lang: String) = File(root, Rules.storeName("$repo|$lang"))

    private companion object {
        const val INDEX = "index.json"

        /** Marks a set not yet complete; no tag can be this, as tags never hold control characters. */
        const val INCOMPLETE = "\u0000"
    }
}
