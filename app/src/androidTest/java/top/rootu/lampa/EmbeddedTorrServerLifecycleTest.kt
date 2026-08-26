package top.rootu.lampa

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import top.rootu.lampa.torrent.EmbeddedTorrServerManager
import top.rootu.lampa.torrent.EmbeddedTorrServerService
import java.net.InetSocketAddress
import java.net.Socket

@RunWith(AndroidJUnit4::class)
class EmbeddedTorrServerLifecycleTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun prepare() {
        if (Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.POST_NOTIFICATIONS
            )
        }
        if (!isPortClosed()) requestStop()
        assertTrue("Port 8090 was occupied before the test", waitForPort(closed = true, 10_000L))
        // Port close precedes Service.onDestroy; do not overlap the next START
        // with teardown from the previous test method.
        SystemClock.sleep(1_000L)
    }

    @Test
    fun rapidStopDuringStartupDoesNotLeaveOrphanServer() {
        repeat(5) {
            EmbeddedTorrServerManager.start(context)
            SystemClock.sleep(20L)
            requestStop()
            SystemClock.sleep(200L)
        }

        assertTrue(
            "MatriX still listened on port 8090 after rapid STOP",
            waitForPort(closed = true, 12_000L)
        )
        assertFalse(EmbeddedTorrServerManager.isCompatibleServer(250))
    }

    @Test
    fun ownedServerStartsHealthyAndStopsCleanly() = runBlocking {
        EmbeddedTorrServerManager.start(context)
        assertTrue(
            "Embedded MatriX did not become healthy",
            EmbeddedTorrServerManager.awaitReady(30_000L)
        )

        requestStop()
        assertTrue(
            "Owned MatriX did not release port 8090 after STOP",
            waitForPort(closed = true, 12_000L)
        )
    }

    @Test
    fun firstUnexpectedExitIsAutomaticallyRecovered() = runBlocking {
        EmbeddedTorrServerManager.start(context)
        assertTrue(EmbeddedTorrServerManager.awaitReady(30_000L))

        sendServiceAction(EmbeddedTorrServerService.ACTION_TEST_CRASH)
        assertTrue(
            "MatriX did not recover after its first unexpected exit",
            waitForHealthyAfterObservedStop(30_000L)
        )

    }

    @Test
    fun prewarmRejectsEveryNonLocalStreamUrl() = runBlocking {
        assertTrue(
            EmbeddedTorrServerManager.isSafeLocalStreamUrl(
                "http://127.0.0.1:8090/stream?link=magnet%3Aexample&index=1"
            )
        )
        listOf(
            "https://127.0.0.1:8090/stream",
            "http://localhost:8090/stream",
            "http://127.0.0.1:8091/stream",
            "http://user@127.0.0.1:8090/stream",
            "http://127.0.0.1:8090/settings",
            "https://example.com/stream"
        ).forEach { url ->
            assertFalse("Unsafe prewarm URL accepted: $url", EmbeddedTorrServerManager.isSafeLocalStreamUrl(url))
            assertFalse(EmbeddedTorrServerManager.prewarmStream(url, 100))
        }
    }

    @Test
    fun diagnosticReportContainsNoPrivateRuntimePaths() = runBlocking {
        EmbeddedTorrServerManager.start(context)
        assertTrue(EmbeddedTorrServerManager.awaitReady(30_000L))
        val report = EmbeddedTorrServerManager.diagnosticReport(
            EmbeddedTorrServerManager.diagnostics(context),
            "1.12.9/private path"
        )
        assertTrue(report.contains("Endpoint: http://127.0.0.1:8090"))
        assertTrue(report.contains("Health: HEALTHY"))
        assertFalse(report.contains(context.noBackupFilesDir.absolutePath))
        assertFalse(report.contains("private path"))
    }

    private fun requestStop() {
        sendServiceAction(EmbeddedTorrServerService.ACTION_STOP)
    }

    private fun sendServiceAction(serviceAction: String) {
        val intent = Intent(context, EmbeddedTorrServerService::class.java).apply { action = serviceAction }
        ContextCompat.startForegroundService(context, intent)
    }

    private fun waitForHealthyAfterObservedStop(timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var observedStop = false
        do {
            if (isPortClosed()) observedStop = true
            if (observedStop && EmbeddedTorrServerManager.isCompatibleServer(500)) return true
            SystemClock.sleep(50L)
        } while (SystemClock.elapsedRealtime() < deadline)
        return false
    }

    private fun waitForPort(closed: Boolean, timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        do {
            if (isPortClosed() == closed) return true
            SystemClock.sleep(100L)
        } while (SystemClock.elapsedRealtime() < deadline)
        return isPortClosed() == closed
    }

    private fun isPortClosed(): Boolean = try {
        Socket().use { socket ->
            socket.connect(
                InetSocketAddress(
                    EmbeddedTorrServerManager.HOST,
                    EmbeddedTorrServerManager.PORT
                ),
                250
            )
        }
        false
    } catch (_: Exception) {
        true
    }
}
