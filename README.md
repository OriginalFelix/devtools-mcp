# DevTools MCP

Desktop-Anwendung (Spring Boot 4 + JavaFX 25, Java 25), die einen lokalen **MCP-Server** bereitstellt.
LLM-Clients (Claude Code, Claude Desktop, Cursor, VS Code, Hermes …) erhalten darüber Werkzeuge für den
Entwickleralltag. Alles wird in der Oberfläche konfiguriert; neue Werkzeuge lassen sich mit einer Klasse ergänzen.

| Modul | Tools |
|---|---|
| **Git** (JGit) | `git_list_repositories`, `git_status`, `git_log`, `git_diff`, `git_show_commit`, `git_branches`, `git_blame`, `git_file_at_revision` · schreibend: `git_create_branch`, `git_checkout`, `git_stage`, `git_unstage`, `git_commit` (kein Push) |
| **SonarQube** / SonarCloud | `sonar_list_projects`, `sonar_quality_gate`, `sonar_issues`, `sonar_issue_detail`, `sonar_rule`, `sonar_measures`, `sonar_hotspots`, `sonar_source` |
| **Build** (Gradle/Maven) | `build_list_projects`, `build_run`, `build_test`, `build_test_report` |

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
