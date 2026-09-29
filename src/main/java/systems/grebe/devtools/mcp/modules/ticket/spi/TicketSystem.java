package systems.grebe.devtools.mcp.modules.ticket.spi;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ein angebundenes Ticket-System. Alle Methoden sind blockierend und werfen {@link IllegalStateException} bzw.
 * {@link IllegalArgumentException} mit einer für das LLM verständlichen Meldung (was ist falsch, was ist zu tun).
 *
 * <p>Projekt-Parameter kommen bereits aufgelöst an: ist im Tool kein Projekt angegeben, reicht das Modul das
 * Standardprojekt des Systems durch ({@code null}, wenn keines konfiguriert ist).
 */
public interface TicketSystem {

    /** ID des Providers, der dieses System erzeugt hat. */
    String id();

    /** Prüft Erreichbarkeit und Anmeldung. */
    Availability probe();

    /**
     * Ob der Schlüssel eindeutig zu diesem System gehört (z.B. eine Issue-URL seines Hosts oder ein Jira-Schlüssel
     * {@code ABC-123}). Damit wählt das Modul das System, wenn das LLM keinen {@code provider} angibt.
     */
    default boolean ownsKey(String key) {
        return false;
    }

    /** Boards eines Projekts (Jira-Board, GitHub Project, GitLab-Issue-Board). */
    List<Board> boards(String project);

    /**
     * Aktueller Inhalt eines Boards nach Spalten.
     *
     * @param board ID oder Name aus {@link #boards(String)}; leer = das einzige Board des Projekts
     */
    BoardView board(String board, String project, BoardOptions options);

    TicketPage search(TicketQuery query);

    /**
     * Vollständiges Ticket inkl. Beschreibung und den neuesten {@code maxComments} Kommentaren.
     * Bei {@code maxComments == 0} sollen keine Kommentare geladen werden (Statusabfrage mehrerer Tickets).
     *
     * @param key Schlüssel oder Web-URL des Tickets; kurze Formen wie {@code #12} beziehen sich auf {@code project}
     */
    TicketDetails ticket(String key, String project, int maxComments);

    // ------------------------------------------------------------------ Hilfen

    /** Wählt ein Board per ID oder Name (exakt vor Teilstring); leer = das einzige vorhandene. */
    static Board pickBoard(List<Board> boards, String ref, String system) {
        if (boards.isEmpty()) {
            throw new IllegalArgumentException(system + ": keine Boards gefunden – Projekt prüfen (ticket_boards).");
        }
        if (ref == null || ref.isBlank()) {
            if (boards.size() == 1) {
                return boards.getFirst();
            }
            throw new IllegalArgumentException(system + ": mehrere Boards – mit 'board' eines auswählen: " + names(boards));
        }
        String r = ref.trim();
        for (Board b : boards) {
            if (b.id().equals(r)) {
                return b;
            }
        }
        List<Board> exact = boards.stream().filter(b -> b.name().equalsIgnoreCase(r)).toList();
        if (exact.size() == 1) {
            return exact.getFirst();
        }
        String lower = r.toLowerCase(Locale.ROOT);
        List<Board> partial = boards.stream().filter(b -> b.name().toLowerCase(Locale.ROOT).contains(lower)).toList();
        if (partial.size() == 1) {
            return partial.getFirst();
        }
        throw new IllegalArgumentException(system + ": Board '" + r + "' ist nicht eindeutig oder existiert nicht. "
                + "Verfügbar: " + names(partial.isEmpty() ? boards : partial));
    }

    private static String names(List<Board> boards) {
        return String.join(", ", boards.stream().map(b -> b.name() + " (" + b.id() + ")").toList());
    }

    // ------------------------------------------------------------------ Modell

    /** Grobe, systemübergreifende Einordnung eines Status. */
    enum StatusCategory { TODO, IN_PROGRESS, DONE, UNKNOWN }

    /** Statusfilter einer Suche. */
    enum State {
        OPEN, CLOSED, ALL;

        public static State parse(String s) {
            if (s == null || s.isBlank()) {
                return OPEN;
            }
            return switch (s.trim().toLowerCase(Locale.ROOT)) {
                case "open", "opened", "offen" -> OPEN;
                case "closed", "done", "geschlossen", "erledigt" -> CLOSED;
                case "all", "alle" -> ALL;
                default -> throw new IllegalArgumentException("Unbekannter Status '" + s + "' – erlaubt: open, closed, all.");
            };
        }
    }

