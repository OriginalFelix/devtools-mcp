package systems.grebe.devtools.mcp.modules.ticket;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.core.Text;
import systems.grebe.devtools.mcp.modules.ticket.spi.TicketSystem;
import systems.grebe.devtools.mcp.modules.ticket.spi.TicketSystem.Board;
import systems.grebe.devtools.mcp.modules.ticket.spi.TicketSystem.BoardOptions;
import systems.grebe.devtools.mcp.modules.ticket.spi.TicketSystem.BoardView;
import systems.grebe.devtools.mcp.modules.ticket.spi.TicketSystem.Column;
import systems.grebe.devtools.mcp.modules.ticket.spi.TicketSystem.Comment;
import systems.grebe.devtools.mcp.modules.ticket.spi.TicketSystem.Ticket;
import systems.grebe.devtools.mcp.modules.ticket.spi.TicketSystem.TicketDetails;
import systems.grebe.devtools.mcp.modules.ticket.spi.TicketSystem.TicketPage;
import systems.grebe.devtools.mcp.modules.ticket.spi.TicketSystem.TicketQuery;

/** Lesende Ticket-Tools. Ausgaben kompakt, eine Zeile je Ticket. */
public class TicketTools {

    static final String PROVIDER = "Ticket-System (jira, github, gitlab, youtrack, openproject …). Leer = aus dem Schlüssel erkannt bzw. Standard-System.";
    static final String PROJECT = "Projekt (Jira-/YouTrack-Schlüssel ABC, GitHub owner/repo bzw. owner, GitLab gruppe/projekt, "
            + "OpenProject-Kennung). "
            + "Leer = Standardprojekt des Systems.";
    static final String ASSIGNEE = "Zuständige: me (angemeldeter Benutzer), none (nicht zugewiesen) oder Benutzername";

    private final TicketEnvironment env;

    TicketTools(TicketEnvironment env) {
        this.env = env;
    }

    @Tool(name = "providers", description = "Listet die aktiven Ticket-Systeme mit Erreichbarkeit, angemeldetem Benutzer, "
            + "Standardprojekt sowie Schlüssel- und Abfrageformat." + ShellHints.TICKET)
    public String providers() {
        if (env.entries().isEmpty()) {
            return "Kein Ticket-System aktiviert – in der DevTools-App unter Module → Tickets einschalten.";
        }
        StringBuilder sb = new StringBuilder();
        for (TicketEnvironment.Entry e : env.entries()) {
            TicketSystem.Availability a = e.system().probe();
            sb.append(e.provider().id()).append(" (").append(e.provider().displayName()).append("): ");
            if (a.available()) {
                sb.append("verfügbar");
                if (a.version() != null) {
                    sb.append(", ").append(a.version());
                }
                if (a.user() != null) {
                    sb.append(", angemeldet als ").append(a.user());
                }
                if (!"verfügbar".equals(a.message())) {
                    sb.append(" – ").append(a.message());
                }
            } else {
                sb.append("nicht erreichbar – ").append(a.message());
            }
            sb.append("\n  Standardprojekt: ").append(Text.orDash(e.defaultProject()))
                    .append("\n  Projekt: ").append(e.provider().projectHelp())
                    .append("\n  Schlüssel: ").append(e.provider().keyHelp())
                    .append("\n  query: ").append(e.provider().queryHelp()).append('\n');
        }
        return sb.toString().strip();
    }

