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

data class Release(val tag: String, val apk: Rules.Asset)

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

    /** The newest published (not draft, not pre-release) release of [repo], with its one APK. */
    fun latestRelease(repo: String, assetPrefix: String): Release {
        val body = open("https://api.github.com/repos/$repo/releases/latest", api = true).use { conn ->
            readLimited(conn.inputStream, Rules.MAX_JSON_BYTES)
        }
        // An answer that cannot be read as a release is a bad release, not a lost connection
        val (tag, assets) = try {
            val o = JSONObject(String(body, Charsets.UTF_8))
            val assetsJson = o.getJSONArray("assets")
            o.getString("tag_name") to (0 until assetsJson.length()).map { i ->
                val a = assetsJson.getJSONObject(i)
                Rules.Asset(
                    name = a.getString("name"),
                    url = a.getString("browser_download_url"),
                    size = a.getLong("size"),
                    sha256 = Rules.parseDigest(a.optString("digest", "").ifEmpty { null }),
                )
            }
        } catch (e: JSONException) {
            throw BadReleaseException("Unreadable release")
        } catch (e: StackOverflowError) {
            // Absurdly nested JSON: never a crash
            throw BadReleaseException("Unreadable release")
        }
        return Release(tag = tag, apk = Rules.pickApk(assets, assetPrefix, repo))
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

    /** An open connection after any redirects, each one checked against the allowed hosts. */
    private fun open(start: String, api: Boolean): Connection {
        var url = start
        repeat(MAX_REDIRECTS + 1) {
            val uri = Rules.allowedUri(url) ?: throw IOException("Refused to connect outside GitHub")
            val conn = uri.toURL().openConnection() as HttpsURLConnection
            try {
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
                when (val code = conn.responseCode) {
                    HttpURLConnection.HTTP_OK -> return Connection(conn)
                    301, 302, 303, 307, 308 -> {
                        val next = conn.getHeaderField("Location")
                        if (next.isNullOrEmpty()) throw IOException("Redirect without a target")
                        url = try { URI(url).resolve(next).toString() } catch (e: IllegalArgumentException) {
                            throw IOException("Bad redirect")
                        }
                    }
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
