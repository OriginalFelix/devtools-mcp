# DevTools MCP

Desktop-Anwendung (Spring Boot 4 + JavaFX 25, Java 25), die einen lokalen **MCP-Server** bereitstellt.
LLM-Clients (Claude Code, Claude Desktop, Cursor, VS Code, Hermes …) erhalten darüber Werkzeuge für den
Entwickleralltag. Alles wird in der Oberfläche konfiguriert; neue Werkzeuge lassen sich mit einer Klasse ergänzen.

| Modul | Tools |
|---|---|
| **Git** (JGit) | `git_list_repositories`, `git_status`, `git_log`, `git_diff`, `git_show_commit`, `git_branches`, `git_blame`, `git_file_at_revision` · schreibend: `git_create_branch`, `git_checkout`, `git_stage`, `git_unstage`, `git_commit` (kein Push) |
| **SonarQube** / SonarCloud | `sonar_list_projects`, `sonar_quality_gate`, `sonar_issues`, `sonar_issue_detail`, `sonar_rule`, `sonar_measures`, `sonar_hotspots`, `sonar_source` |
| **Build** (Gradle/Maven) | `build_list_projects`, `build_run`, `build_test`, `build_test_report` |
| **Code-Graph** (Java, tree-sitter) | `graph_build`, `graph_branches`, `graph_report`, `graph_find`, `graph_explain`, `graph_neighbors`, `graph_path`, `graph_query`, `graph_cypher` – je Projekt und Git-Branch in Neo4j (Spring Data Neo4j) oder als Datei im Projekt (Standard: aus) |
| **JVM-Diagnose** (jcmd) | `jvm_processes`, `jvm_info`, `jvm_threads` (inkl. Deadlock-Erkennung), `jvm_heap`, `jvm_native_memory` · invasiv: `jvm_heap_dump`, `jvm_gc_run`, `jvm_jcmd` (Allowlist) |
| **Flight Recorder** | `jfr_record`, `jfr_start`, `jfr_status`, `jfr_dump`, `jfr_stop`, `jfr_analyze` (cpu/allocation/gc/locks/io/exceptions/threads), `jfr_flamegraph` |
| **async-profiler** 4.5 | `asprof_profile`, `asprof_start`, `asprof_stop`, `asprof_status` – Linux/macOS nativ, unter Windows für JVMs in Docker/Podman-Containern (Standard: aus) |
| **VisualVM** | `visualvm_heap_analyze` (Heap-Engine: Histogramm, Retained Size, Pfad zur GC-Wurzel), `visualvm_sample_cpu` (JMX-Sampler, `.nps`-Snapshot), `visualvm_open`, `visualvm_open_file` (externe VisualVM-GUI) |
| **Debugger** (JDI) | `debug_attach`, `debug_sessions`, `debug_detach`, `debug_set_breakpoint`, `debug_clear_breakpoint`, `debug_wait_for_break`, `debug_threads`, `debug_stack`, `debug_variables`, `debug_step`, `debug_resume` (Standard: aus) |
| **Container (OCI)** | lesend: `container_runtimes`, `container_list`, `container_inspect` (Geheimnisse maskiert), `container_logs`, `container_stats`, `container_top`, `container_diff`, `container_images`, `container_networks`, `container_volumes` · je Schalter (Standard aus): `container_exec`, `container_start`/`stop`/`restart`, `container_copy_from`/`copy_to`, `container_run`, `container_pull`, `container_rm`, `container_rmi`, `container_compose_up`/`down`/`restart` · mit Compose-Projekten: `container_compose_projects`/`ps`/`logs`/`config` |
| **Tickets** (Jira, GitHub, GitLab; erweiterbar per ServiceLoader) | `ticket_providers`, `ticket_boards`, `ticket_board` (Board nach Spalten: Jira-Sprint/Kanban, GitHub Project, GitLab-Issue-Board), `ticket_search`, `ticket_get` (Titel, Status, Zuständige, Beschreibung, Kommentare), `ticket_status` (mehrere Tickets) – nur lesend (Standard: aus) |
| **Skills** (Spring Data JPA, Standard H2) | `skills_list`, `skills_view`, `skills_history` · schreibend (Standard an): `skills_create`, `skills_patch`, `skills_update`, `skills_write_file`, `skills_remove_file` · Selbstverbesserung: `skills_review` (Tool und MCP-Prompt) · Schalter (Standard aus): `skills_delete` |

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

### Ticket-Systeme erweitern (ServiceLoader)

