# DevTools MCP

Desktop-Anwendung (Spring Boot 4 + JavaFX 25, Java 25), die einen lokalen **MCP-Server** bereitstellt.
LLM-Clients (Claude Code, Claude Desktop, Cursor, VS Code, Hermes …) erhalten darüber Werkzeuge für den
Entwickleralltag. Alles wird in der Oberfläche konfiguriert; neue Werkzeuge lassen sich mit einer Klasse ergänzen.

| Modul | Tools |
|---|---|
| **Git** (JGit; Netzwerk über installiertes git) | `git_list_repositories` (inkl. Worktrees als `<repo>/<ordner>`), `git_status` (inkl. laufendem Merge/Rebase), `git_log` (auch `contentChange` wie `git log -S`), `git_diff`, `git_show_commit`, `git_branches`, `git_tags`, `git_remotes`, `git_stash_list`, `git_reflog`, `git_compare` (merge-base, voraus/zurück, Commits je Seite), `git_grep`, `git_blame`, `git_file_at_revision` · schreibend (Standard an): `git_create_branch`, `git_checkout`, `git_rename_branch`, `git_stage`, `git_unstage`, `git_commit`, `git_reset` (soft/mixed), `git_stash` (push/apply/pop), `git_tag` · je Schalter (Standard aus): Remote-Abgleich `git_fetch`, `git_pull` (ff-only/rebase/merge), `git_push` (nie Force, nie auf „Nie pushen auf“, Standard main/master) · Integrieren `git_merge`, `git_rebase`, `git_cherry_pick`, `git_revert`, `git_continue`, `git_abort` · Verwerfen `git_restore`, `git_reset mode=hard`, `git_delete_branch`, `git_delete_tag`, `git_stash_drop` |
| **SonarQube** / SonarCloud | `sonar_list_projects`, `sonar_quality_gate`, `sonar_issues`, `sonar_issue_detail`, `sonar_rule`, `sonar_measures`, `sonar_hotspots`, `sonar_source` |
| **Build** (Gradle/Maven) | `build_list_projects`, `build_run`, `build_test`, `build_test_report` |
| **Code-Graph** (Java, tree-sitter) | `graph_build`, `graph_branches`, `graph_report`, `graph_find`, `graph_explain`, `graph_neighbors`, `graph_path`, `graph_query`, `graph_cypher` – je Projekt und Git-Branch in Neo4j (Spring Data Neo4j) oder als Datei im Projekt (Standard: aus) |
| **JVM-Diagnose** (jcmd) | `jvm_processes`, `jvm_info`, `jvm_threads` (inkl. Deadlock-Erkennung), `jvm_heap`, `jvm_native_memory` · invasiv: `jvm_heap_dump`, `jvm_gc_run`, `jvm_jcmd` (Allowlist) |
| **Flight Recorder** | `jfr_record`, `jfr_start`, `jfr_status`, `jfr_dump`, `jfr_stop`, `jfr_analyze` (cpu/allocation/gc/locks/io/exceptions/threads), `jfr_flamegraph` |
| **async-profiler** 4.5 | `asprof_profile`, `asprof_start`, `asprof_stop`, `asprof_status` – Linux/macOS nativ, unter Windows für JVMs in Docker/Podman-Containern (Standard: aus) |
| **VisualVM** | `visualvm_heap_analyze` (Heap-Engine: Histogramm, Retained Size, Pfad zur GC-Wurzel), `visualvm_sample_cpu` (JMX-Sampler, `.nps`-Snapshot), `visualvm_open`, `visualvm_open_file` (externe VisualVM-GUI) |
| **Decompiler** (Vineflower, Fernflower-Fork) | `decompile_class` (Quelltext einer Klasse inkl. verschachtelter Klassen, seitenweise), `decompile_find` (Fundstellen im JDK und in Maven-/Gradle-Cache mit Version), `decompile_list` (Klassen eines JARs, Klassenverzeichnisses oder JDK-Moduls) |
| **Debugger** (JDI) | `debug_attach`, `debug_sessions`, `debug_detach`, `debug_set_breakpoint`, `debug_clear_breakpoint`, `debug_wait_for_break`, `debug_threads`, `debug_stack`, `debug_variables`, `debug_step`, `debug_resume` (Standard: aus) |
| **Container (OCI)** | lesend: `container_runtimes`, `container_list`, `container_inspect` (Geheimnisse maskiert), `container_logs`, `container_stats`, `container_top`, `container_diff`, `container_images`, `container_networks`, `container_volumes` · je Schalter (Standard aus): `container_exec`, `container_start`/`stop`/`restart`, `container_copy_from`/`copy_to`, `container_run`, `container_pull`, `container_rm`, `container_rmi`, `container_compose_up`/`down`/`restart` · mit Compose-Projekten: `container_compose_projects`/`ps`/`logs`/`config` |
| **Tickets** (Jira, GitHub, GitLab, YouTrack, OpenProject; erweiterbar per ServiceLoader) | `ticket_providers`, `ticket_boards`, `ticket_board` (Board nach Spalten: Jira-Sprint/Kanban, GitHub Project, GitLab-Issue-Board, YouTrack-Agile-Board, OpenProject-Board), `ticket_search`, `ticket_get` (Titel, Status, Zuständige, Beschreibung, Kommentare), `ticket_status` (mehrere Tickets), `ticket_links`, `ticket_transitions` · je Schalter (Standard aus): `ticket_comment`, `ticket_transition`, `ticket_assign`, `ticket_update`, `ticket_create`, `ticket_delete_comment`/`ticket_delete` (standardmäßig nur selbst angelegte), `ticket_classify` (Pre-Classifier: Komplexität einschätzen, Modell für die Umsetzung empfehlen), einschränkbar auf Projekte (Modul Standard: aus) |
| **Pull Requests** (GitHub, GitLab, Bitbucket Cloud/Data Center; erweiterbar per ServiceLoader) | `pr_providers`, `pr_list`, `pr_get` (Branches, Reviewer, Freigaben, Merge-Status, CI-Checks, Beschreibung), `pr_diff`, `pr_comments` (Threads mit ID, Datei/Zeile, offen/erledigt) · je Schalter (Standard aus): `pr_create`/`pr_update`, `pr_comment`/`pr_reply`, `pr_resolve`, `pr_merge`, `pr_push` (Feature-Branch per installiertem `git`, nie Force/Standard-Branch), einschränkbar auf Repositories; Server und Repository aus dem Remote des lokalen Repositories (Modul Standard: aus) |
| **SSH** (JSch) | `ssh_connections`, `ssh_disconnect`, `ssh_list_dir`, `ssh_read_file` · je Schalter: `ssh_exec` und interaktive Shells `ssh_shell_open`/`exec`/`read`/`send`/`close` (Standard an), `ssh_write_file`, `ssh_upload`/`ssh_download`, `ssh_sudo` (Standard aus) – für in der App hinterlegte Verbindungen (Name, Host, Port, Benutzer, Passwort oder Schlüsseldatei; Modul Standard: aus) |
| **Chat** (Matrix, Microsoft Teams; erweiterbar per ServiceLoader) | `chat_conversations`, `chat_send` (Markdown, Antwort/Thread), `chat_ask` (Frage stellen und auf die Antwort warten), `chat_receive` (neue Nachrichten/Anweisungen seit dem letzten Abruf, optional wartend, aus allen aktiven Systemen), `chat_history`, `chat_react`, `chat_login` (Teams: Anmeldung im Browser per Device Code) – beschränkbar auf Räume/Chats und freigegebene Absender (Modul Standard: aus) |
| **Modellwahl** | `classify_task` – Pre-Classifier für beliebige Aufgaben (Feature, Bugfix, Analyse, Text …): Komplexität einschätzen, Modell für die Umsetzung empfehlen (einfach → Haiku, normal → Sonnet, komplex → Opus) – über das LLM des aufrufenden Clients (MCP-Sampling bzw. Prompt zum Selbst-Ausführen, kein API-Key) oder die Claude API mit Claude Opus 5.5; Einstellungen auch für `ticket_classify` (Modul Standard: aus) |
| **Fenstersteuerung** (Fenster per FFM: Windows user32, macOS CoreGraphics/AX, Linux X11; eigener KI-Zeiger per JNA) | `window_list` (Prozesse mit Fenstern, PID, UI-Thread nur Windows; UWP-Apps wie der Rechner unter ihrem eigenen Prozess), `window_bind` (Prozess inkl. Kindprozessen, nie der Elternprozess, Geschwister per Schalter; Bindung je KI, d.h. je MCP-Session – ein Prozess gehört immer nur einer KI), `window_windows`, `window_screenshot` (PNG als Bild-Content, verkleinert auf max. 1280 px), `window_unbind` · Schalter „Programme starten erlauben“ (Standard aus): `window_launch` – startet ein Programm, das zu „Nur diese Prozesse“ passt, im Hintergrund und bindet es; das Fenster bekommt keinen Fokus, Eingaben des Nutzers landen erst dort, wenn er es anklickt (Windows: `ShellExecuteEx` mit `SW_SHOWNOACTIVATE`, holt es sich trotzdem den Vordergrund, wird er sofort zurückgegeben; macOS: `open -g`) · Schalter „Eingaben erlauben“ (Standard aus): `window_click`, `window_scroll`, `window_drag` · Schalter „Tastatur erlauben“ (Standard aus, nur zusammen mit Eingaben): `window_type` (Sonderzeichen über die Zwischenablage, wird wiederhergestellt), `window_key` (ohne Windows-/Super-Taste), Klicks mit gehaltenen Tasten · „Maus der KI“: `eigener-zeiger` (Standard) – Klicks, Ziehen und Scrollen mit einem zweiten Zeiger, die Maus des Nutzers bewegt sich nie und das Fenster wird nicht nach vorn geholt (Windows: Nachrichten an das Fenster, bei UWP/WinUI UI Automation bzw. Touch zum Ziehen; macOS: Ereignisse an den Prozess; X11: zweiter Master-Zeiger per XInput2), kein Klick, wenn ein fremdes Fenster darüber liegt; `maus` – echte Maus (Robot), Fenster wird aktiviert, Not-Aus bei Mausbewegung und Abkühlzeit · „Tastatur der KI“ (getrennt einstellbar): `eigene-tastatur` (Standard) – Text und Tasten gehen an das Element, das der Zeiger der KI zuletzt angeklickt hat (eigener Fokus, Strg/Umschalt/Alt nur im Ziel), die Tastatur des Nutzers bleibt unberührt (Windows: Nachrichten an das Element; macOS: Ereignisse an den Prozess; X11: eigene Master-Tastatur); `tastatur` – echte Tastatur (Robot), Fenster wird aktiviert. „Nur diese Prozesse“ und „Prozesse ausschließen“ mit Button „Fenster wählen…“: Übersicht aller Fenster mit Vorschau (ohne Vorschau Platzhalter mit Prozessname und PID), Klick zeigt das Fenster groß, „Hinzufügen“ hängt den Prozessnamen an den Ausdruck an · Programme, deren ausführbare Datei in einem Ordner unter „Freigaben“ liegt (auch in Unterordnern), sind freigegeben – auch gegen „Nur diese Prozesse“ und „Prozesse ausschließen“; hebt das einen Ausschluss auf, warnt die App beim Speichern (Suche nach Programmen in den freigegebenen Ordnern). Während der Steuerung Rahmen und Hinweis („Claude Code steuert dieses Fenster“) um das Fenster – jede KI in ihrer eigenen Farbe (reine Farbtöne mit größtmöglichem Abstand: Rot, Cyan, Gelbgrün, Violett …; auch der eigene Zeiger, außer unter X11), nach 30 min ohne Fenster-Tool wird ihre Bindung aufgehoben und die Farbe frei; diese App, Anmelde-/Berechtigungsdialoge und Passwortmanager sind nie steuerbar; nicht im Headless-Betrieb (Modul Standard: aus) |
| **Projekte** (Team-Server) | `projects_list` – eigene und freigegebene Projekte vom Team-Server mit Zugriff, lokalem Verzeichnis, Sonar-Schlüssel und Ticket-Projekt; Verwaltung und Freigaben in der Web-UI des Servers (Modul Standard: an) |
| **Maven-Artefakte** | `maven_latest_version` (neueste Release-/Vorabversion, Update-Einschätzung nach SemVer), `maven_artifact_info` (POM inkl. Parent: Lizenz, SCM, Java-Ziel, Relocation, Abhängigkeiten), `maven_breaking_changes` (API-Vergleich der JARs, POM-Änderungen, Breaking-Hinweise aus GitHub-Releases) – Maven Central oder eigener Mirror (Modul Standard: an) |
| **Skills** (Spring Data JPA, Standard H2) | registrierte Abläufe je Aufgabentyp (z.B. `ticket-review`): `skills_list`, `skills_view`, `skills_history` · schreibend (Standard an): `skills_create`, `skills_patch`, `skills_update`, `skills_write_file`, `skills_remove_file` · Selbstverbesserung: `skills_review` (Tool und MCP-Prompt) · Schalter (Standard aus): `skills_delete` |
| **Memories** (Spring Data JPA, Standard H2) | frühere Aktionen (was getan, entschieden, herausgefunden wurde): `memories_search`, `memories_view` · schreibend (Standard an): `memories_save`, `memories_update` · Schalter (Standard aus): `memories_delete` |

