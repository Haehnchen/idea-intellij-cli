package de.espend.intellij.cli.update

import com.intellij.ide.BrowserUtil
import com.intellij.ide.plugins.PluginInstaller
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.ide.plugins.PluginNode
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.text.VersionComparatorUtil
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class AvailablePluginUpdate(
    val installedVersion: String,
    val release: PluginRelease
)

sealed interface PluginUpdateOutcome {
    data class UpToDate(val installedVersion: String, val latestVersion: String) : PluginUpdateOutcome
    data class Scheduled(val installedVersion: String, val newVersion: String) : PluginUpdateOutcome
    data class AlreadyScheduled(val version: String) : PluginUpdateOutcome
}

@Service(Service.Level.APP)
class PluginUpdateService : Disposable {

    private val startupCheckScheduled = AtomicBoolean(false)
    private val updateInProgress = AtomicBoolean(false)
    private val scheduledVersion = AtomicReference<String?>(null)
    private var startupCheckFuture: ScheduledFuture<*>? = null

    fun scheduleStartupCheck(project: Project) {
        if (!startupCheckScheduled.compareAndSet(false, true)) return

        startupCheckFuture = AppExecutorUtil.getAppScheduledExecutorService().schedule({
            if (ApplicationManager.getApplication().isDisposed) return@schedule

            runCatching { checkForUpdate() }
                .onSuccess { update ->
                    if (update != null) {
                        invokeLater { showUpdateAvailableNotification(project, update) }
                    }
                }
                .onFailure { LOG.info("Could not check for an Agent CLI plugin update", it) }
        }, STARTUP_CHECK_DELAY_SECONDS, TimeUnit.SECONDS)
    }

    fun getInstalledVersion(): String = getInstalledDescriptor()?.version ?: "unknown"

    fun checkForUpdate(): AvailablePluginUpdate? {
        val installedVersion = getInstalledVersion()
        if (installedVersion == "unknown") {
            throw IllegalStateException("Could not determine the installed Agent CLI plugin version.")
        }

        val release = GitHubPluginReleaseClient.findLatestPluginRelease()
        return if (VersionComparatorUtil.compare(release.version, installedVersion) > 0) {
            AvailablePluginUpdate(installedVersion, release)
        } else {
            null
        }
    }

