package top.rootu.lampa.helpers

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject
import top.rootu.lampa.helpers.Prefs.APP_PREFERENCES
import top.rootu.lampa.helpers.Prefs.STORAGE_PREFERENCES
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
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

    data class LocalCopy(val file: File, val itemCount: Int, val sourcePackage: String, val checksum: String)

    fun hasDocumentPicker(context: Context, action: String): Boolean {
        if (Build.VERSION.SDK_INT < 19) return false
        return runCatching {
            val intent = Intent(action).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json")
            val activity = context.packageManager.resolveActivity(intent, 0)?.activityInfo
            activity != null && activity.enabled && !activity.packageName.contains("frameworkpackagestubs", true) &&
                !activity.name.contains("DocumentsStub", true)
        }.getOrDefault(false)
    }

    /** App-owned only; API 16-18 use internal storage without requesting broad permissions. */
    fun localDirectory(context: Context): File {
        val base = if (Build.VERSION.SDK_INT >= 19) context.getExternalFilesDir("Documents") else null
        return File(base ?: File(context.filesDir, "Documents"), "LAMPA")
    }

    fun exportLocal(context: Context): Result<LocalCopy> = runCatching {
        val directory = localDirectory(context)
        check(directory.isDirectory || directory.mkdirs()) { "Unable to create backup directory" }
        val file = File(directory, "LAMPA-settings-${System.currentTimeMillis()}-${java.util.UUID.randomUUID()}.json")
        val temporary = File(directory, file.name + ".tmp")
        try {
            val (document, _) = createDocument(context)
            temporary.outputStream().use { output ->
                output.write(document.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            check(temporary.renameTo(file)) { "Unable to commit settings file" }
            inspectLocal(context, file).getOrThrow()
        } catch (error: Exception) {
            temporary.delete()
            file.delete()
            throw error
        }
    }

    fun localFiles(context: Context): List<File> {
        val directory = localDirectory(context)
        if (!directory.exists()) return emptyList()
        check(directory.isDirectory) { "Backup directory is unavailable" }
        val files = directory.listFiles { file ->
            file.isFile && file.name.startsWith("LAMPA-settings-") && file.name.endsWith(".json")
        } ?: error("Unable to list saved settings files")
        return files.sortedByDescending { it.lastModified() }
    }

    fun inspectLocal(context: Context, file: File): Result<LocalCopy> = runCatching {
        require(file.canonicalFile.parentFile == localDirectory(context).canonicalFile) { "Invalid backup path" }
        val document = file.inputStream().use { readDocument(it) }
        val groups = decodeDocument(document)
        val payload = JSONObject(document.getString("payload"))
        LocalCopy(file, groups.values.sumOf { it.size }, payload.getString("source_package"), document.getString("sha256"))
    }

    fun exportToUri(context: Context, uri: Uri): Result<Int> = runCatching {
        val (document, itemCount) = createDocument(context)
        context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)
            ?.use { it.write(document) }
            ?: error("Unable to open the selected file")
        itemCount
    }

    private fun createDocument(context: Context): Pair<String, Int> {
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
        return document to itemCount
    }

    fun importFromUri(context: Context, uri: Uri): Result<ImportResult> = runCatching {
        val document = context.contentResolver.openInputStream(uri)?.use { readDocument(it) }
            ?: error("Unable to open the selected file")
        val decodedGroups = decodeDocument(document).toMutableMap()
        val sourcePackage = JSONObject(document.getString("payload")).getString("source_package")

        // These values describe a particular installation, not user settings.
        decodedGroups[DEFAULT_PREFERENCES] =
            decodedGroups.getValue(DEFAULT_PREFERENCES) - setOf("last_run_version", "migrate")

        val snapshots = groupNames.associateWith { preferences(context, it).all }
        var itemCount = 0
        try {
            groupNames.forEach { groupName ->
                val values = decodedGroups.getValue(groupName)
                check(replace(preferences(context, groupName), values)) { "Unable to save imported settings" }
                itemCount += values.size
            }
        } catch (error: Exception) {
            val restored = restoreGroups(context, snapshots)
            if (!restored) throw IllegalStateException("Import failed and native rollback was incomplete", error)
            throw error
        }
        ImportResult(itemCount, sourcePackage, snapshots)
    }

    private fun readDocument(input: InputStream): JSONObject {
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
        return JSONObject(output.toByteArray().toString(Charsets.UTF_8))
    }

    private fun decodeDocument(document: JSONObject): Map<String, Map<String, Any>> {
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
        return groupNames.associateWith { groupName ->
            decodeGroup(encodedGroups.getJSONObject(groupName))
        }
    }

    /** Restores the exact native preference snapshot captured before an import. */
    fun rollback(context: Context, imported: ImportResult): Boolean = runCatching {
        restoreGroups(context, imported.previousGroups)
    }.getOrDefault(false)

    private fun restoreGroups(context: Context, groups: Map<String, Map<String, *>>): Boolean {
        var success = true
        groupNames.forEach { name ->
            // Attempt every group even when an earlier commit fails.
            if (!runCatching { replace(preferences(context, name), groups.getValue(name)) }.getOrDefault(false)) success = false
        }
        return success
    }

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
