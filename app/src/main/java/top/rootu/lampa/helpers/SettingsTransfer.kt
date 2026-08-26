package top.rootu.lampa.helpers

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject
import top.rootu.lampa.helpers.Prefs.APP_PREFERENCES
import top.rootu.lampa.helpers.Prefs.STORAGE_PREFERENCES
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * Portable settings transfer used between the regular and Matrix package IDs.
 *
 * Android sandboxes prevent one package from reading another package's preferences. A document
 * selected through Storage Access Framework is the explicit, user-controlled bridge.
 */
object SettingsTransfer {
    private const val FORMAT = "top.rootu.lampa.settings-transfer"
    private const val VERSION = 1
    private const val MAX_FILE_BYTES = 32 * 1024 * 1024
    private const val DEFAULT_PREFERENCES = "default"
    private const val LAST_PLAYED_PREFERENCES = "last_played"
    private val groupNames = listOf(
        APP_PREFERENCES,
        STORAGE_PREFERENCES,
        DEFAULT_PREFERENCES,
        LAST_PLAYED_PREFERENCES
    )

    data class ImportResult(
        val itemCount: Int,
        val sourcePackage: String,
        internal val previousGroups: Map<String, Map<String, *>>
    )

    fun exportToUri(context: Context, uri: Uri): Result<Int> = runCatching {
        val groups = JSONObject()
        var itemCount = 0
        groupNames.forEach { groupName ->
            val values = preferences(context, groupName).all
            itemCount += values.size
            groups.put(groupName, encodeGroup(values))
        }

        val payload = JSONObject()
            .put("source_package", context.packageName)
            .put("created_at", System.currentTimeMillis())
            .put("groups", groups)
            .toString()
        val document = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("payload", payload)
            .put("sha256", sha256(payload))
            .toString()

        check(document.toByteArray(Charsets.UTF_8).size <= MAX_FILE_BYTES) {
            "Settings file is too large"
        }
        context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)
            ?.use { it.write(document) }
            ?: error("Unable to open the selected file")
        itemCount
    }

    fun importFromUri(context: Context, uri: Uri): Result<ImportResult> = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                check(total <= MAX_FILE_BYTES) { "Settings file is too large" }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        } ?: error("Unable to open the selected file")

        val document = JSONObject(bytes.toString(Charsets.UTF_8))
        require(document.optString("format") == FORMAT) { "Unsupported settings file" }
        require(document.optInt("version", -1) == VERSION) { "Unsupported settings version" }
        val payloadText = document.getString("payload")
        require(document.optString("sha256").equals(sha256(payloadText), ignoreCase = true)) {
            "Settings file is damaged"
        }

        val payload = JSONObject(payloadText)
        val sourcePackage = payload.getString("source_package")
        require(
            sourcePackage == "top.rootu.lampa" ||
                sourcePackage.startsWith("top.rootu.lampa.")
        ) { "The file was created by another application" }

        val encodedGroups = payload.getJSONObject("groups")
        require(groupNames.all(encodedGroups::has)) { "Settings file is incomplete" }
        val decodedGroups = groupNames.associateWith { groupName ->
            decodeGroup(encodedGroups.getJSONObject(groupName))
        }.toMutableMap()

        // These values describe a particular installation, not user settings.
        decodedGroups[DEFAULT_PREFERENCES] =
            decodedGroups.getValue(DEFAULT_PREFERENCES) - setOf("last_run_version", "migrate")

        val snapshots = groupNames.associateWith { preferences(context, it).all }
        var itemCount = 0
        try {
            groupNames.forEach { groupName ->
                val values = decodedGroups.getValue(groupName)
                check(replace(preferences(context, groupName), values)) {
                    "Unable to save imported settings"
                }
                itemCount += values.size
            }
        } catch (error: Throwable) {
            groupNames.forEach { groupName ->
                replace(preferences(context, groupName), snapshots.getValue(groupName))
            }
            throw error
        }
        ImportResult(itemCount, sourcePackage, snapshots)
    }

    /** Restores the exact native preference snapshot captured before an import. */
    fun rollback(context: Context, imported: ImportResult): Boolean = runCatching {
        groupNames.all { groupName ->
            replace(preferences(context, groupName), imported.previousGroups.getValue(groupName))
        }
    }.getOrDefault(false)

    internal fun encodeGroup(values: Map<String, *>): JSONObject {
        val encoded = JSONObject()
        values.toSortedMap().forEach { (key, value) ->
            val item = JSONObject()
            when (value) {
                is String -> item.put("type", "string").put("value", value)
                is Int -> item.put("type", "int").put("value", value)
                is Long -> item.put("type", "long").put("value", value)
                is Float -> item.put("type", "float").put("value", value.toDouble())
                is Boolean -> item.put("type", "boolean").put("value", value)
                is Set<*> -> {
                    require(value.all { it is String }) { "Unsupported preference value" }
                    val array = JSONArray()
                    value.filterIsInstance<String>().sorted().forEach(array::put)
                    item.put("type", "string_set").put("value", array)
                }
                else -> error("Unsupported preference value")
            }
            encoded.put(key, item)
        }
        return encoded
    }

    internal fun decodeGroup(encoded: JSONObject): Map<String, Any> {
        val decoded = linkedMapOf<String, Any>()
        encoded.keys().forEach { key ->
            require(key.isNotBlank()) { "Invalid preference key" }
            val item = encoded.getJSONObject(key)
            decoded[key] = when (item.getString("type")) {
                "string" -> item.getString("value")
                "int" -> item.getInt("value")
                "long" -> item.getLong("value")
                "float" -> item.getDouble("value").toFloat()
                "boolean" -> item.getBoolean("value")
                "string_set" -> {
                    val array = item.getJSONArray("value")
                    buildSet { for (index in 0 until array.length()) add(array.getString(index)) }
                }
                else -> error("Unsupported preference type")
            }
        }
        return decoded
    }

    private fun preferences(context: Context, groupName: String): SharedPreferences =
        when (groupName) {
            DEFAULT_PREFERENCES -> PreferenceManager.getDefaultSharedPreferences(context)
            else -> context.getSharedPreferences(groupName, Context.MODE_PRIVATE)
        }

    private fun replace(preferences: SharedPreferences, values: Map<String, *>): Boolean {
        val editor = preferences.edit().clear()
        values.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                else -> error("Unsupported preference value")
            }
        }
        return editor.commit()
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
