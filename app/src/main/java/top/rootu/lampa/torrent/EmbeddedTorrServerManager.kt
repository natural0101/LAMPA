package top.rootu.lampa.torrent

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.StatFs
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

object EmbeddedTorrServerManager {
    internal const val HOST = "127.0.0.1"
    internal const val PORT = 8090
    internal const val BASE_URL = "http://$HOST:$PORT"
    internal const val BINARY_NAME = "libtorrserver.so"

    /** Official MatriX.143 Android release hashes, keyed by Android ABI. */
    internal val MATRIX_143_SHA256 = mapOf(
        "arm64-v8a" to "23cea145c38e948f1a967c7fdbcb9c71506cd21a2fe7b3723903e233a323465b",
        "armeabi-v7a" to "9bab078a0976b86ff392c9eee756194643f4e939ee2c9504dfd4ab7094ef9490",
        "x86_64" to "03657ceb430d9f72475598525a2cc6fd90e8732615f615673e9635a024c3b454",
        "x86" to "cb01918f324307c7e852afbb88f34b80b6c2abd1654dcac9925c3da3f5e00ef3"
    )

    enum class Health { HEALTHY, STOPPED, INCOMPATIBLE, UNSUPPORTED }

    data class Status(
        val health: Health,
        val version: String? = null,
        val cacheSizeBytes: Long? = null,
        val diskCacheEnabled: Boolean? = null,
        val errorCode: String? = null,
        val owned: Boolean = false
    ) {
        val isHealthy: Boolean get() = health == Health.HEALTHY
    }

    data class Diagnostics(
        val status: Status,
        val abi: String?,
        val port: Int,
        val appMemoryUsedBytes: Long,
        val appMemoryLimitBytes: Long,
        val deviceMemoryAvailableBytes: Long,
        val deviceMemoryLow: Boolean,
        val storageFreeBytes: Long,
        val storageTotalBytes: Long
    )

    data class OperationResult(val success: Boolean, val errorCode: String? = null)

    // One automatic attempt per failure episode. A successful health check opens
    // the gate again; repeated resumes cannot produce an endless restart loop.
    private val resumeRecoveryAttempted = AtomicBoolean(false)
    private val controlMutex = Mutex()

    @Volatile
    private var serviceStartFailure: String? = null

