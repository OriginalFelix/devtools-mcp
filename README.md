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
| **Container (OCI)** | lesend: `container_runtimes`, `container_list`, `container_inspect` (Geheimnisse maskiert), `container_logs`, `container_stats`, `container_top`, `container_diff`, `container_images`, `container_networks`, `container_volumes` · je Schalter (Standard aus): `container_exec`, `container_start`/`stop`/`restart`, `container_copy_from`/`copy_to`, `container_run`, `container_pull`, `container_rm`, `container_rmi`, `container_compose_up`/`down`/`restart` · mit Compose-Projekten: `container_compose_projects`/`ps`/`logs`/`config` |
| **Skills** (Hibernate, Standard H2) | `skills_list`, `skills_view`, `skills_history` · schreibend (Standard an): `skills_create`, `skills_patch`, `skills_update`, `skills_write_file`, `skills_remove_file` · Schalter (Standard aus): `skills_delete` |

Das Modul **Java-Grundeinstellungen** hat keine eigenen Tools, es liefert JDK, Ablageordner, Prozessfilter
und JMX-Ziele für alle Performance-Module. Container-Laufzeit und freigegebene Container kommen aus dem
Container-Modul (ältere Einstellungen werden beim ersten Start übernommen).

### Container-Laufzeiten erweitern (ServiceLoader)

Laufzeiten sind über `modules/container/spi` austauschbar. Docker und Podman liefert die App mit; eine weitere
Laufzeit (z.B. nerdctl) braucht nur zwei Dinge:

```java
public class NerdctlRuntimeProvider implements ContainerRuntimeProvider {
    public String id() { return "nerdctl"; }
    public String displayName() { return "nerdctl (containerd)"; }
    public List<ConfigField> configFields() {
        return List.of(ConfigField.of("binary", "Programm", FieldType.STRING).withDefault("nerdctl"));
    }
    public ContainerRuntime create(RuntimeSettings s) {
        // Docker-kompatible CLI -> gemeinsame Basis wiederverwenden
        return new CliContainerRuntime("nerdctl", s.getString("binary", "nerdctl"), List.of()) { };
    }
}
```

und eine Zeile in `src/main/resources/META-INF/services/systems.grebe.devtools.mcp.modules.container.spi.ContainerRuntimeProvider`.
Die UI zeigt dann automatisch „nerdctl (containerd): aktiv / Programm“, die Laufzeit erscheint in `container_runtimes`
und ist über den Parameter `runtime` in allen `container_*`-Tools wählbar. Laufzeiten ohne CLI implementieren
`ContainerRuntime` direkt (z.B. über eine REST-API).

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
* Container: Namens- und Image-Filter (Regex); exec, Lifecycle, Kopieren, run/pull, rm/rmi und Compose up/down
  sind einzeln schaltbar und standardmäßig aus. exec optional nur mit freigegebenen Programmen, `run` bindet Ports
  an 127.0.0.1 und erlaubt Bind-Mounts nur aus freigegebenen Host-Verzeichnissen, `rm` standardmäßig nur für über
  `container_run` angelegte Container (Label `devtools-mcp`). Passwörter/Tokens in Umgebungsvariablen werden maskiert.

### Skills – prozedurales Gedächtnis des LLM

Angelehnt an das Skill-Management von Hermes: Das LLM sucht vor einer Aufgabe mit `skills_list` passende Skills und
lädt sie mit `skills_view`. Nach einer schwierigen, mehrstufigen oder korrigierten Aufgabe legt es selbst einen Skill
an (`skills_create`) oder verbessert einen bestehenden gezielt (`skills_patch`, `old_string` → `new_string`, muss
eindeutig sein). Wann das passieren soll, steht in den Server-Instructions und in den Tool-Beschreibungen.

* **Aufbau** wie ein `SKILL.md`: Name (`a-z0-9._-`), ein Satz `description` („wann greift der Skill“), Kategorie,
  Tags, Markdown-Inhalt, dazu Zusatzdateien unter `references/`, `templates/`, `scripts/`, `assets/`.
* **Historie:** jede Änderung erzeugt eine Revision mit Aktion und Notiz (`skills_history`). Mit
  `expected_revision` lehnt ein Patch ab, wenn der Skill inzwischen woanders geändert wurde.
* **Persistenz:** Hibernate ORM 7 (ohne Spring Data, keine Boot-DataSource) über HikariCP. Standard ist eine lokale
  H2-Datei `~/.devtools-mcp/skills.mv.db`; Schema per `hibernate.hbm2ddl.auto=update`. JDBC-URL, Benutzer, Passwort
  (verschlüsselt) und Schema-Modus sind im Modul einstellbar, *Verbindung testen* öffnet die Datenbank probeweise.
  Andere Datenbanken brauchen ihren JDBC-Treiber auf dem Classpath.
* Die H2-Datei ist exklusiv gesperrt, solange die App läuft. Wer parallel mit IntelliJ o.ä. hineinschauen will,
  hängt `;AUTO_SERVER=TRUE` an die JDBC-URL.

### Instructions für das LLM

Beim `initialize` schickt der Server MCP-`instructions`, die Clients wie Claude Code in den System-Prompt übernehmen.
Sie legen fest, **wann welches Tool statt eines Shell-Befehls** zu verwenden ist – z.B. `git_status` statt
`git status`, `build_test` statt `./gradlew test`, `container_list` statt `podman ps`. `ServerInstructions` setzt den
Text aus einem allgemeinen Vorrang-Hinweis, dem optionalen `spring.ai.mcp.server.instructions` und den
`instructions()` aller Module zusammen (Reihenfolge wie die Modulliste). Ein eigenes Modul ergänzt seine Hinweise
über `ToolModule#instructions()`.

Die Instructions stehen ab dem Serverstart fest (MCP sieht keine Änderungsbenachrichtigung dafür) und enthalten
deshalb auch abgeschaltete Module. Sie sind bedingt formuliert („wenn angeboten“), die aktuell verfügbaren Tools
liefert weiterhin `tools/list`. Geänderte Texte kommen beim Client erst nach Neustart der App **und** neuer
Client-Session an.

Nicht jeder Client übernimmt die Instructions (Hermes z.B. wertet nur die Tool-Beschreibungen aus). Deshalb endet
zusätzlich **jede** Tool-Beschreibung mit der Grundregel ihres Moduls (`core/ShellHints`) und nennt, wo es einen
gibt, den ersetzten Befehl („Statt `git status` in der Shell verwenden.“, „Statt `podman ps -a` verwenden.“).
Ein Integrationstest prüft das für alle Tools; neue `@Tool`-Methoden brauchen `+ ShellHints.<MODUL>` am Ende der
Beschreibung.

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