Das Modul **Tickets** arbeitet nach demselben Muster über `modules/ticket/spi`: `TicketProvider` (ID, Felder, Hilfetexte)
erzeugt ein `TicketSystem` mit `boards`, `board`, `search`, `ticket` und optional `ownsKey`. Mitgeliefert sind

| Provider | Anbindung | Board = | Projekt | Schlüssel |
|---|---|---|---|---|
| `jira` | REST v2 + Agile 1.0; Cloud (`/search/jql`, Token-Paging, Basic mit E-Mail+API-Token) oder Data Center (`/search`, PAT als Bearer) | Scrum: aktiver Sprint, Kanban: offen + 14 Tage erledigt; Spalten aus der Board-Konfiguration | `ABC` | `ABC-123`, Browse-URL |
| `github` | REST (Issues, Suche) + GraphQL (Projects v2) | Project, Spalten = Single-Select-Feld `Status` (einstellbar) | `owner/repo` bzw. `owner` | `owner/repo#12`, `#12`, URL |
| `gitlab` | REST v4, Projekt oder Gruppe (automatisch erkannt) | Issue-Board: Open, Label-/Assignee-/Milestone-Listen, Closed | `gruppe/projekt` bzw. `gruppe` | `gruppe/projekt#12`, `#12`, URL |

Jedes System hat in der UI „aktiv“, seine Felder und ein Standardprojekt. Ohne `provider` wählt das Modul das System, das
den Schlüssel als seinen erkennt (Jira-Schlüssel, URL seines Hosts), sonst das Standard-System bzw. das einzige aktive.
Ein weiteres System (z.B. YouTrack) braucht eine `TicketProvider`-Klasse und eine Zeile in
`src/main/resources/META-INF/services/systems.grebe.devtools.mcp.modules.ticket.spi.TicketProvider`; `spi/HttpJson`
(JSON über HTTP mit verständlichen Fehlermeldungen) steht Providern – auch aus Plugins – zur Verfügung.

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
* **Skills**: Übersicht der gespeicherten Skills mit Inhalt, Zusatzdateien und Historie (aktualisiert sich live).
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

### Code-Graph (Java)

