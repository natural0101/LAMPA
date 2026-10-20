package top.rootu.lampa

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import top.rootu.lampa.torrent.EmbeddedTorrServerManager as Manager
import top.rootu.lampa.torrent.EmbeddedTorrServerService as Service

@RunWith(AndroidJUnit4::class)
class MatrixControlsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun statusAndAllActionsShareOneScrollableListAndKeepCorrectClickIndices() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val themed = ContextThemeWrapper(context, androidx.appcompat.R.style.Theme_AppCompat)
            val actions = arrayOf("Restart", "Clear cache", "Check connection", "Diagnostics")
            val clicks = mutableListOf<Int>()
            val dialog = createMatrixControlsDialog(themed, "MatriX.147\n127.0.0.1:8090", actions) { clicks += it }
            // Dialog.create installs AlertController content without launching an Activity or service.
            dialog.create()
            val list = dialog.listView
            assertNotNull("Actions must be installed in the real dialog content", list.parent)
            assertEquals(View.VISIBLE, list.visibility)
            assertEquals(1, list.headerViewsCount)
            assertEquals(5, list.adapter.count)
            assertFalse("Status must not be an actionable row", list.adapter.isEnabled(0))
            val header = list.adapter.getView(0, null, list) as TextView
            assertTrue(header.text.contains("MatriX.147"))
            actions.indices.forEach { index ->
                val row = list.adapter.getView(index + 1, null, list)
                assertTrue("Action row is selectable", list.adapter.isEnabled(index + 1))
                assertEquals(actions[index], (row as TextView).text.toString())
                list.performItemClick(row, index + 1, list.adapter.getItemId(index + 1))
            }
            assertEquals(listOf(0, 1, 2, 3), clicks)
            dialog.dismiss()
        }
    }

    @Test
    fun externalServerCannotReportRestartOrCacheClearSuccess() {
        val external = Manager.Status(Manager.Health.HEALTHY, "MatriX.147", diskCacheEnabled = false)
        assertEquals("external_server", Manager.restartOperationError(external))
        assertEquals("external_server", Manager.cacheOperationError(external))
        assertNull(Manager.restartOperationError(external.copy(owned = true)))
        assertNull(Manager.cacheOperationError(external.copy(owned = true)))
    }

    @Test
    fun restartCanRecoverOwnedProcessEvenWhenHealthEndpointsFail() {
        listOf("settings_unavailable", "settings_invalid", "settings_incompatible", "unexpected_echo").forEach { error ->
            val unhealthy = Manager.Status(Manager.Health.INCOMPATIBLE, errorCode = error, owned = true)
            assertNull("Owned child must remain restartable after $error", Manager.restartOperationError(unhealthy))
            assertEquals(error, Manager.restartOperationError(unhealthy.copy(owned = false)))
            assertEquals(error, Manager.cacheOperationError(unhealthy))
        }
        assertNull(Manager.restartOperationError(
            Manager.Status(Manager.Health.STOPPED, errorCode = "not_running", owned = true)
        ))
    }

    @Test
    fun missingNullAndMalformedDiskCacheSettingsStayUnknownAndPreventClearing() {
        listOf("{}", "{\"UseDisk\":null}", "{\"UseDisk\":\"false\"}", "{\"UseDisk\":0}").forEach {
            val disk = Manager.diskCacheSetting(JSONObject(it))
            assertNull(disk)
            assertEquals("disk_cache_unknown", Manager.cacheOperationError(
                Manager.Status(Manager.Health.HEALTHY, diskCacheEnabled = disk, owned = true)
            ))
        }
        assertEquals(false, Manager.diskCacheSetting(JSONObject("{\"UseDisk\":false}")))
        val disk = Manager.diskCacheSetting(JSONObject("{\"UseDisk\":true}"))
        assertEquals("disk_cache_clear_unsupported", Manager.cacheOperationError(
            Manager.Status(Manager.Health.HEALTHY, diskCacheEnabled = disk, owned = true)
        ))
    }

    @Test
    fun androidServiceStartRejectionsBecomeFailureInsteadOfEscaping() {
        listOf(SecurityException("denied"), IllegalStateException("background start denied")).forEach { failure ->
            val refusingContext = object : ContextWrapper(context) {
                override fun getApplicationContext(): Context = this
                override fun startForegroundService(service: Intent): ComponentName? = throw failure
                override fun startService(service: Intent): ComponentName? = throw failure
            }
            assertFalse(Manager.startService(refusingContext, Service.ACTION_START))
        }
    }
}
