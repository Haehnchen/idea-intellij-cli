package de.espend.intellij.cli.util

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.zip.ZipInputStream

object BinaryInstaller {

    const val RELEASES_PAGE_URL = "https://github.com/Haehnchen/idea-intellij-cli/releases"
    private const val GITHUB_API_LATEST = "https://api.github.com/repos/Haehnchen/idea-intellij-cli/releases/latest"

    fun getOs(): String {
        val name = System.getProperty("os.name").lowercase()
        return when {
            name.contains("linux") -> "linux"
            name.contains("mac") || name.contains("darwin") -> "darwin"
            name.contains("win") -> "windows"
            else -> throw IOException("Unsupported OS: ${System.getProperty("os.name")}")
        }
    }

    fun getArch(): String {
        val arch = System.getProperty("os.arch").lowercase()
        return when (arch) {
            "amd64", "x86_64" -> "amd64"
            "aarch64", "arm64" -> "arm64"
            else -> throw IOException("Unsupported architecture: $arch")
        }
    }

    fun getBinaryName(os: String = getOs()): String = if (os == "windows") "intellij-cli.exe" else "intellij-cli"

    fun getAssetName(os: String = getOs(), arch: String = getArch()): String = "intellij-cli-$os-$arch.zip"

    fun getInstallDir(): File {
        val home = System.getProperty("user.home")
        return File(home, ".local/bin")
    }

    fun getInstalledBinaryPath(): File = File(getInstallDir(), getBinaryName())

    /**
     * Fetches the browser_download_url for the latest release asset matching our OS/arch.
     */
    private fun getLatestDownloadUrl(assetName: String): String {
        val conn = URI(GITHUB_API_LATEST).toURL().openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000

        if (conn.responseCode != 200) {
            throw IOException("GitHub API returned ${conn.responseCode}: ${conn.responseMessage}")
        }

        val json = conn.inputStream.bufferedReader().readText()

        // Find the asset object by name, then locate its browser_download_url.
        // GitHub API lists name before browser_download_url within each asset object.
        val nameIndex = json.indexOf("\"$assetName\"")
        if (nameIndex == -1) throw IOException("No release asset named '$assetName' found in latest release.\nVisit $RELEASES_PAGE_URL for available downloads.")

        val urlPattern = """"browser_download_url"\s*:\s*"([^"]+)"""".toRegex()
        val match = urlPattern.find(json, nameIndex)
            ?: throw IOException("No download URL found for asset '$assetName'")

        return match.groupValues[1]
    }

    /**
     * Downloads and installs the binary. Calls [progressCallback] with status messages.
     * Returns the installed [File].
     */
    fun install(progressCallback: (String) -> Unit = {}): File {
        val os = getOs()
        val arch = getArch()
        val assetName = getAssetName(os, arch)
        val binaryName = getBinaryName(os)

        progressCallback("Fetching latest release info from GitHub...")
        val downloadUrl = getLatestDownloadUrl(assetName)

        progressCallback("Downloading $assetName...")
        val zipBytes = downloadBytes(downloadUrl)

        progressCallback("Extracting $binaryName from archive...")
        val binaryBytes = extractFromZip(zipBytes, binaryName)
            ?: throw IOException("Binary '$binaryName' was not found inside '$assetName'")

        val installDir = getInstallDir()
        if (!installDir.exists() && !installDir.mkdirs()) {
            throw IOException("Could not create install directory: ${installDir.absolutePath}")
        }

        val targetFile = File(installDir, binaryName)
        progressCallback("Writing to ${targetFile.absolutePath}...")
        targetFile.writeBytes(binaryBytes)

        if (os != "windows") {
            targetFile.setExecutable(true)
        }

        return targetFile
    }

    /**
     * Runs the installed binary with `--version` and returns its output.
     */
    fun checkVersion(): String {
        val binary = getInstalledBinaryPath()
        if (!binary.exists()) {
            throw IOException("Binary not found at ${binary.absolutePath}\nUse 'Install Binary' to download it first.")
        }

        val process = ProcessBuilder(binary.absolutePath, "--version")
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText().trim()
        val exitCode = process.waitFor()

        if (exitCode != 0 && output.isEmpty()) {
            throw IOException("Binary exited with code $exitCode and produced no output")
        }

        return output.ifEmpty { "(no output)" }
    }

    private fun downloadBytes(urlStr: String): ByteArray {
        val conn = URI(urlStr).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000

        if (conn.responseCode != 200) {
            throw IOException("Download failed: ${conn.responseCode} ${conn.responseMessage}")
        }

        val out = ByteArrayOutputStream()
        conn.inputStream.use { it.copyTo(out) }
        return out.toByteArray()
    }

    private fun extractFromZip(zipBytes: ByteArray, entryName: String): ByteArray? {
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                // Match exact name or last path segment (e.g. "bin/intellij-cli")
                if (entry.name == entryName || entry.name.substringAfterLast('/') == entryName) {
                    return zis.readBytes()
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return null
    }
}
