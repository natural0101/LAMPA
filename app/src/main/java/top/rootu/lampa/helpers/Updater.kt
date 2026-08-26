package top.rootu.lampa.helpers

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.text.Spanned
import androidx.core.content.FileProvider
import androidx.core.text.HtmlCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import top.rootu.lampa.App
import top.rootu.lampa.BuildConfig
import top.rootu.lampa.R
import top.rootu.lampa.helpers.Helpers.getJson
import top.rootu.lampa.models.Release
import top.rootu.lampa.models.Releases
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import javax.net.ssl.HttpsURLConnection


object Updater {
    private val releaseLink = BuildConfig.updateReleasesUrl
    private const val MAX_APK_BYTES = 300L * 1024L * 1024L
    private var releases: Releases? = null
    private var newVersion: Release? = null

    fun check(): Boolean {
        newVersion = null
        try {
            require(releaseLink.startsWith("https://")) { "Update endpoint must use HTTPS" }
            val connection = URL(releaseLink).openConnection() as HttpsURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.connect()
            if (connection.responseCode !in 200..299) {
                connection.disconnect()
                return false
            }
            val body = connection.inputStream.use {
                it.bufferedReader(Charset.defaultCharset()).readText()
            }
            releases = getJson(body, Releases::class.java)
            newVersion = releases
                ?.asSequence()
                ?.filterNot { it.draft || it.prerelease }
                ?.filter { release -> release.assets.any(::isCompatibleApkAsset) }
                ?.filter { release -> isNewerVersion(release.tag_name, BuildConfig.VERSION_NAME) }
                ?.maxByOrNull { release -> semanticVersion(release.tag_name) }
            connection.disconnect()
            return newVersion != null
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    fun getVersion(): String {
        if (newVersion == null)
            CoroutineScope(Dispatchers.IO).launch {
                check()
            }
        return newVersion?.tag_name?.replace("v", "") ?: ""
    }

    fun getOverview(): Spanned {
        var ret = ""

        releases?.forEach { rel ->
            if (isNewerVersion(rel.tag_name, BuildConfig.VERSION_NAME)) {
                ret += "<font color='white'><b>${rel.tag_name}</b></font> <br>"
                ret += "<i>${rel.body.replace("\r\n", "<br/>")}</i><br/><br/>"
            } else {
                ret += "${rel.tag_name}<br>"
                ret += "<i>${rel.body.replace("\r\n", "<br/>")}</i><br/><br/>"
            }
        }
        return HtmlCompat.fromHtml(ret.trim(), HtmlCompat.FROM_HTML_MODE_LEGACY)
    }

    private val download = Any()

    private fun downloadApk(file: File, onProgress: ((prc: Int) -> Unit)?) {
        synchronized(download) {
            newVersion?.let { rel ->
                if (file.exists())
                    file.delete()
                val asset = rel.assets.firstOrNull(::isCompatibleApkAsset)
                    ?: error("Release does not contain a compatible APK")
                val link = asset.browser_download_url
                require(link.startsWith("https://")) { "APK download must use HTTPS" }
                require(asset.size.toLong() in 1..MAX_APK_BYTES) { "APK has an invalid size" }
                try {
                        val connection = URL(link).openConnection() as HttpsURLConnection
                        connection.connectTimeout = 20_000
                        connection.readTimeout = 30_000
                        connection.instanceFollowRedirects = true
                        connection.connect()
                        require(connection.responseCode in 200..299) { "APK download failed" }
                        require(connection.url.protocol.equals("https", ignoreCase = true)) {
                            "APK download redirected outside HTTPS"
                        }
                        require(connection.contentLengthLong <= MAX_APK_BYTES) { "APK is too large" }
                        connection.inputStream.use { input ->
                            FileOutputStream(file).use { fileOut ->
                                val contentLength = asset.size.coerceAtLeast(1)
                                if (onProgress == null)
                                    input?.copyTo(fileOut)
                                else {
                                    val buffer = ByteArray(65535)
                                    var offset: Long = 0
                                    while (true) {
                                        val read = input.read(buffer)
                                        if (read <= 0) break
                                        offset += read
                                        require(offset <= MAX_APK_BYTES) { "APK is too large" }
                                        val prc = (offset * 100 / contentLength).toInt().coerceIn(0, 100)
                                        onProgress(prc)
                                        fileOut.write(buffer, 0, read)
                                    }
                                }
                                fileOut.flush()
                            }
                        }
                        connection.disconnect()
                        require(file.length() == asset.size.toLong()) { "APK download is incomplete" }
                    } catch (e: Exception) {
                        file.delete()
                        throw e
                    }
            }
        }
    }

    fun installNewVersion(onProgress: ((prc: Int) -> Unit)?) {
        val ctx = App.context
        if (newVersion == null && !check())
            return

        newVersion?.let {
            val destination = File(
                ctx.getExternalFilesDir(null),
                "LAMPA.apk"
            ).apply {
                parentFile?.mkdirs()
                deleteOnExit()
            }

            downloadApk(destination, onProgress)
            ApkUpdateVerifier.verify(ctx, destination).getOrThrow()

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                val uri = Uri.fromFile(destination)
                val install = Intent(Intent.ACTION_VIEW)
                install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                install.setDataAndType(uri, "application/vnd.android.package-archive")
                if (install.resolveActivity(ctx.packageManager) != null)
                    App.context.startActivity(install)
                else
                    App.toast(R.string.error_app_not_found)
            } else {
                val fileUri =
                    FileProvider.getUriForFile(
                        ctx,
                        BuildConfig.APPLICATION_ID + ".update_provider",
                        destination
                    )
                val install = Intent(Intent.ACTION_VIEW, fileUri)
                install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                install.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (install.resolveActivity(ctx.packageManager) != null)
                    ctx.startActivity(install)
                else
                    App.toast(R.string.error_app_not_found)
            }
        }
    }

    private fun isCompatibleApkAsset(asset: top.rootu.lampa.models.Asset): Boolean {
        if (!asset.name.endsWith(".apk", ignoreCase = true)) return false
        return !BuildConfig.APPLICATION_ID.endsWith(".matrix") ||
            asset.name.contains("matrix", ignoreCase = true)
    }

    private fun isNewerVersion(candidate: String, current: String): Boolean =
        semanticVersion(candidate) > semanticVersion(current)

    private fun semanticVersion(value: String): Long {
        val match = Regex("(\\d+)\\.(\\d+)(?:\\.(\\d+))?").find(value) ?: return 0L
        val major = match.groupValues[1].toLongOrNull() ?: 0L
        val minor = match.groupValues[2].toLongOrNull() ?: 0L
        val patch = match.groupValues.getOrNull(3)?.toLongOrNull() ?: 0L
        return major.coerceAtMost(9999) * 1_000_000_000L +
            minor.coerceAtMost(999_999) * 1_000L + patch.coerceAtMost(999)
    }
}