    @JvmStatic
    fun isSupported(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && selectedAbi() != null

    @JvmStatic
    fun start(context: Context) {
        if (!isSupported()) return
        startService(context, EmbeddedTorrServerService.ACTION_START)
    }

    @JvmStatic
    suspend fun awaitReady(timeoutMs: Long = 15_000L): Boolean = withContext(Dispatchers.IO) {
        val timeout = timeoutMs.coerceAtLeast(0L)
        val deadline = SystemClock.elapsedRealtime() + timeout
        do {
            if (isCompatibleServer()) return@withContext true
            if (SystemClock.elapsedRealtime() >= deadline) break
            delay(POLL_INTERVAL_MS.coerceAtMost((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1L)))
        } while (true)
        false
    }

    suspend fun status(timeoutMs: Int = REQUEST_TIMEOUT_MS): Status = withContext(Dispatchers.IO) {
        probe(timeoutMs)
    }

    /** Used from Activity.onResume after TV sleep/background. */
    suspend fun ensureHealthyAfterResume(context: Context): Boolean = withContext(Dispatchers.IO) {
        controlMutex.withLock { ensureHealthyAfterResumeLocked(context) }
    }

    private suspend fun ensureHealthyAfterResumeLocked(context: Context): Boolean {
        if (probe().isHealthy) {
            resumeRecoveryAttempted.set(false)
            return true
        }
        if (!resumeRecoveryAttempted.compareAndSet(false, true)) return false
        if (!startService(context, EmbeddedTorrServerService.ACTION_START)) return false
        val recovered = awaitReady(STARTUP_TIMEOUT_MS)
        if (recovered) resumeRecoveryAttempted.set(false)
        return recovered
    }

    suspend fun restart(context: Context): OperationResult = withContext(Dispatchers.IO) {
        controlMutex.withLock { restartLocked(context) }
    }

    private suspend fun restartLocked(context: Context): OperationResult {
        if (!isSupported()) return OperationResult(false, "unsupported")
        val before = probe()
        restartOperationError(before)?.let { return OperationResult(false, it) }
        resumeRecoveryAttempted.set(false)
        if (EmbeddedTorrServerService.hasOwnedProcess() &&
            !startService(context, EmbeddedTorrServerService.ACTION_STOP)) {
            return OperationResult(false, "service_start_denied")
        }
        val stopDeadline = SystemClock.elapsedRealtime() + STOP_TIMEOUT_MS
        while ((EmbeddedTorrServerService.hasOwnedProcess() || isCompatibleServer(FAST_REQUEST_TIMEOUT_MS)) &&
            SystemClock.elapsedRealtime() < stopDeadline) {
            delay(POLL_INTERVAL_MS)
        }
        if (isCompatibleServer(FAST_REQUEST_TIMEOUT_MS)) {
            return OperationResult(false, if (EmbeddedTorrServerService.hasOwnedProcess()) "stop_timeout" else "external_server")
        }
        if (EmbeddedTorrServerService.hasOwnedProcess()) return OperationResult(false, "stop_timeout")
        // Port close is observed before Service.onDestroy completes. Starting a
        // new instance immediately can race the previous instance's stopSelf().
        delay(RESTART_SERVICE_SETTLE_MS)
        if (!startService(context, EmbeddedTorrServerService.ACTION_START)) return OperationResult(false, "service_start_denied")
        return if (awaitReady(STARTUP_TIMEOUT_MS)) {
            if (EmbeddedTorrServerService.hasOwnedProcess()) OperationResult(true)
            else OperationResult(false, "external_server")
        } else OperationResult(false, EmbeddedTorrServerService.lastFailureCode() ?: "start_timeout")
    }

    /**
     * MatriX.143 has no dedicated global cache-clear endpoint. Restarting safely
     * drops its RAM cache. Disk paths/database are never implicitly deleted.
     */
    suspend fun clearMemoryCache(context: Context): OperationResult = withContext(Dispatchers.IO) {
        controlMutex.withLock { clearMemoryCacheLocked(context) }
    }

    private suspend fun clearMemoryCacheLocked(context: Context): OperationResult {
        val current = probe()
        cacheOperationError(current)?.let { return OperationResult(false, it) }
        return restartLocked(context)
    }

    internal fun cacheOperationError(current: Status): String? = when {
        !current.isHealthy -> current.errorCode ?: "not_running"
        !current.owned -> "external_server"
        current.diskCacheEnabled == null -> "disk_cache_unknown"
        current.diskCacheEnabled -> "disk_cache_clear_unsupported"
        else -> null
    }

    internal fun restartOperationError(current: Status): String? = when {
        current.owned -> null // An unhealthy owned child must remain restartable.
        current.isHealthy && !current.owned -> "external_server"
        current.health == Health.INCOMPATIBLE -> current.errorCode ?: "unexpected_echo"
        else -> null
    }

    internal fun diskCacheSetting(settings: JSONObject): Boolean? =
        (settings.opt("UseDisk") as? Boolean)

    /**
     * Warms only an already-created local MatriX stream URL. This is deliberately
     * not a generic HTTP client and cannot reach LAN or internet hosts.
     */
    suspend fun prewarmStream(url: String, timeoutMs: Int = PREWARM_TIMEOUT_MS): Boolean =
        withContext(Dispatchers.IO) {
            if (!isSafeLocalStreamUrl(url)) return@withContext false
            var connection: HttpURLConnection? = null
            try {
                connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = timeoutMs.coerceIn(100, MAX_PREWARM_TIMEOUT_MS)
                connection.readTimeout = timeoutMs.coerceIn(100, MAX_PREWARM_TIMEOUT_MS)
                connection.useCaches = false
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Range", "bytes=0-0")
                connection.setRequestProperty("Accept", "*/*")
                val code = connection.responseCode
                if (code != HttpURLConnection.HTTP_PARTIAL && code != HttpURLConnection.HTTP_OK) {
                    return@withContext false
                }
                connection.inputStream.use { it.read() >= 0 }
            } catch (_: Exception) {
                false
            } finally {
                connection?.disconnect()
            }
        }

    internal fun isSafeLocalStreamUrl(value: String): Boolean = try {
        val parsed = URL(value)
        parsed.protocol.equals("http", ignoreCase = true) &&
            parsed.host == HOST && parsed.port == PORT &&
            parsed.userInfo == null && parsed.ref == null &&
            (parsed.path == "/stream" || parsed.path.startsWith("/stream/"))
    } catch (_: Exception) {
        false
    }

    suspend fun diagnostics(context: Context): Diagnostics = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val runtime = Runtime.getRuntime()
        val memoryInfo = ActivityManager.MemoryInfo()
        (appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memoryInfo)
        val storage = StatFs(appContext.noBackupFilesDir.absolutePath)
        Diagnostics(
            status = probe(), abi = selectedAbi(), port = PORT,
            appMemoryUsedBytes = runtime.totalMemory() - runtime.freeMemory(),
            appMemoryLimitBytes = runtime.maxMemory(),
            deviceMemoryAvailableBytes = memoryInfo.availMem,
            deviceMemoryLow = memoryInfo.lowMemory,
            storageFreeBytes = storage.availableBytes,
            storageTotalBytes = storage.totalBytes
        )
    }

