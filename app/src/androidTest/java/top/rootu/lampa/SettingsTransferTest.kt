package top.rootu.lampa

import android.content.Context
import android.net.Uri
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import top.rootu.lampa.helpers.Prefs
import top.rootu.lampa.helpers.SettingsTransfer
import java.io.File

@RunWith(AndroidJUnit4::class)
class SettingsTransferTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun exportThenImport_restoresAllPreferenceTypesAndRejectsTampering() {
        val token = "settings_transfer_test"
        val settings = context.getSharedPreferences(Prefs.APP_PREFERENCES, Context.MODE_PRIVATE)
        val storage = context.getSharedPreferences(Prefs.STORAGE_PREFERENCES, Context.MODE_PRIVATE)
        val defaults = PreferenceManager.getDefaultSharedPreferences(context)
        val lastPlayed = context.getSharedPreferences("last_played", Context.MODE_PRIVATE)
        val file = File(context.cacheDir, "settings-transfer-test.json")

        try {
            assertTrue(
                settings.edit()
                    .putString("${token}_string", "value")
                    .putInt("${token}_int", 7)
                    .putLong("${token}_long", 9L)
                    .putFloat("${token}_float", 1.5f)
                    .putBoolean("${token}_boolean", true)
                    .putStringSet("${token}_set", setOf("a", "b"))
                    .commit()
            )
            storage.edit().putString(token, "storage-value").commit()
            defaults.edit().putString(token, "default-value").commit()
            lastPlayed.edit().putLong(token, 1234L).commit()

            SettingsTransfer.exportToUri(context, Uri.fromFile(file)).getOrThrow()
            settings.edit().remove("${token}_string").putString("${token}_target_only", "old").commit()
            storage.edit().remove(token).putString("${token}_target_only", "old").commit()
            defaults.edit().remove(token).putString("${token}_target_only", "old").commit()
            lastPlayed.edit().remove(token).putString("${token}_target_only", "old").commit()

            val imported = SettingsTransfer.importFromUri(context, Uri.fromFile(file)).getOrThrow()
            assertEquals(context.packageName, imported.sourcePackage)
            assertEquals("value", settings.getString("${token}_string", null))
            assertEquals(7, settings.getInt("${token}_int", 0))
            assertEquals(9L, settings.getLong("${token}_long", 0L))
            assertEquals(1.5f, settings.getFloat("${token}_float", 0f))
            assertTrue(settings.getBoolean("${token}_boolean", false))
            assertEquals(setOf("a", "b"), settings.getStringSet("${token}_set", emptySet()))
            assertEquals("storage-value", storage.getString(token, null))
            assertEquals("default-value", defaults.getString(token, null))
            assertEquals(1234L, lastPlayed.getLong(token, 0L))
            assertFalse(settings.contains("${token}_target_only"))
            assertFalse(storage.contains("${token}_target_only"))
            assertFalse(defaults.contains("${token}_target_only"))
            assertFalse(lastPlayed.contains("${token}_target_only"))

            assertTrue(SettingsTransfer.rollback(context, imported))
            assertFalse(settings.contains("${token}_string"))
            assertFalse(storage.contains(token))
            assertFalse(defaults.contains(token))
            assertFalse(lastPlayed.contains(token))
            assertEquals("old", settings.getString("${token}_target_only", null))
            assertEquals("old", storage.getString("${token}_target_only", null))
            assertEquals("old", defaults.getString("${token}_target_only", null))
            assertEquals("old", lastPlayed.getString("${token}_target_only", null))

            val document = JSONObject(file.readText())
            document.put("payload", document.getString("payload") + " ")
            file.writeText(document.toString())
            assertTrue(SettingsTransfer.importFromUri(context, Uri.fromFile(file)).isFailure)
        } finally {
            settings.edit()
                .remove("${token}_string")
                .remove("${token}_int")
                .remove("${token}_long")
                .remove("${token}_float")
                .remove("${token}_boolean")
                .remove("${token}_set")
                .remove("${token}_target_only")
                .commit()
            storage.edit().remove(token).remove("${token}_target_only").commit()
            defaults.edit().remove(token).remove("${token}_target_only").commit()
            lastPlayed.edit().remove(token).remove("${token}_target_only").commit()
            file.delete()
        }
    }
}
