// Action: Show a notification balloon in IntelliJ
// Usage: intellij-cli action notify message="Hello World"
//        intellij-cli action notify title="Deploy done" message="All tests passed" type="information"

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.util.IconLoader

// --- Configure ---
val title: String   = "intellij-cli"           // notification title
val message: String = "Hello from intellij-cli!" // notification message
val type: String    = "information"             // information, warning, error
// -----------------

val notificationType = when (type.lowercase()) {
    "warning" -> NotificationType.WARNING
    "error"   -> NotificationType.ERROR
    else      -> NotificationType.INFORMATION
}

val notification = Notification(
    "intellij-agent-cli",
    title,
    message,
    notificationType
)

application.invokeLater {
    Notifications.Bus.notify(notification, project)
}

println("Notification sent: [$type] $title — $message")
