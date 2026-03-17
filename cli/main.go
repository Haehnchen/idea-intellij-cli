package main

import (
	"bytes"
	"embed"
	"encoding/json"
	"fmt"
	"io"
	"io/fs"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/spf13/cobra"
)

//go:embed actions/*.kt actions/project/*.kt
var embeddedActions embed.FS

const (
	defaultPort       = 8568
	defaultTimeout    = 60
	discoveryTimeout  = 1 // seconds for IDE discovery calls
)

// idePort maps IDE display names to their default server ports.
// Must match IdeProductInfo.kt in the plugin.
var idePort = []struct {
	Name string
	Port int
}{
	{"IntelliJ IDEA", 8568},
	{"Android Studio", 8569},
	{"PyCharm", 8570},
	{"WebStorm", 8571},
	{"GoLand", 8572},
	{"PhpStorm", 8573},
	{"RubyMine", 8574},
	{"CLion", 8575},
	{"RustRover", 8576},
	{"DataGrip", 8577},
	{"Aqua", 8578},
	{"DataSpell", 8579},
	{"Rider", 8580},
	{"JetBrains IDE (unknown)", 8599},
}

// ideInstance represents a running IDE discovered via its HTTP server.
type ideInstance struct {
	Name     string
	Port     int
	Projects []map[string]interface{}
}

// discoverIDEs probes all known IDE ports in parallel and returns those that respond.
func discoverIDEs() []ideInstance {
	var mu sync.Mutex
	var results []ideInstance
	var wg sync.WaitGroup

	client := &http.Client{Timeout: time.Duration(discoveryTimeout) * time.Second}

	for _, ide := range idePort {
		wg.Add(1)
		go func(name string, p int) {
			defer wg.Done()
			url := fmt.Sprintf("http://127.0.0.1:%d/projects", p)
			resp, err := client.Get(url)
			if err != nil {
				return
			}
			defer resp.Body.Close()
			if resp.StatusCode != 200 {
				return
			}
			body, err := io.ReadAll(resp.Body)
			if err != nil {
				return
			}
			var projects []map[string]interface{}
			if err := json.Unmarshal(body, &projects); err != nil {
				return
			}
			mu.Lock()
			results = append(results, ideInstance{Name: name, Port: p, Projects: projects})
			mu.Unlock()
		}(ide.Name, ide.Port)
	}
	wg.Wait()
	return results
}

var (
	port         int
	portExplicit bool
	timeout      int
	project      string
	code         string
	codeFile     string
	defines      []string
)

func main() {
	var rootCmd = &cobra.Command{
		Use:               "intellij-cli",
		Short:             "CLI tool for IntelliJ Agent operations",
		Long:              `Command-line interface for interacting with IntelliJ IDEA via the Agent CLI plugin.`,
		CompletionOptions: cobra.CompletionOptions{DisableDefaultCmd: true},
		Run: func(cmd *cobra.Command, args []string) {
			cmd.Help()
			fmt.Println()
			runListActions(cmd, args)
		},
	}

	rootCmd.PersistentFlags().IntVarP(&port, "port", "P", defaultPort, "HTTP server port (optional, auto-discovered)")
	rootCmd.PersistentFlags().IntVarP(&timeout, "timeout", "t", defaultTimeout, "Request timeout in seconds")
	rootCmd.PersistentFlags().StringVarP(&project, "project", "p", "", "Project name or path (optional, auto-discovered)")
	rootCmd.PersistentPreRun = func(cmd *cobra.Command, args []string) {
		portExplicit = cmd.Flags().Changed("port")
	}

	// Health command
	var healthCmd = &cobra.Command{
		Use:   "health",
		Short: "Check server health",
		Run:   runHealth,
	}

	// Projects command
	var projectsCmd = &cobra.Command{
		Use:   "projects",
		Short: "List open projects",
		Run:   runProjects,
	}

	// Execute command
	var execCmd = &cobra.Command{
		Use:   "exec",
		Short: "Execute Kotlin code or action file",
		Long: `Execute Kotlin code in the IntelliJ context.

The code has access to:
  - project: The current IntelliJ project
  - application: The IntelliJ application instance
  - readAction { action() }: Run code in read action
  - writeAction { action() }: Run code in write action
  - println(message): Print output

Examples:
  intellij-cli exec -c "println(project.name)"
  intellij-cli exec -f plugins
  intellij-cli exec -f diagnostics file=src/main/kotlin/Foo.kt
  intellij-cli exec -f find_references file=src/Foo.kt line=42 column=15`,
		Args: cobra.ArbitraryArgs,
		Run:  runExec,
	}
	execCmd.Flags().StringVarP(&code, "code", "c", "", "Kotlin code to execute")
	execCmd.Flags().StringVarP(&codeFile, "file", "f", "", "Kotlin file to execute (relative to actions dir or absolute path)")

	// action command: list scripts (no args) or run a script directly
	var actionCmd = &cobra.Command{
		Use:   "action [name] [key=value ...]",
		Short: "List or run action scripts",
		Long: `List available action scripts, or run one directly.

Examples:
  intellij-cli action                                    list all actions
  intellij-cli action modules                            run project action
  intellij-cli action diagnostics file=src/Foo.kt        run with parameter
  intellij-cli action find_references file=src/Foo.kt line=42 column=15`,
		Args: cobra.ArbitraryArgs,
		Run:  runActionCmd,
	}

	// source command: show source code of an action script
	var sourceCmd = &cobra.Command{
		Use:   "source <action>",
		Short: "Show Kotlin action source code as examples for programmatically generating new actions or executing code",
		Args:  cobra.ExactArgs(1),
		Run:   runSource,
	}

	// skill command: generate SKILL.md for agent/Claude integration
	var skillCmd = &cobra.Command{
		Use:   "skill <target>",
		Short: "Generate a SKILL.md file for agent or Claude integration",
		Long: `Generate a SKILL.md file describing this CLI tool for AI agent integration.

Targets:
  print          Print SKILL.md to stdout
  project        .agents/skills/intellij-cli/SKILL.md
  global         ~/.agents/skills/intellij-cli/SKILL.md
  project-claude .claude/skills/intellij-cli/SKILL.md
  global-claude  ~/.claude/skills/intellij-cli/SKILL.md

Examples:
  intellij-cli skill print
  intellij-cli skill project
  intellij-cli skill global-claude`,
		Args:      cobra.MaximumNArgs(1),
		ValidArgs: []string{"print", "project", "global", "project-claude", "global-claude"},
		Run:       runSkill,
	}

	rootCmd.AddCommand(healthCmd, projectsCmd, execCmd, actionCmd, sourceCmd, skillCmd)

	if err := rootCmd.Execute(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func getBaseURL() string {
	return fmt.Sprintf("http://127.0.0.1:%d", port)
}

func makeRequest(method, path string, body interface{}) ([]byte, error) {
	client := &http.Client{
		Timeout: time.Duration(timeout) * time.Second,
	}

	var reqBody io.Reader
	if body != nil {
		jsonData, err := json.Marshal(body)
		if err != nil {
			return nil, fmt.Errorf("failed to marshal request: %w", err)
		}
		reqBody = bytes.NewReader(jsonData)
	}

	req, err := http.NewRequest(method, getBaseURL()+path, reqBody)
	if err != nil {
		return nil, fmt.Errorf("failed to create request: %w", err)
	}

	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}

	resp, err := client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("request failed: %w", err)
	}
	defer resp.Body.Close()

	respBody, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, fmt.Errorf("failed to read response: %w", err)
	}

	if resp.StatusCode >= 400 {
		return nil, fmt.Errorf("server error (status %d): %s", resp.StatusCode, string(respBody))
	}

	return respBody, nil
}

