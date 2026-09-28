# DevTools MCP

Desktop-Anwendung (Spring Boot 4 + JavaFX 25, Java 25), die einen lokalen **MCP-Server** bereitstellt.
LLM-Clients (Claude Code, Claude Desktop, Cursor, VS Code, Hermes …) erhalten darüber Werkzeuge für den
Entwickleralltag. Alles wird in der Oberfläche konfiguriert; neue Werkzeuge lassen sich mit einer Klasse ergänzen.

| Modul | Tools |
|---|---|
| **Git** (JGit) | `git_list_repositories`, `git_status`, `git_log`, `git_diff`, `git_show_commit`, `git_branches`, `git_blame`, `git_file_at_revision` · schreibend: `git_create_branch`, `git_checkout`, `git_stage`, `git_unstage`, `git_commit` (kein Push) |
| **SonarQube** / SonarCloud | `sonar_list_projects`, `sonar_quality_gate`, `sonar_issues`, `sonar_issue_detail`, `sonar_rule`, `sonar_measures`, `sonar_hotspots`, `sonar_source` |
| **Build** (Gradle/Maven) | `build_list_projects`, `build_run`, `build_test`, `build_test_report` |
| **JVM-Diagnose** (jcmd) | `jvm_processes`, `jvm_info`, `jvm_threads` (inkl. Deadlock-Erkennung), `jvm_heap`, `jvm_native_memory` · invasiv: `jvm_heap_dump`, `jvm_gc_run`, `jvm_jcmd` (Allowlist) |
| **Flight Recorder** | `jfr_record`, `jfr_start`, `jfr_status`, `jfr_dump`, `jfr_stop`, `jfr_analyze` (cpu/allocation/gc/locks/io/exceptions/threads), `jfr_flamegraph` |
| **async-profiler** 4.5 | `asprof_profile`, `asprof_start`, `asprof_stop`, `asprof_status` – Linux/macOS nativ, unter Windows für JVMs in Docker/Podman-Containern (Standard: aus) |
| **VisualVM** | `visualvm_heap_analyze` (Heap-Engine: Histogramm, Retained Size, Pfad zur GC-Wurzel), `visualvm_sample_cpu` (JMX-Sampler, `.nps`-Snapshot), `visualvm_open`, `visualvm_open_file` (externe VisualVM-GUI) |
| **Debugger** (JDI) | `debug_attach`, `debug_sessions`, `debug_detach`, `debug_set_breakpoint`, `debug_clear_breakpoint`, `debug_wait_for_break`, `debug_threads`, `debug_stack`, `debug_variables`, `debug_step`, `debug_resume` (Standard: aus) |

Das Modul **Java-Grundeinstellungen** hat keine eigenen Tools, es liefert JDK, Ablageordner, Prozessfilter,
Container-CLI und JMX-Ziele für alle Performance-Module.

### Ziel-JVMs

Alle Performance-Tools nehmen dieselbe `target`-Angabe:

| Angabe | Bedeutung |
|---|---|
| leer | einzige freigegebene lokale JVM |
| `12345` / `MyApp` | lokale PID bzw. eindeutiger Teil des Hauptklassen-/Jar-Namens |
| `container:<name>[:<pid>]` | JVM in einem Docker/Podman-Container (`jcmd` per exec, asprof wird hineinkopiert) |
| `jmx:<alias>` | Remote-JVM über JMX (DiagnosticCommand-MBean; JFR-Dateien werden per `FlightRecorderMXBean` übertragen) |

Ergebnisse (`.jfr`, Flame Graphs `.html`, `.hprof`, `.nps`) landen im Ablageordner und erscheinen im Tab
**Artefakte** (öffnen, im Explorer zeigen, in VisualVM öffnen, löschen).

## Starten

```bash
./gradlew bootRun          # Entwicklung
./gradlew bootJar          # build/libs/devtools-mcp-0.1.0-SNAPSHOT.jar → java -jar …
./gradlew test             # Unit- + MCP-Integrationstests
```

Der Server lauscht auf `http://127.0.0.1:8765/mcp` (Streamable HTTP, nur localhost).
Über **„Client verbinden…“** zeigt die App fertige Konfigurationen, z.B.:

```bash
claude mcp add --transport http devtools http://127.0.0.1:8765/mcp
```

## Bedienung

* **Module** (links): an/aus, Status (grün aktiv · grau aus · rot Fehler).
* **Konfiguration** (rechts): Formular wird aus dem Modul-Schema erzeugt; *Speichern* registriert die Tools
  sofort neu, verbundene Clients erhalten `notifications/tools/list_changed`. *Verbindung testen* prüft
  die ungespeicherten Eingaben.
