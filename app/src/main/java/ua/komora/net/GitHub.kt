package ua.komora.net

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

data class Release(val tag: String, val apk: Rules.Asset)

/** Talks only to GitHub, only over HTTPS, and follows redirects by hand so it can refuse any other host. */
object GitHub {
    private const val TIMEOUT_MS = 20_000
    private const val MAX_REDIRECTS = 5

    /** The newest published (not draft, not pre-release) release of [repo], with its one APK. */
    fun latestRelease(repo: String, assetPrefix: String): Release {
        val body = open("https://api.github.com/repos/$repo/releases/latest", json = true).use { conn ->
            readLimited(conn.inputStream, Rules.MAX_JSON_BYTES)
        }
        val o = JSONObject(String(body, Charsets.UTF_8))
        val assetsJson = o.getJSONArray("assets")
        val assets = (0 until assetsJson.length()).map { i ->
            val a = assetsJson.getJSONObject(i)
            Rules.Asset(
                name = a.getString("name"),
                url = a.getString("browser_download_url"),
                size = a.getLong("size"),
                sha256 = Rules.parseDigest(a.optString("digest", "").ifEmpty { null }),
            )
        }
        return Release(tag = o.getString("tag_name"), apk = Rules.pickApk(assets, assetPrefix, repo))
    }

    /**
     * Downloads [asset] into [target], never more than its stated size, and checks GitHub's checksum when given.
     * [onProgress] gets a value from 0 to 1.
     */
    fun download(asset: Rules.Asset, target: File, onProgress: (Float) -> Unit) {
        val sha = MessageDigest.getInstance("SHA-256")
        var total = 0L
        open(asset.url, json = false).use { conn ->
            val declared = conn.contentLengthLong
            if (declared != -1L && declared != asset.size) throw IOException("Size does not match the release")
            conn.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > asset.size) throw IOException("File is larger than the release says")
                        sha.update(buf, 0, n)
                        out.write(buf, 0, n)
                        onProgress(total.toFloat() / asset.size)
                    }
                    out.fd.sync()
                }
            }
        }
        if (total != asset.size) throw IOException("Download incomplete")
        val expected = asset.sha256
        if (expected != null && Rules.toHex(sha.digest()) != expected) {
            throw IOException("Checksum does not match the release")
        }
    }

    /** An open connection after any redirects, each one checked against the allowed hosts. */
    private fun open(start: String, json: Boolean): AutoCloseableConnection {
        var url = start
        repeat(MAX_REDIRECTS + 1) {
            if (!Rules.isAllowedUrl(url)) throw IOException("Refused to connect outside GitHub")
            val conn = URL(url).openConnection() as HttpsURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.useCaches = false
            // A neutral name: nothing about the phone or its owner
            conn.setRequestProperty("User-Agent", "Komora")
            if (json) {
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            }
            val code = conn.responseCode
            when (code) {
                HttpURLConnection.HTTP_OK -> return AutoCloseableConnection(conn)
                301, 302, 303, 307, 308 -> {
                    val next = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (next.isNullOrEmpty()) throw IOException("Redirect without a target")
                    url = URL(URL(url), next).toString()
                }
                403, 429 -> { conn.disconnect(); throw RateLimitException() }
                404 -> { conn.disconnect(); throw NoReleaseException() }
                else -> { conn.disconnect(); throw IOException("GitHub answered $code") }
            }
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

    class AutoCloseableConnection(private val conn: HttpsURLConnection) : AutoCloseable {
        val inputStream: InputStream get() = conn.inputStream
        val contentLengthLong: Long get() = conn.contentLengthLong
        override fun close() = conn.disconnect()
    }
}

/** GitHub allows 60 requests an hour without an account. */
class RateLimitException : IOException("GitHub rate limit")

/** The repository has no published release yet. */
class NoReleaseException : IOException("No release yet")