// getLocalActionsDir returns the project-local actions dir: .idea/intellij-cli/actions
func getLocalActionsDir() string {
	abs, _ := filepath.Abs(".idea/intellij-cli/actions")
	return abs
}

// getGlobalActionsDir returns the global actions dir: ~/.local/share/intellij-cli/actions
func getGlobalActionsDir() string {
	home, err := os.UserHomeDir()
	if err != nil {
		return ""
	}
	return filepath.Join(home, ".local", "share", "intellij-cli", "actions")
}

// resolveActionFile returns the content, display path and whether it is a project-level action.
// Search order: absolute/relative path → local (.idea/intellij-cli/actions) → global (~/.intellij-cli/actions) → embedded.
func resolveActionFile(name string) (content string, displayPath string, isProjectAction bool, err error) {
	fileName := name
	if !strings.HasSuffix(name, ".kt") {
		fileName = name + ".kt"
	}

	// 1. Absolute path
	if filepath.IsAbs(name) {
		data, e := os.ReadFile(name)
		if e != nil {
			return "", "", false, fmt.Errorf("file not found: %s", name)
		}
		return string(data), name, false, nil
	}

	// 2. Relative to current directory
	if _, e := os.Stat(name); e == nil {
		abs, _ := filepath.Abs(name)
		data, e := os.ReadFile(abs)
		if e != nil {
			return "", "", false, fmt.Errorf("failed to read: %s", abs)
		}
		return string(data), abs, false, nil
	}

	// 3. Check external action dirs (local then global) — these override embedded
	for _, dir := range []string{getLocalActionsDir(), getGlobalActionsDir()} {
		if dir == "" {
			continue
		}
		// Application-level
		if p := filepath.Join(dir, fileName); fileExists(p) {
			data, e := os.ReadFile(p)
			if e != nil {
				return "", "", false, fmt.Errorf("failed to read: %s", p)
			}
			return string(data), p, false, nil
		}
		// Project-level
		if p := filepath.Join(dir, "project", fileName); fileExists(p) {
			data, e := os.ReadFile(p)
			if e != nil {
				return "", "", false, fmt.Errorf("failed to read: %s", p)
			}
			return string(data), p, true, nil
		}
	}

	// 4. Embedded actions (built into binary)
	// Application-level
	if data, e := embeddedActions.ReadFile("actions/" + fileName); e == nil {
		return string(data), "(embedded)/actions/" + fileName, false, nil
	}
	// Project-level
	if data, e := embeddedActions.ReadFile("actions/project/" + fileName); e == nil {
		return string(data), "(embedded)/actions/project/" + fileName, true, nil
	}

	return "", "", false, fmt.Errorf("action not found: %s\nSearched:\n  %s\n  %s\n  (embedded)",
		name, getLocalActionsDir(), getGlobalActionsDir())
}

func fileExists(path string) bool {
	_, err := os.Stat(path)
	return err == nil
}

