package top.rootu.lampa

import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

/** Fail before any test can clear prefs, launch a player, or bind the real TorrServe port. */
class IsolatedTestRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle?) {
        check(targetContext.packageName == "top.rootu.lampa.nexttest") {
            "Instrumentation is permitted only against top.rootu.lampa.nexttest"
        }
        val safeClasses = setOf(
            "top.rootu.lampa.SettingsTransferTest",
            "top.rootu.lampa.NextEpisodeJustPlayerTest",
            "top.rootu.lampa.MatrixControlsTest"
        )
        val requested = arguments?.getString("class")?.split(',')?.filter { it.isNotBlank() }.orEmpty()
        check(requested.isNotEmpty() && requested.all { it.substringBefore('#') in safeClasses }) {
            "Specify explicit safe test classes; server lifecycle tests are excluded from this runner"
        }
        super.onCreate(arguments)
    }
}