Das Modul **Java-Grundeinstellungen** hat keine eigenen Tools, es liefert JDK, Ablageordner, Prozessfilter
und JMX-Ziele für alle Performance-Module. Container-Laufzeit und freigegebene Container kommen aus dem
Container-Modul (ältere Einstellungen werden beim ersten Start übernommen).

Das Modul **Freigaben** hat ebenfalls keine eigenen Tools. *Für alle Tools freigegeben* nimmt Verzeichnisse oder
Sammelordner auf, die Git, Build, Code-Graph, Pull Requests und Compose zusätzlich zu ihren eigenen Listen bekommen –
jedes Modul übernimmt, was zu ihm passt (Git-Repositories, Gradle-/Maven-Projekte …). Der Schalter *Beschränkung
aufheben* lässt die Tools jeden absoluten Pfad verwenden (aufgelöst zum nächsten passenden Verzeichnis darüber, z.B.
dem Repository); nur lesend freigegebene Projekte des Team-Servers und die Schalter der Module gelten weiter.

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
Die UI bietet „nerdctl (containerd)“ dann automatisch in der Auswahl „Aktiv“ an und zeigt, solange die Laufzeit aktiv
ist, ihr Feld „Programm“; die Laufzeit erscheint in `container_runtimes` und ist über den Parameter `runtime` in allen
`container_*`-Tools wählbar. Laufzeiten ohne CLI implementieren
`ContainerRuntime` direkt (z.B. über eine REST-API).

### Ticket-Systeme erweitern (ServiceLoader)

Das Modul **Tickets** arbeitet nach demselben Muster über `modules/ticket/spi`: `TicketProvider` (ID, Felder, Hilfetexte)
erzeugt ein `TicketSystem` mit `boards`, `board`, `search`, `ticket` und optional `ownsKey`. Mitgeliefert sind

| Provider | Anbindung | Board = | Projekt | Schlüssel |
|---|---|---|---|---|
| `jira` | REST v2 + Agile 1.0; Cloud (`/search/jql`, Token-Paging, Basic mit E-Mail+API-Token) oder Data Center (`/search`, PAT als Bearer) | Scrum: aktiver Sprint, Kanban: offen + 14 Tage erledigt; Spalten aus der Board-Konfiguration | `ABC` | `ABC-123`, Browse-URL |
| `github` | REST (Issues, Suche) + GraphQL (Projects v2) | Project, Spalten = Single-Select-Feld `Status` (einstellbar) | `owner/repo` bzw. `owner` | `owner/repo#12`, `#12`, URL |
| `gitlab` | REST v4, Projekt oder Gruppe (automatisch erkannt) | Issue-Board: Open, Label-/Assignee-/Milestone-Listen, Closed | `gruppe/projekt` bzw. `gruppe` | `gruppe/projekt#12`, `#12`, URL |
| `youtrack` | REST `/api` mit Permanent Token (Bearer); Suche in der YouTrack-Suchsprache (`$skip`/`$top`), Custom Fields State/Assignee/Priority/Type, Tags = Labels | Agile Board: aktueller Sprint, Spalten aus den Board-Einstellungen (meist State) | `ABC` | `ABC-123`, Issue-URL |
| `openproject` | API v3 (HAL+JSON) mit API-Schlüssel (Basic `apikey:…`); Filter als JSON, keine Labels | Board (Grid): Spalten = gespeicherte Abfragen in Widget-Reihenfolge | Projekt-Kennung, z.B. `mein-projekt` | `#123`, `123`, Arbeitspaket-URL |

Schreibende Tools erscheinen nur mit ihrem Schalter:

