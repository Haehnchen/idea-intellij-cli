package de.espend.intellij.cli.update

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.util.text.VersionComparatorUtil
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

data class PluginRelease(
    val tag: String,
    val version: String,
    val assetName: String,
    val downloadUrl: String,
    val releasePageUrl: String
)

object GitHubPluginReleaseClient {

    const val RELEASES_PAGE_URL = "https://github.com/Haehnchen/idea-intellij-cli/releases"

    private const val GITHUB_ORIGIN = "https://github.com"
    private const val USER_AGENT = "idea-intellij-cli-plugin"
    private const val MAX_REDIRECTS = 5

    private val pluginAssetLinkPattern = Regex(
        """href\s*=\s*["']([^"']*?/Haehnchen/idea-intellij-cli/releases/download/[^"']+/intellij-agent-cli-([0-9][0-9A-Za-z._+\-]*)\.zip)["']"""
    )

    fun findLatestPluginRelease(): PluginRelease {
        val latestReleaseUrl = resolveLatestReleaseUrl()
        val tag = URI(latestReleaseUrl).path.substringAfterLast('/').ifBlank {
            throw IOException("GitHub returned an invalid latest release URL: $latestReleaseUrl")
        }
        val encodedTag = URLEncoder.encode(tag, StandardCharsets.UTF_8).replace("+", "%20")
        val assetsHtml = readText("$RELEASES_PAGE_URL/expanded_assets/$encodedTag")

        return parseLatestPluginRelease(tag, assetsHtml)
            ?: throw IOException("The latest GitHub release '$tag' does not contain an intellij-agent-cli-<version>.zip asset.")
    }

    fun download(
        release: PluginRelease,
        targetDirectory: Path,
        indicator: ProgressIndicator? = null,
        progressCallback: (String) -> Unit = {}
    ): Path {
        Files.createDirectories(targetDirectory)
        val target = Files.createTempFile(targetDirectory, "intellij-agent-cli-${release.version}-", ".zip")
        val connection = openConnection(release.downloadUrl, followRedirects = true, readTimeout = 120_000)

        try {
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("Plugin download failed: $responseCode ${connection.responseMessage}")
            }

            val totalBytes = connection.contentLengthLong
            indicator?.isIndeterminate = totalBytes <= 0
            var downloadedBytes = 0L
            var lastReportedMegabyte = -1L

            connection.inputStream.buffered().use { input ->
                Files.newOutputStream(target).buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                    while (true) {
                        indicator?.checkCanceled()
                        val read = input.read(buffer)
                        if (read < 0) break

                        output.write(buffer, 0, read)
                        downloadedBytes += read

                        if (totalBytes > 0) {
                            indicator?.fraction = downloadedBytes.toDouble() / totalBytes.toDouble()
                        }

                        val downloadedMegabytes = downloadedBytes / (1024 * 1024)
                        if (downloadedMegabytes != lastReportedMegabyte) {
                            lastReportedMegabyte = downloadedMegabytes
                            val message = if (totalBytes > 0) {
                                "Downloading ${release.assetName}: ${formatMegabytes(downloadedBytes)} / ${formatMegabytes(totalBytes)}"
                            } else {
                                "Downloading ${release.assetName}: ${formatMegabytes(downloadedBytes)}"
                            }
                            indicator?.text = message
                            progressCallback(message)
                        }
                    }
                }
            }

            if (downloadedBytes == 0L) {
                throw IOException("GitHub returned an empty plugin archive.")
            }

            return target
        } catch (t: Throwable) {
            Files.deleteIfExists(target)
            throw t
        } finally {
            connection.disconnect()
        }
    }

    private fun resolveLatestReleaseUrl(): String {
        var currentUrl = "$RELEASES_PAGE_URL/latest"

        repeat(MAX_REDIRECTS) {
            val connection = openConnection(currentUrl, followRedirects = false, readTimeout = 10_000)
            try {
                when (val responseCode = connection.responseCode) {
                    HttpURLConnection.HTTP_MOVED_PERM,
                    HttpURLConnection.HTTP_MOVED_TEMP,
                    HttpURLConnection.HTTP_SEE_OTHER,
                    307,
                    308 -> {
                        val location = connection.getHeaderField("Location")
                            ?: throw IOException("GitHub returned a redirect without a Location header.")
                        currentUrl = URI(currentUrl).resolve(location).toString()
                    }

                    HttpURLConnection.HTTP_OK -> return currentUrl
                    else -> throw IOException("Could not resolve the latest GitHub release: $responseCode ${connection.responseMessage}")
                }
            } finally {
                connection.disconnect()
            }
        }

        throw IOException("GitHub redirected more than $MAX_REDIRECTS times while resolving the latest release.")
    }

    private fun readText(url: String): String {
        val connection = openConnection(url, followRedirects = true, readTimeout = 30_000)
        try {
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("Could not read GitHub release assets: $responseCode ${connection.responseMessage}")
            }
            return connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: String, followRedirects: Boolean, readTimeout: Int): HttpURLConnection {
        return (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "text/html,application/octet-stream")
            instanceFollowRedirects = followRedirects
            connectTimeout = 10_000
            this.readTimeout = readTimeout
        }
    }

    private fun formatMegabytes(bytes: Long): String = "%.1f MB".format(bytes.toDouble() / (1024 * 1024))

    internal fun parseLatestPluginRelease(tag: String, assetsHtml: String): PluginRelease? {
        return pluginAssetLinkPattern.findAll(assetsHtml)
            .mapNotNull { match ->
                val href = match.groupValues[1].replace("&amp;", "&")
                val version = match.groupValues[2]
                val downloadUri = runCatching { URI(GITHUB_ORIGIN).resolve(href) }.getOrNull() ?: return@mapNotNull null
                if (downloadUri.scheme != "https" || downloadUri.host != "github.com") return@mapNotNull null

                PluginRelease(
                    tag = tag,
                    version = version,
                    assetName = "intellij-agent-cli-$version.zip",
                    downloadUrl = downloadUri.toString(),
                    releasePageUrl = "$RELEASES_PAGE_URL/tag/$tag"
                )
            }
            .maxWithOrNull { left, right -> VersionComparatorUtil.compare(left.version, right.version) }
    }
}