func runHealth(cmd *cobra.Command, args []string) {
	body, err := makeRequest("GET", "/health", nil)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Error: %v\n", err)
		os.Exit(1)
	}

	var result map[string]interface{}
	if err := json.Unmarshal(body, &result); err != nil {
		fmt.Fprintf(os.Stderr, "Failed to parse response: %v\n", err)
		os.Exit(1)
	}

	fmt.Printf("Status: %s\n", result["status"])
	fmt.Printf("Version: %s\n", result["version"])
	fmt.Printf("Port: %v\n", result["port"])
}

func runProjects(cmd *cobra.Command, args []string) {
	// If user explicitly set --port, only query that single port
	if portExplicit {
		body, err := makeRequest("GET", "/projects", nil)
		if err != nil {
			fmt.Fprintf(os.Stderr, "Error: %v\n", err)
			os.Exit(1)
		}

		var projects []map[string]interface{}
		if err := json.Unmarshal(body, &projects); err != nil {
			fmt.Fprintf(os.Stderr, "Failed to parse response: %v\n", err)
			os.Exit(1)
		}

		printProjectList(projects)
		return
	}

	// Discover all running IDEs and collect all projects
	ides := discoverIDEs()
	if len(ides) == 0 {
		fmt.Println("No running IDEs found")
		return
	}

	var allProjects []map[string]interface{}
	for _, ide := range ides {
		url := fmt.Sprintf("http://127.0.0.1:%d", ide.Port)
		for _, p := range ide.Projects {
			p["serverUrl"] = url
			allProjects = append(allProjects, p)
		}
	}

	printProjectList(allProjects)
}

func printProjectList(projects []map[string]interface{}) {
	if len(projects) == 0 {
		fmt.Println("No open projects found")
		return
	}

	for i, p := range projects {
		name := p["name"]
		basePath := p["basePath"]
		focused := p["hasFocus"]

		focusMarker := ""
		if focused == true {
			focusMarker = " [ACTIVE]"
		}

		fmt.Printf("%d. %s%s\n", i+1, name, focusMarker)
		if basePath != nil && basePath != "" {
			fmt.Printf("   Path: %s\n", basePath)
		}
		if serverUrl, ok := p["serverUrl"].(string); ok && serverUrl != "" {
			fmt.Printf("   Server: %s\n", serverUrl)
		}
	}
}

// stripValDeclarations removes top-level "val <key> = ..." lines from code
// for each key present in defines, so that the preamble can redefine them.
func stripValDeclarations(code string, defines []string) string {
	keys := make(map[string]bool, len(defines))
	for _, def := range defines {
		if idx := strings.IndexByte(def, '='); idx > 0 {
			keys[def[:idx]] = true
		}
	}
	lines := strings.Split(code, "\n")
	out := make([]string, 0, len(lines))
	for _, line := range lines {
		trimmed := strings.TrimSpace(line)
		skip := false
		if strings.HasPrefix(trimmed, "val ") {
			// Extract the identifier after "val "
			rest := strings.TrimPrefix(trimmed, "val ")
			ident := strings.FieldsFunc(rest, func(r rune) bool {
				return r == ' ' || r == ':' || r == '='
			})
			if len(ident) > 0 && keys[ident[0]] {
				skip = true
			}
		}
		if !skip {
			out = append(out, line)
		}
	}
	return strings.Join(out, "\n")
}

// injectAfterImports inserts preamble after the last import/comment/blank header
// line so that Kotlin's rule "imports must precede executable code" is respected.
func injectAfterImports(code, preamble string) string {
	lines := strings.Split(code, "\n")
	lastHeaderLine := -1
	for i, line := range lines {
		trimmed := strings.TrimSpace(line)
		if strings.HasPrefix(trimmed, "import ") ||
			strings.HasPrefix(trimmed, "//") ||
			trimmed == "" {
			lastHeaderLine = i
		} else {
			break
		}
	}
	insertAt := lastHeaderLine + 1
	result := make([]string, 0, len(lines)+4)
	result = append(result, lines[:insertAt]...)
	result = append(result, preamble)
	result = append(result, lines[insertAt:]...)
	return strings.Join(result, "\n")
}

func isIntLiteral(s string) bool {
	if len(s) == 0 {
		return false
	}
	start := 0
	if s[0] == '-' {
		start = 1
	}
	if start >= len(s) {
		return false
	}
	for _, c := range s[start:] {
		if c < '0' || c > '9' {
			return false
		}
	}
	return true
}

