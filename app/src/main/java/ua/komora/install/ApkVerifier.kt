package ua.komora.install

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import ua.komora.catalog.CatalogApp
import ua.komora.net.Rules
import java.io.File
import java.security.MessageDigest

/** Why a downloaded APK was refused. Each one stops the install; none can be skipped. */
sealed class VerifyError : Exception() {
    object Unreadable : VerifyError()
    object WrongPackage : VerifyError()
    object WrongSigner : VerifyError()
    object NotNewer : VerifyError()
    object InstalledByOtherKey : VerifyError()
}

object ApkVerifier {

    /**
     * Checks a downloaded APK before Android ever sees it:
     *  - it is the app the catalog names (same package),
     *  - it is signed by exactly one certificate, and that certificate is the author's pinned one,
     *  - it is newer than what is installed (no downgrades).
     * Android then checks the APK signature itself while installing.
     */
    fun verify(context: Context, apk: File, app: CatalogApp) {
        val pm = context.packageManager
        val archive = archiveInfo(pm, apk) ?: throw VerifyError.Unreadable
        if (archive.packageName != app.packageName) throw VerifyError.WrongPackage

        val pinned = pinnedCert(context, app)
        if (signerDigests(archive) != setOf(pinned)) throw VerifyError.WrongSigner

        val installed = installedInfo(pm, app.packageName)
        if (installed != null) {
            // Android would refuse this update anyway; saying so plainly is clearer
            if (signerDigests(installed) != setOf(pinned)) throw VerifyError.InstalledByOtherKey
            if (archive.longVersionCode <= installed.longVersionCode) throw VerifyError.NotNewer
        }
    }

    /** The certificate an app must be signed with. For Komora itself, the one it is running with now. */
    fun pinnedCert(context: Context, app: CatalogApp): String =
        if (app.isSelf) {
            // "self" is only ever Komora's own package
            if (app.packageName != context.packageName) throw VerifyError.WrongPackage
            val self = installedInfo(context.packageManager, context.packageName)
                ?: throw VerifyError.Unreadable
            signerDigests(self).singleOrNull() ?: throw VerifyError.WrongSigner
        } else app.certSha256

    fun installedInfo(pm: PackageManager, pkg: String): PackageInfo? = try {
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private fun archiveInfo(pm: PackageManager, apk: File): PackageInfo? =
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageArchiveInfo(apk.path, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
        }

    /**
     * SHA-256 of the one certificate that signed the APK contents, as a set of one; empty when there is not
     * exactly one signer. Past certificates from key rotation are deliberately not counted: only the key
     * signing the APK now must match the pin.
     */
    private fun signerDigests(info: PackageInfo): Set<String> {
        val signing = info.signingInfo ?: return emptySet()
        val certs: Array<Signature> = signing.apkContentsSigners ?: return emptySet()
        if (certs.size != 1) return emptySet()
        val md = MessageDigest.getInstance("SHA-256")
        return setOf(Rules.toHex(md.digest(certs[0].toByteArray())))
    }
}
