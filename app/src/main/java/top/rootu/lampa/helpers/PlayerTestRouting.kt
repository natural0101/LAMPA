package top.rootu.lampa.helpers

import top.rootu.lampa.BuildConfig

/** Test transport selection only; the configured Just Player intent protocol stays unchanged. */
object PlayerTestRouting {
    fun protocolPackage(playerPackage: String): String =
        if (BuildConfig.BUILD_TYPE == "nextTest" &&
            BuildConfig.testPlayerPackage.isNotEmpty() &&
            playerPackage == BuildConfig.testPlayerPackage
        ) "com.brouken.player" else playerPackage
}