func buildDefinesPreamble(defines []string) (string, error) {
	if len(defines) == 0 {
		return "", nil
	}
	var sb strings.Builder
	for _, def := range defines {
		idx := strings.IndexByte(def, '=')
		if idx < 1 {
			return "", fmt.Errorf("invalid -D value %q: expected key=value", def)
		}
		key := def[:idx]
		val := def[idx+1:]
		// Emit as Int literal if the value is a plain integer, otherwise as a String
		if isIntLiteral(val) {
			sb.WriteString(fmt.Sprintf("val %s = %s\n", key, val))
		} else if val == "true" || val == "false" {
			sb.WriteString(fmt.Sprintf("val %s = %s\n", key, val))
		} else {
			escaped := strings.ReplaceAll(val, `\`, `\\`)
			escaped = strings.ReplaceAll(escaped, `"`, `\"`)
			sb.WriteString(fmt.Sprintf("val %s = \"%s\"\n", key, escaped))
		}
	}
	sb.WriteString("\n")
	return sb.String(), nil
}

// resolveIDE discovers running IDEs and sets the global port variable.
// If only one IDE is running, it selects it automatically.
// If --port was explicitly set, it's a no-op.
// For multiple IDEs it tries CWD matching, then errors with a list.
func resolveIDE() []ideInstance {
	ides := discoverIDEs()
	if len(ides) == 0 {
		fmt.Fprintln(os.Stderr, "Error: No running IDEs found.")
		os.Exit(1)
	}
	if len(ides) == 1 {
		port = ides[0].Port
		return ides
	}

	// Try to match CWD against project basePaths
	if cwd, err := os.Getwd(); err == nil {
		normalizedCwd := strings.TrimRight(cwd, "/")
		for _, ide := range ides {
			for _, p := range ide.Projects {
				basePath, _ := p["basePath"].(string)
				if basePath != "" && strings.TrimRight(basePath, "/") == normalizedCwd {
					port = ide.Port
					return ides
				}
			}
		}
	}

	fmt.Fprintln(os.Stderr, "Error: Multiple IDEs running. Specify one with --port:")
	for _, ide := range ides {
		fmt.Fprintf(os.Stderr, "  --port %d  (%s)\n", ide.Port, ide.Name)
	}
	os.Exit(1)
	return nil
}

// resolveProject returns the project name to use.
// Priority: explicit flag (-p) > positional project=name arg > auto-detect via discovery.
// Auto-detect: if exactly one project is open, use it; otherwise error with list.
func resolveProject(explicitProject string, defines []string) (string, []string) {
	// Check if project=value was passed as a positional arg
	for i, def := range defines {
		if strings.HasPrefix(def, "project=") {
			val := strings.TrimPrefix(def, "project=")
			remaining := append(defines[:i:i], defines[i+1:]...)
			return val, remaining
		}
	}

	if explicitProject != "" {
		return explicitProject, defines
	}

	// Discover all IDEs and collect all projects
	var ides []ideInstance
	if portExplicit {
		// Single port: just fetch projects from that port
		body, err := makeRequest("GET", "/projects", nil)
		if err != nil {
			fmt.Fprintf(os.Stderr, "Error fetching projects: %v\n", err)
			os.Exit(1)
		}
		var projects []map[string]interface{}
		if err := json.Unmarshal(body, &projects); err != nil {
			fmt.Fprintf(os.Stderr, "Error parsing projects: %v\n", err)
			os.Exit(1)
		}
		ides = []ideInstance{{Port: port, Projects: projects}}
	} else {
		ides = discoverIDEs()
	}

	if len(ides) == 0 {
		fmt.Fprintln(os.Stderr, "Error: No running IDEs found.")
		os.Exit(1)
	}

	// Collect all projects across IDEs
	type projectWithPort struct {
		name     string
		basePath string
		port     int
	}
	var allProjects []projectWithPort
	for _, ide := range ides {
		for _, p := range ide.Projects {
			name, _ := p["name"].(string)
			basePath, _ := p["basePath"].(string)
			allProjects = append(allProjects, projectWithPort{name, basePath, ide.Port})
		}
	}

	if len(allProjects) == 0 {
		fmt.Fprintln(os.Stderr, "Error: No open projects found.")
		os.Exit(1)
	}

	if len(allProjects) == 1 {
		port = allProjects[0].port
		return allProjects[0].name, defines
	}

	// Multiple projects — try to match against CWD
	if cwd, err := os.Getwd(); err == nil {
		normalizedCwd := strings.TrimRight(cwd, "/")
		for _, p := range allProjects {
			if p.basePath != "" && strings.TrimRight(p.basePath, "/") == normalizedCwd {
				port = p.port
				return p.name, defines
			}
		}
	}

	fmt.Fprintln(os.Stderr, "Error: Multiple projects open. Specify one with project=<name>:")
	for _, p := range allProjects {
		if p.basePath != "" {
			fmt.Fprintf(os.Stderr, "  project=%s  (%s)\n", p.name, p.basePath)
		} else {
			fmt.Fprintf(os.Stderr, "  project=%s\n", p.name)
		}
	}
	os.Exit(1)
	return "", defines
}

func runExec(cmd *cobra.Command, args []string) {
	// Positional key=value args are treated as defines
	for _, arg := range args {
		if strings.ContainsRune(arg, '=') {
			defines = append(defines, arg)
		} else {
			fmt.Fprintf(os.Stderr, "Error: unexpected argument %q (expected key=value)\n", arg)
			os.Exit(1)
		}
	}

	var codeToExecute string
	var resolvedPath string
	var isProjectAction bool
	var resolveErr error
	needsProject := true // default for -c inline code

	// Determine code source
	if code != "" {
		codeToExecute = code
	} else if codeFile != "" {
		codeToExecute, resolvedPath, isProjectAction, resolveErr = resolveActionFile(codeFile)
		if resolveErr != nil {
			fmt.Fprintf(os.Stderr, "Error: %v\n", resolveErr)
			os.Exit(1)
		}
		needsProject = isProjectAction
		fmt.Printf("Executing: %s\n\n", resolvedPath)
	} else {
		fmt.Fprintln(os.Stderr, "Error: Either -c (code) or -f (file) is required")
		fmt.Fprintln(os.Stderr, "")
		fmt.Fprintln(os.Stderr, "Examples:")
		fmt.Fprintln(os.Stderr, "  intellij-cli exec -c \"println(project.name)\"")
		fmt.Fprintln(os.Stderr, "  intellij-cli exec -c \"println(application.name)\"")
		fmt.Fprintln(os.Stderr, "  intellij-cli exec -f /path/to/script.kt")
		fmt.Fprintln(os.Stderr, "")
		fmt.Fprintln(os.Stderr, "Tip: Use 'intellij-cli action' to list and run built-in action scripts.")
		os.Exit(1)
	}

	// Resolve project only for project-level actions
	if needsProject {
		project, defines = resolveProject(project, defines)
	} else if !portExplicit {
		resolveIDE()
	}

	// Inject -D defines as Kotlin val declarations, inserted after the last import line
	// so that Kotlin's "imports must precede executable code" rule is not violated.
	if len(defines) > 0 {
		preamble, err := buildDefinesPreamble(defines)
		if err != nil {
			fmt.Fprintf(os.Stderr, "Error: %v\n", err)
			os.Exit(1)
		}
		// Remove existing val declarations for the same keys from the script
		// to avoid "variable is already declared" compile errors.
		codeToExecute = stripValDeclarations(codeToExecute, defines)
		codeToExecute = injectAfterImports(codeToExecute, preamble)
	}

	request := map[string]interface{}{
		"code":    codeToExecute,
		"timeout": timeout,
	}

	request["project"] = project

	body, err := makeRequest("POST", "/execute", request)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Error: %v\n", err)
		os.Exit(1)
	}

	var result map[string]interface{}
	if err := json.Unmarshal(body, &result); err != nil {
		fmt.Fprintf(os.Stderr, "Failed to parse response: %v\n", err)
		os.Exit(1)
	}

	execTime := result["executionTimeMs"]
	fmt.Printf("Execution time: %v ms\n\n", execTime)

	if result["success"] == true {
		fmt.Println("Output:")
		fmt.Println("-------")
		if output, ok := result["output"].(string); ok && output != "" {
			fmt.Println(output)
		} else {
			fmt.Println("(no output)")
		}
	} else {
		fmt.Fprintln(os.Stderr, "Execution failed:")
		if errMsg, ok := result["error"].(string); ok && errMsg != "" {
			fmt.Fprintln(os.Stderr, errMsg)
		}
		os.Exit(1)
	}
}