    fun diagnosticReport(diagnostics: Diagnostics, appVersion: String): String = buildString {
        appendLine("LAMPA Matrix diagnostic report")
        appendLine("Report schema: 1")
        appendLine("App version: ${safeReportValue(appVersion)}")
        appendLine("Android API: ${Build.VERSION.SDK_INT}")
        appendLine("ABI: ${diagnostics.abi ?: "unsupported"}")
        appendLine("Endpoint: http://127.0.0.1:${diagnostics.port}")
        appendLine("Health: ${diagnostics.status.health.name}")
        appendLine("Owned process: ${diagnostics.status.owned}")
        appendLine("Version: ${diagnostics.status.version ?: "unknown"}")
        appendLine("Error code: ${diagnostics.status.errorCode ?: "none"}")
        appendLine("Cache limit: ${formatBytes(diagnostics.status.cacheSizeBytes)}")
        appendLine("Disk cache: ${diagnostics.status.diskCacheEnabled ?: "unknown"}")
        appendLine("App memory: ${formatBytes(diagnostics.appMemoryUsedBytes)} / ${formatBytes(diagnostics.appMemoryLimitBytes)}")
        appendLine("Device memory available: ${formatBytes(diagnostics.deviceMemoryAvailableBytes)}")
        appendLine("Device low-memory: ${diagnostics.deviceMemoryLow}")
        appendLine("App storage free: ${formatBytes(diagnostics.storageFreeBytes)} / ${formatBytes(diagnostics.storageTotalBytes)}")
        append("Privacy: no torrent links, hashes, titles, logs, credentials, LAN addresses or file paths included")
    }

    internal fun selectedAbi(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return null
        return Build.SUPPORTED_ABIS.firstOrNull(MATRIX_143_SHA256::containsKey)
    }

    internal fun isCompatibleServer(timeoutMs: Int = REQUEST_TIMEOUT_MS): Boolean = probe(timeoutMs).isHealthy

    private fun probe(timeoutMs: Int = REQUEST_TIMEOUT_MS): Status {
        if (!isSupported()) return Status(Health.UNSUPPORTED, errorCode = "unsupported")
        // Ownership is a process observation, independent of either HTTP endpoint.
        val owned = EmbeddedTorrServerService.hasOwnedProcess()
        val echo = request("$BASE_URL/echo", "GET", null, timeoutMs)
            ?: return Status(Health.STOPPED, errorCode = serviceStartFailure ?: EmbeddedTorrServerService.lastFailureCode() ?: "not_running", owned = owned)
        val version = echo.trim().takeIf { it.matches(MATRIX_VERSION_PATTERN) }
            ?: return Status(Health.INCOMPATIBLE, errorCode = "unexpected_echo", owned = owned)
        val settingsText = request("$BASE_URL/settings", "POST", "{\"action\":\"get\"}", timeoutMs)
            ?: return Status(Health.INCOMPATIBLE, version, errorCode = "settings_unavailable", owned = owned)
        return try {
            val settings = JSONObject(settingsText)
            if (!settings.has("CacheSize")) {
                Status(Health.INCOMPATIBLE, version, errorCode = "settings_incompatible", owned = owned)
            } else {
                Status(
                    Health.HEALTHY, version,
                    settings.optLong("CacheSize").takeIf { it > 0L },
                    diskCacheSetting(settings),
                    owned = EmbeddedTorrServerService.hasOwnedProcess()
                )
            }
        } catch (_: Exception) {
            Status(Health.INCOMPATIBLE, version, errorCode = "settings_invalid", owned = owned)
        }
    }

    internal fun startService(context: Context, action: String): Boolean {
        val appContext = context.applicationContext
        val intent = Intent(appContext, EmbeddedTorrServerService::class.java).apply { this.action = action }
        return try {
            val component = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) appContext.startForegroundService(intent)
            else appContext.startService(intent)
            if (component == null) {
                serviceStartFailure = "service_start_denied"
                false
            } else {
                serviceStartFailure = null
                true
            }
        } catch (_: IllegalStateException) {
            serviceStartFailure = "service_start_denied"
            false
        } catch (_: SecurityException) {
            serviceStartFailure = "service_start_denied"
            false
        }
    }

    private fun request(url: String, method: String, body: String?, timeoutMs: Int): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = method
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json, text/plain, */*")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.bufferedReader(Charsets.UTF_8).use(BufferedReader::readText)
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun safeReportValue(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._+()-]"), "_").take(80)

    private fun formatBytes(value: Long?): String {
        if (value == null || value < 0L) return "unknown"
        return String.format(Locale.US, "%.1f MiB", value.toDouble() / (1024.0 * 1024.0))
    }

    private val MATRIX_VERSION_PATTERN = Regex("MatriX\\.[A-Za-z0-9._-]{1,32}")
    private const val REQUEST_TIMEOUT_MS = 1_000
    private const val FAST_REQUEST_TIMEOUT_MS = 250
    private const val PREWARM_TIMEOUT_MS = 1_500
    private const val MAX_PREWARM_TIMEOUT_MS = 5_000
    private const val POLL_INTERVAL_MS = 250L
    private const val STARTUP_TIMEOUT_MS = 20_000L
    private const val STOP_TIMEOUT_MS = 10_000L
    private const val RESTART_SERVICE_SETTLE_MS = 1_000L
}
