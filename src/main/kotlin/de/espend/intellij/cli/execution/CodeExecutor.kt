package de.espend.intellij.cli.execution

import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.net.URLClassLoader
import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.*
import kotlin.script.experimental.jvm.JvmDependency
import kotlin.script.experimental.jvm.baseClassLoader
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost

/**
 * Executes Kotlin code in the context of an IntelliJ project
 */
@Service(Service.Level.APP)
class CodeExecutor {

    @Serializable
    data class ExecutionResult(
        val success: Boolean,
        val output: String,
        val error: String?,
        val executionTimeMs: Long
    )

    private val scriptingHost = BasicJvmScriptingHost()
    private val platformClasspath by lazy { buildPlatformClasspath() }

    /**
     * Execute Kotlin code with access to IntelliJ APIs
     */
    suspend fun execute(
        project: Project?,
        code: String,
        timeoutSeconds: Long = 60
    ): ExecutionResult {
        val startTime = System.currentTimeMillis()
        val outputBuffer = StringWriter()
        val errorBuffer = StringWriter()

        try {
            // Wrap result in a Box so that null (Unit return) is distinguishable from
            // an actual timeout (withTimeoutOrNull returns null only on timeout).
            val result = withTimeoutOrNull(timeoutSeconds * 1000) {
                try {
                    Box(executeCode(project, code, outputBuffer))
                } catch (e: Exception) {
                    throw e
                }
            }

            val executionTime = System.currentTimeMillis() - startTime

            return if (result == null) {
                // Actual timeout
                ExecutionResult(
                    success = false,
                    output = outputBuffer.toString(),
                    error = "Execution timed out after ${timeoutSeconds}s",
                    executionTimeMs = executionTime
                )
            } else {
                ExecutionResult(
                    success = true,
                    output = outputBuffer.toString(),
                    error = null,
                    executionTimeMs = executionTime
                )
            }
        } catch (e: Exception) {
            val executionTime = System.currentTimeMillis() - startTime
            e.printStackTrace(PrintWriter(errorBuffer))
            return ExecutionResult(
                success = false,
                output = outputBuffer.toString(),
                error = errorBuffer.toString(),
                executionTimeMs = executionTime
            )
        }
    }

    private fun executeCode(project: Project?, code: String, output: StringWriter): Any? {
        val scriptContext = ExecutionScriptContext(
            project = project,
            application = ApplicationManager.getApplication(),
            output = output
        )
        val script = StringScriptSource(code)

        val result = scriptingHost.evalWithTemplate<ExecutionScriptTemplate>(
            script = script,
            compilation = {
                dependencies(JvmDependency(platformClasspath))
                defaultImports(
                    "com.intellij.openapi.application.*",
                    "com.intellij.openapi.command.*",
                    "com.intellij.openapi.project.*",
                    "com.intellij.openapi.vfs.*",
                    "com.intellij.psi.*"
                )
            },
            evaluation = {
                constructorArgs(scriptContext)
                jvm {
                    baseClassLoader(javaClass.classLoader)
                }
            }
        )

        return when (result) {
            is ResultWithDiagnostics.Success -> unwrapResult(result.value)
            is ResultWithDiagnostics.Failure -> throw ScriptExecutionException(renderDiagnostics(result.reports))
        }
    }

    private fun unwrapResult(result: EvaluationResult): Any? {
        return when (val returnValue = result.returnValue) {
            is ResultValue.Value -> {
                // Scripts use println() for output - ignore return values to avoid
                // unexpected output when script ends with expressions like readAction { }
                if (returnValue.value == Unit) null else null
            }
            is ResultValue.Unit -> null
            is ResultValue.Error -> throw returnValue.error
            ResultValue.NotEvaluated -> null
        }
    }

