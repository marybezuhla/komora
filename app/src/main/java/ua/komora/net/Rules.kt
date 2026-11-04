package ua.komora.net

import java.net.URI

/** The release exists but has no single, correctly named APK in this repository. */
class BadReleaseException(message: String) : Exception(message)

/** Plain rules with no Android code, so they can be tested on a computer. */
object Rules {
    /** The only places Komora ever connects to: GitHub's API and the hosts GitHub serves release files from. */
    val APP_HOSTS = setOf(
        "api.github.com",
        "github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
    )

    /** Screenshots: the list from GitHub's API, the pictures from the repository itself. Nothing else. */
    val SCREENSHOT_HOSTS = setOf("api.github.com", "raw.githubusercontent.com")

    /** A screenshot is never bigger than this; a larger file is left out. */
    const val MAX_IMAGE_BYTES: Int = 3 * 1024 * 1024

    /** At most this many screenshots per app. */
    const val MAX_SCREENSHOTS = 8

    /** Where an app keeps its screenshots, as F-Droid reads them; the language shown first. */
    fun screenshotDirs(lang: String): List<String> {
        val order = if (lang == "uk") listOf("uk", "uk-UA", "en-US", "en") else listOf("en-US", "en", "uk", "uk-UA")
        return order.map { "fastlane/metadata/android/$it/images/phoneScreenshots" }
    }

    /** A screenshot of [repo]: an image file in its own repository, nothing else. */
    fun isScreenshot(name: String, url: String, size: Long, repo: String): Boolean =
        Regex("(?i)^[A-Za-z0-9._-]{1,80}\\.(png|jpe?g|webp)$").matches(name) &&
            url.startsWith("https://raw.githubusercontent.com/$repo/", ignoreCase = true) &&
            !url.contains("/.") && !url.contains("%2e", ignoreCase = true) && !url.contains("%2f", ignoreCase = true) &&
            size in 1..MAX_IMAGE_BYTES.toLong()

    /** Big enough for an app with a bundled model, small enough that a bad release cannot fill the phone. */
    const val MAX_APK_BYTES: Long = 600L * 1024 * 1024

    /** A JSON answer from GitHub is never this big. */
    const val MAX_JSON_BYTES: Int = 2 * 1024 * 1024

    val SHA256_HEX = Regex("^[0-9a-f]{64}$")