// scriptParam holds a parsed parameter from a script's configure block.
type scriptParam struct {
	name        string
	typeName    string
	defaultVal  string
	description string
}

// parseScriptParams extracts parameters from the "// --- Configure ---" block.
// Each val line has the form:  val name: Type = default  // description
func parseScriptParams(content string) []scriptParam {
	var params []scriptParam
	inBlock := false
	for _, line := range strings.Split(content, "\n") {
		trimmed := strings.TrimSpace(line)
		if trimmed == "// --- Configure ---" {
			inBlock = true
			continue
		}
		if inBlock && trimmed == "// -----------------" {
			break
		}
		if !inBlock {
			continue
		}
		// Match:  val <name>: <type> = <default>   // <desc>
		if !strings.HasPrefix(trimmed, "val ") {
			continue
		}
		rest := strings.TrimPrefix(trimmed, "val ")

		// Split off inline comment
		desc := ""
		if idx := strings.Index(rest, "//"); idx >= 0 {
			desc = strings.TrimSpace(rest[idx+2:])
			rest = strings.TrimSpace(rest[:idx])
		}

		// Parse:  name: Type = default
		colonIdx := strings.Index(rest, ":")
		eqIdx := strings.Index(rest, "=")
		if colonIdx < 0 || eqIdx < 0 || eqIdx < colonIdx {
			continue
		}
		name := strings.TrimSpace(rest[:colonIdx])
		typeName := strings.TrimSpace(rest[colonIdx+1 : eqIdx])
		defaultVal := strings.TrimSpace(rest[eqIdx+1:])

		params = append(params, scriptParam{
			name:        name,
			typeName:    typeName,
			defaultVal:  defaultVal,
			description: desc,
		})
	}
	return params
}

func buildActionListFromFS(fsys fs.FS, subdir string, isProjectDir bool) (string, int) {
	dir := "actions"
	if subdir != "" {
		dir = "actions/" + subdir
	}
	files, err := fs.ReadDir(fsys, dir)
	if err != nil {
		return "", 0
	}
	var buf strings.Builder
	count := 0
	for _, file := range files {
		if file.IsDir() || !strings.HasSuffix(file.Name(), ".kt") {
			continue
		}
		count++
		name := strings.TrimSuffix(file.Name(), ".kt")
		fmt.Fprintf(&buf, "  %s\n", name)

		fullPath := dir + "/" + file.Name()
		content, err := fs.ReadFile(fsys, fullPath)
		if err != nil {
			continue
		}

		for _, line := range strings.Split(string(content), "\n") {
			line = strings.TrimSpace(line)
			if strings.HasPrefix(line, "//") && !strings.HasPrefix(line, "// Usage:") {
				desc := strings.TrimSpace(strings.TrimPrefix(line, "//"))
				if desc != "" {
					fmt.Fprintf(&buf, "    %s\n", desc)
				}
				break
			}
			if !strings.HasPrefix(line, "//") && line != "" {
				break
			}
		}

		params := parseScriptParams(string(content))
		if isProjectDir || len(params) > 0 {
			fmt.Fprintf(&buf, "    Parameters:\n")
			if isProjectDir {
				fmt.Fprintf(&buf, "      %-28s %s\n", "project=<string>", "project name or path (optional, auto-detected if only one open)")
			}
			for _, p := range params {
				t := strings.ToLower(strings.TrimSuffix(strings.TrimSuffix(p.typeName, "?"), " "))
				flag := fmt.Sprintf("%s=<%s>", p.name, t)
				if p.description != "" {
					fmt.Fprintf(&buf, "      %-28s %s\n", flag, p.description)
				} else {
					fmt.Fprintf(&buf, "      %s  (default: %s)\n", flag, p.defaultVal)
				}
			}
		}
	}
	return buf.String(), count
}

