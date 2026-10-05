package systems.grebe.devtools.mcp.modules.ticket.spi;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
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

    /** Boards eines Projekts (Jira-Board, GitHub Project, GitLab-Issue-Board, YouTrack-Agile-Board, OpenProject-Board). */
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

    /**
     * Projekt, zu dem ein Ticket gehört (Jira-Projektschlüssel, GitHub {@code owner/repo}, GitLab-Projektpfad) – für
     * die Schreibfreigabe je Projekt. Soll ohne Netzwerkzugriff aus dem Schlüssel ableitbar sein; Systeme mit
     * instanzweiten Nummern (OpenProject {@code #123}) dürfen das Ticket dafür lesen. {@code null} = unbekannt; das
     * Modul lässt Schreibzugriffe dann nur zu, wenn keine Projektfreigabe eingeschränkt ist.
     */
    default String projectOf(String key, String project) {
        return null;
    }

    /**
     * Mögliche Statuswechsel eines Tickets (Workflow-Übergänge, Schließen/Wiedereröffnen, Board-Spalten).
     * Lesend – das Modul bietet es immer an; ausgeführt wird mit {@link #transition(String, String, Transition)}.
     */
    default List<Transition> transitions(String key, String project) {
        throw unsupported("Statuswechsel");
    }

    /** Verknüpfte Tickets: Parent, Unteraufgaben, Links, zugehörige Pull/Merge Requests. */
    default List<Link> links(String key, String project) {
        throw unsupported("Verknüpfungen");
    }

    /** Gebuchte Arbeitszeiten eines Tickets, chronologisch. Lesend – das Modul bietet es immer an. */
    default List<WorkLogEntry> worklogs(String key, String project) {
        throw unsupported("Zeiterfassung");
    }

    // ------------------------------------------------------------------ Schreiben
    // Die Freigaben (allowComment, allowTransition …, Projekte) prüft das Modul vor dem Aufruf. Provider, die eine
    // Aktion nicht können, lassen den Default stehen – das Tool meldet es dann dem LLM.

    /** Fügt einen Kommentar hinzu ({@link #markup()} als Format). */
    default WriteResult comment(String key, String project, String body) {
        throw unsupported("Kommentieren");
    }

    /**
     * Führt einen Statuswechsel aus, den {@link #transitions(String, String)} geliefert hat (Auswahl per
     * {@link #pickTransition}). Ein Kommentar zum Wechsel geht über {@link #comment} und braucht dessen Freigabe.
     */
    default WriteResult transition(String key, String project, Transition transition) {
        throw unsupported("Statuswechsel");
    }

    /**
     * Setzt die Zuständigen auf genau diese Liste ({@code me} = angemeldeter Benutzer; leer = niemand).
     * Systeme mit nur einem Zuständigen (Jira) weisen mehr als einen Eintrag ab.
     */
    default WriteResult assign(String key, String project, List<String> assignees) {
        throw unsupported("Zuweisen");
    }

    /** Ändert Titel, Beschreibung und Labels; {@code null}-Felder bleiben unverändert. */
    default WriteResult update(String key, String project, TicketUpdate update) {
        throw unsupported("Bearbeiten");
    }

    /** Legt ein Ticket im Projekt an. */
    default WriteResult create(String project, NewTicket ticket) {
        throw unsupported("Anlegen");
    }

    /**
     * Bucht Arbeitszeit auf ein Ticket (Jira-Worklog, GitLab-Timelog, YouTrack-Arbeitselement, OpenProject-Zeiteintrag).
     * {@link WriteResult#id()} ist die ID der Buchung.
     */
    default WriteResult logTime(String key, String project, WorkLog work) {
        throw unsupported("Zeiten buchen");
    }

    /** Löscht einen Kommentar eines Tickets. */
    default WriteResult deleteComment(String key, String project, String commentId) {
        throw unsupported("Kommentare löschen");
    }

    /** Löscht ein Ticket endgültig (Unteraufgaben werden nicht mitgelöscht). */
    default WriteResult delete(String key, String project) {
        throw unsupported("Tickets löschen");
    }

    /**
     * Eindeutige Schreibweise eines Schlüssels ({@code ABC-123}, {@code owner/repo#12}) – ohne Netzwerkzugriff.
     * Das Modul vergleicht damit angelegte und zu löschende Tickets; {@link WriteResult#key()} muss dieselbe Form haben.
     */
    default String canonicalKey(String key, String project) {
        return key.trim();
    }

    /**
     * Welche Instanz des Systems das ist (z.B. die Server-URL). Das Verzeichnis selbst angelegter Tickets und Kommentare
     * ist je Instanz getrennt – {@code ABC-1} auf einem anderen Jira gilt nicht als „selbst angelegt“.
     */
    default String instance() {
        return id();
    }

    /** Format von Beschreibungen und Kommentaren, z.B. „Markdown“. */
    default String markup() {
        return "Markdown";
    }

    private UnsupportedOperationException unsupported(String what) {
        return new UnsupportedOperationException(what + " wird vom Ticket-System '" + id() + "' nicht unterstützt.");
    }

    // ------------------------------------------------------------------ Hilfen

    /**
     * Wählt einen Statuswechsel per ID, Name oder Zielstatus – exakt vor eindeutigem Teilstring, ohne Groß-/Kleinschreibung.
     */
    static Transition pickTransition(List<Transition> transitions, String ref, String system, String key) {
        if (ref == null || ref.isBlank()) {
            throw new IllegalArgumentException(system + ": kein Ziel angegeben ('to'). Möglich für " + key + ": "
                    + describe(transitions));
        }
        if (transitions.isEmpty()) {
            throw new IllegalArgumentException(system + ": für " + key + " ist derzeit kein Statuswechsel möglich.");
        }
        String r = ref.trim();
        for (Transition t : transitions) {
            if (t.id().equals(r)) {
                return t;
            }
        }
        List<Transition> exact = transitions.stream()
                .filter(t -> t.name().equalsIgnoreCase(r) || r.equalsIgnoreCase(t.to())).toList();
        if (exact.size() == 1) {
            return exact.getFirst();
        }
        String lower = r.toLowerCase(Locale.ROOT);
        List<Transition> partial = (exact.isEmpty() ? transitions : exact).stream()
                .filter(t -> t.name().toLowerCase(Locale.ROOT).contains(lower)
                        || t.to() != null && t.to().toLowerCase(Locale.ROOT).contains(lower)).toList();
        if (partial.size() == 1) {
            return partial.getFirst();
        }
        throw new IllegalArgumentException(system + ": Ziel '" + r + "' ist für " + key + " nicht eindeutig oder nicht "
                + "möglich. Möglich (per ID angeben): " + describe(partial.isEmpty() ? transitions : partial));
    }

    private static String describe(List<Transition> transitions) {
        return transitions.isEmpty() ? "keine" : String.join(", ", transitions.stream()
                .map(t -> "'" + t.name() + "' → " + t.to() + " [" + t.id() + "]").toList());
    }

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

    /** @param id Kommentar-ID im System ({@code null}, wenn unbekannt) – Ziel für {@code ticket_delete_comment} */
    record Comment(String id, String author, String created, String body) {
        public Comment(String author, String created, String body) {
            this(null, author, created, body);
        }
    }

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

    /**
     * Ein möglicher Statuswechsel.
     *
     * @param id vom Provider vergeben, stabil für genau dieses Ticket (Jira-Transition-ID, {@code close:completed},
     *           {@code label:Doing} …)
     * @param name Anzeigename, z.B. „In Arbeit nehmen“ oder „Schließen (erledigt)“
     * @param to Zielstatus bzw. Zielspalte
     * @param kind Art, z.B. „Workflow“, „Status“, „Board-Spalte“, „Project Status“
     */
    record Transition(String id, String name, String to, StatusCategory category, String kind) { }

    /**
     * Verknüpfung zu einem anderen Ticket oder Pull/Merge Request.
     *
     * @param relation Beziehung aus Sicht des Tickets, z.B. „Parent“, „Unteraufgabe“, „blocks“, „is blocked by“, „Merge Request“
     */
    record Link(String relation, String key, String title, String status, String url) { }

    /**
     * Ergebnis einer schreibenden Aktion.
     *
     * @param key betroffenes Ticket in kanonischer Form ({@link #canonicalKey}); beim Anlegen der neue Schlüssel
     * @param message was passiert ist, eine Zeile
     * @param url Link zum Ergebnis (Ticket oder Kommentar) oder {@code null}
     * @param id ID des neu angelegten Kommentars ({@link #comment}), sonst {@code null} – das Modul merkt sie sich für
     *           „nur selbst angelegte löschen“
     */
    record WriteResult(String key, String message, String url, String id) {
        /** Ohne eigene ID (Statuswechsel, Zuweisung …). */
        public WriteResult(String key, String message, String url) {
            this(key, message, url, null);
        }
    }

    /** Änderung eines Tickets; {@code null} = Feld bleibt unverändert, leere Label-Liste = alle Labels entfernen. */
    record TicketUpdate(String title, String description, List<String> labels) {
        public TicketUpdate {
            labels = labels == null ? null : labels.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        }

        public boolean isEmpty() {
            return title == null && description == null && labels == null;
        }

        /** Was geändert wird, z.B. „Titel, Labels [bug]“. */
        public String summary() {
            List<String> parts = new java.util.ArrayList<>();
            if (title != null) {
                parts.add("Titel");
            }
            if (description != null) {
                parts.add("Beschreibung");
            }
            if (labels != null) {
                parts.add("Labels " + labels);
            }
            return String.join(", ", parts);
        }
    }

    /**
     * Neues Ticket.
     *
     * @param type Tickettyp (Jira: Issue-Typ wie „Bug“; GitHub: Issue-Typ der Organisation; GitLab: issue, incident,
     *             task) oder {@code null} für den Standard
     * @param assignees Zuständige wie bei {@link #assign}; leer = niemand
     */
    record NewTicket(String title, String description, String type, List<String> labels, List<String> assignees) {
        public NewTicket {
            labels = labels == null ? List.of() : labels.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
            assignees = assignees == null ? List.of() : assignees.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        }
    }

    /**
     * Zu buchende Arbeitszeit.
     *
     * @param duration positiv, auf Minuten genau
     * @param date Tag der Arbeit (nicht in der Zukunft)
     * @param comment Beschreibung der Tätigkeit oder {@code null}
     * @param activity Tätigkeitsart (YouTrack: Work Item Type, OpenProject: Aktivität) oder {@code null} für den Standard
     */
    record WorkLog(Duration duration, LocalDate date, String comment, String activity) {

        /**
         * Beginn der Arbeit für Systeme, die einen Zeitpunkt verlangen (lokale Zeitzone): heute = jetzt minus Dauer,
         * frühestens 0 Uhr; andere Tage 9 Uhr.
         */
        public ZonedDateTime start() {
            ZoneId zone = ZoneId.systemDefault();
            ZonedDateTime now = ZonedDateTime.now(zone).truncatedTo(ChronoUnit.SECONDS);
            if (date.equals(now.toLocalDate())) {
                ZonedDateTime start = now.minus(duration);
                ZonedDateTime midnight = date.atStartOfDay(zone);
                return start.isBefore(midnight) ? midnight : start;
            }
            return date.atTime(9, 0).atZone(zone);
        }
    }

    /**
     * Eine gebuchte Arbeitszeit.
     *
     * @param date Tag der Arbeit als {@code yyyy-MM-dd}
     * @param activity Tätigkeitsart oder {@code null}
     */
    record WorkLogEntry(String id, String author, String date, Duration duration, String comment, String activity) { }

    /** {@code 1h 30m}, {@code 45m}, {@code 2h} – verstehen Menschen, GitLab und YouTrack gleichermaßen. */
    static String formatDuration(Duration d) {
        long minutes = d.toMinutes();
        long h = minutes / 60;
        long m = minutes % 60;
        if (h == 0) {
            return m + "m";
        }
        return m == 0 ? h + "h" : h + "h " + m + "m";
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