* **Tools**: jedes Tool einzeln abschaltbar.
* **Aufrufe**: Live-Protokoll aller Tool-Aufrufe mit Argumenten, Ergebnis, Dauer und Fehlern.
* **Einstellungen**: Port (nach Neustart), optionales Bearer-Token (sofort wirksam), Tray-Verhalten.
* Fenster schließen → läuft im System-Tray weiter; *Beenden* über das Tray-Menü.

Einstellungen liegen in `~/.devtools-mcp/settings.json` (Pfad per `DEVTOOLS_MCP_HOME` bzw.
`-Ddevtools.mcp.home` änderbar). Geheimnisse (Sonar-Token, Zugriffstoken) werden mit AES-GCM verschlüsselt,
der Schlüssel liegt in `secret.key` daneben.

### Sicherheit

* Nur `127.0.0.1`; optional zusätzlich Bearer-Token.
* Git/Build arbeiten ausschließlich in den freigegebenen Verzeichnissen; Pfade außerhalb werden abgewiesen.
* Build: nur freigegebene Tasks/Goals, Argumente werden gegen eine Zeichen-Whitelist geprüft (kein Shell-Injection
  über `cmd.exe`), ein Build pro Projekt gleichzeitig, Timeout.
* Git-Schreibtools lassen sich per Schalter „Schreibende Operationen erlauben“ komplett abschalten.
* Performance: nur JVMs, deren Name zum Prozessfilter passt; nur freigegebene Container und JMX-Ziele.
  Invasive jcmd-Operationen (Heap-Dump, GC, freie Befehle) per Schalter abschaltbar; `jvm_jcmd` nur mit
  Befehlen aus der Allowlist. Der Debugger verbindet sich nur mit freigegebenen Hosts, wertet keine Ausdrücke
  aus und verändert keine Werte; Sitzungen werden beim Deaktivieren/Beenden getrennt (Ziel-JVM läuft weiter).
* Downloads (async-profiler, VisualVM) sind per SHA-256 geprüft und abschaltbar.

## Eigenes Modul schreiben

1. Tool-Klasse mit `@Tool`-Methoden (Spring AI). Rückgabe: kompakter Text für das LLM.

```java
public class JiraTools {
    private final JiraClient client;
    JiraTools(JiraClient client) { this.client = client; }

    @Tool(name = "issue", description = "Liest ein Jira-Ticket inkl. Beschreibung und Kommentaren.")
    public String issue(@ToolParam(description = "Ticket-Schlüssel, z.B. ABC-123") String key) {
        return client.issue(key).toText();
    }
}
```

2. Modul als Spring-Bean – fertig. Formular, Persistenz (inkl. Verschlüsselung von `SECRET`-Feldern),
   Aktivierung, Präfix (`jira_issue`) und Protokollierung übernimmt die App.

```java
@Component
public class JiraModule implements ToolModule {
    public String id() { return "jira"; }
    public String displayName() { return "Jira"; }
    public String description() { return "Tickets lesen und kommentieren."; }

    public List<ConfigField> configSchema() {
        return List.of(
            ConfigField.of("url", "Server-URL", FieldType.URL).asRequired(),
            ConfigField.of("token", "API-Token", FieldType.SECRET).asRequired());
    }

    public List<ToolCallback> createTools(ModuleConfig c) {
        return List.of(ToolCallbacks.from(new JiraTools(new JiraClient(c.require("url"), c.require("token")))));
    }

    @Override
    public ConnectionTestResult testConnection(ModuleConfig c) { /* optional */ }
}
```

Feldtypen: `STRING`, `SECRET`, `INT`, `BOOLEAN`, `URL`, `DIRECTORY`, `DIRECTORY_LIST`, `ENUM`, `STRING_LIST`.
Für Verzeichnis-basierte Module hilft `Workspaces` (Freigabe + Pfad-Guard).

## Architektur

```
DevToolsMcpApplication ── main() → JavaFX
fx/FxApp                ── init(): Spring-Kontext starten · start(): Fenster + Tray · stop(): Kontext schließen
core/ToolModule         ── Erweiterungspunkt (SPI)
core/ToolRegistry       ── Module ⇄ McpSyncServer (addTool/removeTool zur Laufzeit, notifyToolsListChanged)
core/ManagedToolCallback── Präfix, Protokollierung, Klartext-Ergebnisse
config/SettingsStore    ── JSON-Persistenz, SecretCipher (AES-GCM)
server/BearerTokenFilter── optionaler Token-Schutz
modules/{git,sonar,build}
ui/                     ── MainView, ModuleDetailPane, ConfigForm (schema-getrieben), InvocationLogView, Dialoge
```

MCP-Server: Spring AI `spring-ai-starter-mcp-server-webflux` 2.0.1 (MCP Java SDK 2.0.0), Protokoll `STREAMABLE`.