func buildActionListFromDir(dir string, isProjectDir bool) (string, int) {
	files, err := os.ReadDir(dir)
	if err != nil {
		return "", 0
	}
	var buf strings.Builder
	count := 0
	for _, file := range files {
		if file.IsDir() || !strings.HasSuffix(file.Name(), ".kt") {
			continue
		}
		count++
		name := strings.TrimSuffix(file.Name(), ".kt")
		fmt.Fprintf(&buf, "  %s\n", name)

		fullPath := filepath.Join(dir, file.Name())
		content, err := os.ReadFile(fullPath)
		if err != nil {
			continue
		}

		for _, line := range strings.Split(string(content), "\n") {
			line = strings.TrimSpace(line)
			if strings.HasPrefix(line, "//") && !strings.HasPrefix(line, "// Usage:") {
				desc := strings.TrimSpace(strings.TrimPrefix(line, "//"))
				if desc != "" {
					fmt.Fprintf(&buf, "    %s\n", desc)
				}
				break
			}
			if !strings.HasPrefix(line, "//") && line != "" {
				break
			}
		}

		params := parseScriptParams(string(content))
		if isProjectDir || len(params) > 0 {
			fmt.Fprintf(&buf, "    Parameters:\n")
			if isProjectDir {
				fmt.Fprintf(&buf, "      %-28s %s\n", "project=<string>", "project name or path (optional, auto-detected if only one open)")
			}
			for _, p := range params {
				t := strings.ToLower(strings.TrimSuffix(strings.TrimSuffix(p.typeName, "?"), " "))
				flag := fmt.Sprintf("%s=<%s>", p.name, t)
				if p.description != "" {
					fmt.Fprintf(&buf, "      %-28s %s\n", flag, p.description)
				} else {
					fmt.Fprintf(&buf, "      %s  (default: %s)\n", flag, p.defaultVal)
				}
			}
		}
	}
	return buf.String(), count
}

func runSkillList() {
	home, _ := os.UserHomeDir()

	targets := []struct {
		label string
		path  string
	}{
		{"Project agent", ".agents/skills/intellij-cli/SKILL.md"},
		{"Global agent", filepath.Join(home, ".agents", "skills", "intellij-cli", "SKILL.md")},
		{"Project Claude", ".claude/skills/intellij-cli/SKILL.md"},
		{"Global Claude", filepath.Join(home, ".claude", "skills", "intellij-cli", "SKILL.md")},
	}

	fmt.Println("Installed Skills:")
	fmt.Println("=================")
	found := 0
	for _, t := range targets {
		if fileExists(t.path) {
			fmt.Printf("  ✓ %-16s %s\n", t.label, t.path)
			found++
		} else {
			fmt.Printf("  ✗ %-16s %s\n", t.label, t.path)
		}
	}

	fmt.Printf("\nUsage:\n")
	fmt.Printf("  intellij-cli skill print          print SKILL.md to stdout\n")
	fmt.Printf("  intellij-cli skill project         install to .agents/skills/\n")
	fmt.Printf("  intellij-cli skill global          install to ~/.agents/skills/\n")
	fmt.Printf("  intellij-cli skill project-claude  install to .claude/skills/\n")
	fmt.Printf("  intellij-cli skill global-claude   install to ~/.claude/skills/\n")
}

