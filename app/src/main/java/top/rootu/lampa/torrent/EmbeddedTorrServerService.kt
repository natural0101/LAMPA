package top.rootu.lampa.torrent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.rootu.lampa.MainActivity
import top.rootu.lampa.R
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class EmbeddedTorrServerService : Service() {
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)
    private val starting = AtomicBoolean(false)
    private val automaticRecoveryAttempts = AtomicInteger(0)
    private val processLock = Any()

    @Volatile
    private var ownedProcess: Process? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var stopRequested = false
    private var generation = 0L
    private var recoveryScheduledForGeneration = -1L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(isReady = false))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            requestStop()
            serviceScope.launch {
                withContext(NonCancellable) {
                    stopOwnedProcess(GRACEFUL_STOP_TIMEOUT_MS)
                    finishService()
                }
            }
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_TEST_CRASH) {
            synchronized(processLock) { ownedProcess }?.destroy()
            return START_STICKY
        }

        automaticRecoveryAttempts.set(0)
        synchronized(processLock) { recoveryScheduledForGeneration = -1L }
        setLastFailure(null)
        startServerIfNeeded()
        return START_STICKY
    }

    override fun onDestroy() {
        // onDestroy is not guaranteed, but when it is called we must at least
        // signal the child immediately. The explicit Stop path waits gracefully.
        val process = synchronized(processLock) {
            stopRequested = true
            generation++
            val current = ownedProcess
            ownedProcess = null
            current
        }
        process?.destroy()
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startServerIfNeeded() {
        if (!starting.compareAndSet(false, true)) return
        var releaseStaleWakeLock = false
        val startGeneration = synchronized(processLock) {
            val current = ownedProcess
            if (current != null && isAlive(current)) {
                null
            } else {
                if (current != null) {
                    ownedProcess = null
                    releaseStaleWakeLock = true
                }
                stopRequested = false
                ++generation
            }
        }
        if (releaseStaleWakeLock) releaseWakeLock()
        if (startGeneration == null) {
            starting.set(false)
            return
        }

        serviceScope.launch {
            try {
                currentCoroutineContext().ensureActive()
                if (!isGenerationActive(startGeneration)) return@launch

                if (!EmbeddedTorrServerManager.isSupported()) {
                    setLastFailure("unsupported")
                    finishService()
                    return@launch
                }

                // A compatible server already listening on 8090 is deliberately
                // reused. Since no Process is stored, this service can never kill it.
                if (EmbeddedTorrServerManager.isCompatibleServer()) {
                    finishService()
                    return@launch
                }

                val binary = bundledBinary() ?: run {
                    setLastFailure("binary_missing")
                    finishService()
                    return@launch
                }

                val expectedHash = EmbeddedTorrServerManager.MATRIX_143_SHA256[
                    EmbeddedTorrServerManager.selectedAbi()
                ] ?: run {
                    setLastFailure("abi_unsupported")
                    finishService()
                    return@launch
                }
                if (!binary.isFile || sha256(binary) != expectedHash) {
                    setLastFailure("binary_verification_failed")
                    finishService()
                    return@launch
                }

                currentCoroutineContext().ensureActive()
                if (!isGenerationActive(startGeneration)) return@launch

                val root = File(noBackupFilesDir, "torrserver")
                val dataDir = File(root, "data")
                val logDir = File(root, "log")
                if ((!dataDir.exists() && !dataDir.mkdirs()) ||
                    (!logDir.exists() && !logDir.mkdirs())
                ) {
                    setLastFailure("data_directory_unavailable")
                    finishService()
                    return@launch
                }
                val logFile = File(logDir, "torrserver.log")

                val process = ProcessBuilder(
                    binary.absolutePath,
                    "-i", EmbeddedTorrServerManager.HOST,
                    "-p", EmbeddedTorrServerManager.PORT.toString(),
                    "--path", dataDir.absolutePath,
                    "--logpath", logFile.absolutePath
                ).apply {
                    redirectErrorStream(true)
                    environment()["GODEBUG"] = "madvdontneed=1"
                }.start()

                val accepted = synchronized(processLock) {
                    if (!stopRequested && generation == startGeneration && ownedProcess == null) {
                        ownedProcess = process
                        true
                    } else {
                        false
                    }
                }
                if (!accepted) {
                    withContext(NonCancellable) {
                        stopProcess(process, GRACEFUL_STOP_TIMEOUT_MS)
                    }
                    return@launch
                }

                acquireWakeLock()
                consumeProcessOutput(process, logFile)
                monitorProcess(process, startGeneration)

                if (!awaitOwnedServer(process, STARTUP_TIMEOUT_MS)) {
                    setLastFailure("start_timeout")
                    withContext(NonCancellable) {
                        stopOwnedProcess(GRACEFUL_STOP_TIMEOUT_MS)
                        if (automaticRecoveryAttempts.get() == 0) {
                            scheduleSingleAutomaticRecovery(startGeneration)
                        }
                    }
                } else if (isOwnedGeneration(process, startGeneration)) {
                    setLastFailure(null)
                    showReadyNotification()
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    stopOwnedProcess(GRACEFUL_STOP_TIMEOUT_MS)
                }
                throw cancelled
            } catch (_: Exception) {
                setLastFailure("process_start_failed")
                withContext(NonCancellable) {
                    stopOwnedProcess(GRACEFUL_STOP_TIMEOUT_MS)
                    scheduleSingleAutomaticRecovery(startGeneration)
                }
            } finally {
                starting.set(false)
            }
        }
    }

    private fun bundledBinary(): File? {
        val nativeLibraryDir = applicationInfo.nativeLibraryDir ?: return null
        return File(nativeLibraryDir, EmbeddedTorrServerManager.BINARY_NAME)
    }

    private fun consumeProcessOutput(process: Process, logFile: File) {
        serviceScope.launch {
            try {
                process.inputStream.use { input ->
                    FileOutputStream(logFile, true).use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (_: Exception) {
                // The log stream normally closes when the process is stopped.
            }
        }
    }

    private fun monitorProcess(process: Process, processGeneration: Long) {
        serviceScope.launch {
            try {
                process.waitFor()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                var unexpectedExit = false
                synchronized(processLock) {
                    if (ownedProcess === process && generation == processGeneration) {
                        ownedProcess = null
                        unexpectedExit = !stopRequested
                    }
                }
                if (unexpectedExit) {
                    setLastFailure("process_exited")
                    releaseWakeLock()
                    scheduleSingleAutomaticRecovery(processGeneration)
                }
            }
        }
    }

    private fun scheduleSingleAutomaticRecovery(failedGeneration: Long) {
        val duplicateForSameFailure = synchronized(processLock) {
            if (recoveryScheduledForGeneration == failedGeneration) true
            else {
                recoveryScheduledForGeneration = failedGeneration
                false
            }
        }
        if (duplicateForSameFailure) return
        if (!automaticRecoveryAttempts.compareAndSet(0, 1)) {
            finishService()
            return
        }
        serviceScope.launch {
            kotlinx.coroutines.delay(AUTOMATIC_RECOVERY_DELAY_MS)
            while (starting.get()) kotlinx.coroutines.delay(STARTUP_POLL_INTERVAL_MS)
            if (isGenerationActive(failedGeneration)) startServerIfNeeded()
            else finishService()
        }
    }

    private suspend fun awaitOwnedServer(process: Process, timeoutMs: Long): Boolean {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (!isAlive(process)) return false
            if (EmbeddedTorrServerManager.isCompatibleServer()) return true
            kotlinx.coroutines.delay(STARTUP_POLL_INTERVAL_MS)
        }
        return isAlive(process) && EmbeddedTorrServerManager.isCompatibleServer()
    }

    private fun stopOwnedProcess(timeoutMs: Long) {
        val process = synchronized(processLock) { ownedProcess } ?: return

        stopProcess(process, timeoutMs)
        synchronized(processLock) {
            if (ownedProcess === process) ownedProcess = null
        }
        releaseWakeLock()
    }

    private fun stopProcess(process: Process, timeoutMs: Long) {

        // Process.destroy() sends SIGTERM on Android. MatriX handles it and
        // closes the torrent engine/database when it is launched without -k.
        process.destroy()
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (isAlive(process) && android.os.SystemClock.elapsedRealtime() < deadline) {
            try {
                Thread.sleep(STOP_POLL_INTERVAL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        if (isAlive(process)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                process.destroyForcibly()
            } else {
                process.destroy()
            }
        }
    }

    private fun isAlive(process: Process): Boolean = try {
        process.exitValue()
        false
    } catch (_: IllegalThreadStateException) {
        true
    }

    private fun requestStop() {
        synchronized(processLock) {
            stopRequested = true
            generation++
        }
    }

    private fun isGenerationActive(expectedGeneration: Long): Boolean = synchronized(processLock) {
        !stopRequested && generation == expectedGeneration
    }

    private fun isOwnedGeneration(process: Process, expectedGeneration: Long): Boolean =
        synchronized(processLock) {
            !stopRequested && generation == expectedGeneration && ownedProcess === process
        }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun finishService() {
        releaseWakeLock()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    @Suppress("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:EmbeddedTorrServer"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "LAMPA TorrServer",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun showReadyNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(isReady = true))
    }

    private fun buildNotification(isReady: Boolean): android.app.Notification {
        val stopIntent = Intent(this, EmbeddedTorrServerService::class.java).apply {
            action = ACTION_STOP
        }
        val stopFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val stopPendingIntent = PendingIntent.getService(this, 1, stopIntent, stopFlags)

        val openIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val openFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val openPendingIntent = PendingIntent.getActivity(this, 2, openIntent, openFlags)

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.lampa_icon)
            .setContentTitle("LAMPA TorrServer")
            .setContentText(
                if (isReady) "MatriX.143 работает на 127.0.0.1:8090"
                else "MatriX.143 запускается"
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Остановить", stopPendingIntent)
            .build()
    }

    companion object {
        internal const val ACTION_START = "top.rootu.lampa.torrent.action.START"
        internal const val ACTION_STOP = "top.rootu.lampa.torrent.action.STOP"
        internal const val ACTION_TEST_CRASH = "top.rootu.lampa.torrent.action.TEST_CRASH"

        @Volatile
        private var lastFailure: String? = null

        internal fun lastFailureCode(): String? = lastFailure

        private fun setLastFailure(value: String?) {
            lastFailure = value
        }

        private const val NOTIFICATION_CHANNEL_ID = "lampa_embedded_torrserver"
        private const val NOTIFICATION_ID = 143
        private const val STARTUP_TIMEOUT_MS = 15_000L
        private const val STARTUP_POLL_INTERVAL_MS = 250L
        private const val AUTOMATIC_RECOVERY_DELAY_MS = 500L
        private const val GRACEFUL_STOP_TIMEOUT_MS = 7_000L
        private const val STOP_POLL_INTERVAL_MS = 100L
    }
}
