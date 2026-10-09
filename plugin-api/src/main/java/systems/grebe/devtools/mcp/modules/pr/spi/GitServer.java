package systems.grebe.devtools.mcp.modules.pr.spi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ein angebundener Git-Server mit Pull/Merge Requests. Alle Methoden sind blockierend und werfen
 * {@link IllegalStateException} bzw. {@link IllegalArgumentException} mit einer für das LLM verständlichen Meldung.
 *
 * <p>{@code project} ist der Repository-Pfad auf dem Server ({@code owner/repo}, {@code gruppe/projekt},
 * {@code workspace/repo}, {@code PROJ/repo}) – vom Modul aus dem Parameter, dem Remote des lokalen Repositories oder
 * der Pull-Request-URL bestimmt. {@code ref} ist die Pull-Request-Angabe des LLM (Nummer, {@code #12}, voller
 * Schlüssel oder Web-URL).
 */
public interface GitServer {

    /** ID des Providers, der diesen Server erzeugt hat. */
    String id();

    /** Prüft Erreichbarkeit und Anmeldung. */
    Availability probe();

    /** Welche Instanz das ist (Web-URL des Servers). */
    String instance();

    /**
     * Repository-Pfad auf diesem Server aus der Remote-URL eines lokalen Repositories (HTTPS oder SSH), oder
     * {@code null}, wenn das Remote nicht zu diesem Server gehört. Ohne Netzwerkzugriff.
     */
    String projectOfRemote(String remoteUrl);

    /** Ob die Pull-Request-Angabe eine Web-URL dieses Servers ist. */
    default boolean ownsKey(String ref) {
        return false;
    }

    /**
     * Repository, zu dem ein Pull Request gehört (aus URL oder vollem Schlüssel, sonst {@code project}) – ohne
     * Netzwerkzugriff; für die Schreibfreigabe je Repository.
     */
    String projectOf(String ref, String project);

    /** Format von Beschreibungen und Kommentaren. */
    default String markup() {
        return "Markdown";
    }

    // ------------------------------------------------------------------ Lesen

    List<PullRequest> list(PrQuery query);

    /** Pull Request mit Beschreibung, Reviewern, Freigaben, Merge-Status und CI-Status des letzten Commits. */
    PrDetails get(String ref, String project);

    /** Geänderte Dateien mit Unified-Diff-Ausschnitt ({@code patch} kann bei sehr großen Dateien fehlen). */
    List<FileChange> diff(String ref, String project);

    /**
     * Alle Kommentare, gruppiert nach Thread: allgemeine Kommentare (je einer ein Thread), Code-Kommentare mit Datei
     * und Zeile bzw. Datei-Kommentare (nur Datei) samt Antworten, Review-Zusammenfassungen. Chronologisch nach erstem
     * Kommentar. Kommentare von Integrationen (Apps, Bots, Dienstkonten) tragen {@link Comment#integration()}.
     */
    List<Thread> threads(String ref, String project);

    /**
     * Ergebnisse von Integrationen zum letzten Commit des Pull Requests – Berichte von Code-Analyse, Tests und
     * Sicherheits-Scans (Bitbucket Code Insights, GitHub Check-Runs mit Ausgabe, GitLab Testberichte) samt Befunden an
     * Datei und Zeile. Leere Liste, wenn keine Integration berichtet hat.
     */
    default List<Insight> insights(String ref, String project) {
        throw unsupported("Berichte von Integrationen lesen");
    }

    // ------------------------------------------------------------------ Schreiben
    // Die Freigaben prüft das Modul vor dem Aufruf. Server, die eine Aktion nicht können, lassen den Default stehen.

    /** Legt einen Pull Request an; ohne Ziel-Branch wird der Standard-Branch des Repositories verwendet. */
    default WriteResult create(String project, NewPullRequest pr) {
        throw unsupported("Pull Requests anlegen");
    }

    /** Ändert Titel, Beschreibung oder Ziel-Branch; {@code null} = unverändert. */
    default WriteResult update(String ref, String project, PrUpdate update) {
        throw unsupported("Pull Requests bearbeiten");
    }

    /**
     * Allgemeiner Kommentar, mit Datei und Zeile ein Code-Kommentar zur neuen Fassung, mit Datei ohne Zeile ein
     * Datei-Kommentar zur Datei als Ganzes.
     */
    default WriteResult comment(String ref, String project, NewComment comment) {
        throw unsupported("Kommentieren");
    }

    /** Antwort in einem Thread aus {@link #threads}. */
    default WriteResult reply(String ref, String project, String threadId, String body) {
        throw unsupported("Antworten");
    }

    /** Markiert einen Thread als erledigt bzw. öffnet ihn wieder. */
    default WriteResult resolve(String ref, String project, String threadId, boolean resolved) {
        throw unsupported("Threads auflösen");
    }

    /** Führt den Pull Request zusammen. */
    default WriteResult merge(String ref, String project, MergeOptions options) {
        throw unsupported("Mergen");
    }

    private UnsupportedOperationException unsupported(String what) {
        return new UnsupportedOperationException(what + " wird vom Git-Server '" + id() + "' nicht unterstützt.");
    }

    // ------------------------------------------------------------------ Modell

    /** Statusfilter einer Suche. */
    enum State {
        OPEN, MERGED, CLOSED, ALL;

        public static State parse(String s) {
            if (s == null || s.isBlank()) {
                return OPEN;
            }
            return switch (s.trim().toLowerCase(Locale.ROOT)) {
                case "open", "opened", "offen" -> OPEN;
                case "merged", "gemergt", "zusammengeführt" -> MERGED;
                case "closed", "declined", "geschlossen", "abgelehnt" -> CLOSED;
                case "all", "alle" -> ALL;
                default -> throw new IllegalArgumentException("Unbekannter Status '" + s
                        + "' – erlaubt: open, merged, closed, all.");
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

    /**
     * Pull Request in Kurzform.
     *
     * @param key systemweit eindeutig ({@code owner/repo#12}, {@code gruppe/projekt!12}, {@code PROJ/repo#12})
     * @param state {@code open}, {@code merged} oder {@code closed}
     */
    record PullRequest(String key, String title, String state, boolean draft, String author, String source,
                       String target, String updated, String url) { }

    /** CI-Status eines Commits, z.B. ein Check-Run oder die Pipeline. */
    record Check(String name, String status, String url) { }

    /**
     * @param reviewers angefragte Reviewer
     * @param approvedBy wer freigegeben hat
     * @param mergeStatus z.B. „mergeable“, „Konflikte“, „blockiert: 1 Freigabe fehlt“; {@code null} = unbekannt
     * @param headCommit letzter Commit des Quell-Branches
     * @param fields weitere Angaben in Anzeigereihenfolge (Kommentaranzahl, Labels, Milestone …)
     */
    record PrDetails(PullRequest pr, String description, String created, List<String> reviewers, List<String> approvedBy,
                     String mergeStatus, String headCommit, List<Check> checks, Map<String, String> fields) {
        public PrDetails {
            reviewers = reviewers == null ? List.of() : List.copyOf(reviewers);
            approvedBy = approvedBy == null ? List.of() : List.copyOf(approvedBy);
            checks = checks == null ? List.of() : List.copyOf(checks);
            fields = fields == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        }
    }

    /**
     * @param status {@code added}, {@code modified}, {@code deleted}, {@code renamed}
     * @param oldPath bisheriger Pfad bei Umbenennungen, sonst {@code null}
     * @param additions hinzugefügte Zeilen, {@code -1} = unbekannt
     */
    record FileChange(String path, String oldPath, String status, int additions, int deletions, String patch) { }

    /** @param integration von einer Integration (App, Bot, Dienstkonto) statt von einer Person geschrieben */
    record Comment(String id, String author, String created, String body, boolean integration) {
        public Comment(String id, String author, String created, String body) {
            this(id, author, created, body, false);
        }
    }

    /**
     * Kommentar-Thread.
     *
     * @param id Ziel für {@code pr_reply} und {@code pr_resolve}
     * @param kind „Kommentar“ (allgemein), „Code“ (Datei/Zeile), „Datei“ (Datei als Ganzes) oder „Review“
     *             (Zusammenfassung mit Urteil); Provider dürfen eigene ergänzen (z.B. „Aufgabe“)
     * @param resolved erledigt; {@code null} = nicht auflösbar (allgemeine Kommentare bei GitHub)
     * @param line Zeile in der neuen Fassung, bei veralteten Kommentaren die ursprüngliche; {@code null} = allgemeiner
     *             oder Datei-Kommentar
     * @param outdated der Code hat sich seit dem Kommentar geändert
     */
    record Thread(String id, String kind, Boolean resolved, String path, Integer line, boolean outdated,
                  List<Comment> comments) {
        public Thread {
            comments = comments == null ? List.of() : List.copyOf(comments);
        }
    }

    /**
     * @param author Benutzername oder {@code me}; {@code null} = alle
     * @param source Quell-Branch; {@code null} = alle
     * @param target Ziel-Branch; {@code null} = alle
     */
    record PrQuery(String project, State state, String author, String source, String target, int limit) {
        public PrQuery {
            state = state == null ? State.OPEN : state;
        }
    }

    /**
     * @param target Ziel-Branch oder {@code null} für den Standard-Branch
     * @param reviewers Benutzernamen (Bitbucket Cloud: Account-ID oder UUID)
     */
    record NewPullRequest(String title, String description, String source, String target, boolean draft,
                          List<String> reviewers, boolean deleteSourceBranch) {
        public NewPullRequest {
            reviewers = reviewers == null ? List.of()
                    : reviewers.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        }
    }

    /** {@code null} = unverändert. */
    record PrUpdate(String title, String description, String target) {
        public boolean isEmpty() {
            return title == null && description == null && target == null;
        }
    }

    /**
     * @param path Datei für einen Code- oder Datei-Kommentar, {@code null} = allgemeiner Kommentar
     * @param line Zeile der neuen Fassung; {@code null} mit {@code path} = Kommentar zur ganzen Datei
     */
    record NewComment(String body, String path, Integer line) {
        /** An einer Datei – mit oder ohne Zeile. */
        public boolean inline() {
            return path != null && !path.isBlank();
        }

        /** An der Datei als Ganzes, nicht an einer Zeile. */
        public boolean fileLevel() {
            return inline() && line == null;
        }
    }

    /**
     * Bericht einer Integration zum letzten Commit, z.B. SonarQube-Analyse, Testlauf oder Sicherheits-Scan.
     *
     * @param id ID des Berichts beim Server
     * @param source Integration bzw. App, die berichtet hat (z.B. „SonarQube“, „GitHub Actions“); {@code null} = unbekannt
     * @param result z.B. {@code PASS}/{@code FAIL}, {@code success}/{@code failure}; {@code null} = ohne Urteil
     * @param summary Kurzbeschreibung bzw. Zusammenfassung; {@code null} = keine
     * @param data Kennzahlen in Anzeigereihenfolge (Abdeckung, Fehleranzahl …)
     * @param annotations Befunde an Datei und Zeile
     * @param annotationCount Anzahl der Befunde laut Server; größer als {@code annotations.size()}, wenn gekürzt
     */
    record Insight(String id, String title, String source, String result, String summary, String url,
                   Map<String, String> data, List<Annotation> annotations, int annotationCount) {
        public Insight {
            data = data == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(data));
            annotations = annotations == null ? List.of() : List.copyOf(annotations);
            annotationCount = Math.max(annotationCount, annotations.size());
        }
    }

    /**
     * Befund einer Integration.
     *
     * @param path Datei; {@code null} = betrifft den ganzen Pull Request
     * @param line Zeile der neuen Fassung; {@code null} = ganze Datei
     * @param severity z.B. {@code HIGH}, {@code warning}
     * @param type z.B. {@code BUG}, {@code CODE_SMELL}, {@code VULNERABILITY}; {@code null} = unbekannt
     */
    record Annotation(String path, Integer line, String severity, String type, String message, String url) { }

    /**
     * @param method {@code merge}, {@code squash} oder {@code rebase}; {@code null} = Standard des Repositories
     * @param message Commit-Nachricht oder {@code null}
     */
    record MergeOptions(String method, String message, boolean deleteSourceBranch) { }

    /**
     * Ergebnis einer schreibenden Aktion.
     *
     * @param key betroffener Pull Request (kanonisch)
     * @param id ID eines neuen Kommentars, sonst {@code null}
     */
    record WriteResult(String key, String message, String url, String id) {
        public WriteResult(String key, String message, String url) {
            this(key, message, url, null);
        }
    }

    // ------------------------------------------------------------------ Hilfen für Provider

    /** {@code me}/{@code ich}/{@code @me} als „angemeldeter Benutzer“. */
    static boolean isMe(String user) {
        return user != null && List.of("me", "@me", "ich", "self").contains(user.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Zerlegte Remote-URL.
     *
     * @param path Pfad ohne führenden Schrägstrich und ohne {@code .git}
     * @param port {@code -1} = Standard
     */
    record Remote(String scheme, String host, int port, String path) { }

    Pattern SCP_LIKE = Pattern.compile("(?:[^@/]+@)?([^:/]+):(?!//)(.+)");
    Pattern URL_LIKE = Pattern.compile("([a-z][a-z0-9+.-]*)://(?:[^@/]+@)?([^:/]+)(?::(\\d+))?(/.*)?",
            Pattern.CASE_INSENSITIVE);

    /** Liest {@code https://[user@]host[:port]/pfad.git}, {@code ssh://git@host:7999/pfad.git} und {@code git@host:pfad.git}. */
    static Remote parseRemote(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String u = url.trim();
        Matcher m = URL_LIKE.matcher(u);
        if (m.matches()) {
            return new Remote(m.group(1).toLowerCase(Locale.ROOT), m.group(2).toLowerCase(Locale.ROOT),
                    m.group(3) == null ? -1 : Integer.parseInt(m.group(3)), cleanPath(m.group(4)));
        }
        m = SCP_LIKE.matcher(u);
        if (m.matches() && m.group(1).length() > 1) { // kein Laufwerksbuchstabe (C:/…)
            return new Remote("ssh", m.group(1).toLowerCase(Locale.ROOT), -1, cleanPath(m.group(2)));
        }
        return null;
    }

    private static String cleanPath(String p) {
        String s = p == null ? "" : p.trim();
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s.endsWith(".git") ? s.substring(0, s.length() - 4) : s;
    }

    /** Host einer HTTP(S)-URL in Kleinbuchstaben oder {@code ""}. */
    static String hostOf(String url) {
        Remote r = parseRemote(url);
        return r == null ? "" : r.host();
    }

    /** Pfad einer HTTP(S)-URL ohne führenden Schrägstrich, z.B. Kontextpfad eines selbst betriebenen Servers. */
    static String pathOf(String url) {
        Remote r = parseRemote(url);
        return r == null ? "" : r.path();
    }

    /**
     * Zerlegt einen Unified Diff ({@code diff --git a/x b/y …}) in Abschnitte je Datei; Schlüssel ist der neue Pfad
     * (bei gelöschten Dateien der alte).
     */
    static Map<String, String> splitUnifiedDiff(String diff) {
        Map<String, String> out = new LinkedHashMap<>();
        if (diff == null || diff.isBlank()) {
            return out;
        }
        String path = null;
        List<String> lines = new ArrayList<>();
        for (String line : diff.split("\r?\n", -1)) {
            if (line.startsWith("diff --git ")) {
                if (path != null) {
                    out.put(path, String.join("\n", lines).strip());
                }
                lines.clear();
                path = diffPath(line);
                continue;
            }
            if (path == null) {
                continue;
            }
            if (line.startsWith("+++ ") && !line.startsWith("+++ /dev/null")) {
                path = stripPrefix(line.substring(4));
            } else if (line.startsWith("--- ") && !line.startsWith("--- /dev/null") && path.isEmpty()) {
                path = stripPrefix(line.substring(4));
            }
            if (line.startsWith("@@") || !lines.isEmpty()) {
                lines.add(line);
            }
        }
        if (path != null) {
            out.put(path, String.join("\n", lines).strip());
        }
        return out;
    }

    private static String diffPath(String header) {
        // diff --git a/pfad b/pfad – bei Leerzeichen im Pfad nicht eindeutig, +++ korrigiert es
        int b = header.lastIndexOf(" b/");
        return b < 0 ? "" : header.substring(b + 3);
    }

    private static String stripPrefix(String p) {
        String s = p.trim();
        int tab = s.indexOf('\t');
        if (tab >= 0) {
            s = s.substring(0, tab);
        }
        return s.startsWith("a/") || s.startsWith("b/") ? s.substring(2) : s;
    }
}
