package de.espend.intellij.cli.settings

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.UIUtil
import java.awt.FlowLayout
import javax.swing.JComponent
import javax.swing.JPanel

class PluginSettingsConfigurable : Configurable {

    private var panel: JPanel? = null
    private var enabledCheckBox: JBCheckBox? = null
    private var hintLabel: JBLabel? = null
    private var serverUrlLink: ActionLink? = null
    private var showExecutionIndicatorCheckBox: JBCheckBox? = null
    private var executionIndicatorHintLabel: JBLabel? = null

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

        enabledCheckBox!!.addItemListener {
            updateHint()
        }

        showExecutionIndicatorCheckBox = JBCheckBox("Show background task indicator during script execution")

        executionIndicatorHintLabel = JBLabel("Displays a progress indicator in the IDE status bar while a script is running").apply {
            foreground = UIUtil.getContextHelpForeground()
            font = UIUtil.getLabelFont(UIUtil.FontSize.SMALL)
        }

        panel = FormBuilder.createFormBuilder()
            .addComponent(enabledCheckBox!!)
            .addComponent(hintPanel)
            .addVerticalGap(8)
            .addComponent(showExecutionIndicatorCheckBox!!)
            .addComponent(executionIndicatorHintLabel!!)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        updateHint()

        return panel!!
    }

    private fun updateHint() {
        val enabled = enabledCheckBox?.isSelected ?: false
        if (enabled) {
            hintLabel?.text = "Server listening on "
        } else {
            hintLabel?.text = "Server disabled, would listen on "
        }
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
    }
}