| Schalter | Tool | Jira | GitHub | GitLab | YouTrack | OpenProject |
|---|---|---|---|---|---|---|
| `allowComment` | `ticket_comment` | Kommentar (Wiki-Markup) | Issue-Kommentar | Note | Kommentar (Markdown) | Aktivität mit Kommentar (Markdown) |
| `allowTransition` | `ticket_transition` | Workflow-Übergang | Schließen mit Grund/Wiedereröffnen, Spalte in jedem Project des Issues | Schließen/Wiedereröffnen, Board-Liste (Listen-Label tauschen) | Wert des State-Felds, als Befehl (`/api/commands`), damit Workflows greifen | Status, die der Workflow erlaubt (Formular-Endpunkt), PATCH mit `lockVersion` |
| `allowAssign` | `ticket_assign` | ein Zuständiger (DC: `name`, Cloud: `accountId`, Suche über zuweisbare Benutzer) | Logins, ignorierte werden gemeldet | Benutzer-IDs, `[0]` = niemand | Assignee-Feld (einfach oder mehrfach), Login/E-Mail/Name | ein Zuständiger aus `available_assignees` |
| `allowEdit` | `ticket_update` | Titel, Beschreibung, Labels | dito | dito | dito, Labels = Tags (`tag`/`untag`-Befehle) | Titel, Beschreibung (keine Labels) |
| `allowCreate` | `ticket_create` | Issue-Typ (Standard Task, bei Fehler Liste der gültigen) | Issue-Typ der Organisation | `issue_type` | Feld `Type`, Tags und Zuständige danach | Typ des Projekts (bei Fehler Liste der gültigen), sonst Standardtyp |
| `allowDelete` | `ticket_delete_comment`, `ticket_delete` | DELETE, Tickets mit Unteraufgaben werden abgelehnt (`deleteSubtasks=false`) | GraphQL `deleteIssue` (nur Repo-Admins, keine PRs); Kommentar wird vorher dem Issue zugeordnet | DELETE (Owner/Planner, ab 18.10 auch Autor) | DELETE | DELETE, Arbeitspakete mit Unteraufgaben werden abgelehnt (OpenProject löschte sie mit); Kommentare löschen kann die API nicht |

**Nur selbst angelegte löschen** (`deleteOnlyOwn`, Standard an – wie `removeOnlyOwn` beim Container-Modul): gelöscht werden
nur Tickets und Kommentare, die über `ticket_create` bzw. `ticket_comment` (auch der Kommentar von `ticket_transition`)
angelegt wurden. Das Modul merkt sie sich je System **und Instanz** (Server-URL) mit kanonischem Schlüssel in
`~/.devtools-mcp/tickets-own.json`, damit die Zuordnung einen Neustart übersteht; nach dem Löschen wird ausgetragen.
Fremdes wird abgelehnt, bevor ein Request rausgeht. `ticket_get` zeigt die Kommentar-IDs.

`writeProjects` („Schreiben nur in diesen Projekten“) schränkt alle schreibenden Tools ein: ein Eintrag je Zeile,
optional mit System (`jira:ABC`, `github:octo/*`); leer = alle. Geprüft wird das Projekt **aus dem Ticket-Schlüssel**,
nicht der Parameter `project` – `DEF-9` mit `project=ABC` wird abgelehnt, bevor ein Request rausgeht. Ausnahme
OpenProject: Nummern wie `#123` verraten das Projekt nicht, daher wird das Arbeitspaket dafür einmal (lesend) abgerufen
(`openproject:mein-projekt`). Ein Kommentar beim
Statuswechsel (`ticket_transition … comment=`) braucht zusätzlich `allowComment`; `commentSuffix` hängt eine Kennzeichnung
an jeden Kommentar. `ticket_links` und `ticket_transitions` sind lesend und immer da.

Provider implementieren Schreiben über `default`-Methoden von `TicketSystem` (`comment`, `transition`, `assign`, `update`,
`create`, `links`, `transitions`) – was ein Provider nicht kann, meldet das Tool als „nicht unterstützt“; bestehende
Plugin-Provider kompilieren unverändert.

In der UI wählt „Aktiv“ (Mehrfachauswahl) die Systeme; darunter stehen die Felder und das Standardprojekt des gerade
gewählten aktiven Systems, ein Umschalter wechselt zwischen ihnen. Ohne `provider` wählt das Modul das System, das
den Schlüssel als seinen erkennt (Jira-/YouTrack-Schlüssel, URL seines Hosts), sonst das Standard-System bzw. das
einzige aktive. Sind Jira und YouTrack beide aktiv, ist `ABC-123` mehrdeutig – dann entscheidet das Standard-System oder
der Parameter `provider`. Ein weiteres System (z.B. Redmine) braucht eine `TicketProvider`-Klasse und eine Zeile in
`src/main/resources/META-INF/services/systems.grebe.devtools.mcp.modules.ticket.spi.TicketProvider`; `spi/HttpJson`
(JSON über HTTP mit verständlichen Fehlermeldungen) steht Providern – auch aus Plugins – zur Verfügung.

### Modellwahl: Komplexität einschätzen (`classify_task`, `ticket_classify`)

Pre-Classifier vor der Umsetzung. Ergebnis sind Stufe, Sicherheit, Begründung, Faktoren, Risiken, offene Fragen und das
**empfohlene Modell** für die Umsetzung, z.B. als Modell eines Subagenten:

| Stufe | Typisch | Modell (Standard, einstellbar) |
|---|---|---|
| einfach | klar umrissen, wenige Schritte, leicht prüfbar (Texte, Konfiguration, Bugfix mit bekannter Ursache) | `claude-haiku-4-5` |
| normal | mehrere Schritte nach bekannten Mustern (Feature in einem Modul, strukturierte Analyse) | `claude-sonnet-4-5` |
| komplex | modulübergreifend, Architektur, Migrationen, Nebenläufigkeit, Sicherheit, vage Anforderungen, teure Fehler | `claude-opus-5-5` |

* `classify_task` (Modul **Modellwahl**) bewertet eine beliebige Aufgabe: `task` (Beschreibung), optional `title`, `kind`
  (Feature, Analyse, Text …), `scope` (Umfang) und `context` (Projekt, Architektur, Randbedingungen).
* `ticket_classify` (Modul **Tickets**, Schalter „Komplexität einschätzen“) lädt das Ticket selbst: Titel, Beschreibung,
  Typ, Priorität, Status, Labels, Story Points, weitere Felder wie Komponenten, die neuesten 10 Kommentare und die
  Verknüpfungen; `context` ergänzt den Architektur-Kontext aus dem Code. Jira-Story-Points (Custom Field, je Instanz
  andere ID) erkennt das Modul über `/rest/api/2/field`, GitLab liefert das Gewicht, YouTrack und OpenProject ihre Felder.

**Ausführung** (Modul Modellwahl → „Ausführung“):

| Modus | Wer schätzt ein | API-Key |
|---|---|---|
| `client` (Standard) | das LLM des aufrufenden Clients per MCP-Sampling (`sampling/createMessage`, Modellwunsch `claude-opus-5-5` mit höchster Priorität auf Intelligenz – die Wahl trifft der Client, die Ausgabe nennt das tatsächlich verwendete Modell und warnt bei einem anderen). Kann der Client kein Sampling (**Claude Code und Claude Desktop derzeit nicht**), liefert das Tool den fertigen Classifier-Prompt zurück und das aufrufende LLM führt die Einschätzung selbst durch, am besten per Subagent auf Opus | nein |
| `auto` | Sampling, wenn der Client es anbietet, sonst Claude API | für den Rückfall |
| `api` | immer direkt die Claude API mit `claude-opus-5-5` und Structured Output – das einzige Verfahren, das Opus 5.5 garantiert | ja |

Story Points zählen als Hinweis, nicht als Regel; zwischen zwei Stufen wählt der Classifier die höhere. Die Einstellungen
für beide Tools liegen im Modul Modellwahl (Standard aus): Ausführung, „Claude API-Key“ (nur `auto`/`api`; leer =
`ANTHROPIC_API_KEY` bzw. `ant auth login`), optional eine API-URL für ein Gateway, die Gründlichkeit (Effort, nur API,
Standard `high`), die Modelle je Stufe und eigene **Regeln** des Teams (eine je Zeile, z.B. „Änderungen am Lohnmodul sind
immer komplex“), die den allgemeinen Kriterien vorgehen. „Verbindung testen“ prüft im Modus `api`/`auto` Key und Modell,
ohne Token zu verbrauchen.

### Git-Server und Pull Requests (ServiceLoader)

Das Modul **Pull Requests** folgt demselben Muster über `modules/pr/spi`: `GitServerProvider` (ID, Felder, Hilfetexte)
erzeugt einen `GitServer` mit `list`, `get`, `diff`, `threads`, `projectOfRemote` und optional `ownsKey`; Schreiben
(`create`, `update`, `comment`, `reply`, `resolve`, `merge`) sind `default`-Methoden. Einstellungen und `HttpJson` teilt
es mit den Ticket-Providern. Mitgeliefert sind

| Provider | Anbindung | Repository | Pull Request | Threads auflösen |
|---|---|---|---|---|
| `github` | REST + GraphQL (Review-Threads) | `owner/repo` | `owner/repo#12`, URL | Code-Threads (`PRRT_…`) |
| `gitlab` | REST v4 (Merge Requests, Diskussionen, Pipeline inkl. fehlgeschlagener Jobs) | `gruppe/projekt` | `gruppe/projekt!12`, URL | Diskussionen |
| `bitbucket` | Cloud: API 2.0 (API-Token mit E-Mail als Basic oder Access Token als Bearer); Data Center: REST 1.0 + Build-Status (HTTP Access Token) – `deployment=auto` erkennt Cloud an bitbucket.org | `workspace/repo` bzw. `PROJ/repo`, `~user/repo` | `…#12`, URL | Code-Kommentare (Cloud), Threads und Aufgaben (DC) |

