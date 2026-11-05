package ua.komora.net

import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import javax.net.ssl.HttpsURLConnection
import ua.komora.catalog.CatalogApp

/**
 * A published release: its tag, its one APK, its description made plain ("What's new"), and when it was
 * published (milliseconds; null if GitHub did not say), so automatic updates can wait before taking it.
 * [repo] is the repository as GitHub spells it now (after any rename).
 */
data class Release(val tag: String, val apk: Rules.Asset, val notes: String = "", val published: Long? = null, val repo: String? = null)

/** GitHub allows 60 requests an hour without an account. */
class RateLimitException : IOException("GitHub rate limit")

/** The repository has no published release yet. */
class NoReleaseException : IOException("No release yet")

/** The file could not be fully downloaded (connection dropped, or it did not match the release). */
class DownloadException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Talks only to GitHub, only over HTTPS, and follows redirects by hand so it can refuse any other host. */
object GitHub {
    private const val TIMEOUT_MS = 20_000
    private const val MAX_REDIRECTS = 5

    /**
     * The newest release of an app: for Komora's own list by the APK's name, for added sources by its pattern.
     * GitHub's API (60 requests an hour without an account) is asked only when [store] has nothing usable
     * or a new tag is out; a recent check ([Rules.trustKept]) is used as it is, unless [force].
     */
    fun latestRelease(app: CatalogApp, store: ReleaseStore, force: Boolean = false): Release {
        val now = System.currentTimeMillis()
        val kept = store.get(app.repo)
        if (kept != null && Rules.trustKept(kept.checkedAt, now, force)) {
            fromKept(app, kept, store)?.let { return it }
        }
        // Which tag is newest, from the page github.com sends "latest" on to: free of the API's limit
        if (kept != null && Rules.sameRelease(kept.tag, latestTag(app.repo))) {
            fromKept(app, kept, store)?.let {
                store.touch(app.repo, kept.tag, now)
                return it
            }
        }
        return fromApi(app, store, now)
    }

    /**
     * The newest release straight from GitHub's API, whatever is kept: right before an automatic install,
     * so a release pulled or replaced under the same tag is never installed from an older answer.
     */
    fun confirmedRelease(app: CatalogApp, store: ReleaseStore): Release = fromApi(app, store, System.currentTimeMillis())

    private fun fromApi(app: CatalogApp, store: ReleaseStore, now: Long): Release {
        val body = fetchLatest(app.repo)
        val latest = parse(body)
        val release = try {
            releaseOf(app, latest)
        } catch (e: BadReleaseException) {
            // Kept only once it holds the right APK: a release whose files are still uploading is asked about again
            store.forget(app.repo)
            throw e
        }
        store.put(app.repo, latest.tagKey, body, now)
        return release
    }

    /** The kept answer as a release, or null (and forgotten) when it no longer gives one. */
    private fun fromKept(app: CatalogApp, kept: ReleaseStore.Entry, store: ReleaseStore): Release? = try {
        releaseOf(app, parse(kept.body))
    } catch (_: BadReleaseException) {
        store.forget(app.repo)
        null
    }

    private fun releaseOf(app: CatalogApp, r: Latest): Release {
        // The account or repository may have been renamed since: GitHub sends the old name on, and the files
        // carry the new one. Trusting it is safe because every APK must still be signed with the pinned key
        val repo = r.repo ?: app.repo
        val apk = app.apkPattern?.let { Rules.pickByPattern(r.assets, it, repo) }
            ?: Rules.pickApk(r.assets, app.assetPrefix, repo)
        return Release(tag = r.tag, apk = apk, notes = r.notes, published = r.published, repo = repo)
    }

    /**
     * Every APK in a repository's newest release, to choose one when adding it as a source, with the
     * repository's name as GitHub spells it.
     */
    fun latestApks(repo: String): Pair<String, List<Rules.Asset>> {
        val r = parse(fetchLatest(repo))
        val name = r.repo ?: repo
        return name to r.assets.filter { it.name.endsWith(".apk") && Rules.inRepo(it.url, name) }
    }

    private class Latest(
        val tag: String, val tagKey: String, val assets: List<Rules.Asset>, val notes: String, val repo: String?, val published: Long?,
    )

    private val RELEASE_PAGE = Regex("^https://github\\.com/(${Rules.OWNER}/${Rules.REPO_NAME})/releases/")

    private fun fetchLatest(repo: String): ByteArray =
        open("https://api.github.com/repos/$repo/releases/latest", api = true).use { readLimited(it.inputStream, Rules.MAX_JSON_BYTES) }