    @Tool(name = "boards", description = "Listet die Boards eines Projekts: Jira-Boards (Scrum/Kanban), GitHub Projects "
            + "(owner oder owner/repo), GitLab-Issue-Boards (Projekt oder Gruppe), YouTrack-Agile-Boards, OpenProject-Boards. Liefert ID und Namen für ticket_board."
            + ShellHints.TICKET)
    public String boards(
            @ToolParam(required = false, description = PROJECT) String project,
            @ToolParam(required = false, description = PROVIDER) String provider) {
        TicketEnvironment.Entry e = env.resolve(provider, null);
        String p = e.project(project);
        List<Board> boards = e.system().boards(p);
        if (boards.isEmpty()) {
            return "Keine Boards gefunden (" + e.provider().id() + (p == null ? "" : ", " + p) + ").";
        }
        StringBuilder sb = new StringBuilder(boards.size() + " Board(s) (" + e.provider().id()
                + (p == null ? "" : ", " + p) + "):\n");
        for (Board b : boards) {
            sb.append("- ").append(b.id()).append("  ").append(b.name());
            if (b.type() != null) {
                sb.append("  [").append(b.type()).append(']');
            }
            if (b.project() != null && !b.project().equals(p)) {
                sb.append("  Projekt ").append(b.project());
            }
            if (b.url() != null) {
                sb.append("  ").append(b.url());
            }
            sb.append('\n');
        }
        return Text.limitLines(sb.toString().strip(), env.maxLines());
    }

    @Tool(name = "board", description = "Aktueller Stand eines Boards nach Spalten: Tickets mit Status, Zuständigen und "
            + "Titel. Jira: aktiver Sprint bzw. offene Kanban-Tickets; GitHub: Project nach Feld 'Status'; GitLab: Board-"
            + "Listen; YouTrack: aktueller Sprint nach Board-Spalten; OpenProject: Spalten-Abfragen. Mit assignee=me nur eigene Tickets." + ShellHints.TICKET)
    public String board(
            @ToolParam(required = false, description = "Board-ID oder Name aus ticket_boards. Leer = das einzige Board des Projekts.") String board,
            @ToolParam(required = false, description = PROJECT) String project,
            @ToolParam(required = false, description = ASSIGNEE) String assignee,
            @ToolParam(required = false, description = "Höchstens so viele Tickets je Spalte (Standard aus den Einstellungen)") Integer maxPerColumn,
            @ToolParam(required = false, description = PROVIDER) String provider) {
        TicketEnvironment.Entry e = env.resolve(provider, null);
        int max = maxPerColumn == null || maxPerColumn < 1 ? env.maxPerColumn() : Math.min(maxPerColumn, 100);
        BoardView view = e.system().board(board, e.project(project), new BoardOptions(blankToNull(assignee), max));
        StringBuilder sb = new StringBuilder("Board ").append(view.board().name())
                .append(" (").append(e.provider().id()).append(", ID ").append(view.board().id()).append(")");
        if (view.scope() != null) {
            sb.append(" – ").append(view.scope());
        }
        if (assignee != null && !assignee.isBlank()) {
            sb.append(" – nur assignee=").append(assignee.trim());
        }
        sb.append('\n');
        if (view.board().url() != null) {
            sb.append(view.board().url()).append('\n');
        }
        for (Column c : view.columns()) {
            sb.append("\n## ").append(c.name()).append(" (").append(c.total() < 0 ? c.tickets().size() + "+" : c.total())
                    .append(")\n");
            for (Ticket t : c.tickets()) {
                sb.append("- ").append(line(t, false)).append('\n');
            }
            if (c.total() > c.tickets().size() || c.total() < 0) {
                sb.append("  … weitere – mit ticket_search eingrenzen\n");
            }
        }
        if (view.note() != null) {
            sb.append("\nHinweis: ").append(view.note());
        }
        return Text.limitLines(sb.toString().strip(), env.maxLines());
    }