Angelehnt an den AST-Durchlauf von [graphify](https://github.com/safishamsi/graphify), aber in Java und ohne LLM:
tree-sitter liest alle `.java`-Dateien eines freigegebenen Projekts in zwei Durchläufen – erst Deklarationen, dann
Referenzen, aufgelöst über die Deklarationen aller Dateien. Gemessen an eGECKO (~10.800 Dateien): Aufbau ~20 s,
Datei ~115 MB, ~280 MB Heap für den geladenen Graphen, Abfragen im Millisekundenbereich, Prüfung „aktuell?“ ~1,5 s.

* **Native Bibliotheken:** Aufruf über die offiziellen FFM-Bindings `io.github.tree-sitter:jtreesitter`
  (`jtreesitter.internal.TreeSitter`, Version fest). Die vorkompilierten Bibliotheken für macOS/Linux (x86_64,
  aarch64) und Windows (x86_64) kommen als reine Ressourcen aus `io.github.bonede:tree-sitter(-java)`;
  `TreeSitterNatives` entpackt sie beim ersten Aufruf. Ausnahme Windows-Kernbibliothek: die DLL von bonede exportiert
  nur ihre JNI-Funktionen, nicht die C-API (`ts_*`) – jtreesitter scheitert daran mit `NoClassDefFoundError: Could not
  initialize class …TreeSitter`. Deshalb liegt unter `src/main/resources/natives/` eine eigene, aus den Original-Quellen
  (gleiche Version) mit MinGW gebaute `x86_64-windows-tree-sitter.dll`; `malloc`/`free` kommen dort aus `msvcrt.dll`.
  Neu bauen: `podman run --rm -v "$PWD:/src" docker.io/library/eclipse-temurin:25-jdk sh
  /src/natives/build-windows-tree-sitter.sh`. Bewusst **nicht** verwendet: die JNI-Klassen von bonede –
  sie prüfen Allokationen nicht und beenden bei vollem Heap die ganze JVM mit SIGSEGV (reproduziert). Ebenfalls
  nicht: `jtreesitter.Node` beim Durchlaufen – jedes Knotenobjekt hat eine eigene Arena mit Cleaner, bei großen
  Projekten läuft der Heap voll. Stattdessen kopiert `SyntaxNode` jede Datei einmal per Tree-Cursor in eine schlanke
  Java-Struktur und gibt den nativen Baum sofort frei.

* **Knoten:** `package`, `file`, `class`/`interface`/`enum`/`record`/`annotation`, `method`, `constructor`, `field`
  (mit Datei, Zeilen, Modifiern, Signatur, erstem Javadoc-Satz) sowie `external` für referenzierte Bibliothekstypen.
  IDs: `com.acme.OrderService`, `com.acme.OrderService#save(Order)`, `…#<init>(Repo)`, `…#repo`, `file:src/…`.
* **Kanten:** `contains`, `imports`, `extends`, `implements`, `overrides`, `calls`, `instantiates`, `has_type`
  (Feld-/Rückgabetyp innerhalb des Projekts), `annotated_with`. Jede Kante trägt die Sicherheit wie bei graphify:
  `EXTRACTED` (steht so im Code, z.B. Aufruf über ein Feld mit deklariertem Typ), `INFERRED` mit Score (abgeleitet,
  z.B. über den Rückgabetyp einer Aufrufkette 0.8, über den Methodennamen bei unbekanntem Empfänger 0.6,
  `overrides` ohne `@Override` 0.9), `AMBIGUOUS` (Überladungen gleicher Stelligkeit – Kante zu jedem Kandidaten).
  Mehrfache Fundstellen werden zu einer Kante mit `count` zusammengefasst.
* **Grenzen:** keine vollständige Typinferenz (Lambdas, Generics-Rückgaben, `var` aus externen Aufrufen) und kein
  Classpath – Aufrufe auf Bibliothekstypen erzeugen keine Kante, Obertypen außerhalb des Projekts werden nur über
  Imports erkannt.
* **Communities:** Louvain über die auf Typen hochgezogenen Kanten, deterministisch (graphify verwendet Leiden).
  Member erben die Community ihres Typs.
* **Je Projekt und Branch:** Gebaut wird immer der im Arbeitsverzeichnis ausgecheckte Git-Branch (JGit); gespeichert
  wird unter Projektwurzel + Branch, ohne Git genau ein Graph. Alle Abfrage-Tools nehmen optional `branch` und lesen
  dann einen anderen gespeicherten Branch (`graph_branches` listet sie); ohne Angabe gilt der ausgecheckte. Nach
  jedem Aufbau werden Graphen von Branches gelöscht, die es weder lokal noch auf einem Remote mehr gibt.
* **Ablage Neo4j (Standard)** über **Spring Data Neo4j** – Hibernate OGM für Neo4j ist seit Jahren eingestellt
  (javax, Neo4j 3.x). Verwaltungsdaten sind Entities (`GraphProjectEntity` `(:GraphProject {root, name})`
  `-[:HAS_BRANCH]->` `GraphBranchEntity` `(:GraphBranch {key, branch, commitId, graphId, stats, communities, …})`,
  gespeichert per `Neo4jTemplate`); die Code-Knoten und Kanten schreibt `Neo4jClient` als Bulk-Cypher
  (`UNWIND`-Batches à 10.000) – Entity-Mapping wäre für eine Million Kanten viel zu langsam:
  `(:CodeNode:<Class|Method|…>[:Type|:Member] {g, id, kind, name, file, line, …, community, t})`,
  echte Relationship-Typen `CALLS`, `EXTENDS`, … mit `{conf, score, count, line}`, dazu `(:SourceFile {g, path,
  sha256, …})` für die Änderungserkennung. `g` ist die `graphId` des Branches: jeder Aufbau schreibt eine neue
  Generation und schaltet `GraphBranch.graphId` erst am Ende in einer Transaktion um, danach wird die alte entfernt –
  Leser sehen nie einen halben Graphen; Reste eines abgebrochenen Aufbaus (`pendingGraphId`) räumt der nächste auf.
  Constraints/Indizes legt der Server selbst an (u.a. eindeutiges `uid = g|id` für den Knoten-Lookup beim
  Kantenimport). Gemessen an Vaadin Flow (3.600 Dateien, 46.000 Knoten, 186.000 Kanten, Neo4j 2026.06 Community im
  Container): Aufbau 10,5 s (Datei 3,6 s), davon Schreiben 6,2 s; Prüfung „aktuell?“ 0,15 s; Abfragen 50–500 ms. Alle Abfragen laufen als Cypher in der Datenbank (`Neo4jGraphReader`),
  der Graph wird nie komplett geladen. Die Verbindung wird aus den Moduleinstellungen gebaut (kein Spring-Bean,
  Änderungen gelten sofort); die Community Edition genügt.
* **Ablage Datei** (`storage=file`): `devtools-fileinfo@<branch>.graph` (ohne Git `devtools-fileinfo.graph`) im
  Projektwurzelverzeichnis: JSON mit `stats`, `communities`, `files` (Pfad, SHA-256, Zeilen, Syntaxfehler), `nodes`,
  `edges` – ein Eintrag je Zeile, damit Diffs lesbar bleiben. Kompakt: Kanten sind Arrays
  `[von, nach, relation, sicherheit?, score?, anzahl?, zeile?]` mit Indizes in `nodes`, Standardwerte (`EXTRACTED`,
  Datei eines Members = Datei seines Typs) entfallen. Wird atomar geschrieben; ein anderes Format (`version`) führt
  zum Neubau. Abfragen laufen auf dem geladenen Graphen (höchstens zwei im Speicher). Soll die Datei nicht ins
  Repository, `devtools-fileinfo*.graph` in `.gitignore` aufnehmen.
* **Aktualität:** `graph_build` baut nur neu, wenn sich eine Quelldatei geändert hat (SHA-256) oder Dateien
  hinzugekommen/entfallen sind; die Abfrage-Tools bauen, falls der Graph des ausgecheckten Branches fehlt.
* **Abfragen:** `graph_report` (God Nodes, meistaufgerufene Methoden, Communities, überraschende Verbindungen zwischen
  Paketen), `graph_find` (Name, `*`-Platzhalter), `graph_explain` (alles zu einem Knoten), `graph_neighbors`
  (Aufrufbaum, `direction=in` = wer ruft mich), `graph_path` (kürzester Weg, ohne Abkürzung über externe Typen;
  Neo4j: `shortestPath`), `graph_query` (Frage → Stichworte inkl. CamelCase und einfacher Wortstämme wie *gebucht* ~
  `buchen` → beste Treffer, Testcode nachrangig → verbindender Teilgraph), `graph_branches` (gespeicherte Branches),
  `graph_cypher` (freies, nur lesendes Cypher – Lesetransaktion, `$g` ist auf Projekt+Branch gesetzt und Pflicht).
  Beide Ablagen liefern dieselben Antworten (`Neo4jGraphStorageTest` vergleicht die Ausgaben).
* **Einstellungen:** Projekte/Sammelordner, Standardprojekt, Ablage (`neo4j`/`file`), Neo4j-URI, -Benutzer,
  -Passwort (verschlüsselt), -Datenbank, Ausschlüsse (Ordnername außerhalb von `src/`, relativer Pfad oder
  `*.endung`), Tests einbeziehen, max. Dateien. *Verbindung testen* prüft Neo4j und listet die gespeicherten Branches.
  Lokaler Server z.B.: `podman run -d --name neo4j -p 7474:7474 -p 7687:7687 -e NEO4J_AUTH=neo4j/<passwort> neo4j`.
* **Indizieren in der App:** Im Modul unter **Aktionen** ein Projekt wählen und *Indizieren* klicken (optional
  *Komplett neu*) – mit Fortschrittsbalken, Abbrechen und dem Stand der vorhandenen Graph-Datei. Läuft mit der
  gespeicherten Konfiguration und auch bei inaktivem Modul, d.h. ohne dass `graph_*`-Tools beim LLM erscheinen.
  Andere Module können eigene Aktionen über `ToolModule#actions()` (`core/ModuleAction`) anbieten.

### Skills – prozedurales Gedächtnis des LLM

Angelehnt an das Skill-Management von Hermes: Das LLM sucht vor einer Aufgabe mit `skills_list` passende Skills und
lädt sie mit `skills_view`. Nach einer schwierigen, mehrstufigen oder korrigierten Aufgabe legt es selbst einen Skill
an (`skills_create`) oder verbessert einen bestehenden gezielt (`skills_patch`, `old_string` → `new_string`, muss
eindeutig sein). Wann das passieren soll, steht in den Server-Instructions und in den Tool-Beschreibungen.

* **Aufbau** wie ein `SKILL.md`: Name (`a-z0-9._-`), ein Satz `description` („wann greift der Skill“), Kategorie,
  Tags, Markdown-Inhalt, dazu Zusatzdateien unter `references/`, `templates/`, `scripts/`, `assets/`.
* **Historie:** jede Änderung erzeugt eine Revision mit Aktion und Notiz (`skills_history`). Mit
  `expected_revision` lehnt ein Patch ab, wenn der Skill inzwischen woanders geändert wurde.
* **Persistenz:** Spring Data JPA (`SkillRepository`, `SkillRevisionRepository`; Zusatzdateien hängen per Cascade am Skill) auf
  Hibernate ORM 7 und HikariCP, Transaktionen per `@Transactional` im `SkillService`. Standard ist eine lokale
  H2-Datei `~/.devtools-mcp/skills.mv.db`; Schema per `hibernate.hbm2ddl.auto=update`. JDBC-URL, Benutzer, Passwort
  (verschlüsselt) und Schema-Modus sind im Modul einstellbar. Andere Datenbanken brauchen ihren JDBC-Treiber auf dem
  Classpath.
* **Verbindung ändern:** `SkillsPersistenceConfig` baut die `DataSource` beim Start aus den Modul-Einstellungen
  (nicht aus `application.properties`). Neue Werte gelten deshalb **erst nach einem Neustart der App**;
  *Verbindung testen* prüft sie vorher per JDBC, ohne die laufende Verbindung anzufassen. Ist die Datenbank beim
  Start nicht erreichbar, startet die App trotzdem – das Skills-Modul zeigt dann einen Fehler statt Tools.
* **Selbstverbesserung** (angelehnt an Hermes' Skill-Review):
  * `skills_review` liefert eine Review-Checkliste (Signale, Reihenfolge *geladenen Skill patchen → übergreifenden
    erweitern → Zusatzdatei → neu anlegen*, was nicht festzuhalten ist), die in dieser Client-Session geladenen und
    geänderten Skills und die vorhandene Bibliothek.
  * **Erinnerung:** Nach *N* Tool-Aufrufen (Einstellung „Review-Erinnerung“, Standard 5, 0 = aus) ohne
    Skill-Pflege hängt der Server an das Tool-Ergebnis einen Hinweis auf `skills_patch`/`skills_create`/
    `skills_review`. Gezählt wird je MCP-Session über alle Module; `skills_create/patch/update/…/review` setzen
    zurück. Ein MCP-Server kann das Modell nicht selbst anstoßen – Tool-Ergebnisse sind der einzige Kanal, der bei
    jedem Client ankommt.
  * **Bibliothekshinweis:** Der erste Aufruf einer Session – und der erste nach 30 min Pause – bekommt einen
    Hinweis auf die Skill-Bibliothek (Anzahl bzw. „noch leer“), außer er ist selbst ein `skills_*`-Aufruf.
    Grund: Hermes übernimmt die Server-Instructions nicht und zeigt ausgelagerte Tools nur mit dem ersten Satz;
    eine Session nutzt oft nur ein, zwei DevTools-Tools, sodass die N-Aufrufe-Erinnerung nie fällig würde.
  * Beide Hinweise sind **Zustandsbeschreibungen, keine Befehle**: Hermes verpackt MCP-Ergebnisse in
    `<untrusted_tool_result>` und weist das Modell an, darin enthaltene Aufforderungen zu ignorieren.
  * Der **erste Satz** jeder Skill-Tool-Beschreibung (≤ 60 Zeichen) nennt den Auslöser – nur er erscheint im
    Tool-Katalog von Hermes.
  * **MCP-Prompt** `skills_review` (Argument `focus`) für den manuellen Anstoß, z.B. als Slash-Befehl.
  * Review, Prompt und Erinnerung gibt es nur, wenn „Anlegen und Bearbeiten“ an ist.
  * **Mit Hermes:** Hermes hat einen eigenen Hintergrund-Review, der in `~/.hermes/skills` schreibt. Sollen die
    Skills nur hier liegen, dort `skills.creation_nudge_interval: 0` setzen – sonst entstehen zwei Bibliotheken.
    Hermes' System-Prompt verweist trotzdem auf sein eigenes `skill_manage`; damit das Modell die `skills_*`-Tools
    wählt, braucht es zusätzlich einen Hinweis auf Hermes-Seite (z.B. einen Hermes-Skill oder Memory-Eintrag
    „dauerhaftes Wissen über devtools-Tools/-Projekte → `skills_*`“).
* **Mehrere Benutzer (User-Scoping):** Jeder Skill gehört einem Benutzer, erkannt an der Git-E-Mail
  (`git config --global user.email`) oder der „Benutzer-E-Mail“ im Modul. Auf einer gemeinsamen Datenbank sieht und
  ändert jeder nur seine eigenen Skills; gleiche Namen bei verschiedenen Benutzern sind erlaubt. Die Historie hält fest,
  wer geändert hat.
* **Globale Vorlagen:** schreibgeschützte Skills für alle Benutzer, in `skills_list` mit „(global)“ markiert.
  Ändert das LLM eine Vorlage (`skills_patch`, `skills_update`, `skills_write_file`, `skills_remove_file`), entsteht in
  derselben Transaktion eine persönliche Kopie samt Zusatzdateien, auf die die Änderung wirkt; sie verdeckt ab dann die
  Vorlage. Schlägt die Änderung fehl, bleibt auch keine Kopie zurück. Löschen der Kopie zeigt wieder die Vorlage;
  Vorlagen selbst sind nicht löschbar.
  * Veröffentlichen/Aktualisieren und Zurückziehen nur in der App (Tab **Skills**) mit dem Schalter „Globale Vorlagen
    verwalten“. Der Schalter ist Komfort, kein Zugriffsschutz – wer Vorlagen wirklich absichern will, vergibt die
    Schreibrechte in der Datenbank entsprechend.
  * Wurde eine Vorlage nach dem Kopieren weiterentwickelt, zeigt die Übersicht die Kopie als „Kopie ⟳“ mit beiden
    Revisionen.
* **Bestehende Datenbanken** (vor dem User-Scoping) werden beim Start einmalig angehoben: Spalte `owner` ergänzen, alle
  vorhandenen Skills dem aktuellen Benutzer zuordnen, alte Regel „Name global eindeutig“ entfernen. Nur mit Schema
  `update`; ohne bekannten Benutzer bleibt die Datenbank unangetastet und das Modul meldet den Fehler.
* **Übersicht in der App:** Tab **Skills** – links alle Skills (Name, Kategorie, Revision, wie oft geladen, zuletzt
  geändert) mit Suche über Name/Beschreibung/Tags und Kategorie-Filter, rechts Beschreibung, Metadaten, Inhalt,
  Zusatzdateien und Änderungshistorie mit dem jeweiligen Stand. Legt oder ändert das LLM einen Skill, aktualisiert
  sich die Übersicht selbst (nach dem Commit, nicht bei Rollback). Löschen geht hier auch ohne den Schalter
  „Löschen erlauben“ – der gilt nur für das LLM.
* Die H2-Datei ist exklusiv gesperrt, solange die App läuft. Wer parallel mit IntelliJ o.ä. hineinschauen will,
  hängt `;AUTO_SERVER=TRUE` an die JDBC-URL.

### Instructions für das LLM

Beim `initialize` schickt der Server MCP-`instructions`, die Clients wie Claude Code in den System-Prompt übernehmen.
Sie legen fest, **wann welches Tool statt eines Shell-Befehls** zu verwenden ist – z.B. `git_status` statt
`git status`, `build_test` statt `./gradlew test`, `container_list` statt `podman ps`. `ServerInstructions` setzt den
Text aus einem allgemeinen Vorrang-Hinweis, dem optionalen `spring.ai.mcp.server.instructions` und den
`instructions()` aller Module zusammen (Reihenfolge wie die Modulliste). Ein eigenes Modul ergänzt seine Hinweise
über `ToolModule#instructions()`.

Die Instructions werden bei **jedem `initialize`** neu gebaut: Das MCP-SDK friert den Text beim Serveraufbau ein,
deshalb liegt um den WebFlux-Transport eine Hülle (`core/LiveInstructionsTransport`), die im Session-Aufbau das
`InitializeResult` mit dem aktuellen Text ersetzt. Installierte, aktivierte oder entfernte Plugins sind so für jede
**neue** Client-Session sofort berücksichtigt. Eine bestehende Session behält den Text ihres `initialize` – MCP kennt
keine Änderungsbenachrichtigung für Instructions; der Client muss neu verbinden. Abgeschaltete eingebaute Module sind
enthalten, die Texte sind bedingt formuliert („wenn angeboten“), die aktuell verfügbaren Tools liefert weiterhin
`tools/list`. Codeänderungen an eingebauten Texten brauchen natürlich einen Neustart der App.

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

## Plugins

Neue Module lassen sich auch **ohne Änderung an der App** ergänzen – als Plugin-Jar, angelehnt an Bukkit. Plugins liegen
in `~/.devtools-mcp/plugins/` (Tab **Plugins** → *Ordner öffnen*), werden beim Start geladen und lassen sich zur
Laufzeit installieren, aktualisieren, an-/abschalten und entfernen; verbundene Clients erhalten sofort
`tools/list_changed`. Die Module eines Plugins erscheinen in der Modulliste wie eingebaute (mit „· Plugin *name*“),
inkl. Formular, Schaltern, Aktionen und Protokoll.

### Plugin schreiben

`src/main/resources/plugin.yml`:

```yaml
name: jira                        # Pflicht, [a-z][a-z0-9-]*, eindeutig
version: 1.2.0                    # Pflicht
main: com.acme.jira.JiraPlugin    # Pflicht, erweitert DevToolsPlugin
api-version: 1                    # optional; höher als die App → Plugin wird abgewiesen
description: Tickets lesen und kommentieren
author: Team Tools                # oder authors: [a, b]
website: https://git.acme.de/jira-plugin
depend: [http-commons]            # Pflicht-Abhängigkeiten: werden vorher geladen, ihre Klassen sind sichtbar
softdepend: [sonar-extras]        # optional: vorher geladen, falls vorhanden
libraries:                        # Maven-Koordinaten, beim Laden samt transitiver Abhängigkeiten aufgelöst
  - com.squareup.okhttp3:okhttp:4.12.0
```

Jedes Plugin hat einen **eigenen Spring-Kontext** – Code wie in einer Spring-Anwendung:

```java
public class JiraPlugin extends DevToolsPlugin {          // ist selbst Bean und @Configuration
    @Bean
    JiraClient jiraClient(@Value("${jira.timeout:30}") int timeout) { return new JiraClient(timeout); }
}

@Component                                                  // ToolModule-Beans werden automatisch Module
class JiraModule implements ToolModule {
    private final JiraClient client;
    private final SettingsStore settings;                   // Bean der App

    JiraModule(JiraClient client, SettingsStore settings, PluginContext plugin) { … }

    @PostConstruct void connect() { … }
    @PreDestroy void close() { … }

    public String id() { return "jira"; }                   // → Tools jira_*
    public List<ToolCallback> createTools(ModuleConfig c) { return List.of(ToolCallbacks.from(new JiraTools(client))); }
    …
}
```

* **Scan:** Paket der Hauptklasse samt Unterpaketen, nur im Plugin-Jar (nicht in `libraries` oder der App). Ein
  `@ComponentScan` auf der Hauptklasse ersetzt das; `@Import`, `@Configuration`, `@Bean` wirken wie gewohnt.
* **Injizierbar:** alle eigenen Beans, `PluginContext`, `PluginDescriptor` und die Beans der App (`SettingsStore`,
  `ToolRegistry`, `JavaEnvironmentProvider`, `SkillService` …). `@Value` sieht die Properties der App. Eltern ist die
  BeanFactory der App, nicht ihr Kontext: Plugin-Beans sind für die App unsichtbar, Ereignisse des Plugin-Kontexts
  erreichen sie nicht.
* **Ohne Spring:** geht weiter wie bei Bukkit – `registerModule(new JiraModule(dataFolder()))` in `onEnable()`.
  Ein Modul, das `@Component` ist *und* per `registerModule` gemeldet wird, zählt einmal.
* **Fehler** beim Aufbau (fehlende Bean, Exception in `@PostConstruct`) lassen nur dieses Plugin scheitern; die
  Meldung von Spring steht im Tab **Plugins**.

Build (Gradle) – die App stellt die API bereit, ins Jar gehört nur der eigene Code:

```kotlin
dependencies {
    compileOnly("systems.grebe:devtools-mcp:0.1.0-SNAPSHOT") // ./gradlew publishToMavenLocal in diesem Repo
}
```

* **Lebenszyklus:** Kontext aufbauen (`@PostConstruct`) → `onLoad()` → `ToolModule`-Beans aufnehmen → `onEnable()`;
  beim Abschalten, Entfernen, Aktualisieren und Beenden `onDisable()` → Module entfernen → Kontext schließen
  (`@PreDestroy`) → ClassLoader freigeben (Spring-Caches werden geleert; ein Test prüft, dass er per GC verschwindet).
  Eine Exception lässt nur dieses Plugin scheitern (Status „Fehler“ mit Meldung im Tab), der Rest läuft weiter.
  Plugins, die per `depend` auf ein abgeschaltetes Plugin zeigen, werden mit abgeschaltet.
* **`PluginContext`** (`context()`): `registerModule`, `dataFolder()` (`plugins/<name>/`, bleibt beim Entfernen
  erhalten), `logger()` (`plugin.<name>`), `plugin(name)` (andere aktive Plugins), `apiVersion()`.
* **ClassLoader:** je Plugin ein eigener; Reihenfolge *App → Plugin → depend/softdepend*. App-Bibliotheken (Spring AI,
  Jackson, SLF4J …) gibt es damit genau einmal in der Version der App, eigene `libraries` nur für Klassen, die die App
  nicht mitbringt. Bei jedem Aufruf in Plugin-Code (Tools, Formular, Aktionen, Verbindungstest) ist der
  Thread-Context-ClassLoader der des Plugins – `ServiceLoader` und Jackson finden die Plugin-Klassen.
* **Modul-IDs** sind app-weit eindeutig (2–32 Kleinbuchstaben/Ziffern); eingebaute IDs sind gesperrt. Einstellungen
  und Schalter eines Plugin-Moduls liegen wie bei eingebauten in `settings.json` und überleben Updates.
* **Instructions:** `instructions()` aktiver Plugin-Module stehen ab der nächsten Client-Session in den
  MCP-Instructions – ohne Neustart (siehe „Instructions für das LLM“). Die Tools sind sofort in `tools/list`.
* **Sicherheit:** Plugins laufen im Prozess der App mit denselben Rechten – kein Sandboxing. Nur Plugins aus
  vertrauenswürdigen Quellen installieren; der Store prüft Prüfsummen, keine Signaturen.

### Plugin-Store (Maven)

Der Store lädt Plugins als gewöhnliche Jar-Artefakte aus Maven-Repositories – per Maven Resolver, derselben Bibliothek
wie in Maven selbst: Versionen aus `maven-metadata.xml`, Prüfsummen (Abbruch bei Abweichung), Basic-Auth, Proxy aus den
JVM-Einstellungen, `file:`-Repositories. Heruntergeladenes landet in `plugins/.repository`, nicht in `~/.m2`.

* **Repositories** (Tab *Repositories*): voreingestellt Maven Central; eigene (Nexus, Artifactory, Bitbucket/GitLab
  Packages, `file:`-Ordner) mit ID, URL, optional Benutzer und Passwort/Token (AES-GCM verschlüsselt in
  `settings.json`, Feld leer lassen = unverändert), SNAPSHOT-Freigabe und Katalog. Reihenfolge = Suchreihenfolge,
  *Testen* prüft Erreichbarkeit und Katalog.
* **Katalog** (optional, je Repository): ein YAML-Artefakt mit Extension `yml`, deployt wie jedes andere, z.B.
  `mvn deploy:deploy-file -DgroupId=com.acme -DartifactId=devtools-plugins -Dversion=3 -Dpackaging=yml -Dfile=catalog.yml …`.
  Der Store liest immer die neueste Version.

  ```yaml
  plugins:
    - coordinates: com.acme.devtools:jira-plugin   # groupId:artifactId
      name: Jira
      description: Tickets lesen und kommentieren
      author: Team Tools
      tags: [ticket, atlassian]
  ```
* **Store** (Tab *Store*): *Katalog laden* → suchen → Version wählen → *Installieren*. Ohne Katalog direkt über
  `groupId:artifactId[:version]` (ohne Version: neueste). *Nach Updates suchen* vergleicht über den Store installierte
  Plugins mit der neuesten Version (Maven-Versionsvergleich, `1.10.0` > `1.9.0`); *Aktualisieren* tauscht sie ohne
  Neustart. Plugins aus Dateien (*Jar installieren…* oder in den Ordner kopiert und *Neu laden*) haben keine Quelle
  und werden nicht auf Updates geprüft.

## Architektur

```
DevToolsMcpApplication ── main() → JavaFX
fx/FxApp                ── init(): Spring-Kontext starten · start(): Fenster + Tray · stop(): Kontext schließen
core/ToolModule         ── Erweiterungspunkt (SPI)
core/ToolRegistry       ── Module ⇄ McpSyncServer (addTool/removeTool zur Laufzeit, notifyToolsListChanged)
core/ManagedToolCallback── Präfix, Protokollierung, Klartext-Ergebnisse
config/SettingsStore    ── JSON-Persistenz, SecretCipher (AES-GCM)
server/BearerTokenFilter── optionaler Token-Schutz
modules/{git,sonar,build,graph,…}
plugin/PluginManager    ── Plugin-Ordner, plugin.yml, ClassLoader je Plugin, Lebenszyklus, depend-Reihenfolge
plugin/store/           ── Plugin-Store: Maven Resolver, Repositories, Katalog, Updates
ui/                     ── MainView, ModuleDetailPane, ConfigForm (schema-getrieben), InvocationLogView, PluginsView, Dialoge
```

MCP-Server: Spring AI `spring-ai-starter-mcp-server-webflux` 2.0.1 (MCP Java SDK 2.0.0), Protokoll `STREAMABLE`.
