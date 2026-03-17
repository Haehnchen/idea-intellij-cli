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
        val serverName: String,
        val defaultPort: Int,
        val displayName: String
    ) {
        INTELLIJ_IDEA(setOf("IC", "IU", "IE"), "intellij-agent-cli", 8568, "IntelliJ IDEA"),
        ANDROID_STUDIO(setOf("AI"), "android-studio-agent-cli", 8569, "Android Studio"),
        PYCHARM(setOf("PC", "PY", "PE"), "pycharm-agent-cli", 8570, "PyCharm"),
        WEBSTORM(setOf("WS"), "webstorm-agent-cli", 8571, "WebStorm"),
        GOLAND(setOf("GO"), "goland-agent-cli", 8572, "GoLand"),
        PHPSTORM(setOf("PS"), "phpstorm-agent-cli", 8573, "PhpStorm"),
        RUBYMINE(setOf("RM"), "rubymine-agent-cli", 8574, "RubyMine"),
        CLION(setOf("CL"), "clion-agent-cli", 8575, "CLion"),
        RUSTROVER(setOf("RR"), "rustrover-agent-cli", 8576, "RustRover"),
        DATAGRIP(setOf("DB"), "datagrip-agent-cli", 8577, "DataGrip"),
        AQUA(setOf("QA"), "aqua-agent-cli", 8578, "Aqua"),
        DATASPELL(setOf("DS"), "dataspell-agent-cli", 8579, "DataSpell"),
        RIDER(setOf("RD"), "rider-agent-cli", 8580, "Rider"),
        UNKNOWN(emptySet(), "jetbrains-agent-cli", 8599, "JetBrains IDE");

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
     * Gets the detected IDE product.
     */
    fun detectIdeProduct(): IdeProduct = cachedProduct

    /**
     * Gets the IDE-specific server name (e.g., "intellij-agent-cli", "pycharm-agent-cli").
     */
    fun getServerName(): String = cachedProduct.serverName

    /**
     * Gets the IDE-specific default port.
     */
    fun getDefaultPort(): Int = cachedProduct.defaultPort

    /**
     * Gets the IDE display name.
     */
    fun getIdeDisplayName(): String = cachedProduct.displayName

    /**
     * Gets the raw product code from ApplicationInfo.
     */
    fun getProductCode(): String {
        return try {
            ApplicationInfo.getInstance().build.productCode
        } catch (e: Exception) {
            "UNKNOWN"
        }
    }
}
