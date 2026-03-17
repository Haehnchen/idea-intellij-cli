package de.espend.intellij.cli.util

import com.intellij.openapi.application.ApplicationInfo

/**
 * Provides IDE-specific information for server configuration.
 *
 * Each JetBrains IDE gets a unique server name and default port to avoid conflicts
 * when multiple IDEs are running simultaneously.
 *
 * Uses the public ApplicationInfo API to detect the current IDE product via
 * the build's product code (e.g., "IC", "IU", "PY", "WS").
 */
object IdeProductInfo {

    /**
     * IDE product types with their server names and default ports.
     *
     * Product codes reference:
     * - IC = IntelliJ IDEA Community
     * - IU = IntelliJ IDEA Ultimate
     * - IE = IntelliJ IDEA Educational
     * - PC = PyCharm Community
     * - PY = PyCharm Professional
     * - PE = PyCharm Educational
     * - WS = WebStorm
     * - GO = GoLand
     * - PS = PhpStorm
     * - RM = RubyMine
     * - CL = CLion
     * - RR = RustRover
     * - DB = DataGrip
     * - AI = Android Studio
     * - QA = Aqua
     * - DS = DataSpell
     * - RD = Rider
     */
    enum class IdeProduct(
        val productCodes: Set<String>,
        val defaultPort: Int,
    ) {
        INTELLIJ_IDEA(setOf("IC", "IU", "IE"), 8568),
        ANDROID_STUDIO(setOf("AI"), 8569),
        PYCHARM(setOf("PC", "PY", "PE"), 8570),
        WEBSTORM(setOf("WS"), 8571),
        GOLAND(setOf("GO"), 8572),
        PHPSTORM(setOf("PS"), 8573),
        RUBYMINE(setOf("RM"), 8574),
        CLION(setOf("CL"), 8575),
        RUSTROVER(setOf("RR"), 8576),
        DATAGRIP(setOf("DB"), 8577),
        AQUA(setOf("QA"), 8578),
        DATASPELL(setOf("DS"), 8579),
        RIDER(setOf("RD"), 8580),
        UNKNOWN(emptySet(), 8599);

        companion object {
            /**
             * Find the IdeProduct matching the given product code.
             */
            fun fromProductCode(code: String): IdeProduct {
                return entries.find { code in it.productCodes } ?: UNKNOWN
            }
        }
    }

    // Cached product detection (IDE doesn't change during runtime)
    private val cachedProduct: IdeProduct by lazy {
        detectIdeProductInternal()
    }

    /**
     * Detects the current IDE product using ApplicationInfo.
     * Uses the build's product code which is part of the public API.
     */
    private fun detectIdeProductInternal(): IdeProduct {
        return try {
            val productCode = ApplicationInfo.getInstance().build.productCode
            IdeProduct.fromProductCode(productCode)
        } catch (e: Exception) {
            IdeProduct.UNKNOWN
        }
    }

    /**
     * Gets the IDE-specific default port.
     */
    fun getDefaultPort(): Int = cachedProduct.defaultPort
}