    @Tool(name = "search", description = "Sucht Tickets nach Status, Zuständigen, Labels, Freitext und optional einer "
            + "systemeigenen Abfrage (Jira: JQL, YouTrack: Suchsprache). Eine Zeile je Ticket mit Schlüssel, Status, Zuständigen und Titel."
            + ShellHints.TICKET)
    public String search(
            @ToolParam(required = false, description = PROJECT) String project,
            @ToolParam(required = false, description = "Freitext in Titel/Beschreibung") String text,
            @ToolParam(required = false, description = "open (Standard), closed oder all") String state,
            @ToolParam(required = false, description = ASSIGNEE) String assignee,
            @ToolParam(required = false, description = "Labels, die alle gesetzt sein müssen") List<String> labels,
            @ToolParam(required = false, description = "Systemeigene Abfrage, UND-verknüpft: Jira JQL, GitHub Suchsyntax, "
                    + "GitLab API-Parameter a=b&c=d, YouTrack-Suchsprache, OpenProject-Filter als JSON (siehe ticket_providers)") String query,
            @ToolParam(required = false, description = "Anzahl Treffer (Standard 30, max. 100)") Integer limit,
            @ToolParam(required = false, description = "Fortsetzung: 'cursor' aus der vorigen Ausgabe") String cursor,
            @ToolParam(required = false, description = PROVIDER) String provider) {
        TicketEnvironment.Entry e = env.resolve(provider, null);
        TicketQuery q = new TicketQuery(e.project(project), blankToNull(text), TicketSystem.State.parse(state),
                blankToNull(assignee), labels, blankToNull(query), limit == null ? 30 : limit, blankToNull(cursor));
        TicketPage page = e.system().search(q);
        StringBuilder sb = new StringBuilder();
        sb.append(page.total() == null ? page.tickets().size() + " Treffer" : "Treffer: " + page.total())
                .append(" (").append(e.provider().id()).append(")");
        if (page.effectiveQuery() != null) {
            sb.append(" – Abfrage: ").append(page.effectiveQuery());
        }
        sb.append('\n');
        if (page.tickets().isEmpty()) {
            sb.append("(keine Tickets gefunden)");
        }
        for (Ticket t : page.tickets()) {
            sb.append("- ").append(line(t, true)).append('\n');
        }
        if (page.nextCursor() != null) {
            sb.append("\nWeitere Treffer: ticket_search mit cursor=").append(page.nextCursor());
        }
        return Text.limitLines(sb.toString().strip(), env.maxLines());
    }

    @Tool(name = "get", description = "Liest ein Ticket vollständig: Titel, Status, Typ, Priorität, Zuständige, Autor, "
            + "Labels, Beschreibung und die neuesten Kommentare. Schlüssel: ABC-123, owner/repo#12, gruppe/projekt#12, #123 oder URL."
            + ShellHints.TICKET)
    public String get(
            @ToolParam(description = "Ticket-Schlüssel oder URL; #12 bzw. 12 zusammen mit 'project'") String key,
            @ToolParam(required = false, description = PROJECT) String project,
            @ToolParam(required = false, description = "Anzahl neuester Kommentare (0 = keine; Standard aus den Einstellungen)") Integer comments,
            @ToolParam(required = false, description = PROVIDER) String provider) {
        TicketEnvironment.Entry e = env.resolve(provider, key);
        int n = comments == null || comments < 0 ? env.comments() : Math.min(comments, 50);
        TicketDetails d = e.system().ticket(key, e.project(project), n);
        Ticket t = d.ticket();
        StringBuilder sb = new StringBuilder();
        sb.append(t.key()).append(": ").append(t.title()).append('\n');
        row(sb, "Status", t.status() + (t.category() == TicketSystem.StatusCategory.UNKNOWN ? "" : " [" + t.category() + "]"));
        row(sb, "Typ", t.type());
        row(sb, "Priorität", t.priority());
        row(sb, "Zuständig", t.assignees().isEmpty() ? "niemand" : String.join(", ", t.assignees()));
        row(sb, "Autor", d.reporter());
        row(sb, "Labels", t.labels().isEmpty() ? null : String.join(", ", t.labels()));
        row(sb, "Erstellt", d.created());
        row(sb, "Geändert", t.updated());
        for (Map.Entry<String, String> f : d.fields().entrySet()) {
            row(sb, f.getKey(), f.getValue());
        }
        row(sb, "URL", t.url());
        sb.append("\n## Beschreibung\n").append(limit(d.description() == null ? "(keine)" : d.description().strip(),
                env.maxDescription())).append('\n');
        if (n > 0) {
            sb.append("\n## Kommentare (").append(d.comments().size()).append(" von ").append(d.totalComments())
                    .append(d.comments().size() < d.totalComments() ? ", neueste" : "").append(")\n");
            for (Comment c : d.comments()) {
                sb.append("\n### ").append(Text.orDash(c.author())).append(", ").append(Text.orDash(c.created()))
                        .append(c.id() == null ? "" : "  (Kommentar " + c.id() + ")").append('\n')
                        .append(limit(c.body() == null ? "" : c.body().strip(), 2000)).append('\n');
            }
        }
        return Text.limitLines(sb.toString().strip(), env.maxLines());
    }