func buildSkillContent() string {
	var buf strings.Builder
	buf.WriteString(`---
name: intellij-cli
description: Access JetBrains IDE intelligence to find errors and warnings, analyze code, find usages, run diagnostics, refactor, navigate codebases, programmatically control, and notify the user via IDE notifications across IntelliJ IDEA, PhpStorm, WebStorm, GoLand, PyCharm and other JetBrains IDEs
---

`)

	buf.WriteString("## What I do\n\n")
	buf.WriteString("- Find usages, references, and call hierarchies for any symbol across the codebase\n")
	buf.WriteString("- Run IDE diagnostics and code inspections to surface errors, warnings, and hints\n")
	buf.WriteString("- Refactor code: rename symbols, extract methods, move files with full IDE safety\n")
	buf.WriteString("- Navigate to declarations, implementations, and type definitions\n")
	buf.WriteString("- Query project structure, modules, and dependencies\n")
	buf.WriteString("- Send notifications to the user directly inside the IDE\n")
	buf.WriteString("- Execute custom Kotlin scripts in the full IDE context for any IDE operation\n\n")

	buf.WriteString("## When to use me\n\n")
	buf.WriteString("Use when you need IDE-level intelligence: finding all usages of a symbol, running inspections,\n")
	buf.WriteString("performing safe refactors, understanding code structure, or anything that requires deep\n")
	buf.WriteString("language understanding beyond what static file reading provides.\n")
	buf.WriteString("Works with IntelliJ IDEA, PhpStorm, WebStorm, GoLand, PyCharm, RubyMine, CLion, Rider, and more.\n\n")

	buf.WriteString("## CLI Reference\n\n")
	buf.WriteString("```\n")
	buf.WriteString(buildListActionsOutput())
	buf.WriteString("```\n")

	buf.WriteString("\n## IDE and Project Auto-Detection\n\n")
	buf.WriteString("The IDE and project are auto-detected from the current working directory (CWD).\n")
	buf.WriteString("Nothing extra is needed when only one IDE or project is open.\n")
	buf.WriteString("If auto-detection fails, the default fallback port is 8568 (IntelliJ IDEA).\n\n")
	buf.WriteString("If multiple IDEs or projects are running, pass the port or project path explicitly:\n\n")
	buf.WriteString("```\n")
	buf.WriteString("intellij-cli -P 8571 action diagnostics file=src/Foo.kt\n")
	buf.WriteString("intellij-cli action diagnostics project=/path/to/project file=src/Foo.kt\n")
	buf.WriteString("```\n\n")
	buf.WriteString("Each JetBrains IDE listens on its own port: IntelliJ IDEA 8568, Android Studio 8569, PyCharm 8570, WebStorm 8571, GoLand 8572, PhpStorm 8573, RubyMine 8574, CLion 8575, RustRover 8576, DataGrip 8577, Aqua 8578, DataSpell 8579, Rider 8580. Unrecognized IDEs fall back to 8599.\n")

	buf.WriteString("\n## Execute Code Directly\n\n")
	buf.WriteString("In addition to named actions, Kotlin code can be run inline or from a file:\n\n")
	buf.WriteString("```\n")
	buf.WriteString("intellij-cli exec -c \"println(project.name)\"   # inline Kotlin\n")
	buf.WriteString("intellij-cli exec -f <script>                   # execute a .kt script file\n")
	buf.WriteString("```\n\n")
	buf.WriteString("Example — list all open files:\n\n")
	buf.WriteString("```\n")
	buf.WriteString("intellij-cli exec -c \"readAction { com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFiles.forEach { println(it.path) } }\"\n")
	buf.WriteString("```\n\n")
	buf.WriteString("Available bindings: `project`, `application`, `readAction { }`, `writeAction { }`, `println()`\n")

	buf.WriteString("\n## Writing Custom Scripts\n\n")
	buf.WriteString("Use `intellij-cli source <action>` to print the full Kotlin source of any action as a starting point.\n")
	buf.WriteString("Every action listed above supports this — source is the intended way to learn the API patterns.\n\n")
	buf.WriteString("```\n")
	buf.WriteString("intellij-cli source notify           # notifications\n")
	buf.WriteString("intellij-cli source diagnostics      # file errors/warnings\n")
	buf.WriteString("intellij-cli source find_references  # symbol usages\n")
	buf.WriteString("intellij-cli source modules          # project modules\n")
	buf.WriteString("intellij-cli source tree             # file tree\n")
	buf.WriteString("```\n\n")
	buf.WriteString("Place custom scripts in:\n")
	buf.WriteString("- `.idea/intellij-cli/actions/` — project-local\n")
	buf.WriteString("- `~/.local/share/intellij-cli/actions/` — global\n\n")
	buf.WriteString("Scripts needing a `project` binding go in a `project/` subdirectory. Local scripts shadow embedded ones with the same name.\n")

	return buf.String()
}

func runSkill(cmd *cobra.Command, args []string) {
	if len(args) == 0 {
		runSkillList()
		return
	}

	target := args[0]

	if target == "print" {
		fmt.Print(buildSkillContent())
		return
	}

	var dir string
	switch target {
	case "project":
		dir = ".agents/skills/intellij-cli"
	case "global":
		home, err := os.UserHomeDir()
		if err != nil {
			fmt.Fprintf(os.Stderr, "Error: %v\n", err)
			os.Exit(1)
		}
		dir = filepath.Join(home, ".agents", "skills", "intellij-cli")
	case "project-claude":
		dir = ".claude/skills/intellij-cli"
	case "global-claude":
		home, err := os.UserHomeDir()
		if err != nil {
			fmt.Fprintf(os.Stderr, "Error: %v\n", err)
			os.Exit(1)
		}
		dir = filepath.Join(home, ".claude", "skills", "intellij-cli")
	default:
		fmt.Fprintf(os.Stderr, "Error: unknown target %q (valid: print, project, global, project-claude, global-claude)\n", target)
		os.Exit(1)
	}

	content := buildSkillContent()

	if err := os.MkdirAll(dir, 0755); err != nil {
		fmt.Fprintf(os.Stderr, "Error creating directory: %v\n", err)
		os.Exit(1)
	}

	skillPath := filepath.Join(dir, "SKILL.md")
	if err := os.WriteFile(skillPath, []byte(content), 0644); err != nil {
		fmt.Fprintf(os.Stderr, "Error writing file: %v\n", err)
		os.Exit(1)
	}

	fmt.Printf("Created %s\n", skillPath)
}

