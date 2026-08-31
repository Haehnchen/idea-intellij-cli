package de.espend.intellij.cli.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubPluginReleaseClientTest {

    @Test
    fun `extracts plugin version independently from release tag`() {
        val html = """
            <a href="/Haehnchen/idea-intellij-cli/releases/download/v0.0.9/intellij-agent-cli-0.0.7.zip">
                intellij-agent-cli-0.0.7.zip
            </a>
            <a href="/Haehnchen/idea-intellij-cli/releases/download/v0.0.9/intellij-cli-linux-amd64.zip">
                intellij-cli-linux-amd64.zip
            </a>
        """.trimIndent()

        val release = GitHubPluginReleaseClient.parseLatestPluginRelease("v0.0.9", html)

        requireNotNull(release)
        assertEquals("v0.0.9", release.tag)
        assertEquals("0.0.7", release.version)
        assertEquals("intellij-agent-cli-0.0.7.zip", release.assetName)
        assertEquals(
            "https://github.com/Haehnchen/idea-intellij-cli/releases/download/v0.0.9/intellij-agent-cli-0.0.7.zip",
            release.downloadUrl
        )
    }

    @Test
    fun `selects highest plugin version when a release contains multiple plugin assets`() {
        val html = """
            <a href="/Haehnchen/idea-intellij-cli/releases/download/v1/intellij-agent-cli-0.0.9.zip">old</a>
            <a href="/Haehnchen/idea-intellij-cli/releases/download/v1/intellij-agent-cli-0.0.10.zip">new</a>
        """.trimIndent()

        val release = GitHubPluginReleaseClient.parseLatestPluginRelease("v1", html)

        assertEquals("0.0.10", release?.version)
    }

    @Test
    fun `ignores releases without a plugin asset`() {
        val html = """
            <a href="/Haehnchen/idea-intellij-cli/releases/download/v2/intellij-cli-linux-amd64.zip">CLI</a>
        """.trimIndent()

        assertNull(GitHubPluginReleaseClient.parseLatestPluginRelease("v2", html))
    }
}