    /**
     * The newest tag as github.com's "releases/latest" page names it, following its redirects one by one
     * (a renamed repository first sends on to its new name). Null when GitHub answers in any other way:
     * then the API is asked, as before. Only a lost connection is an error.
     */
    private fun latestTag(repo: String): String? {
        var url = "https://github.com/$repo/releases/latest"
        repeat(MAX_REDIRECTS + 1) {
            val uri = Rules.allowedUri(url, Rules.RELEASE_PAGE_HOSTS) ?: return null
            val conn = connect(uri, api = false)
            try {
                url = nextUrl(conn, url) ?: return null
                Rules.tagFromRedirect(url)?.let { return it }
            } finally {
                conn.disconnect()
            }
        }
        return null
    }

    /** Where a redirect sends to, whole; null when the answer is not a redirect or names no valid address. */
    private fun nextUrl(conn: HttpsURLConnection, from: String): String? {
        if (conn.responseCode !in REDIRECTS) return null
        val next = conn.getHeaderField("Location")?.takeIf { it.isNotEmpty() } ?: return null
        return try { URI(from).resolve(next).toString() } catch (_: IllegalArgumentException) { null }
    }

    // An answer that cannot be read as a release is a bad release, not a lost connection
    private fun parse(body: ByteArray): Latest = try {
        val o = JSONObject(String(body, Charsets.UTF_8))
        val assetsJson = o.getJSONArray("assets")
        // The repository as GitHub spells it now (from GitHub's own answer, over the checked connection)
        val canonical = RELEASE_PAGE.find(o.optString("html_url"))?.groupValues?.get(1)?.takeUnless { it.substringAfter('/').startsWith(".") }
        val tag = o.getString("tag_name")
        Latest(Rules.plainTag(tag), Rules.tagKey(tag), (0 until assetsJson.length()).map { i ->
            val a = assetsJson.getJSONObject(i)
            Rules.Asset(
                name = a.getString("name"),
                url = a.getString("browser_download_url"),
                size = a.getLong("size"),
                sha256 = Rules.parseDigest(a.optString("digest", "").ifEmpty { null }),
            )
        }, ReleaseNotes.clean(o.optString("body", "")), canonical,
            runCatching { java.time.Instant.parse(o.getString("published_at")).toEpochMilli() }.getOrNull())
    } catch (_: JSONException) {
        throw BadReleaseException("Unreadable release")
    } catch (_: StackOverflowError) {
        // Absurdly nested JSON: never a crash
        throw BadReleaseException("Unreadable release")
    }