Server und Repository ergeben sich aus dem Remote (`remote`, Standard `origin`) des lokalen Repositories – die
Repository-Liste übernimmt das Modul beim ersten Start aus dem Modul Git, am Team-Server kommt sie aus den Projekten.
Ohne `pr` beziehen sich `pr_get`, `pr_diff`, `pr_comments`, `pr_reply` … auf den offenen Pull Request des aktuellen
Branches. `pr_create` nimmt den aktuellen Branch als Quelle und den Standard-Branch als Ziel und lehnt ab, solange der
Branch nicht gepusht ist. `pr_push` ruft `git push --porcelain -u <remote> <branch>` mit den Zugangsdaten des Rechners
auf (SSH-Schlüssel, Credential Manager), nicht interaktiv (`GIT_TERMINAL_PROMPT=0`), nie Force und nie auf
`main`/`master` bzw. den Standard-Branch des Remotes.

Typischer Ablauf „Review-Kommentare abarbeiten“: `pr_comments unresolved=true` → Code ändern, `git_commit` → `pr_push`
→ je Thread `pr_reply` (was geändert wurde) → `pr_resolve`. `writeProjects` schränkt alle schreibenden Tools auf
Repositories ein (`github:octo/*`, `PROJ/app`); geprüft wird das Repository aus dem Pull-Request-Schlüssel.
`commentSuffix` kennzeichnet Kommentare und Antworten. Ein weiterer Server (z.B. Gitea) braucht eine
`GitServerProvider`-Klasse und eine Zeile in
`src/main/resources/META-INF/services/systems.grebe.devtools.mcp.modules.pr.spi.GitServerProvider`.

### SSH

Verbindungen werden unter Module → SSH als Tabelle gepflegt (Name, Host, Port, Benutzer, Passwort, optional
Schlüsseldatei + Passphrase und eine Beschreibung für das LLM). Die ganze Liste liegt verschlüsselt in `settings.json`;
das LLM sieht nur Name, `benutzer@host:port`, Anmeldeverfahren und Beschreibung. Alle Tools nehmen `connection`
(Name, ohne Groß-/Kleinschreibung; leer = die einzige Verbindung).

* `ssh_exec` führt eine Befehlszeile in der Login-Shell aus (kein PTY), optional mit `workDir` und `stdin`; Ausgabe
  getrennt nach stdout/stderr, begrenzt auf „Max. Ausgabe (KB)“ und „Max. Ausgabezeilen“, Abbruch nach
  „Max. Befehlsdauer“. Sitzungen werden je Verbindung wiederverwendet (nach 10 min Leerlauf, bei geänderter
  Konfiguration und beim Beenden geschlossen). `ssh_disconnect` trennt sie sofort; offene Shells bleiben davon unberührt.