    @Tool(name = "status", description = "Status, Zuständige und Titel mehrerer Tickets auf einmal (ohne Beschreibung), "
            + "z.B. für alle Tickets aus Branch-Namen oder Commit-Nachrichten." + ShellHints.TICKET)
    public String status(
            @ToolParam(description = "Ticket-Schlüssel oder URLs (max. 30)") List<String> keys,
            @ToolParam(required = false, description = PROJECT) String project,
            @ToolParam(required = false, description = PROVIDER) String provider) {
        if (keys == null || keys.isEmpty()) {
            throw new IllegalArgumentException("Keine Schlüssel angegeben – z.B. keys=[\"ABC-1\", \"ABC-2\"].");
        }
        List<String> lines = new ArrayList<>();
        for (String key : keys.stream().filter(k -> k != null && !k.isBlank()).distinct().limit(30).toList()) {
            try {
                TicketEnvironment.Entry e = env.resolve(provider, key);
                Ticket t = e.system().ticket(key.trim(), e.project(project), 0).ticket();
                lines.add("- " + line(t, true));
            } catch (RuntimeException ex) {
                // ein fehlendes Ticket soll die übrigen nicht verhindern
                lines.add("- " + key.trim() + "  FEHLER: " + ex.getMessage());
            }
        }
        return String.join("\n", lines);
    }

    // ------------------------------------------------------------------ Verknüpfungen und Statuswechsel (lesend)

    @Tool(name = "links", description = "Verknüpfte Tickets eines Tickets: Parent, Unteraufgaben/Sub-Issues, Links wie "
            + "blocks/is blocked by sowie zugehörige Pull bzw. Merge Requests." + ShellHints.TICKET)
    public String links(
            @ToolParam(description = "Ticket-Schlüssel oder URL") String key,
            @ToolParam(required = false, description = PROJECT) String project,
            @ToolParam(required = false, description = PROVIDER) String provider) {
        TicketEnvironment.Entry e = env.resolve(provider, key);
        List<TicketSystem.Link> links = e.system().links(key.trim(), e.project(project));
        if (links.isEmpty()) {
            return "Keine Verknüpfungen für " + key.trim() + ".";
        }
        StringBuilder sb = new StringBuilder(links.size() + " Verknüpfung(en) von " + key.trim() + ":\n");
        for (TicketSystem.Link l : links) {
            sb.append("- ").append(l.relation()).append(": ").append(l.key())
                    .append("  [").append(Text.orDash(l.status())).append("]  ").append(Text.orDash(l.title()));
            if (l.url() != null) {
                sb.append("  ").append(l.url());
            }
            sb.append('\n');
        }
        return Text.limitLines(sb.toString().strip(), env.maxLines());
    }

    @Tool(name = "transitions", description = "Mögliche Statuswechsel eines Tickets mit ID, Name und Zielstatus: "
            + "Jira-Workflow-Übergänge, Schließen/Wiedereröffnen, GitHub-Project-Spalten, GitLab-Board-Listen, YouTrack-State, "
            + "OpenProject-Status. "
            + "Die ID ist das Ziel für ticket_transition." + ShellHints.TICKET)
    public String transitions(
            @ToolParam(description = "Ticket-Schlüssel oder URL") String key,
            @ToolParam(required = false, description = PROJECT) String project,
            @ToolParam(required = false, description = PROVIDER) String provider) {
        TicketEnvironment.Entry e = env.resolve(provider, key);
        List<TicketSystem.Transition> list = e.system().transitions(key.trim(), e.project(project));
        if (list.isEmpty()) {
            return "Für " + key.trim() + " ist derzeit kein Statuswechsel möglich.";
        }
        StringBuilder sb = new StringBuilder("Mögliche Statuswechsel für " + key.trim() + ":\n");
        for (TicketSystem.Transition t : list) {
            sb.append("- [").append(t.id()).append("]  ").append(t.name()).append("  → ").append(t.to());
            if (t.category() != TicketSystem.StatusCategory.UNKNOWN) {
                sb.append(" (").append(t.category()).append(')');
            }
            if (t.kind() != null) {
                sb.append("  – ").append(t.kind());
            }
            sb.append('\n');
        }
        return Text.limitLines(sb.toString().strip(), env.maxLines());
    }

