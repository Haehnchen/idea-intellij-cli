package de.espend.intellij.cli.server

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import de.espend.intellij.cli.execution.CodeExecutor
import de.espend.intellij.cli.services.ProjectService
import de.espend.intellij.cli.settings.PluginSettings
import io.javalin.Javalin
import io.javalin.http.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.CompletableFuture

/**
 * HTTP Server providing REST API for IntelliJ operations.
 *
 * Uses IDE-specific default ports to avoid conflicts when multiple IDEs run simultaneously.
 */
class HttpServer {

    private var app: Javalin? = null
    private var currentPort: Int = 0
    private var currentHost: String = PluginSettings.DEFAULT_SERVER_HOST

    @Serializable
    data class HealthResponse(
        val status: String,
        val version: String = "1.0.0",
        val port: Int
    )

    @Serializable
    data class ExecuteRequest(
        val project: String? = null,
        val code: String,
        val timeout: Long = 60
    )

    /**
     * Starts the HTTP server using settings from PluginSettings.
     */
    fun start() {
        if (app != null) {
            println("[intellij-agent-cli] Server already running on $currentHost:$currentPort")
            return
        }

        val settings = PluginSettings.getInstance()
        currentHost = settings.serverHost
        currentPort = settings.serverPort

        app = Javalin.create { config ->
            config.startup.showJavalinBanner = false
            config.http.maxRequestSize = 10_000_000L // 10MB

            // CORS headers for local development
            config.routes.before { ctx ->
                ctx.header("Access-Control-Allow-Origin", "*")
                ctx.header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
                ctx.header("Access-Control-Allow-Headers", "Content-Type")
            }

            // Routes
            config.routes.get("/") { ctx -> handleIndex(ctx) }
            config.routes.get("/health") { ctx -> handleHealth(ctx) }
            config.routes.get("/projects") { ctx -> handleProjects(ctx) }
            config.routes.post("/execute") { ctx -> handleExecute(ctx) }

            // Error handling
            config.routes.exception(Exception::class.java) { e, ctx ->
                ctx.status(500)
                ctx.json(mapOf(
                    "error" to (e.message ?: "Internal server error"),
                    "type" to e::class.simpleName
                ))
            }
        }

        app?.start(currentPort)
        println("[intellij-agent-cli] HTTP server started on $currentHost:$currentPort")
    }

    fun stop() {
        app?.stop()
        app = null
        println("[intellij-agent-cli] HTTP server stopped")
    }

    private fun handleIndex(ctx: Context) {
        ctx.contentType("text/plain")
        ctx.result("""
            Agent CLI Server

            GET  /health            Server health check
            GET  /projects          List open projects
            POST /execute           Execute Kotlin code
        """.trimIndent())
    }

    private fun handleHealth(ctx: Context) {
        ctx.json(HealthResponse(status = "ok", port = currentPort))
    }

    private fun handleProjects(ctx: Context) {
        val projectService = ProjectService.instance
        val projects = projectService.getOpenProjects()
        ctx.json(projects)
    }

    private fun handleExecute(ctx: Context) {
        val request = try {
            Json.decodeFromString<ExecuteRequest>(ctx.body())
        } catch (e: Exception) {
            ctx.status(400).json(mapOf("error" to "Invalid request: ${e.message}"))
            return
        }

        val projectService = ProjectService.instance
        val codeExecutor = CodeExecutor.instance

        // Find the target project by name or path
        val project = request.project?.let { identifier ->
            projectService.resolveProject(identifier)
        } ?: projectService.getActiveProject()

        if (project == null && request.project != null) {
            ctx.status(404).json(mapOf("error" to "Project not found: ${request.project}"))
            return
        }

        // Execute asynchronously to avoid blocking Javalin worker threads
        val resultFuture = CompletableFuture<CodeExecutor.ExecutionResult>()

        if (PluginSettings.getInstance().showExecutionIndicator) {
            ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Agent CLI: Executing script", false) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    try {
                        val result = kotlinx.coroutines.runBlocking {
                            codeExecutor.execute(project, request.code, request.timeout)
                        }
                        resultFuture.complete(result)
                    } catch (e: Exception) {
                        resultFuture.completeExceptionally(e)
                    }
                }
            })
        } else {
            CompletableFuture.supplyAsync {
                kotlinx.coroutines.runBlocking {
                    codeExecutor.execute(project, request.code, request.timeout)
                }
            }.thenAccept(resultFuture::complete)
                .exceptionally { e -> resultFuture.completeExceptionally(e); null }
        }

        ctx.future {
            resultFuture.thenAccept { result -> ctx.json(result) }
        }
    }

}
