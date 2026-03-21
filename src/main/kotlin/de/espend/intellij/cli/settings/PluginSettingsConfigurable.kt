package de.espend.intellij.cli.settings

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.ui.JBColor
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.UIUtil
import de.espend.intellij.cli.util.BinaryInstaller
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

class PluginSettingsConfigurable : Configurable {

    private var panel: JPanel? = null
    private var enabledCheckBox: JBCheckBox? = null
    private var hintLabel: JBLabel? = null
    private var serverUrlLink: ActionLink? = null
    private var showExecutionIndicatorCheckBox: JBCheckBox? = null
    private var executionIndicatorHintLabel: JBLabel? = null

    private var installButton: JButton? = null
    private var installStatusLabel: JBLabel? = null
    private var checkVersionButton: JButton? = null
    private var checkVersionResultLabel: JBLabel? = null

    override fun getDisplayName(): String = "Agent CLI"

    override fun createComponent(): JComponent {
        enabledCheckBox = JBCheckBox("Enable Agent CLI Server")

        val serverUrl = PluginSettings.getInstance().getServerUrl()

        hintLabel = JBLabel("Server listening on ").apply {
            foreground = UIUtil.getContextHelpForeground()
            font = UIUtil.getLabelFont(UIUtil.FontSize.SMALL)
        }

        serverUrlLink = ActionLink(serverUrl) {
            BrowserUtil.browse(serverUrl)
        }.apply {
            font = UIUtil.getLabelFont(UIUtil.FontSize.SMALL)
        }

        val hintPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(hintLabel)
            add(serverUrlLink)
        }

        enabledCheckBox!!.addItemListener { updateHint() }

        showExecutionIndicatorCheckBox = JBCheckBox("Show background task indicator during script execution")

        executionIndicatorHintLabel = JBLabel("Displays a progress indicator in the IDE status bar while a script is running").apply {
            foreground = UIUtil.getContextHelpForeground()
            font = UIUtil.getLabelFont(UIUtil.FontSize.SMALL)
        }

        // --- Binary installer section ---

        val releasesLink = ActionLink("GitHub Releases") {
            BrowserUtil.browse(BinaryInstaller.RELEASES_PAGE_URL)
        }.apply {
            font = UIUtil.getLabelFont(UIUtil.FontSize.SMALL)
        }

        val installDir = BinaryInstaller.getInstallDir().absolutePath
        val installHintPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(JBLabel("Downloads the CLI binary for your OS/arch and installs it to $installDir — ").apply {
                foreground = UIUtil.getContextHelpForeground()
                font = UIUtil.getLabelFont(UIUtil.FontSize.SMALL)
            })
            add(releasesLink)
        }

        installButton = JButton("Install Binary from GitHub").apply {
            addActionListener { onInstall() }
        }

        installStatusLabel = JBLabel("").apply {
            foreground = UIUtil.getContextHelpForeground()
            font = UIUtil.getLabelFont(UIUtil.FontSize.SMALL)
        }

        checkVersionButton = JButton("Check Version").apply {
            addActionListener { onCheckVersion() }
        }

        val checkVersionHintLabel = JBLabel("Runs the installed binary with --version").apply {
            foreground = UIUtil.getContextHelpForeground()
            font = UIUtil.getLabelFont(UIUtil.FontSize.SMALL)
        }

        checkVersionResultLabel = JBLabel("").apply {
            foreground = UIUtil.getContextHelpForeground()
            font = UIUtil.getLabelFont(UIUtil.FontSize.SMALL)
        }

        panel = FormBuilder.createFormBuilder()
            .addComponent(enabledCheckBox!!)
            .addComponent(hintPanel)
            .addVerticalGap(8)
            .addComponent(showExecutionIndicatorCheckBox!!)
            .addComponent(executionIndicatorHintLabel!!)
            .addVerticalGap(16)
            .addComponent(TitledSeparator("intellij-cli Binary"))
            .addComponent(JBLabel("<html>The CLI binary is required to interact with the plugin from the terminal or AI agents.<br>Optionally, run <b>intellij-cli skill project</b> to generate a project skill file for your AI agent.</html>").apply {
                foreground = UIUtil.getContextHelpForeground()
                font = UIUtil.getLabelFont(UIUtil.FontSize.SMALL)
            })
            .addVerticalGap(6)
            .addComponent(installButton!!)
            .addComponent(installHintPanel)
            .addComponent(installStatusLabel!!)
            .addVerticalGap(8)
            .addComponent(checkVersionButton!!)
            .addComponent(checkVersionHintLabel)
            .addComponent(checkVersionResultLabel!!)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        updateHint()

        return panel!!
    }

    private fun invokeLater(block: () -> Unit) {
        ApplicationManager.getApplication().invokeLater(block, ModalityState.any())
    }

    private fun onInstall() {
        installButton?.isEnabled = false
        installStatusLabel?.foreground = UIUtil.getContextHelpForeground()
        installStatusLabel?.text = "Starting download..."

        ProgressManager.getInstance().run(object : Task.Backgroundable(null, "Installing intellij-cli binary", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                try {
                    val file = BinaryInstaller.install { msg ->
                        indicator.text = msg
                        invokeLater { installStatusLabel?.text = msg }
                    }
                    invokeLater {
                        installStatusLabel?.foreground = UIUtil.getContextHelpForeground()
                        installStatusLabel?.text = "Installed: ${file.absolutePath}"
                        installButton?.isEnabled = true
                    }
                } catch (e: Exception) {
                    invokeLater {
                        installStatusLabel?.foreground = JBColor.RED
                        installStatusLabel?.text = "Error: ${e.message}"
                        installButton?.isEnabled = true
                    }
                }
            }
        })
    }

    private fun onCheckVersion() {
        try {
            val version = BinaryInstaller.checkVersion()
            checkVersionResultLabel?.foreground = UIUtil.getContextHelpForeground()
            checkVersionResultLabel?.text = version
        } catch (e: Exception) {
            checkVersionResultLabel?.foreground = JBColor.RED
            checkVersionResultLabel?.text = e.message ?: "Unknown error"
        }
    }

    private fun updateHint() {
        val enabled = enabledCheckBox?.isSelected ?: false
        hintLabel?.text = if (enabled) "Server listening on " else "Server disabled, would listen on "
    }

    override fun isModified(): Boolean {
        val settings = PluginSettings.getInstance()
        return enabledCheckBox?.isSelected != settings.enabled
            || showExecutionIndicatorCheckBox?.isSelected != settings.showExecutionIndicator
    }

    override fun apply() {
        val settings = PluginSettings.getInstance()
        val oldEnabled = settings.enabled
        val newEnabled = enabledCheckBox?.isSelected ?: false

        settings.enabled = newEnabled
        settings.showExecutionIndicator = showExecutionIndicatorCheckBox?.isSelected ?: true

        if (oldEnabled != newEnabled) {
            val pluginService = de.espend.intellij.cli.IntellijAgentCliPlugin.instance
            if (newEnabled) {
                pluginService.startServer()
            } else {
                pluginService.stopServer()
            }
        }
    }

    override fun reset() {
        val settings = PluginSettings.getInstance()
        enabledCheckBox?.isSelected = settings.enabled
        showExecutionIndicatorCheckBox?.isSelected = settings.showExecutionIndicator
        updateHint()
    }

    override fun disposeUIResources() {
        panel = null
        enabledCheckBox = null
        hintLabel = null
        serverUrlLink = null
        showExecutionIndicatorCheckBox = null
        executionIndicatorHintLabel = null
        installButton = null
        installStatusLabel = null
        checkVersionButton = null
        checkVersionResultLabel = null
    }
}
