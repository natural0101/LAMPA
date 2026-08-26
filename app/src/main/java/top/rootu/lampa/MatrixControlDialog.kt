package top.rootu.lampa

import android.content.Intent
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import top.rootu.lampa.helpers.getAppVersion
import top.rootu.lampa.torrent.EmbeddedTorrServerManager
import java.io.File

fun MainActivity.showMatrixControlDialog() {
    lifecycleScope.launch {
        val diagnostics = EmbeddedTorrServerManager.diagnostics(this@showMatrixControlDialog)
        val actions = arrayOf(
            getString(R.string.matrix_restart),
            getString(R.string.matrix_clear_cache),
            getString(R.string.matrix_check_connection),
            getString(R.string.matrix_diagnostics)
        )
        AlertDialog.Builder(this@showMatrixControlDialog)
            .setTitle(R.string.matrix_control_title)
            .setMessage(matrixStatusText(diagnostics.status))
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> runMatrixOperation(clearCache = false)
                    1 -> runMatrixOperation(clearCache = true)
                    2 -> showMatrixControlDialog()
                    3 -> showMatrixDiagnostics(diagnostics)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}

private fun MainActivity.runMatrixOperation(clearCache: Boolean) {
    App.toast(if (clearCache) R.string.matrix_cache_clearing else R.string.matrix_restarting, false)
    lifecycleScope.launch {
        val result = if (clearCache) {
            EmbeddedTorrServerManager.clearMemoryCache(this@runMatrixOperation)
        } else {
            EmbeddedTorrServerManager.restart(this@runMatrixOperation)
        }
        if (result.success) {
            App.toast(if (clearCache) R.string.matrix_cache_cleared else R.string.matrix_restart_ok)
            showMatrixControlDialog()
        } else {
            AlertDialog.Builder(this@runMatrixOperation)
                .setTitle(R.string.matrix_operation_failed)
                .setMessage(matrixErrorText(result.errorCode))
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }
}

private fun MainActivity.showMatrixDiagnostics(
    diagnostics: EmbeddedTorrServerManager.Diagnostics
) {
    val version = getAppVersion(this)?.versionName ?: "unknown"
    val report = EmbeddedTorrServerManager.diagnosticReport(diagnostics, version)
    AlertDialog.Builder(this)
        .setTitle(R.string.matrix_diagnostics)
        .setMessage(report)
        .setPositiveButton(R.string.matrix_export_report) { _, _ -> exportMatrixReport(report) }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}

private fun MainActivity.exportMatrixReport(report: String) {
    try {
        val reportFile = File(cacheDir, "lampa-matrix-diagnostics.txt")
        reportFile.writeText(report, Charsets.UTF_8)
        val reportUri = FileProvider.getUriForFile(
            this,
            "$packageName.update_provider",
            reportFile
        )
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, reportUri)
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.matrix_diagnostics))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, getString(R.string.matrix_export_report)))
    } catch (_: Exception) {
        App.toast(R.string.matrix_export_failed)
    }
}

private fun MainActivity.matrixStatusText(status: EmbeddedTorrServerManager.Status): String {
    val state = when (status.health) {
        EmbeddedTorrServerManager.Health.HEALTHY -> getString(R.string.matrix_health_ready)
        EmbeddedTorrServerManager.Health.STOPPED -> getString(R.string.matrix_health_stopped)
        EmbeddedTorrServerManager.Health.INCOMPATIBLE -> getString(R.string.matrix_health_incompatible)
        EmbeddedTorrServerManager.Health.UNSUPPORTED -> getString(R.string.matrix_health_unsupported)
    }
    return buildString {
        append(getString(R.string.matrix_status_line, state))
        append('\n')
        append(getString(R.string.matrix_version_line, status.version ?: getString(R.string.matrix_unknown)))
        append('\n')
        append(getString(R.string.matrix_endpoint_line, "127.0.0.1:8090"))
        status.errorCode?.let {
            append("\n\n")
            append(matrixErrorText(it))
        }
    }
}

private fun MainActivity.matrixErrorText(code: String?): String = when (code) {
    "unsupported", "abi_unsupported" -> getString(R.string.matrix_error_unsupported)
    "binary_missing", "binary_verification_failed" -> getString(R.string.matrix_error_binary)
    "data_directory_unavailable" -> getString(R.string.matrix_error_storage)
    "disk_cache_clear_unsupported" -> getString(R.string.matrix_error_disk_cache)
    "unexpected_echo", "settings_unavailable", "settings_incompatible", "settings_invalid" ->
        getString(R.string.matrix_error_port_occupied)
    "process_start_failed", "process_exited" -> getString(R.string.matrix_error_process)
    "start_timeout" -> getString(R.string.matrix_error_timeout)
    else -> getString(R.string.matrix_error_not_running)
}