    /** Parses [url] once; the same object is then used to connect, so the check and the connection agree. */
    fun allowedUri(url: String, hosts: Set<String> = APP_HOSTS): URI? {
        val uri = try { URI(url) } catch (_: Exception) { return null }
        val ok = uri.scheme == "https" &&
            uri.host != null && uri.host.lowercase() in hosts &&
            uri.rawUserInfo == null &&
            (uri.port == -1 || uri.port == 443)
        return uri.takeIf { ok }
    }

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
        if (!inRepo(asset.url, repo)) throw BadReleaseException("APK link outside the repository")
        if (asset.size !in 1..MAX_APK_BYTES) throw BadReleaseException("APK size out of range")
        return asset
    }

    /** For added sources: the one APK whose name matches the pattern chosen when the source was added. */
    fun pickByPattern(assets: List<Asset>, pattern: String, repo: String): Asset {
        val name = try { Regex(pattern) } catch (_: Exception) { throw BadReleaseException("Bad pattern") }
        val matching = assets.filter { name.matches(it.name) }
        if (matching.size != 1) throw BadReleaseException("Expected exactly one matching APK, found ${matching.size}")
        val asset = matching.single()
        if (!inRepo(asset.url, repo)) throw BadReleaseException("APK link outside the repository")
        if (asset.size !in 1..MAX_APK_BYTES) throw BadReleaseException("APK size out of range")
        return asset
    }

    /** A release file of [repo]; GitHub's names ignore case, so the check does too. */
    fun inRepo(url: String, repo: String): Boolean =
        url.startsWith("https://github.com/$repo/releases/download/", ignoreCase = true)

    /**
     * "app-1.2.3-universal.apk" -> a pattern that also matches the next versions' names. Numbers that
     * tell apart files of the same release ("v7a" and "v8a") stay as they are, so exactly one of
     * [siblings] (all the APKs of that release) matches.
     */
    fun patternFor(assetName: String, siblings: List<String> = listOf(assetName)): String {
        val parts = assetName.split(DIGITS)
        val numbers = DIGITS.findAll(assetName).map { it.value }.toList()
        // Files of the same shape in this release ("app-1.2-v7a.apk" beside "app-1.2-v8a.apk"): the numbers in
        // which they differ tell them apart and stay as they are; the rest (the version) may change
        val sameShape = siblings.filter { it != assetName && it.split(DIGITS) == parts }
            .map { s -> DIGITS.findAll(s).map { it.value }.toList() }
        val loose = BooleanArray(numbers.size) { i -> sameShape.none { it[i] != numbers[i] } }
        fun build() = buildString {
            for ((i, part) in parts.withIndex()) {
                append(escape(part))
                if (i < numbers.size) append(if (loose[i]) "[0-9]+" else numbers[i])
            }
        }
        val r = Regex(build())
        // Should never happen; if it does, only this very name
        if (siblings.count { r.matches(it) } > 1) loose.fill(false)
        return build()
    }

    private val DIGITS = Regex("[0-9]+")

    private fun escape(part: String) = part.map { c -> if (c in "\\.^$|?*+()[]{}-") "\\$c" else c.toString() }.joinToString("")

    /** A tag or version from a release, as one short plain line. */
    fun plainTag(tag: String): String = tag.replace(Regex("[\\p{C}\\s]"), "").take(40)

    /** GitHub gives a file's checksum as "sha256:<hex>"; anything else is treated as not given. */
    fun parseDigest(digest: String?): String? {
        if (digest == null || !digest.startsWith("sha256:")) return null
        val hex = digest.removePrefix("sha256:").lowercase()
        return hex.takeIf { SHA256_HEX.matches(it) }
    }

    /** "v0.1.42" and "0.1.42" are the same version for display. */
    fun normalizeVersion(v: String): String = v.trim().removePrefix("v").removePrefix("V")

    /**
     * Compares two versions by their leading numbers ("v1.2.0", "1.2.0-debug" -> 1.2.0). With the same
     * numbers, a test version ("-alpha", "-beta", "-rc.2", "-pre", "-preview") comes before the final one,
     * and test versions go by their kind, then their number. Returns null when either has no leading
     * number, so nothing is offered on a guess.
     */
    fun compareVersions(a: String, b: String): Int? {
        val pa = numbers(a) ?: return null
        val pb = numbers(b) ?: return null
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val c = pa.getOrElse(i) { 0L }.compareTo(pb.getOrElse(i) { 0L })
            if (c != 0) return c
        }
        return compareTest(testOf(a), testOf(b))
    }

    private val LEADING = Regex("^[0-9]{1,9}(\\.[0-9]{1,9})*")
    private val TEST = Regex("^[-._+]?(alpha|beta|pre|preview|rc)[-._]?([0-9]{0,9})", RegexOption.IGNORE_CASE)
    private val TEST_KINDS = listOf("alpha", "beta", "pre", "preview", "rc")

    private fun numbers(v: String): List<Long>? =
        LEADING.find(normalizeVersion(v))?.value?.split(".")?.map { it.toLong() }

    /** The kind (0 alpha … 4 rc) and number of a test version; null for a final one ("-debug" counts as final). */
    private fun testOf(v: String): Pair<Int, Long>? {
        val rest = normalizeVersion(v).replaceFirst(LEADING, "")
        val m = TEST.find(rest) ?: return null
        return TEST_KINDS.indexOf(m.groupValues[1].lowercase()) to (m.groupValues[2].toLongOrNull() ?: 0L)
    }

    private fun compareTest(a: Pair<Int, Long>?, b: Pair<Int, Long>?): Int = when {
        a == null && b == null -> 0
        a == null -> 1
        b == null -> -1
        else -> compareValuesBy(a, b, { it.first }, { it.second })
    }

    /** Automatic updates take a release only after this long, so a bad one can be noticed and pulled first. */
    const val AUTO_UPDATE_WAIT_MS: Long = 48L * 60 * 60 * 1000

    /** True when a release published at [publishedMs] has waited long enough at [nowMs]; unknown is never. */
    fun oldEnough(publishedMs: Long?, nowMs: Long): Boolean =
        publishedMs != null && nowMs - publishedMs >= AUTO_UPDATE_WAIT_MS

    fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
