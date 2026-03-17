# IntelliJ Agent CLI

Programmatically control IntelliJ IDEA (and other JetBrains IDEs) via a CLI and HTTP API. Useful for automation, scripting, and integrations with AI assistants like Claude Code.

The **IDE plugin** starts an HTTP server inside your JetBrains IDE. The **CLI binary** talks to that server, letting you run commands and Kotlin scripts that have full access to the IntelliJ API.

---

## Installation

### Step 1 — Install the IDE Plugin

The plugin adds the HTTP server to your JetBrains IDE.

**Option A: Install from disk (recommended)**

1. Build the plugin ZIP:
   ```bash
   ./gradlew buildPlugin
   ```
   The ZIP is output to `build/distributions/`.

2. In your IDE: **Settings → Plugins → ⚙ gear icon → Install Plugin from Disk…**

3. Select the ZIP and restart the IDE.

**After installing**, enable the server:

> **Settings → Tools → Agent CLI** — toggle the server on.

The server is **disabled by default** and starts on the IDE-specific port (see [Ports](#ide-specific-ports)).

---

### Step 2 — Install the CLI Binary

The CLI is a Go binary that sends commands to the plugin's HTTP server.

**Build from source:**

```bash
cd cli && go build -o intellij-cli
```

**Install to `~/.local/bin` (so it's on your PATH):**

Or build and install

```bash
make install
```

This runs `go build` and copies the binary to `~/.local/bin/intellij-cli`. Make sure `~/.local/bin` is in your `$PATH`:

```bash
# Add to ~/.bashrc or ~/.zshrc if needed
export PATH="$HOME/.local/bin:$PATH"
```

**Verify the install:**

```bash
intellij-cli health
```

### Step 3 — Install the AI agent skill (optional)

If you use Claude Code or another agent that supports skills, install the skill so the agent knows how to use the CLI:

```bash
intellij-cli skill project-claude   # this project only (.claude/skills/)
intellij-cli skill global-claude    # all projects    (~/.claude/skills/)
```

For other agents (non-Claude):

```bash
intellij-cli skill project          # this project only (.agents/skills/)
intellij-cli skill global           # all projects    (~/.agents/skills/)
```

---

## Usage

```bash
intellij-cli health              # Check server status
intellij-cli projects            # List open projects
intellij-cli plugins            # List installed plugins
intellij-cli exec -c "println(project.name)"   # Execute inline Kotlin code
intellij-cli exec -f <script>    # Execute a .kt script
intellij-cli actions             # List available action scripts
```

**Options:**

| Flag | Default | Description |
|------|---------|-------------|
| `-P, --port` | `8568` | HTTP port to connect to |
| `-t, --timeout` | `60` | Request timeout in seconds |

---

## Writing Custom Scripts

Scripts run inside the IDE with full access to IntelliJ APIs. Available bindings:

```kotlin
project        // Current IntelliJ project
application    // IntelliJ Application instance
readAction { } // Run code in a read action
writeAction { } // Run code in a write action
println(msg)   // Print output back to the CLI
```

Example — print all open files:

```kotlin
readAction {
    val fm = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
    fm.openFiles.forEach { println(it.path) }
}
```

Run it inline:

```bash
intellij-cli exec -c "readAction { com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFiles.forEach { println(it.path) } }"
```

---

## Multiple IDEs / Projects

The IDE and project are auto-detected from the current working directory. Nothing extra needed when only one IDE or project is open. If auto-detection fails, the default fallback port is **8568** (IntelliJ IDEA).

If multiple IDEs or projects are running, pass the port or project path explicitly:

```bash
intellij-cli -P 8571 action diagnostics file=src/Foo.kt
intellij-cli action diagnostics project=/path/to/project file=src/Foo.kt
```

Each JetBrains IDE listens on its own port: IntelliJ IDEA 8568, Android Studio 8569, PyCharm 8570, WebStorm 8571, GoLand 8572, PhpStorm 8573, RubyMine 8574, CLion 8575, RustRover 8576, DataGrip 8577, Aqua 8578, DataSpell 8579, Rider 8580. Unrecognized IDEs fall back to **8599**.

---

## API Endpoints

The plugin exposes a REST API that the CLI talks to. You can also call it directly:

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/health` | GET | Server health check |
| `/projects` | GET | List open projects |
| `/execute` | POST | Execute Kotlin code |
| `/action/discover` | POST | Discover available actions |

---

## Example Agent Run

```
can look add the inspection warning / error in resources/META-INF/plugin.xml with the intellij tool skill

● Skill(intellij-cli)
  ⎿  Successfully loaded skill

● Let me run diagnostics on the plugin.xml file and read it simultaneously.

● Let me run diagnostics on the plugin.xml file.

● Bash(intellij-cli action diagnostics file=resources/META-INF/plugin.xml)
  ⎿  Executing: (embedded)/actions/project/diagnostics.kt

     Execution time: 1449 ms
     … +4 lines (ctrl+o to expand)

● Searched for 1 pattern (ctrl+o to expand)

● Bash(intellij-cli action diagnostics file=src/main/resources/META-INF/plugin.xml)
  ⎿  Executing: (embedded)/actions/project/diagnostics.kt

     Execution time: 803 ms
     … +12 lines (ctrl+o to expand)

● Here are the 5 issues found:

  Errors (2):
  - Line 8:11 — Must not contain template text 'IntelliJ'
  - Line 46:10 — Service class annotated with '@Service' must not be registered in the 'plugin.xml' file

  Warnings (3):
  - Line 47:10 — Service can be converted to a light service
  - Line 48:10 — Service can be converted to a light service
  - Line 56:26 — Extract displayName for i18n

  Want me to read the file and fix these issues?
```