    /**
     * The screenshots an app keeps in its repository for F-Droid, in the language shown if it has them:
     * two requests to GitHub's API (which languages, then which files). Empty when there are none; null
     * when GitHub could not be asked (to try again later). [isCancelled] stops it between requests.
     */
    fun screenshotList(repo: String, lang: String, isCancelled: () -> Boolean): List<Rules.Shot>? {
        fun list(path: String): org.json.JSONArray = try {
            val body = open("https://api.github.com/repos/$repo/contents/$path", api = true, hosts = Rules.SCREENSHOT_HOSTS)
                .use { readLimited(it.inputStream, Rules.MAX_JSON_BYTES) }
            org.json.JSONArray(String(body, Charsets.UTF_8))
        } catch (_: NoReleaseException) {
            // 404: not there
            org.json.JSONArray()
        } catch (_: JSONException) {
            org.json.JSONArray()
        } catch (_: StackOverflowError) {
            org.json.JSONArray()
        }
        return try {
            val base = "fastlane/metadata/android"
            val langs = list(base)
            val have = (0 until langs.length()).mapNotNull { langs.optJSONObject(it)?.takeIf { o -> o.optString("type") == "dir" }?.optString("name") }
            // The language shown first; the next one only if that one has no pictures
            var files = emptyList<Rules.Shot>()
            for (dir in Rules.screenshotDirs(lang).filter { d -> d.removePrefix("$base/").substringBefore('/') in have }) {
                if (isCancelled()) return null
                val a = list(dir)
                files = (0 until a.length()).mapNotNull { i ->
                    val o = a.optJSONObject(i) ?: return@mapNotNull null
                    val name = o.optString("name"); val url = o.optString("download_url"); val size = o.optLong("size")
                    val sha = o.optString("sha").lowercase()
                    if (o.optString("type") == "file" && Rules.isScreenshot(name, url, size, repo) && Rules.GIT_SHA_HEX.matches(sha)) {
                        Rules.Shot(name, url, size, sha)
                    } else null
                }
                if (files.isNotEmpty()) break
            }
            // In their own order: 1, 2, … 10
            files.sortedWith(compareBy({ it.name.takeWhile { c -> c.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE }, { it.name }))
                .take(Rules.MAX_SCREENSHOTS)
        } catch (_: IOException) {
            null
        }
    }

    /** One screenshot's bytes, only if they are exactly the file GitHub listed; null otherwise. */
    fun screenshot(shot: Rules.Shot): ByteArray? = try {
        open(shot.url, api = false, hosts = Rules.SCREENSHOT_HOSTS).use { readLimited(it.inputStream, shot.size.toInt()) }
            .takeIf { it.size.toLong() == shot.size && Rules.gitBlobSha(it) == shot.sha }
    } catch (_: IOException) {
        null
    }

    /**
     * Downloads [asset] into [target], never more than its stated size, and checks GitHub's checksum when given.
     * [onProgress] gets the bytes so far; [isCancelled] stops the download between chunks.
     */
    fun download(asset: Rules.Asset, target: File, isCancelled: () -> Boolean, onProgress: (Long) -> Unit) {
        val sha = MessageDigest.getInstance("SHA-256")
        var total = 0L
        try {
            open(asset.url, api = false).use { conn ->
                val declared = conn.contentLengthLong
                if (declared != -1L && declared != asset.size) throw DownloadException("Size does not match the release")
                conn.inputStream.use { input ->
                    target.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (isCancelled()) throw CancellationException("Download stopped")
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > asset.size) throw DownloadException("File is larger than the release says")
                            sha.update(buf, 0, n)
                            out.write(buf, 0, n)
                            onProgress(total)
                        }
                        out.fd.sync()
                    }
                }
            }
        } catch (e: RateLimitException) {
            throw e
        } catch (e: NoReleaseException) {
            // The release was there a moment ago: the link changed or expired
            throw DownloadException("File not found", e)
        } catch (e: DownloadException) {
            throw e
        } catch (e: IOException) {
            throw DownloadException("Download interrupted", e)
        }
        if (total != asset.size) throw DownloadException("Download incomplete")
        val expected = asset.sha256
        if (expected != null && Rules.toHex(sha.digest()) != expected) {
            throw DownloadException("Checksum does not match the release")
        }
    }

    private val REDIRECTS = setOf(301, 302, 303, 307, 308)

    /** One request, redirects not followed: each next address is checked by the caller first. */
    private fun connect(uri: URI, api: Boolean): HttpsURLConnection {
        val conn = uri.toURL().openConnection() as HttpsURLConnection
        conn.instanceFollowRedirects = false
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        conn.useCaches = false
        // A neutral name: nothing about the phone or its owner
        conn.setRequestProperty("User-Agent", "Komora")
        if (api) {
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        return conn
    }

    /** An open connection after any redirects, each one checked against the allowed hosts. */
    private fun open(start: String, api: Boolean, hosts: Set<String> = Rules.APP_HOSTS): Connection {
        var url = start
        repeat(MAX_REDIRECTS + 1) {
            val uri = Rules.allowedUri(url, hosts) ?: throw IOException("Refused to connect outside GitHub")
            val conn = connect(uri, api)
            try {
                when (val code = conn.responseCode) {
                    HttpURLConnection.HTTP_OK -> return Connection(conn)
                    in REDIRECTS -> url = nextUrl(conn, url) ?: throw IOException("Bad redirect")
                    // Only the API's own "rate limit" answers count as such
                    403, 429 -> if (api && (code == 429 || conn.getHeaderField("X-RateLimit-Remaining") == "0")) {
                        throw RateLimitException()
                    } else {
                        throw IOException("GitHub answered $code")
                    }
                    404 -> throw NoReleaseException()
                    else -> throw IOException("GitHub answered $code")
                }
            } catch (e: Throwable) {
                conn.disconnect()
                throw e
            }
            conn.disconnect()
        }
        throw IOException("Too many redirects")
    }

    private fun readLimited(input: InputStream, max: Int): ByteArray = input.use {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = it.read(buf)
            if (n < 0) break
            if (out.size() + n > max) throw IOException("Answer too large")
            out.write(buf, 0, n)
        }
        out.toByteArray()
    }

    private class Connection(private val conn: HttpsURLConnection) : AutoCloseable {
        val inputStream: InputStream get() = conn.inputStream
        val contentLengthLong: Long get() = conn.contentLengthLong
        override fun close() = conn.disconnect()
    }
}
