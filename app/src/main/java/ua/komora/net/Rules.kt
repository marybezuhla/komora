package ua.komora.net

import java.net.URI

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

    fun isAllowedUrl(url: String): Boolean {
        val uri = try { URI(url) } catch (_: Exception) { return false }
        return uri.scheme == "https" &&
            uri.host != null && uri.host.lowercase() in ALLOWED_HOSTS &&
            uri.userInfo == null &&
            (uri.port == -1 || uri.port == 443)
    }

    data class Asset(val name: String, val url: String, val size: Long, val sha256: String?)

    /**
     * Picks the one APK named "<prefix>-<version>.apk" whose link points into this very repository's releases.
     * Zero or several matching files is an error: Komora never guesses.
     */
    fun pickApk(assets: List<Asset>, prefix: String, repo: String): Asset {
        val name = Regex("^" + Regex.escape(prefix) + "-[0-9A-Za-z._+-]+\\.apk$")
        val matching = assets.filter { name.matches(it.name) }
        require(matching.size == 1) { "Expected exactly one $prefix APK, found ${matching.size}" }
        val asset = matching.single()
        require(asset.url.startsWith("https://github.com/$repo/releases/download/")) { "APK link outside the repository" }
        require(asset.size in 1..MAX_APK_BYTES) { "APK size out of range" }
        return asset
    }

    /** GitHub gives a file's checksum as "sha256:<hex>"; anything else is treated as not given. */
    fun parseDigest(digest: String?): String? {
        if (digest == null || !digest.startsWith("sha256:")) return null
        val hex = digest.removePrefix("sha256:").lowercase()
        return hex.takeIf { Regex("^[0-9a-f]{64}$").matches(it) }
    }

    /** "v0.1.42" and "0.1.42" are the same version for display. */
    fun normalizeVersion(v: String): String = v.trim().removePrefix("v").removePrefix("V")

    fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
