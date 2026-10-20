package top.rootu.lampa.helpers

import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.rootu.lampa.App
import top.rootu.lampa.MainActivity
import top.rootu.lampa.R

fun MainActivity.showSettingsTransferExportOptions(onLocalExport: () -> Unit, onSafExport: () -> Unit) {
    showTransferOptions(Intent.ACTION_CREATE_DOCUMENT, R.string.transfer_export_local, onLocalExport, onSafExport)
}

fun MainActivity.showSettingsTransferImportOptions(onImport: (Uri) -> Unit, onSafImport: () -> Unit) {
    showTransferOptions(Intent.ACTION_OPEN_DOCUMENT, R.string.transfer_import_local, {
        showLocalSettingsCopies(onImport)
    }, onSafImport)
}

private fun MainActivity.showTransferOptions(action: String, localLabel: Int, local: () -> Unit, saf: () -> Unit) {
    val hasPicker = SettingsTransfer.hasDocumentPicker(this, action)
    val labels = if (hasPicker) arrayOf(getString(localLabel), getString(R.string.transfer_system_picker))
        else arrayOf(getString(localLabel))
    val builder = AlertDialog.Builder(this).setTitle(R.string.transfer_title)
        .setItems(labels) { _, selected -> if (selected == 0) local() else saf() }
        .setNegativeButton(android.R.string.cancel, null)
    if (!hasPicker) builder.setTitle(R.string.transfer_picker_unavailable)
    builder.show()
}

/** Call only after the activity has successfully synchronized WebView localStorage. */
fun MainActivity.exportSettingsToOwnedStorage(onImport: (Uri) -> Unit) {
    lifecycleScope.launch {
        val result = withContext(Dispatchers.IO) { SettingsTransfer.exportLocal(this@exportSettingsToOwnedStorage) }
        result.fold(onSuccess = { copy ->
            AlertDialog.Builder(this@exportSettingsToOwnedStorage)
                .setTitle(R.string.transfer_saved_verified)
                .setMessage(getString(R.string.transfer_saved_details, copy.file.absolutePath, copy.itemCount))
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.transfer_import_this) { _, _ -> confirmLocalSettingsCopy(copy, onImport) }
                .show()
        }, onFailure = { showSettingsTransferError(it) })
    }
}

private fun MainActivity.showLocalSettingsCopies(onImport: (Uri) -> Unit) {
    lifecycleScope.launch {
        val listed = withContext(Dispatchers.IO) {
            runCatching { SettingsTransfer.localFiles(this@showLocalSettingsCopies) }
        }
        if (listed.isFailure) {
            showSettingsTransferError(listed.exceptionOrNull()!!)
            return@launch
        }
        val files = listed.getOrThrow()
        if (files.isEmpty()) {
            AlertDialog.Builder(this@showLocalSettingsCopies).setTitle(R.string.transfer_title)
                .setMessage(getString(R.string.transfer_no_copies, SettingsTransfer.localDirectory(this@showLocalSettingsCopies).absolutePath))
                .setPositiveButton(android.R.string.ok, null).show()
            return@launch
        }
        AlertDialog.Builder(this@showLocalSettingsCopies).setTitle(R.string.transfer_choose_copy)
            .setItems(files.map { it.name }.toTypedArray()) { _, position ->
                lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) { SettingsTransfer.inspectLocal(this@showLocalSettingsCopies, files[position]) }
                    result.fold(onSuccess = { confirmLocalSettingsCopy(it, onImport) }, onFailure = { showSettingsTransferError(it) })
                }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }
}

private fun MainActivity.confirmLocalSettingsCopy(copy: SettingsTransfer.LocalCopy, onImport: (Uri) -> Unit) {
    AlertDialog.Builder(this).setTitle(R.string.transfer_import_confirm)
        .setMessage(getString(R.string.transfer_import_details, copy.file.absolutePath, copy.sourcePackage, copy.itemCount))
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(R.string.transfer_import_this) { _, _ ->
            lifecycleScope.launch {
                val checked = withContext(Dispatchers.IO) {
                    SettingsTransfer.inspectLocal(this@confirmLocalSettingsCopy, copy.file).mapCatching {
                        check(it.checksum == copy.checksum) { "Backup changed after selection" }
                        it
                    }
                }
                checked.fold(onSuccess = { onImport(Uri.fromFile(it.file)) }, onFailure = { showSettingsTransferError(it) })
            }
        }.show()
}

fun MainActivity.notifySettingsTransferCancelled() {
    App.toast(R.string.transfer_cancelled)
}

fun MainActivity.showSettingsTransferError(error: Throwable) {
    android.util.Log.e("SettingsTransfer", "Settings transfer failed", error)
    AlertDialog.Builder(this).setTitle(R.string.transfer_failed)
        .setMessage(R.string.transfer_failed_details).setPositiveButton(android.R.string.ok, null).show()
}
