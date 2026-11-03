package ua.komora.net

import java.net.URI

/** The release exists but has no single, correctly named APK in this repository. */
class BadReleaseException(message: String) : Exception(message)

/** Plain rules with no Android code, so they can be tested on a computer. */
object Rules {
    /** The only places Komora ever connects to: GitHub's API and the hosts GitHub serves release files from. */
    val ALLOWED_HOSTS = setOf(
        "api.github.com",
        "github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
    )

    /** Big enough for an app with a bundled model, small enough that a bad release cannot fill the phone. */
    const val MAX_APK_BYTES: Long = 600L * 1024 * 1024

    /** A JSON answer from GitHub is never this big. */
    const val MAX_JSON_BYTES: Int = 2 * 1024 * 1024

    val SHA256_HEX = Regex("^[0-9a-f]{64}$")

    /** Parses [url] once; the same object is then used to connect, so the check and the connection agree. */
    fun allowedUri(url: String): URI? {
        val uri = try { URI(url) } catch (_: Exception) { return null }
        val ok = uri.scheme == "https" &&
            uri.host != null && uri.host.lowercase() in ALLOWED_HOSTS &&
            uri.rawUserInfo == null &&
            (uri.port == -1 || uri.port == 443)
        return uri.takeIf { ok }
    }

    fun isAllowedUrl(url: String): Boolean = allowedUri(url) != null

    data class Asset(val name: String, val url: String, val size: Long, val sha256: String?)

    /**
     * Picks the one APK named "<prefix>-<version>.apk" (the version starts with a digit, optionally after "v")
     * whose link points into this very repository's releases. Zero or several matches is an error: Komora never guesses.
     */
    fun pickApk(assets: List<Asset>, prefix: String, repo: String): Asset {
        val name = Regex("^" + Regex.escape(prefix) + "-v?[0-9][0-9A-Za-z._+]*\\.apk$")
        val matching = assets.filter { name.matches(it.name) }
        if (matching.size != 1) throw BadReleaseException("Expected exactly one $prefix APK, found ${matching.size}")
        val asset = matching.single()
        if (!asset.url.startsWith("https://github.com/$repo/releases/download/")) throw BadReleaseException("APK link outside the repository")
        if (asset.size !in 1..MAX_APK_BYTES) throw BadReleaseException("APK size out of range")
        return asset
    }

    /** GitHub gives a file's checksum as "sha256:<hex>"; anything else is treated as not given. */
    fun parseDigest(digest: String?): String? {
        if (digest == null || !digest.startsWith("sha256:")) return null
        val hex = digest.removePrefix("sha256:").lowercase()
        return hex.takeIf { SHA256_HEX.matches(it) }
    }

    /** "v0.1.42" and "0.1.42" are the same version for display. */
    fun normalizeVersion(v: String): String = v.trim().removePrefix("v").removePrefix("V")

    /**
     * Compares the leading numbers of two versions ("v1.2.0", "1.2.0-debug" -> 1.2.0).
     * Returns null when either has no leading number, so nothing is offered on a guess.
     */
    fun compareVersions(a: String, b: String): Int? {
        val pa = numbers(a) ?: return null
        val pb = numbers(b) ?: return null
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val c = pa.getOrElse(i) { 0L }.compareTo(pb.getOrElse(i) { 0L })
            if (c != 0) return c
        }
        return 0
    }

    private val LEADING = Regex("^[0-9]{1,9}(\\.[0-9]{1,9})*")

    private fun numbers(v: String): List<Long>? =
        LEADING.find(normalizeVersion(v))?.value?.split(".")?.map { it.toLong() }

    fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