    private fun renderDiagnostics(reports: List<ScriptDiagnostic>): String {
        val relevantReports = reports.filter {
            it.severity == ScriptDiagnostic.Severity.WARNING ||
                it.severity == ScriptDiagnostic.Severity.ERROR ||
                it.severity == ScriptDiagnostic.Severity.FATAL
        }
        val reportsToRender = if (relevantReports.isNotEmpty()) relevantReports else reports
        return reportsToRender.joinToString("\n") { it.render(withStackTrace = true) }
    }

    companion object {
        val instance: CodeExecutor
            get() = service()
    }

    /**
     * Builds the compilation classpath for scripts.
     *
     * Iterate all loaded plugins via PluginManagerCore to collect the complete IntelliJ
     * + plugin classpath. kotlin-* JARs are excluded because BasicJvmScriptingHost
     * bundles its own kotlin-stdlib; adding a second copy causes ambiguous method errors.
     */
    private fun buildPlatformClasspath(): List<File> {
        val files = linkedSetOf<File>()

        fun addFile(file: File?) {
            if (file != null && file.exists() && !file.name.startsWith("kotlin-")) {
                files += file.canonicalFile
            }
        }

        fun addClassLoaderJars(classLoader: ClassLoader?) {
            var current = classLoader
            while (current != null) {
                try {
                    @Suppress("UNCHECKED_CAST")
                    val paths = current.javaClass.getMethod("getFiles").invoke(current) as? List<*>
                    paths?.forEach { entry ->
                        when (entry) {
                            is java.nio.file.Path -> addFile(entry.toFile())
                            is File -> addFile(entry)
                        }
                    }
                } catch (_: Exception) {
                    if (current is URLClassLoader) {
                        current.urLs.forEach { url ->
                            addFile(runCatching { File(url.toURI()) }.getOrNull())
                        }
                    }
                }
                current = current.parent
            }
        }

        // Iterate all loaded plugins to collect the complete IntelliJ + plugin classpath.
        // This ensures all platform JARs (including util-8.jar and others not reachable via the plugin
        // classloader chain) are available for script compilation.
        com.intellij.ide.plugins.PluginManagerCore.plugins.forEach { descriptor ->
            addClassLoaderJars(descriptor.pluginClassLoader)
        }

        return files.toList()
    }

}

private class Box(val value: Any?)

private class ScriptExecutionException(message: String) : RuntimeException(message)

private class StringScriptSource(override val text: String) : SourceCode {
    override val name: String = "intellij-agent-cli.exec.kts"
    override val locationId: String = name
}

class ExecutionScriptContext(
    project: Project?,
    val application: Application,
    private val output: StringWriter
) {
    val project: Project = project ?: error("No active project available for this script")
    val projectOrNull: Project? = project

    fun readAction(action: () -> Any?): Any? {
        var result: Any? = null
        application.runReadAction {
            result = action()
        }
        return result
    }

    fun writeAction(action: () -> Any?): Any? {
        var result: Any? = null
        if (projectOrNull != null) {
            WriteCommandAction.runWriteCommandAction(projectOrNull) {
                result = action()
            }
        } else {
            application.runWriteAction {
                result = action()
            }
        }
        return result
    }

    fun println(message: Any?) {
        output.write((message?.toString() ?: "null") + "\n")
    }
}

internal object ExecutionScriptCompilationConfiguration : ScriptCompilationConfiguration() {
    private fun readResolve(): Any = ExecutionScriptCompilationConfiguration
}

@KotlinScript(
    fileExtension = "exec.kts",
    compilationConfiguration = ExecutionScriptCompilationConfiguration::class
)
@Suppress("unused") // Script API bindings — used at runtime by executed scripts
abstract class ExecutionScriptTemplate(private val ctx: ExecutionScriptContext) {
    val project: Project
        get() = ctx.project

    val projectOrNull: Project?
        get() = ctx.projectOrNull

    val application: Application
        get() = ctx.application

    fun readAction(action: () -> Any?): Any? = ctx.readAction(action)

    fun writeAction(action: () -> Any?): Any? = ctx.writeAction(action)

    fun println(message: Any?) = ctx.println(message)
}
