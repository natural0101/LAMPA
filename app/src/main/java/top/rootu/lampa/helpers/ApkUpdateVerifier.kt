package top.rootu.lampa.helpers

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import top.rootu.lampa.BuildConfig
import java.io.File
import java.security.MessageDigest

/** Verifies an update before Android's package installer is opened. */
object ApkUpdateVerifier {
    data class VerifiedApk(val versionName: String, val versionCode: Long)

    fun verify(context: Context, apk: File): Result<VerifiedApk> = runCatching {
        require(apk.isFile && apk.length() > 0L) { "Downloaded APK is empty" }
        val packageManager = context.packageManager
        val candidate = archiveInfo(packageManager, apk)
            ?: error("Downloaded file is not a valid APK")
        require(candidate.packageName == BuildConfig.APPLICATION_ID) {
            "Update package does not match this application"
        }

        val installed = installedInfo(packageManager, BuildConfig.APPLICATION_ID)
        val candidateVersion = versionCode(candidate)
        val installedVersion = versionCode(installed)
        require(candidateVersion > installedVersion) { "Update version is not newer" }

        val candidateSigners = signerDigests(candidate)
        val installedSigners = signerDigests(installed)
        require(candidateSigners.isNotEmpty()) { "Update has no signing certificate" }
        require(candidateSigners == installedSigners) {
            "Update signing certificate does not match the installed application"
        }

        val pinned = BuildConfig.updateCertificateSha256
            .trim()
            .replace(":", "")
            .uppercase()
        if (pinned.isNotEmpty()) {
            require(candidateSigners == setOf(pinned)) {
                "Update is not signed by the pinned release certificate"
            }
        }

        VerifiedApk(candidate.versionName.orEmpty(), candidateVersion)
    }

    @Suppress("DEPRECATION")
    private fun archiveInfo(packageManager: PackageManager, apk: File): PackageInfo? =
        packageManager.getPackageArchiveInfo(
            apk.absolutePath,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                PackageManager.GET_SIGNATURES
            }
        )

    @Suppress("DEPRECATION")
    private fun installedInfo(packageManager: PackageManager, packageName: String): PackageInfo =
        packageManager.getPackageInfo(
            packageName,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                PackageManager.GET_SIGNATURES
            }
        )

    @Suppress("DEPRECATION")
    private fun signerDigests(packageInfo: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners?.toList().orEmpty()
        } else {
            packageInfo.signatures?.toList().orEmpty()
        }
        return signatures.map { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { "%02X".format(it) }
        }.toSet()
    }

    @Suppress("DEPRECATION")
    private fun versionCode(packageInfo: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            packageInfo.versionCode.toLong()
        }
}