    @Tool(name = "worklogs", description = "Gebuchte Arbeitszeiten eines Tickets mit Tag, Dauer, Person, Tätigkeitsart "
            + "und Beschreibung sowie der Summe: Jira-Worklogs, GitLab-Zeiterfassung, YouTrack-Arbeitselemente, "
            + "OpenProject-Zeiteinträge." + ShellHints.TICKET)
    public String worklogs(
            @ToolParam(description = "Ticket-Schlüssel oder URL") String key,
            @ToolParam(required = false, description = PROJECT) String project,
            @ToolParam(required = false, description = PROVIDER) String provider) {
        TicketEnvironment.Entry e = env.resolve(provider, key);
        List<TicketSystem.WorkLogEntry> list = e.system().worklogs(key.trim(), e.project(project));
        if (list.isEmpty()) {
            return "Auf " + key.trim() + " ist keine Zeit gebucht.";
        }
        Duration total = list.stream().map(TicketSystem.WorkLogEntry::duration).reduce(Duration.ZERO, Duration::plus);
        StringBuilder sb = new StringBuilder(list.size() + " Buchung(en) auf " + key.trim() + ", gesamt "
                + TicketSystem.formatDuration(total) + ":\n");
        for (TicketSystem.WorkLogEntry w : list) {
            sb.append("- ").append(Text.orDash(w.date())).append("  ").append(TicketSystem.formatDuration(w.duration()))
                    .append("  ").append(Text.orDash(w.author()));
            if (w.activity() != null) {
                sb.append("  [").append(w.activity()).append(']');
            }
            if (w.comment() != null && !w.comment().isBlank()) {
                sb.append("  ").append(limit(w.comment().strip().replaceAll("\\s+", " "), 200));
            }
            if (w.id() != null) {
                sb.append("  (Buchung ").append(w.id()).append(')');
            }
            sb.append('\n');
        }
        return Text.limitLines(sb.toString().strip(), env.maxLines());
    }

    // ------------------------------------------------------------------ Formatierung

    /** Ergebniszeile einer schreibenden Aktion. */
    static String written(TicketSystem.WriteResult r) {
        return r.key() + ": " + r.message() + (r.url() == null ? "" : "\n" + r.url());
    }

    /** {@code KEY  [Status]  @a,@b  Titel  (Typ, Priorität, Labels)}. */
    static String line(Ticket t, boolean withLabels) {
        StringBuilder sb = new StringBuilder(t.key()).append("  [").append(Text.orDash(t.status())).append("]  ")
                .append(t.assignees().isEmpty() ? "–" : "@" + String.join(", @", t.assignees()))
                .append("  ").append(Text.orDash(t.title()));
        List<String> extra = new ArrayList<>();
        if (t.type() != null && !List.of("Issue", "issue").contains(t.type())) {
            extra.add(t.type());
        }
        if (t.priority() != null) {
            extra.add(t.priority());
        }
        if (withLabels && !t.labels().isEmpty()) {
            extra.add(String.join(", ", t.labels()));
        }
        if (!extra.isEmpty()) {
            sb.append("  (").append(String.join("; ", extra)).append(')');
        }
        return sb.toString();
    }

    private static void row(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) {
            sb.append(String.format("%-11s %s%n", label + ":", value));
        }
    }

    private static String limit(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "\n… [gekürzt: " + (s.length() - max) + " weitere Zeichen]";
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