* Interaktive Shells für Abläufe über mehrere Befehle: `ssh_shell_open` → `ssh_shell_exec` → … → `ssh_shell_close`.
  Zustand (Verzeichnis, Variablen) bleibt erhalten. Ein Hintergrund-Thread puffert die Ausgabe; `ssh_shell_exec` wartet
  auf Exit-Code und Arbeitsverzeichnis oder liefert nach dem Timeout die Teilausgabe – der Befehl läuft weiter.
  `ssh_shell_read` holt die neue Ausgabe seit dem letzten Lesen (Mitlesen lang laufender Befehle) und meldet das Ende,
  `ssh_shell_send` schickt Eingaben (Rückfragen, REPL) oder `ctrl=c`. Optional mit PTY (sudo, top, less); Steuersequenzen
  werden entfernt. Befehlsgrenzen erkennt das Modul an Markierungen (POSIX-Shell):
  `{ printf '%s%s\n' 'DTMCP_B_' '<nonce>'; befehl⏎}; printf '%s%s__%s__%s\n' 'DTMCP_E_' '<nonce>' "$?" "$PWD"`. Der Block
  wird ganz gelesen, bevor der Befehl startet (ein Programm, das von stdin liest, verschluckt die Endmarkierung nicht);
  die Markierungen werden aus getrennten Teilen zusammengesetzt, damit das Echo mit PTY nie eine fertige enthält, und die
  Nonce ist zufällig (64 Bit), damit sich kein Exit-Code fälschen lässt – beides nach dem Vorbild von
  [ssh-mcp](https://github.com/tufantunc/ssh-mcp).
* Während `ssh_exec`, `ssh_shell_exec` und `ssh_shell_read` warten, sendet der Server die jeweils letzte Ausgabezeile als
  `notifications/progress`, sofern der Client ein `progressToken` mitschickt (`core/ToolProgress`, für alle Module nutzbar).
  Das sieht nur der Nutzer im Client – das LLM bekommt Ausgabe ausschließlich über die Tool-Ergebnisse. Jede Shell hat eine eigene SSH-Sitzung; höchstens „Max. offene Shells“, geschlossen nach
  „Shells schließen nach“ Minuten ohne Nutzung, bei geänderter Konfiguration und beim Beenden.
* Abbrechen ist ehrlich: bei Zeitüberschreitung von `ssh_exec` und bei `ssh_shell_close` (^C, `exit`) folgt die
  Signal-Leiter INT → TERM → KILL; das Ergebnis sagt, ob der Kanal danach zu ist oder der Prozess womöglich weiterläuft
  (Server ohne Signal-Unterstützung, Prozess ignoriert Signale).
* `ssh_sudo` (Schalter, Standard aus) führt `sudo -S -p '' -- sh -c '…'` aus; das Passwort (eigenes Feld je Verbindung,
  leer = Login-Passwort) geht per stdin an sudo, nie in die Befehlszeile oder zum LLM, und wird in der Ausgabe maskiert.
  Vorher prüft `sudo -n true`, ob überhaupt ein Passwort verlangt wird – sonst landete es auf dem stdin des Befehls.
* `ssh_upload`/`ssh_download` (Schalter, Standard aus) übertragen Dateien per SFTP zwischen diesem Rechner und dem
  Server, auch binär und groß, ohne den Inhalt durch den Kontext des LLM zu schicken. Lokal nur innerhalb von
  „Lokale Verzeichnisse für Übertragungen“ (auch Symlinks werden aufgelöst geprüft); vorhandene Ziele nur mit
  `overwrite=true`. Fortschritt in Prozent als `notifications/progress`.
* `ssh_list_dir`, `ssh_read_file` (Zeilenbereich, Binärdateien werden abgelehnt) und `ssh_write_file` laufen über SFTP.
* Host-Keys: `accept-new` (Standard) merkt sich den Schlüssel beim ersten Verbinden in `~/.devtools-mcp/ssh_known_hosts`
  und lehnt einen geänderten ab; `strict` akzeptiert nur Hosts, die schon in der Datei stehen. *Verbindung testen*
  verbindet sich mit jeder Verbindung und zeigt Server-Version und Fingerprint.

### Chat-Systeme (ServiceLoader)

Das LLM schreibt dem Nutzer über einen Chat und bekommt von dort Antworten und Anweisungen – etwa Rückfragen und
Freigaben, während der Nutzer nicht am Rechner sitzt, oder „fertig“-Meldungen nach langen Aufgaben. Das Modul **Chat**
folgt dem Muster der Tickets über `modules/chat/spi`: `ChatProvider` (ID, Felder, Hilfetexte) erzeugt ein `ChatSystem`
mit `account`, `conversations`, `resolve`, `send`, `poll`, `history` und optional `react`, `markRead`, `login`.
Eingang, Warten auf Antworten, Markdown und Ausgabe übernimmt das Modul. Mitgeliefert sind

| Provider | Anbindung | Unterhaltung | Anmeldung | Warten auf Antworten |
|---|---|---|---|---|
| `matrix` | Client-Server-API `/_matrix/client/v3`, `/sync` mit Long-Polling | Raum: `!id:server`, `#alias:server` oder Name | Zugangstoken oder Benutzer + Passwort (Gerät „DevTools MCP“ mit fester ID; läuft ein Token ab, meldet sich das Modul mit dem Passwort neu an) | ja |
| `teams` | Microsoft Graph v1.0, delegiert – schreibt unter dem Konto des Nutzers | Chat (1:1, Gruppe, Besprechung): `19:…`, Thema oder Name/E-Mail des Gegenübers | im Browser per Device Code (App-Aktion „Anmelden“ oder `chat_login`), Refresh-Token verschlüsselt in `chat-state.json` | nur mit „Warten durch Abfragen“ (Standard aus, siehe unten) |

* `chat_send` schickt Markdown; Formatierung geht als HTML mit (commonmark mit GFM-Tabellen und Durchstreichen; Matrix
  `formatted_body`, Teams Nachrichtentext), rohes HTML im Text wird maskiert. `replyTo` antwortet auf eine Nachricht
  (Teams: `replyWithQuote`), `thread=true` im Matrix-Thread dieser Nachricht. „Kennzeichnung eigener Nachrichten“ stellt
  z.B. „🤖“ voran.
* `chat_ask` stellt eine Frage und wartet bis `waitSeconds` (Standard „Wartezeit auf Antworten“, höchstens
  „Max. Wartezeit“) auf die Antwort: bevorzugt eine Antwort bzw. Thread-Nachricht auf die Frage, sonst die erste
  Nachricht eines freigegebenen Absenders danach – samt direkt folgender Nachrichten desselben Absenders. Was vor der
  Frage einging, zählt nicht als Antwort und bleibt für `chat_receive` liegen. Während des Wartens meldet das Tool den
  Stand als `notifications/progress`.
* `chat_receive` liefert jede neue Nachricht genau einmal – aus allen aktiven Systemen (mit Präfix `[matrix]`/`[teams]`)
  oder einem – und wartet mit `waitSeconds`, bis etwas eingeht. Für „hör auf den Chat“ ruft das LLM es in einer Schleife
  auf. Abgeholte Nachrichten werden als gelesen markiert (abschaltbar), `chat_react` setzt z.B. 👀/✅.
* `chat_history` zeigt den Verlauf (auch eigene Nachrichten) und ändert nichts am Eingang; `chat_conversations` nennt je
  System Konto, Standard-Unterhaltung, freigegebene Absender, Unterhaltungen und offene Einladungen.
* Ohne `provider` wählt das Modul das System an der Unterhaltung bzw. Nachricht (`!…`/`$…` = Matrix, `19:…`/Zahlen =
  Teams), sonst das Standard-System bzw. das einzige aktive.

Empfangen ohne Hintergrund-Thread: Jeder Abruf setzt beim gespeicherten Stand des Kontos auf (Matrix `next_batch`,
Teams je Chat der Zeitpunkt der letzten Nachricht), der in `~/.devtools-mcp/chat-state.json` einen Neustart übersteht.
Beim allerersten Abruf gilt als neu, was der Nutzer noch nicht gelesen hat (Matrix `unread_notifications`, Teams
`viewpoint.lastMessageReadDateTime`). Mitgeholte, aber nicht abgefragte Nachrichten bleiben im Speicher, bis
`chat_receive` sie abholt; mehrere Clients am selben Profil teilen sich diesen Eingang. Eigene Nachrichten erkennt das
Modul an den gespeicherten IDs der zuletzt gesendeten (Matrix zusätzlich an der Transaktions-ID) – was unter demselben
Konto **ohne** das Modul geschrieben wurde, gilt als Nachricht des Nutzers. So funktioniert Teams, wo das Modul unter dem
Konto des Nutzers schreibt, und Matrix auch ohne eigenes Bot-Konto.

Von sich aus in eine laufende Sitzung schreiben (Push) kann der Server nicht: Die *Channels* von Claude Code
(`notifications/claude/channel`) gibt es nur für per stdio gestartete MCP-Server, DevTools MCP spricht Streamable HTTP.

In der UI wählt „Aktiv“ (Mehrfachauswahl) die Systeme; darunter stehen die Felder und die Standard-Unterhaltung des
gerade gewählten aktiven Systems, ein Umschalter wechselt zwischen ihnen.

Freigaben je System:

* **Nur diese Räume/Chats**: Senden, Lesen und Einladungen nur dort; leer = alle des Kontos.
* **Freigegebene Absender** (Matrix-IDs bzw. E-Mail/Benutzer-ID): nur deren Nachrichten erreichen das LLM – auch im
  Verlauf, Fremdes wird ausgeblendet und gezählt. Das eigene Konto ist immer freigegeben. Leer = alle Mitglieder;
  *Verbindung testen* warnt dann.
* Matrix **Einladungen freigegebener Absender annehmen** (Standard an): das Konto tritt Räumen bei, in die ein
  freigegebener Absender es einlädt – ohne Absenderliste nie, sonst könnte jeder den Bot in einen Raum holen und dort
  Anweisungen geben.

**Matrix:** Ende-zu-Ende-verschlüsselte Räume kann das Modul nicht lesen; Nachrichten dort erscheinen als Hinweis,
gesendet wird unverschlüsselt (mit Warnung). Für verschlüsselte Räume den Homeserver über
[Pantalaimon](https://github.com/matrix-org/pantalaimon) anbinden (dessen Adresse als Homeserver-URL).

**Teams einrichten:** In Entra ID eine App-Registrierung anlegen, unter *Authentifizierung* „Öffentliche Clientflows
zulassen“ = Ja, delegierte Berechtigungen `User.Read`, `Chat.ReadWrite`, `ChatMessage.Send` (keine Admin-Zustimmung
nötig, sofern der Tenant Benutzerzustimmung erlaubt). Client-ID (und ggf. Tenant) eintragen, speichern, *Anmelden*
ausführen: die App zeigt Adresse und Code und öffnet den Browser. Im Headless-Betrieb liefert `chat_login` beides an das
LLM, die Anmeldung läuft im Hintergrund weiter. Selbst-Chats („Notizen“) bietet Graph nicht an – für den Austausch
einen eigenen Gruppenchat anlegen.

**Teams und Polling:** Microsoft erlaubt in den Nutzungsbedingungen der Teams-APIs kein regelmäßiges Abfragen auf
Änderungen (Change Notifications bräuchten einen öffentlichen HTTPS-Endpunkt). Deshalb ruft das Modul je Tool-Aufruf
standardmäßig **genau einmal** ab: `chat_ask` sendet die Frage und kehrt zurück, die Antwort holt später
`chat_receive`. „Warten durch Abfragen alle … Sekunden“ (mind. 10) schaltet das Warten ein – nur, wenn das für die
eigene App-Registrierung in Ordnung ist. Ein Abruf kostet eine Anfrage für die Chat-Liste plus eine je Chat mit Neuem.

Die Einstellungen des früheren Moduls **Matrix** übernimmt das Chat-Modul beim ersten Start (als `matrix.*`, Matrix
aktiv); das Modul selbst ist danach in der App einzuschalten.

Ein weiteres System (z.B. Slack, Mattermost) braucht eine `ChatProvider`-Klasse und eine Zeile in
`src/main/resources/META-INF/services/systems.grebe.devtools.mcp.modules.chat.spi.ChatProvider`; Anmeldedaten, die zur
Laufzeit entstehen, legt es über `ChatSettings.vault()` verschlüsselt ab.

### Maven-Artefakte

Liest aus einem Maven-Repository im Standard-Layout (Standard: Maven Central, sonst Nexus/Artifactory mit optionaler
Anmeldung) `maven-metadata.xml`, POMs und JARs – ohne lokales `~/.m2` und ohne Maven-Installation.

* `maven_latest_version` sortiert nach Maven-Versionslogik und trennt Releases von Vorabversionen (alpha, beta, RC, M,
  SNAPSHOT …). Mit `currentVersion` meldet es, wie viele Releases dazwischen liegen und ob der Sprung laut SemVer
  inkompatibel sein darf (Major bzw. Minor unter 1.0).
* `maven_artifact_info` wertet das POM samt Parent-Kette aus (Properties, Lizenzen, SCM, dependencyManagement);
  Versionen aus importierten BOMs bleiben offen.
* `maven_breaking_changes` vergleicht zwei Versionen dreifach: POM (Java-Ziel, Lizenz, weggefallene transitive
  Abhängigkeiten, Major-Sprünge von Abhängigkeiten), öffentliche API beider JARs auf Bytecode-Ebene
  (`java.lang.classfile`, ähnlich japicmp: entfernte Klassen/Methoden/Felder, geänderte Signaturen, final/abstract/static,
  weggefallene Obertypen, neue abstrakte Methoden und Pflicht-Elemente in Annotations; Methoden, die in eine –
  auch package-private – Oberklasse wandern, zählen nicht) und die GitHub-Releases dazwischen (Abschnitte und Zeilen mit
  „Breaking“, „incompatible“, „removed“ …). Änderungen in `internal`/`impl`/`shaded`-Paketen sind standardmäßig
  ausgeblendet. Ohne GitHub-Token erlaubt GitHub 60 Abfragen pro Stunde.
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

## Projektstruktur

Gradle-Multiprojekt:

| Projekt | Inhalt | Artefakt |
|---|---|---|
| `desktop` | Desktop-App: MCP-Server, alle Module, Plugins, JavaFX-Oberfläche; Backend eingebettet oder Anbindung an einen Team-Server | `desktop/build/libs/devtools-mcp-<version>.jar` |
| `backend` | Benutzer, Profile und Einstellungs-Ebenen, Modul-Katalog, Projekte, Skills, Memories mit **GraphQL-API** (HTTP + WebSocket-Subscriptions) | – (Bibliothek) |
| `server` | Team-Server: Backend + Web-UI (Vaadin) – **kein MCP** | `server/build/libs/devtools-server-<version>.jar` (Jetty), `…-wildfly.war` |
| `shared` | Gemeinsam: Einstellungs-Modell, Datenklassen der GraphQL-API (`api`) | – |
| `natives` | Zugriffe auf das Betriebssystem für die Fenstersteuerung: Fenstersysteme (FFM), zweiter KI-Zeiger mit eigener Maus und Tastatur (JNA), Programmstart im Hintergrund | – (Bibliothek) |

MCP-Server ist nur die Desktop-App; Tools laufen immer auf dem Rechner des Entwicklers. Das **Backend läuft immer**:
im Team-Server, und in der Desktop-App eingebettet – außer dort ist ein Team-Server eingetragen, dann nutzt sie dessen
Backend. Die Desktop-App spricht in beiden Fällen dieselbe GraphQL-API.

## Starten

```bash
./gradlew :desktop:bootRun          # Desktop-App (Entwicklung, Backend eingebettet)
./gradlew :desktop:bootJar          # desktop/build/libs/devtools-mcp-0.1.0-SNAPSHOT.jar → java -jar …
./gradlew :server:bootRun           # Team-Server auf Port 8080
./gradlew :server:bootJar :server:war   # Server als Jar (Jetty) bzw. WAR für WildFly
./gradlew build                     # alles inkl. Tests
```

Der MCP-Server der Desktop-App lauscht auf `http://127.0.0.1:8765/mcp` (Streamable HTTP, nur localhost).
Über **„Client verbinden…“** zeigt die App fertige Konfigurationen, z.B.:

```bash
claude mcp add --transport http devtools http://127.0.0.1:8765/mcp
```

## Bedienung

* **Module** (links): an/aus, Status (grün aktiv · grau aus · rot Fehler).
* **Konfiguration** (rechts): Formular wird aus dem Modul-Schema erzeugt; *Speichern* registriert die Tools
  sofort neu, verbundene Clients erhalten `notifications/tools/list_changed`. *Verbindung testen* prüft
  die ungespeicherten Eingaben. Gespeichert wird im Backend als Überschreibung im aktiven Profil; gesperrte
  Felder nennt der Hinweis über dem Formular.
* **Tools**: jedes Tool einzeln abschaltbar.
* **Aufrufe**: Live-Protokoll aller Tool-Aufrufe mit Argumenten, Ergebnis, Dauer und Fehlern.
* **Skills**: Übersicht der gespeicherten Skills mit Inhalt, Zusatzdateien und Historie.
* **Memories**: die vom LLM festgehaltenen früheren Aktionen mit Suche (wie `memories_search`) und Löschen.
* **Backend**: eingebettet oder Team-Server, Status, aktives Profil, Projekte mit lokalem Verzeichnis (siehe unten).
* **Einstellungen**: Port (nach Neustart), optionales Bearer-Token (sofort wirksam), Tray-Verhalten.
* Fenster schließen → läuft im System-Tray weiter; *Beenden* über das Tray-Menü.

Die Modul-Einstellungen liegen im Backend (eingebettet: `core.mv.db` im Datenordner). `~/.devtools-mcp/settings.json`
(Pfad per `DEVTOOLS_MCP_HOME` bzw. `-Ddevtools.mcp.home` änderbar) hält nur noch App-Einstellungen: Port,
Zugriffstoken, Tray, Team-Server (Adresse + Desktop-Token), Plugins und die lokalen Projektverzeichnisse. Beim ersten
eingebetteten Start übernimmt das Backend die bisherigen Modul-Einstellungen aus `settings.json` als globale Vorgaben
(Marker `backend-import.done`). Geheimnisse werden mit AES-GCM verschlüsselt, der Schlüssel liegt in `secret.key`.

### Sicherheit

* Nur `127.0.0.1`; Clients auf demselben Rechner ohne Token oder mit dem Zugriffstoken aus den Einstellungen.
* Git/Build arbeiten ausschließlich in den freigegebenen Verzeichnissen (im Modul oder global unter **Freigaben**);
  Pfade außerhalb werden abgewiesen – außer die Beschränkung ist unter **Freigaben** bewusst aufgehoben.
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
* SSH: Zugangsdaten verschlüsselt und nie in Tool-Ausgaben oder Fehlermeldungen; Host-Key-Prüfung gegen eine eigene
  known_hosts-Datei (geänderte Schlüssel werden immer abgelehnt). `ssh_exec` läuft mit den vollen Rechten des
  hinterlegten Benutzers – dafür einen eingeschränkten Benutzer anlegen oder den Schalter abschalten.
* Chat: Token, Passwörter und das Teams-Refresh-Token verschlüsselt und nie in Tool-Ausgaben. Chat-Nachrichten sind
  Eingaben, die das LLM wie Anweisungen behandelt – deshalb „Freigegebene Absender“ setzen (sonst kann jedes Mitglied
  einer freigegebenen Unterhaltung Anweisungen geben), für Matrix ein eigenes Bot-Konto verwenden und Unterhaltungen
  einschränken. Einladungen nimmt das Modul nur von freigegebenen Absendern an.

### Backend und Team-Server

Das Backend verwaltet Benutzer, Profile, Einstellungs-Vorgaben, Projekte, Skills und Memories und bietet dafür eine
**GraphQL-API** unter `/graphql` (Schema: `backend/src/main/resources/backend-graphql/schema.graphqls`; Queries/Mutations über
HTTP, Subscriptions über WebSocket).

* **Eingebettet** (Standard, kein Team-Server eingetragen): Das Backend läuft in der Desktop-App auf
  `http://127.0.0.1:<port>/graphql`, mit Core- und Skill-Datenbank im Datenordner. Angemeldet ist ohne Login der
  lokale Benutzer `local` (Administrator; E-Mail = frühere Einstellung „Benutzer-E-Mail“ des Skill-Moduls, sonst
  Git-E-Mail), die App erzeugt dafür bei jedem Start ein Token.
* **Team-Server** (`server`): dasselbe Backend plus Web-UI, für mehrere Entwickler. Tab *Backend* → Server-Adresse und
  Desktop-Token → *Server eintragen*; gilt nach einem Neustart der App (dann läuft kein eingebettetes Backend, keine
  lokalen Datenbanken). *Eingebettet verwenden* stellt zurück.

```bash
java -jar devtools-server.jar            # Port 8080, Web-UI unter /, GraphQL unter /graphql
```

* **Abgleich:** Die App meldet dem Backend ihre Module samt Feldern und Tools (`reportCatalog`; daraus baut die Web-UI
  die Formulare), lädt Benutzer, Vorgaben und Projekte und abonniert `settingsChanged`, `projectsChanged`,
  `skillsChanged` und `memoriesChanged`. Änderungen – auch aus der Web-UI oder von einer anderen Desktop-App – kommen sofort an; die Tools
  werden neu gebaut, MCP-Clients bekommen `tools/list_changed`. Bricht die Verbindung ab, verbinden sich die
  Subscriptions mit wachsendem Abstand neu; dazwischen gilt der letzte Stand (beim Team-Server auch über einen
  Neustart: verschlüsselte Cache-Datei `team-cache.json`). Überholte Stände erkennt die App am Änderungszähler
  (`revision`).
* **Benutzer** (Rolle Administrator/Benutzer) liegen in der Core-Datenbank `core.mv.db` (Server: `devtools.server.home`
  bzw. `DEVTOOLS_SERVER_HOME`, sonst `~/.devtools-server` – getrennt vom Ordner der Desktop-App, damit beide auf einem
  Rechner laufen; H2, Schema per Flyway aus `db/core`; andere Datenbank über
  `devtools.core.datasource.url/username/password`). Beim ersten Start des Servers wird `admin` angelegt – Passwort aus
  `DEVTOOLS_MCP_ADMIN_PASSWORD`, sonst zufällig und einmalig im Log. Der letzte aktive Administrator lässt sich weder
  sperren, herabstufen noch löschen.
* **Passwörter:** PBKDF2 mit HMAC-SHA3-512, 16 Byte Zufalls-Salt, 210.000 Iterationen
  (`pbkdf2-sha3-512$<iterationen>$<salt>$<hash>`); wird die Iterationszahl angehoben, rechnet die nächste Anmeldung
  den Hash neu.
* **Desktop-Tokens:** Jeder Benutzer erzeugt unter *Mein Konto* persönliche Tokens (JWT, HS512, Schlüssel
  `jwt.key`; Gültigkeit 30/90/365 Tage oder unbegrenzt). Das Token wird nur einmal angezeigt, gespeichert wird nur
  seine ID; Widerruf, Sperren oder Löschen des Benutzers wirken sofort. Die GraphQL-API erwartet es als
  `Authorization: Bearer …` (HTTP) bzw. im Payload von `connection_init` (WebSocket); ohne gültiges Token antwortet
  jede Operation mit `UNAUTHORIZED`, fachliche Fehler kommen als `BAD_REQUEST` mit lesbarer Meldung.
* Die Vorgaben enthalten entschlüsselte Geheimnisse – den Team-Server deshalb nur über HTTPS erreichbar machen. TLS
  übernimmt ein Reverse-Proxy (`server.forward-headers-strategy=native`; WebSocket-Upgrade für `/graphql` durchreichen).
* Entwicklung der Web-UI mit Hot-Reload: `./gradlew :server:bootRun -Pvaadin.productionMode=false`.

#### Deployment in WildFly

Alternativ zum Jar läuft der Team-Server als WAR in einem externen WildFly (Jakarta EE 11 / Servlet 6.1, Java 25):
`./gradlew :server:war` baut `devtools-server-<version>-wildfly.war` ohne Jetty. Einstieg ist `WildFlyInitializer`.

* **Pfad:** `jboss-web.xml` deployt unter `/` – wie beim Jar.
  `jboss-deployment-structure.xml` schaltet die WildFly-Subsysteme ab, die Spring selbst mitbringt
  (JPA, CDI/Weld, Faces, JAX-RS, Bean Validation, Logging); `web.xml` mit `metadata-complete` verhindert, dass
  WildFly annotierte Servlets aus den Bibliotheken selbst registriert.
* Getestet mit `quay.io/wildfly/wildfly:41.0.1.Final-jdk25`.
* **Port und Adresse** bestimmt WildFly.
* **Datenverzeichnis:** `~/.devtools-server` des WildFly-Benutzers, oder `-Ddevtools.server.home=…`.
  Admin-Passwort wie oben über `DEVTOOLS_MCP_ADMIN_PASSWORD`.

### Profile und Einstellungs-Ebenen

Die wirksamen Modul-Einstellungen sind die Vorbelegung des Moduls, darüber die Ebenen **Global → Benutzer → Profil**;
jede Ebene speichert nur, was sie vorgibt bzw. überschreibt. Speichern in der Desktop-App schreibt ins aktive Profil
(nur geänderte Werte).

* **Global:** Vorgaben des Administrators für alle (Web → *Globale Einstellungen*; je Feld „vorgeben“).
* **Benutzer** („Alle meine Profile“) und **Profil** (z.B. Work, Home) überschreiben einzelne Felder, Modul an/aus
  und einzelne Tools (*Einstellungen*: je Feld „überschreiben“, sonst geerbt mit Herkunft). Geheimnisse liegen
  verschlüsselt (`secret.key`) in der Core-Datenbank (`module_override`).
* **Aktives Profil** wird oben in der Web-UI oder im Tab *Backend* der Desktop-App umgeschaltet (Verwaltung in der
  Web-UI unter *Profile*: anlegen, kopieren samt Überschreibungen, umbenennen, löschen – das letzte bleibt). Jeder
  Benutzer startet mit „Standard“. Die Desktop-Apps übernehmen den Wechsel sofort (gleiche MCP-Session, neue Tools).
* **Sperren:** Administratoren sperren unter *Globale Einstellungen* einzelne Felder, „Modul an/aus“ oder alle
  Tool-Schalter eines Moduls. Gesperrtes gilt nur global; Überschreibungen werden beim Speichern abgelehnt und beim
  Auflösen ignoriert (auch bestehende).
* Die Formulare der Web-UI entstehen aus den Modulen, die die Desktop-Apps melden (Tabelle `module_catalog`) – auch
  aus Plugins. Solange sich keine App verbunden hat, zeigt die Web-UI keine Module.

### Projekte und Freigaben

* Ein **Projekt** ist im Backend nur Metadaten: Eigentümer, Name, optional Beschreibung, Sonar-Projektschlüssel und
  Ticket-Projekt (Web-UI → *Projekte* bzw. Tab *Backend* → *Neues Projekt*; Core-DB `project`, `project_share`).
* Das **Verzeichnis** ordnet jeder in seiner Desktop-App zu (Tab *Backend* → *Verzeichnis wählen…*). Projekte mit
  Verzeichnis ergänzen die Verzeichnis-Felder von **Git** (`repositories`), **Build** und **Code-Graph**
  (`projects`); eigene heißen in den Tools wie angelegt, freigegebene `name@eigentümer`.
* **Freigaben** vergibt der Eigentümer (oder ein Administrator) je Benutzer: *nur lesen* oder *lesen + schreiben*.
  Nur lesend lehnen `git_create_branch`/`checkout`/`stage`/`unstage`/`commit`, `build_run`/`build_test` (führen Code
  des Projekts aus) und ein nötiger Neuaufbau des Code-Graphen ab (`Workspaces.requireWritable`, Prüfung bei jedem
  Aufruf).
* `projects_list` zeigt dem LLM die Projekte mit Zugriff, lokalem Verzeichnis, erkanntem Git/Gradle/Maven,
  Sonar-Schlüssel und Ticket-Projekt.

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

Ein Skill registriert einen wiederkehrenden Aufgabentyp mit seinem erprobten Ablauf, z.B. `ticket-review`: wie ein
Ticket geprüft wird (Schritte, Kriterien, Tool-Aufrufe, Vorlieben des Nutzers). Was bei einem einzelnen Durchlauf
konkret passiert ist, gehört nicht in den Skill, sondern in eine [Memory](#memories--gedächtnis-für-frühere-aktionen).

Angelehnt an das Skill-Management von Hermes: Das LLM sucht vor einer Aufgabe mit `skills_list` passende Skills und
lädt sie mit `skills_view`. Nach einer schwierigen, mehrstufigen oder korrigierten Aufgabe legt es selbst einen Skill
an (`skills_create`) oder verbessert einen bestehenden gezielt (`skills_patch`, `old_string` → `new_string`, muss
eindeutig sein). Wann das passieren soll, steht in den Server-Instructions und in den Tool-Beschreibungen.

* **Aufbau** wie ein `SKILL.md`: Name (`a-z0-9._-`), ein Satz `description` („wann greift der Skill“), Kategorie,
  Tags, Markdown-Inhalt, dazu Zusatzdateien unter `references/`, `templates/`, `scripts/`, `assets/`.
* **Registrierung (`triggers`):** Tool-Namen oder Präfixe (`ticket_get`, `pr_*`), für die der Skill gilt. Ruft das LLM
  ein solches Tool auf, hängt der Server einmal je Session eine Zeile an das Ergebnis: „[DevTools] Registrierter Skill
  für ticket_get: ticket-review – … (per skills_view ladbar)“. Ein bereits geladener Skill wird nicht mehr genannt.
* **Sparsam ausgeliefert:** `skills_list` zeigt je Skill nur Name und gekürzte Beschreibung (ohne Tags); bei Suchtext
  und genau einem Treffer kommt der Inhalt gleich mit (spart den zweiten Aufruf). `skills_view` hat statt Frontmatter
  eine Kopfzeile (Name, Revision, Herkunft, Registrierung).
* **Historie:** jede Änderung erzeugt eine Revision mit Aktion und Notiz (`skills_history`). Mit
  `expected_revision` lehnt ein Patch ab, wenn der Skill inzwischen woanders geändert wurde.
* **Ablage im Backend:** Skills liegen im Backend (eingebettet oder Team-Server) und gehören der E-Mail des
  Benutzerkontos; globale Vorlagen verwalten Administratoren (eingebettet: der lokale Benutzer). Die App erreicht sie
  über GraphQL, Änderungen meldet `skillsChanged` (die Skills-Ansicht aktualisiert sich live).
* **Persistenz:** Spring Data JPA (`SkillRepository`, `SkillRevisionRepository`; Zusatzdateien hängen per Cascade am
  Skill) auf Hibernate ORM 7 und HikariCP, Transaktionen per `@Transactional` im `SkillService`. Standard ist die
  H2-Datei `skills.mv.db` im Datenordner; andere Datenbank über `devtools.skills.datasource.url/username/password`
  (eine bisher gemeinsam genutzte Skill-Datenbank lässt sich direkt übernehmen; früher im Skill-Modul eingetragene
  Verbindungen übernimmt die Desktop-App beim Start). Schema per `hibernate.hbm2ddl.auto=update`
  (`devtools.skills.schema-action`). Ist die Datenbank nicht erreichbar, startet das Backend nicht.
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

### Memories – Gedächtnis für frühere Aktionen

Memories ergänzen die Skills: Ein Skill sagt, *wie* ein Aufgabentyp abläuft (`ticket-review`), eine Memory hält fest,
*was* bei einem konkreten Durchlauf passiert ist – „Ticket ABC-123 reviewt: Akzeptanzkriterien fehlen, an PO zurück“.
Hier gehören die Einmal-Details hinein, die ein Skill bewusst nicht enthält: Ticket-/PR-Nummern, Ergebnis,
Entscheidungen, Datum.

* **Anlegen:** Nach einer nennenswerten Aktion (Ticket reviewt, Fehler behoben, PR erstellt, Entscheidung mit dem
  Nutzer) legt das LLM mit `memories_save` eine Memory an: `title` (eine Zeile), `content` (Markdown: Ausgangslage,
  Vorgehen, Ergebnis, offene Punkte) und optional `project` (Name aus `projects_list`), `skill` (die
  Skill-Registrierung, nach der gearbeitet wurde), `reference` (Ticket-Key, PR, Commit) und `tags`. Gibt es zum selben
  Bezug schon Memories, nennt die Antwort sie und verweist auf `memories_update`.
* **Nachtragen:** `memories_update` mit `append` hängt einen datierten Nachtrag an (z.B. Ergebnis nach Rückmeldung);
  `content` ersetzt den Inhalt, die übrigen Felder lassen sich einzeln ändern (leerer Text entfernt sie).
* **Suchen:** `memories_search` zerlegt den Suchtext in Begriffe und sucht in Titel, Inhalt, Tags, Bezug, Projekt und
  Skill; Treffer werden nach Anzahl getroffener Begriffe gewichtet (Titel und Bezug doppelt), dann nach Datum. Filter:
  `project`, `skill` (z.B. alle früheren Ticket-Reviews), `tag`, `days`, `limit` (Standard 5, max. 50). Ohne Suchtext
  kommen die neuesten. `memories_view` lädt eine Memory vollständig.
* **Ablage:** im Backend neben den Skills (Tabelle `memory` in derselben Datenbank, Spring Data JPA mit
  `MemoryRepository`/`MemoryService`), je Benutzerkonto (E-Mail) – andere Benutzer sehen sie nicht, globale Memories
  gibt es nicht. GraphQL: `memories`, `memory`, `memorySearch`, `memoryView`, `saveMemory`, `updateMemory`,
  `deleteMemory`, Subscription `memoriesChanged`.
* **Schalter:** „Anlegen und Nachtragen erlauben“ (Standard an), „Löschen erlauben“ (Standard aus, nur für das LLM –
  im Tab **Memories** der App geht Löschen immer), „Max. Zeichen je Memory“ (Standard 20 000).
* **Sparsam ausgeliefert:** Standard 5 Treffer mit einer Zeile plus kurzem Ausschnitt; bei genau einem Treffer kommt
  die Memory direkt vollständig.

### Hinweise des Servers: Skills und Memories finden das LLM

Damit das LLM nicht ohne das vorhandene Wissen loslegt, hängt der Server (`RecallHints`) an Tool-Ergebnisse kurze
Zeilen „[DevTools] …“ – nur bei einem Treffer, jeder Skill und jede Memory höchstens einmal je MCP-Session:

| Auslöser | Hinweis |
|---|---|
| Tool, für das ein Skill registriert ist (`triggers`) | „Registrierter Skill für ticket_get: ticket-review – …“ |
| Argument enthält einen Bezug einer Memory (z.B. `ABC-123`) | „Frühere Aktionen zu abc-123: #12 2026-10-01 …“ |
| `skills_view` | frühere Durchläufe dieses Skills (Memories mit `skill`) |
| `skills_list` mit Suchtext | passende Memories |
| `memories_search` mit Suchtext | passende Skills |

Registrierungen und Bezüge hält die App im Speicher und lädt sie bei jeder Änderung neu (eigene Schreib-Tools sofort,
andere Apps über `skillsChanged`/`memoriesChanged`) – ein Tool-Aufruf ohne Treffer kostet keinen Backend-Zugriff.
Zahlen in Argumenten zählen nur unter ID-artigen Namen (`id`, `number`, `pr`, `key` …), nicht etwa `limit`. Die
Hinweise beschreiben nur den Zustand („per skills_view ladbar“), weil Clients Aufforderungen in Tool-Ergebnissen
misstrauen. Abgeschaltete Module bzw. Lese-Tools liefern keine Hinweise.

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

Feldtypen: `STRING`, `SECRET`, `INT`, `BOOLEAN`, `URL`, `DIRECTORY`, `DIRECTORY_LIST`, `ENUM`, `STRING_LIST`,
`RECORD_LIST`. Letzterer ist eine Liste gleichartiger Datensätze (z.B. Verbindungen):
`ConfigField.records("connections", "Verbindungen", ConfigField.of("name", …), …)` – die UI zeigt eine Tabelle mit
Hinzufügen/Bearbeiten/Entfernen (Dialog aus denselben Feldern), gelesen wird mit `config.getRecords("connections")`.
Gespeichert wird ein JSON-Array; enthält ein Feld ein `SECRET`, wird der ganze Wert verschlüsselt.

Austauschbare Provider eines Moduls (wie die Ticket-Systeme) beschreibt `ConfigGroup`:
`new ConfigGroup("jira", "Jira").fields(false, felder)` liefert den Schalter `jira.enabled` und die Felder unter
`jira.<feld>` (Beschriftung „Jira: …“). Statt aller Felder untereinander zeigt die UI dann eine Mehrfachauswahl „Aktiv“
und darunter die Einstellungen eines aktiven Providers mit einem Umschalter zwischen ihnen. Felder inaktiver Provider
prüft `validate()` nicht. Die Gruppe ist nur für die Desktop-UI; der Team-Server sieht die Felder wie bisher einzeln.

MCP-Tool-Annotations (`readOnlyHint`, `destructiveHint`, `idempotentHint`, `openWorldHint`) setzt `@ToolHints` an der
Tools-Klasse oder einzelnen `@Tool`-Methoden (Methode hat Vorrang); dafür die Callbacks mit
`ToolBeans.callbacks(beans…)` statt `ToolCallbacks.from(…)` erzeugen. Clients können damit lesende Tools ohne Rückfrage
ausführen und vor verändernden nachfragen; ohne Annotation gilt ein Tool laut Spezifikation als möglicherweise
zerstörerisch. Bisher annotiert: SSH, Chat. Für Verzeichnis-basierte Module hilft `Workspaces` (Freigabe + Pfad-Guard); wer die global freigegebenen
Verzeichnisse mitbekommen soll, nennt seine Verzeichnisliste in `sharedDirectoryFields()`.

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
desktop/
  DevToolsMcpApplication ── main() → JavaFX; Backend-Paket nur über remote/EmbeddedBackend
  fx/FxApp                ── init(): Spring-Kontext starten · start(): Fenster + Tray · stop(): Kontext schließen
  core/ToolModule         ── Erweiterungspunkt (SPI)
  core/ToolRegistry       ── Module ⇄ McpSyncServer (addTool/removeTool zur Laufzeit, notifyToolsListChanged)
  core/SettingsResolver   ── wirksame Einstellungen lesen/speichern (Backend, vorher settings.json)
  core/ManagedToolCallback── Präfix, Protokollierung, Klartext-Ergebnisse
  config/SettingsStore    ── settings.json (App-Einstellungen), SecretCipher (AES-GCM)
  server/BearerTokenFilter── optionaler Token-Schutz für /mcp
  remote/                 ── EmbeddedBackend + LocalUser, BackendConnection (GraphQL-Client, Subscriptions, Cache),
                             BackendSettingsResolver, BackendSkills, BackendMemories
  modules/{git,sonar,build,graph,skills,memories,…} ── skills/RecallHints: Hinweise auf Skills und Memories
  plugin/PluginManager    ── Plugin-Ordner, plugin.yml, ClassLoader je Plugin, Lebenszyklus, depend-Reihenfolge
  plugin/store/           ── Plugin-Store: Maven Resolver, Repositories, Katalog, Updates
  ui/                     ── MainView, ModuleDetailPane, ConfigForm, InvocationLogView, PluginsView, BackendView, Dialoge
backend/
  backend/BackendConfig   ── Einstieg (Component-Scan des Backends)
  backend/BackendGraphQlController, GraphQlAuth, GraphQlErrors, ChangeBus ── GraphQL-API, Token, Fehler, Subscriptions
  backend/{account,profile,project,catalog,skills,memories} ── Benutzer + Tokens, Profile + Ebenen, Projekte,
                             Katalog, Skills, Memories
server/
  DevToolsServerApplication ── Spring Boot (Jetty) · WildFlyInitializer (WAR)
  server/SecurityConfig, web/ ── Web-Login und Vaadin-Web-UI
natives/
  modules/window/platform/ ── WindowSystem (Win32/macOS/X11 über FFM), ScreenMapper, ProgramLauncher
  modules/window/cursor/   ── CursorProvider/CursorController (SPI): zweiter KI-Zeiger je Betriebssystem (JNA)
shared/
  api/                    ── Datenklassen der GraphQL-API
  core/ConfigField, config/ModuleSettings, profile/Overrides, modules/{skills,memories}/{…Backend,…Views}
```

MCP-Server: Spring AI `spring-ai-starter-mcp-server-webflux` 2.0.1 (MCP Java SDK 2.0.0), Protokoll `STREAMABLE`.
