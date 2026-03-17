# IntelliJ Agent CLI

A system for programmatically controlling IntelliJ IDEA via a CLI and HTTP API.

## Build and Development Commands

### Building the Plugin

```bash
./gradlew clean buildPlugin
```

The distributable ZIP artifact will be in `build/distributions/`.

## Components

### 1. IntelliJ Plugin (`src/`)

Kotlin-based plugin that runs inside IntelliJ IDEA.

**Key files:**
- `server/HttpServer.kt` - Javalin HTTP server on port 8568
- `execution/CodeExecutor.kt` - Kotlin script engine for executing code
- `services/ProjectService.kt` - Project management utilities

**Endpoints:**
| Endpoint | Method | Description |
|----------|--------|-------------|
| `/health` | GET | Server health check |
| `/projects` | GET | List open projects |
| `/execute` | POST | Execute Kotlin code |
| `/action/discover` | POST | Discover available actions |

### 2. CLI (`cli/`)

Go-based command-line tool for interacting with the plugin via the Javalin

```bash
intellij-cli health              # Check server status
intellij-cli projects            # List open projects
intellij-cli exec -c "code"      # Execute Kotlin code
intellij-cli exec -f <script>    # Execute action script
intellij-cli actions             # List available scripts
```

**Options:**
- `-P, --port` - HTTP port (default: 8568)
- `-t, --timeout` - Request timeout in seconds (default: 60)

### 3. Action Scripts (`cli/actions/*`)

Kotlin scripts that can be executed via the CLI.

## CodeExecutor

Executes Kotlin code with IntelliJ context:

```kotlin
// Available bindings in scripts:
project      // Current IntelliJ project
application  // IntelliJ Application instance
readAction { }   // Run code in read action
writeAction { }  // Run code in write action
println(msg)     // Print output
```

Example:
```bash
# List projects
intellij-cli projects

# Execute inline code
intellij-cli exec -c "println(project.name)"

# Run an action script
intellij-cli exec -f plugins
```


## Decompiler Tools

For analyzing bundled plugins like Twig and PHP you MUST use **vineflower** and NOT **Fernflower** from IntelliJ (less quality):

**vineflower**

- **GitHub:** https://github.com/Vineflower/vineflower
- **Download:** https://repo1.maven.org/maven2/org/vineflower/vineflower/1.11.2/vineflower-1.11.2.jar
- **Local copy:** `decompiled/vineflower.jar`
- **Usage:** `java -jar vineflower.jar input.jar output/`

**Bundled Plugin JARs (for decompilation):**
- **Location:** `~/.gradle/caches/[gradle-version]/transforms/*/transformed/com.jetbrains.[plugin]-[intellij-version]/[plugin]/lib/[plugin].jar`
- **Example:** `~/.gradle/caches/9.3.0/transforms/*/transformed/com.jetbrains.twig-253.28294.322/twig/lib/twig.jar`