func runSource(cmd *cobra.Command, args []string) {
	source, resolvedPath, _, err := resolveActionFile(args[0])
	if err != nil {
		fmt.Fprintf(os.Stderr, "Error: %v\n", err)
		os.Exit(1)
	}

	fmt.Printf("// Source: %s\n\n", resolvedPath)
	fmt.Print(source)
	if !strings.HasSuffix(source, "\n") {
		fmt.Println()
	}
}

// runActionCmd: no args → list, first arg = action name → execute it
func runActionCmd(cmd *cobra.Command, args []string) {
	if len(args) == 0 {
		runListActions(cmd, args)
		return
	}

	// First arg is the action name, rest are key=value defines
	actionName := args[0]
	defines = nil
	for _, arg := range args[1:] {
		if strings.ContainsRune(arg, '=') {
			defines = append(defines, arg)
		} else {
			fmt.Fprintf(os.Stderr, "Error: unexpected argument %q (expected key=value)\n", arg)
			os.Exit(1)
		}
	}

	codeToExecute, resolvedPath, isProjectAction, err := resolveActionFile(actionName)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Error: %v\n", err)
		os.Exit(1)
	}

	fmt.Printf("Executing: %s\n\n", resolvedPath)

	if isProjectAction {
		project, defines = resolveProject(project, defines)
	} else if !portExplicit {
		resolveIDE()
	}

	if len(defines) > 0 {
		preamble, err := buildDefinesPreamble(defines)
		if err != nil {
			fmt.Fprintf(os.Stderr, "Error: %v\n", err)
			os.Exit(1)
		}
		codeToExecute = stripValDeclarations(codeToExecute, defines)
		codeToExecute = injectAfterImports(codeToExecute, preamble)
	}

	request := map[string]interface{}{
		"code":    codeToExecute,
		"timeout": timeout,
	}
	if isProjectAction {
		request["project"] = project
	}

	body, err := makeRequest("POST", "/execute", request)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Error: %v\n", err)
		os.Exit(1)
	}

	var result map[string]interface{}
	if err := json.Unmarshal(body, &result); err != nil {
		fmt.Fprintf(os.Stderr, "Failed to parse response: %v\n", err)
		os.Exit(1)
	}

	fmt.Printf("Execution time: %v ms\n\n", result["executionTimeMs"])

	if result["success"] == true {
		fmt.Println("Output:")
		fmt.Println("-------")
		if output, ok := result["output"].(string); ok && output != "" {
			fmt.Println(output)
		} else {
			fmt.Println("(no output)")
		}
	} else {
		fmt.Fprintln(os.Stderr, "Execution failed:")
		if errMsg, ok := result["error"].(string); ok {
			fmt.Fprintln(os.Stderr, errMsg)
		}
		os.Exit(1)
	}
}

func buildListActionsOutput() string {
	localDir := getLocalActionsDir()
	globalDir := getGlobalActionsDir()

	var buf strings.Builder

	buf.WriteString("Available Actions:\n")
	buf.WriteString("==================\n")

	// 1. Local project actions (.idea/intellij-cli/actions/)
	fmt.Fprintf(&buf, "\nLocal (%s):\n", localDir)
	localOut, localCount := buildActionListFromDir(localDir, false)
	buf.WriteString(localOut)
	localProjOut, localProjCount := buildActionListFromDir(filepath.Join(localDir, "project"), true)
	buf.WriteString(localProjOut)
	if localCount+localProjCount == 0 {
		buf.WriteString("  (none)\n")
	}

	// 2. Global actions (~/.intellij-cli/actions/)
	fmt.Fprintf(&buf, "\nGlobal (%s):\n", globalDir)
	globalOut, globalCount := buildActionListFromDir(globalDir, false)
	buf.WriteString(globalOut)
	globalProjOut, globalProjCount := buildActionListFromDir(filepath.Join(globalDir, "project"), true)
	buf.WriteString(globalProjOut)
	if globalCount+globalProjCount == 0 {
		buf.WriteString("  (none)\n")
	}

	// 3. Embedded actions
	buf.WriteString("\nEmbedded:\n")
	embAppOut, embAppCount := buildActionListFromFS(embeddedActions, "", false)
	buf.WriteString(embAppOut)
	embProjOut, embProjCount := buildActionListFromFS(embeddedActions, "project", true)
	buf.WriteString(embProjOut)
	if embAppCount+embProjCount == 0 {
		buf.WriteString("  (none)\n")
	}

	buf.WriteString("\nSearch order: local → global → embedded\n")
	buf.WriteString("\nUsage:\n")
	buf.WriteString("  intellij-cli action <name>                     run action (IDE auto-discovered)\n")
	buf.WriteString("  intellij-cli action <name> [key=value ...]     run with parameters\n")
	buf.WriteString("  intellij-cli action <name> project=<name|path> run with explicit project\n")
	buf.WriteString("\nIDE and project are auto-discovered when only one is running.\n")
	buf.WriteString("Use -P/--port or -p/--project when multiple IDEs or projects are open.\n")

	return buf.String()
}

func runListActions(cmd *cobra.Command, args []string) {
	fmt.Print(buildListActionsOutput())
}