    fun updateInBackground(
        project: Project? = null,
        knownUpdate: AvailablePluginUpdate? = null,
        progressCallback: (String) -> Unit = {},
        completion: (Result<PluginUpdateOutcome>) -> Unit = {}
    ) {
        if (!updateInProgress.compareAndSet(false, true)) {
            invokeLater {
                completion(Result.failure(IllegalStateException("An Agent CLI plugin update is already running.")))
            }
            return
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Updating Agent CLI plugin", false) {
            override fun run(indicator: ProgressIndicator) {
                val result = runCatching {
                    performUpdate(knownUpdate, indicator, progressCallback)
                }
                updateInProgress.set(false)
                invokeLater { completion(result) }
            }
        })
    }

    override fun dispose() {
        startupCheckFuture?.cancel(false)
        startupCheckFuture = null
    }

    private fun performUpdate(
        knownUpdate: AvailablePluginUpdate?,
        indicator: ProgressIndicator,
        progressCallback: (String) -> Unit
    ): PluginUpdateOutcome {
        progressCallback("Checking the latest GitHub release...")
        indicator.text = "Checking the latest GitHub release..."

        val installedDescriptor = getInstalledDescriptor()
            ?: throw IllegalStateException("Could not find the installed Agent CLI plugin.")
        val installedVersion = installedDescriptor.version
        val release = knownUpdate?.release ?: GitHubPluginReleaseClient.findLatestPluginRelease()

        scheduledVersion.get()?.let { version ->
            if (VersionComparatorUtil.compare(version, release.version) >= 0) {
                return PluginUpdateOutcome.AlreadyScheduled(version)
            }
        }

        if (VersionComparatorUtil.compare(release.version, installedVersion) <= 0) {
            return PluginUpdateOutcome.UpToDate(installedVersion, release.version)
        }

        progressCallback("Downloading ${release.assetName}...")
        indicator.text = "Downloading ${release.assetName}..."

        var archive: Path? = null
        var installationScheduled = false
        try {
            archive = GitHubPluginReleaseClient.download(
                release = release,
                targetDirectory = PathManager.getTempDir(),
                indicator = indicator,
                progressCallback = progressCallback
            )

            progressCallback("Scheduling Agent CLI ${release.version} for installation...")
            indicator.text = "Scheduling Agent CLI ${release.version} for installation..."

            val descriptor = PluginNode(PLUGIN_ID, installedDescriptor.name, release.version).apply {
                downloadUrl = release.downloadUrl
            }
            PluginInstaller.installAfterRestart(
                descriptor,
                archive,
                installedDescriptor.pluginPath,
                true
            )
            installationScheduled = true
            scheduledVersion.set(release.version)

            return PluginUpdateOutcome.Scheduled(installedVersion, release.version)
        } finally {
            if (!installationScheduled && archive != null) {
                Files.deleteIfExists(archive)
            }
        }
    }

    private fun showUpdateAvailableNotification(project: Project, update: AvailablePluginUpdate) {
        val notification = createNotification(
            title = "Agent CLI plugin update available",
            content = "Version ${update.release.version} is available. Installed: ${update.installedVersion}.",
            type = NotificationType.INFORMATION
        )
        notification.addAction(NotificationAction.createSimpleExpiring("Update now") {
            updateInBackground(
                project = project.takeUnless { it.isDisposed },
                knownUpdate = update,
                completion = { result -> showUpdateResultNotification(project, result, update.release) }
            )
        })
        notification.addAction(NotificationAction.createSimpleExpiring("View release") {
            BrowserUtil.browse(update.release.releasePageUrl)
        })
        notification.notify(project.takeUnless { it.isDisposed })
    }

    private fun showUpdateResultNotification(
        project: Project,
        result: Result<PluginUpdateOutcome>,
        release: PluginRelease
    ) {
        result.onSuccess { outcome ->
            when (outcome) {
                is PluginUpdateOutcome.Scheduled -> {
                    val notification = createNotification(
                        title = "Agent CLI update ready",
                        content = "Version ${outcome.newVersion} will be installed after restarting the IDE.",
                        type = NotificationType.INFORMATION
                    )
                    if (ApplicationManager.getApplication().isRestartCapable) {
                        notification.addAction(NotificationAction.createSimpleExpiring("Restart IDE") {
                            ApplicationManager.getApplication().restart()
                        })
                    }
                    notification.notify(project.takeUnless { it.isDisposed })
                }

                is PluginUpdateOutcome.UpToDate -> createNotification(
                    title = "Agent CLI is up to date",
                    content = "Installed version: ${outcome.installedVersion}.",
                    type = NotificationType.INFORMATION
                ).notify(project.takeUnless { it.isDisposed })

                is PluginUpdateOutcome.AlreadyScheduled -> createNotification(
                    title = "Agent CLI update already scheduled",
                    content = "Version ${outcome.version} will be installed after restarting the IDE.",
                    type = NotificationType.INFORMATION
                ).notify(project.takeUnless { it.isDisposed })
            }
        }.onFailure { error ->
            LOG.warn("Agent CLI plugin update failed", error)
            val notification = createNotification(
                title = "Agent CLI update failed",
                content = error.message ?: "Unknown error",
                type = NotificationType.ERROR
            )
            notification.addAction(NotificationAction.createSimpleExpiring("View releases") {
                BrowserUtil.browse(release.releasePageUrl)
            })
            notification.notify(project.takeUnless { it.isDisposed })
        }
    }

    private fun createNotification(title: String, content: String, type: NotificationType): Notification {
        return NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(title, content, type)
    }

    private fun getInstalledDescriptor() = PluginManagerCore.getPlugin(PLUGIN_ID)

    private fun invokeLater(block: () -> Unit) {
        val application = ApplicationManager.getApplication()
        if (!application.isDisposed) {
            application.invokeLater(block, ModalityState.any())
        }
    }

    companion object {
        private val LOG = Logger.getInstance(PluginUpdateService::class.java)
        private val PLUGIN_ID = PluginId.getId("de.espend.intellij.cli")
        private const val NOTIFICATION_GROUP_ID = "Agent CLI Updates"
        private const val STARTUP_CHECK_DELAY_SECONDS = 15L

        val instance: PluginUpdateService
            get() = service()
    }
}
