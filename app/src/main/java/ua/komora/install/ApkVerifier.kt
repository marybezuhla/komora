package ua.komora.install

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.SigningInfo
import android.os.Build
import ua.komora.catalog.CatalogApp
import ua.komora.net.Rules
import java.io.File
import java.security.MessageDigest

/** Why a downloaded APK was refused. Each one stops the install; none can be skipped. */
sealed class ApkRejected : Exception() {
    object Unreadable : ApkRejected()
    object WrongPackage : ApkRejected()
    object WrongSigner : ApkRejected()
    object NotNewer : ApkRejected()
    object InstalledByOtherKey : ApkRejected()
}

object ApkVerifier {
    private const val FLAGS = PackageManager.GET_SIGNING_CERTIFICATES

    /**
     * Checks a downloaded APK before Android ever sees it:
     *  - it is the app the catalog names (same package),
     *  - it is signed by exactly one certificate, and that certificate is the pinned one,
     *  - it is newer than what is installed (no downgrades),
     *  - the installed copy (if any) is the pinned key, or a key the new APK names as its own past key
     *    (Android's key rotation), so a planned key change still reaches people who have the app.
     * Android then checks the APK signature itself while installing.
     */
    fun verify(context: Context, apk: File, app: CatalogApp) {
        val pm = context.packageManager
        val archive = archiveInfo(pm, apk) ?: throw ApkRejected.Unreadable
        if (archive.packageName != app.packageName) throw ApkRejected.WrongPackage

        val pinned = pinnedCert(context, app)
        val signing = archive.signingInfo ?: throw ApkRejected.WrongSigner
        if (currentSigner(signing) != pinned) throw ApkRejected.WrongSigner

        val installed = installedInfo(pm, app.packageName) ?: return
        val installedSigner = installed.signingInfo?.let(::currentSigner)
        val accepted = setOf(pinned) + pastSigners(signing)
        if (installedSigner == null || installedSigner !in accepted) throw ApkRejected.InstalledByOtherKey
        if (archive.longVersionCode <= installed.longVersionCode) throw ApkRejected.NotNewer
    }

    /** True when the installed copy is signed with the pinned key, so Komora can update it. */
    fun installedMatchesPin(context: Context, app: CatalogApp, installed: PackageInfo): Boolean = try {
        installed.signingInfo?.let(::currentSigner) == pinnedCert(context, app)
    } catch (_: ApkRejected) {
        false
    }

    /** The certificate an app must be signed with. For Komora itself, the one it is running with now. */
    fun pinnedCert(context: Context, app: CatalogApp): String =
        if (app.isSelf) {
            // "self" is only ever Komora's own package
            if (app.packageName != context.packageName) throw ApkRejected.WrongPackage
            val self = installedInfo(context.packageManager, context.packageName) ?: throw ApkRejected.Unreadable
            self.signingInfo?.let(::currentSigner) ?: throw ApkRejected.WrongSigner
        } else app.certSha256

    fun installedInfo(pm: PackageManager, pkg: String): PackageInfo? = try {
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(FLAGS.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, FLAGS)
        }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private fun archiveInfo(pm: PackageManager, apk: File): PackageInfo? =
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageArchiveInfo(apk.path, PackageManager.PackageInfoFlags.of(FLAGS.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apk.path, FLAGS)
        }

    /** SHA-256 of the one certificate signing the APK now; null when there is not exactly one. */
    private fun currentSigner(signing: SigningInfo): String? {
        if (signing.hasMultipleSigners()) return null
        val certs = signing.apkContentsSigners ?: return null
        if (certs.size != 1) return null
        return digest(certs[0].toByteArray())
    }

    /**
     * Past keys the APK itself proves it rotated away from (signed lineage). Only for a single signer;
     * the current key is excluded, since it must still match the pin on its own.
     */
    private fun pastSigners(signing: SigningInfo): Set<String> {
        if (signing.hasMultipleSigners()) return emptySet()
        val history = signing.signingCertificateHistory ?: return emptySet()
        val current = currentSigner(signing)
        return history.map { digest(it.toByteArray()) }.filter { it != current }.toSet()
    }

    private fun digest(bytes: ByteArray): String = Rules.toHex(MessageDigest.getInstance("SHA-256").digest(bytes))
}
