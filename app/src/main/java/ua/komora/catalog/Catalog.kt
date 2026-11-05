package ua.komora.catalog

import org.json.JSONObject
import ua.komora.net.Rules

/** One app in Komora's catalog. The catalog ships inside Komora's own signed APK, so nobody can change it on the way. */
data class CatalogApp(
    val packageName: String,
    /** GitHub "owner/name". */
    val repo: String,
    /** The APK in a release must be named "<assetPrefix>-<version>.apk". */
    val assetPrefix: String,
    /** SHA-256 of the author's signing certificate, lowercase hex; or [SELF] for Komora itself. */
    val certSha256: String,
    /** The colour of the app's small square in the list (ARGB). */
    val badge: Long,
    private val names: Map<String, String>,
    private val summaries: Map<String, String>,
    /** Added by the owner (not part of Komora's own list). */
    val added: Boolean = false,
    /** For added sources: the APK's name in a release, as a pattern (versions as digits). */
    val apkPattern: String? = null,
    /** The lowest version Komora installs, so an old, flawed version cannot be published again as new. */
    val minVersion: String? = null,
) {
    val isSelf: Boolean get() = certSha256 == SELF

    /** The key Komora knows it by: the package, or for an added source not yet installed, its repository. */
    val key: String get() = packageName.ifEmpty { "src:$repo" }

    /** An added source whose key is not yet known: it is shown and confirmed at the first install. */
    val needsTrust: Boolean get() = added && certSha256.isEmpty()

    fun name(lang: String): String = names[lang] ?: names.getValue("en")
    fun summary(lang: String): String = summaries[lang] ?: summaries.getValue("en")

    companion object {
        /** Komora checks its own updates against the key it was itself signed with. */
        const val SELF = "self"
    }
}

object Catalog {
    private val PACKAGE = Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$")
    private val REPO = Regex("^${Rules.OWNER}/${Rules.REPO_NAME}$")
    private val PREFIX = Regex("^[a-z0-9][a-z0-9_-]{0,40}$")
    private val SHA256 = Regex("^[0-9a-f]{64}$")
    private val BADGE = Regex("^#[0-9A-Fa-f]{6}$")
    private val VERSION = Regex("^[0-9]{1,9}(\\.[0-9]{1,9}){0,3}$")
    private const val DEFAULT_BADGE = "#5A5048"

    /** Reads and strictly checks the catalog. Anything unexpected stops it, rather than being skipped quietly. */
    fun parse(json: String): List<CatalogApp> {
        val root = JSONObject(json)
        require(root.getInt("version") == 1) { "Unknown catalog version" }
        val list = root.getJSONArray("apps")
        val apps = (0 until list.length()).map { i ->
            val o = list.getJSONObject(i)
            val app = CatalogApp(
                packageName = o.getString("packageName"),
                repo = o.getString("repo"),
                assetPrefix = o.getString("assetPrefix"),
                certSha256 = o.getString("certSha256"),
                badge = o.optString("badge", DEFAULT_BADGE).let { c ->
                    require(BADGE.matches(c)) { "Bad badge colour: $c" }
                    0xFF000000L or c.removePrefix("#").toLong(16)
                },
                names = o.getJSONObject("name").toMap(),
                summaries = o.getJSONObject("summary").toMap(),
                minVersion = if (o.has("minVersion")) o.getString("minVersion").also { v ->
                    require(VERSION.matches(v)) { "Bad minVersion: $v" }
                } else null,
            )
            require(PACKAGE.matches(app.packageName)) { "Bad package name: ${app.packageName}" }
            require(REPO.matches(app.repo)) { "Bad repo: ${app.repo}" }
            require(PREFIX.matches(app.assetPrefix)) { "Bad asset prefix: ${app.assetPrefix}" }
            require(app.isSelf || SHA256.matches(app.certSha256)) { "Bad certificate for ${app.packageName}" }
            require(o.getJSONObject("name").has("en") && o.getJSONObject("summary").has("en")) {
                "English name and summary are required for ${app.packageName}"
            }
            app
        }
        require(apps.map { it.packageName }.toSet().size == apps.size) { "Duplicate package in catalog" }
        require(apps.count { it.isSelf } <= 1) { "Only Komora itself may use \"self\"" }
        return apps
    }

    // Android's org.json has keys(), not keySet()
    private fun JSONObject.toMap(): Map<String, String> = keys().asSequence().associateWith { getString(it) }
}