    record Availability(boolean available, String version, String user, String message) {
        public static Availability ok(String version, String user) {
            return new Availability(true, version, user, "verfügbar");
        }

        public static Availability unavailable(String message) {
            return new Availability(false, null, null, message);
        }
    }

    record Board(String id, String name, String type, String project, String url) { }

    /** @param total Anzahl Tickets in der Spalte (kann größer sein als {@code tickets}, die gekürzt sind) */
    record Column(String name, List<Ticket> tickets, int total) {
        public Column {
            tickets = List.copyOf(tickets);
        }
    }

    /** @param scope was das Board zeigt (z.B. „Sprint 42“), @param note Hinweis wie „gekürzt“ oder {@code null} */
    record BoardView(Board board, String scope, List<Column> columns, String note) {
        public BoardView {
            columns = List.copyOf(columns);
        }
    }

    /**
     * @param assignee {@code me}, {@code none} oder Benutzer; {@code null} = alle
     * @param maxPerColumn höchstens so viele Tickets je Spalte ausgeben
     */
    record BoardOptions(String assignee, int maxPerColumn) { }

    /**
     * Ein Ticket in Kurzform.
     *
     * @param key systemweit eindeutig ({@code ABC-123}, {@code owner/repo#12}, {@code group/project#12})
     * @param assignees Anzeigenamen bzw. Logins; leer = nicht zugewiesen
     */
    record Ticket(String key, String title, String status, StatusCategory category, String type, String priority,
                  List<String> assignees, List<String> labels, String updated, String url) {
        public Ticket {
            assignees = assignees == null ? List.of() : List.copyOf(assignees);
            labels = labels == null ? List.of() : List.copyOf(labels);
        }
    }

    record Comment(String author, String created, String body) { }

    /**
     * @param fields weitere Felder in Anzeigereihenfolge (Parent, Sprint, Milestone …), leere Werte weglassen
     * @param comments neueste Kommentare, chronologisch
     * @param totalComments Gesamtzahl der Kommentare
     */
    record TicketDetails(Ticket ticket, String reporter, String created, String description,
                         Map<String, String> fields, List<Comment> comments, int totalComments) {
        public TicketDetails {
            fields = fields == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(fields));
            comments = comments == null ? List.of() : List.copyOf(comments);
        }
    }

    /**
     * Suchanfrage. Alle Felder außer {@code state} und {@code limit} sind optional.
     *
     * @param assignee {@code me}, {@code none} oder Benutzer
     * @param labels alle angegebenen Labels müssen gesetzt sein
     * @param rawQuery systemeigene Abfrage (JQL, GitHub-Suchsyntax …), wird mit den übrigen Filtern UND-verknüpft
     * @param cursor Fortsetzung aus {@link TicketPage#nextCursor()}
     */
    record TicketQuery(String project, String text, State state, String assignee, List<String> labels,
                       String rawQuery, int limit, String cursor) {
        public TicketQuery {
            labels = labels == null ? List.of() : labels.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
            state = state == null ? State.OPEN : state;
        }
    }

    /**
     * @param total Gesamtzahl oder {@code null}, wenn das System sie nicht liefert (Jira Cloud)
     * @param nextCursor für die nächste Seite oder {@code null}
     * @param effectiveQuery tatsächlich gestellte Abfrage (zur Kontrolle für das LLM)
     */
    record TicketPage(List<Ticket> tickets, Integer total, String nextCursor, String effectiveQuery) {
        public TicketPage {
            tickets = List.copyOf(tickets);
        }
    }

    /** {@code me}/{@code ich}/{@code @me} als „angemeldeter Benutzer“. */
    static boolean isMe(String assignee) {
        return assignee != null && List.of("me", "@me", "ich", "self").contains(assignee.trim().toLowerCase(Locale.ROOT));
    }

    /** {@code none}/{@code niemand} als „nicht zugewiesen“. */
    static boolean isNone(String assignee) {
        return assignee != null && List.of("none", "niemand", "unassigned", "-").contains(assignee.trim().toLowerCase(Locale.ROOT));
    }
}
