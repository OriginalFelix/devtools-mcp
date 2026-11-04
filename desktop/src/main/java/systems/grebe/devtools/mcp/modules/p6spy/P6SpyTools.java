package systems.grebe.devtools.mcp.modules.p6spy;

import java.util.List;
import java.util.Locale;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.LocalFiles;
import systems.grebe.devtools.mcp.core.ToolHints;
import systems.grebe.sqlproxy.SpyAnalysis;
import systems.grebe.sqlproxy.SpyAnalysis.Group;
import systems.grebe.sqlproxy.SpyAnalysis.Repeat;
import systems.grebe.sqlproxy.SpyLog;
import systems.grebe.sqlproxy.SpyLog.Chunk;
import systems.grebe.sqlproxy.SpyLog.Entry;

/** Auswertung eines p6spy-Protokolls: Statements filtern, teure und wiederholte Statements finden. */
@ToolHints(readOnly = true, idempotent = true)
public class P6SpyTools {

    private static final String PATH_PARAM = "Pfad zum p6spy-Protokoll (Einstellung logfile in spy.properties, "
            + "Standard spy.log im Arbeitsverzeichnis der Anwendung)";
    private static final String SINCE_PARAM = "Nur Einträge ab dieser Byte-Position – den Wert since aus dem "
            + "vorigen Ergebnis übergeben, um nur das seither Protokollierte zu sehen (z.B. vor und nach einer Aktion)";

    private final LocalFiles files;
    private final long maxBytes;

    P6SpyTools(LocalFiles files, long maxBytes) {
        this.files = files;
        this.maxBytes = maxBytes;
    }

    @Tool(name = "statements", description = "SQL-Statements aus dem p6spy-Protokoll einer Anwendung (spy.log) mit "
            + "Zeit, Dauer, Kategorie, Connection und SQL samt eingesetzten Werten – die neuesten zuletzt. Filterbar "
            + "nach Mindestdauer, Text, Kategorie und Connection. Mit since nur das seit dem letzten Aufruf "
            + "Protokollierte. Statt spy.log per `tail`/`grep` in der Shell zu lesen.")
    public String statements(
            @ToolParam(description = PATH_PARAM) String path,
            @ToolParam(required = false, description = "Nur Statements ab dieser Dauer in ms") Long minMillis,
            @ToolParam(required = false, description = "Nur Statements, deren SQL diesen Text enthält (ohne "
                    + "Groß-/Kleinschreibung)") String contains,
            @ToolParam(required = false, description = "Nur diese Kategorie: statement, batch, commit, rollback, "
                    + "result, resultset, error … (Standard alle)") String category,
            @ToolParam(required = false, description = "Nur diese Connection-ID") String connection,
            @ToolParam(required = false, description = SINCE_PARAM) Long since,
            @ToolParam(required = false, description = "Höchstens so viele (die neuesten, Standard 50, max. 1000)") Integer limit) {
        Chunk chunk = SpyLog.read(files.readable(path), since, maxBytes);
        String needle = contains == null || contains.isBlank() ? null : contains.toLowerCase(Locale.ROOT);
        List<Entry> hits = chunk.entries().stream()
                .filter(e -> minMillis == null || e.millis() >= minMillis)
                .filter(e -> category == null || category.isBlank() || e.category().equalsIgnoreCase(category.strip()))
                .filter(e -> connection == null || connection.isBlank() || e.connection().equals(connection.strip()))
                .filter(e -> needle == null || e.text().toLowerCase(Locale.ROOT).contains(needle))
                .toList();
        int n = limit == null || limit < 1 ? 50 : Math.min(limit, 1000);
        List<Entry> shown = hits.subList(Math.max(0, hits.size() - n), hits.size());

        StringBuilder sb = header(chunk);
        sb.append(hits.size()).append(" passende von ").append(chunk.entries().size()).append(" Einträgen");
        if (shown.size() < hits.size()) {
            sb.append(", die neuesten ").append(shown.size());
        }
        sb.append(":\n");
        for (Entry e : shown) {
            sb.append(e.displayTime()).append("  ").append(e.millis()).append(" ms  ").append(e.category())
                    .append("  conn ").append(e.connection());
            String t = e.text();
            sb.append(t.isEmpty() ? "" : "\n  " + t).append('\n');
        }
        return sb.append(footer(chunk)).toString().trim();
    }

    @Tool(name = "analyze", description = "Wertet das p6spy-Protokoll einer Anwendung aus: Anzahl und Gesamtdauer der "
            + "Statements, die teuersten Statements gruppiert nach SQL ohne Werte (Anzahl, Summe, Mittel, Maximum, "
            + "Beispiel mit Werten) und N+1-Verdacht – dasselbe Statement mit Tabellenzugriff viele Male kurz "
            + "hintereinander auf einer Connection (andere Statements dazwischen erlaubt), unterschieden nach identischen "
            + "Werten (Cache-Kandidat) und verschiedenen Werten (klassisches N+1). Mit since nur das seit dem letzten Aufruf Protokollierte, etwa eine einzelne Aktion.")
    public String analyze(
            @ToolParam(description = PATH_PARAM) String path,
            @ToolParam(required = false, description = SINCE_PARAM) Long since,
            @ToolParam(required = false, description = "Sortierung: total (Gesamtdauer, Standard), count (Anzahl) "
                    + "oder max (langsamste Ausführung)") String orderBy,
            @ToolParam(required = false, description = "Ab so vielen Wiederholungen kurz hintereinander gilt ein "
                    + "Statement als N+1-Verdacht (Standard 5)") Integer repeats,
            @ToolParam(required = false, description = "Höchstens so viele andere Statements dürfen zwischen zwei "
                    + "Wiederholungen liegen (Standard 10; 0 = direkt hintereinander)") Integer gap,
            @ToolParam(required = false, description = "Anzahl der gezeigten Statements (Standard 15, max. 200)") Integer limit) {
        Chunk chunk = SpyLog.read(files.readable(path), since, maxBytes);
        SpyAnalysis a = SpyAnalysis.of(chunk.entries());
        StringBuilder sb = header(chunk);
        if (a.statements().isEmpty()) {
            return sb.append("Keine Statements (Kategorie statement/batch) im gelesenen Bereich.\n")
                    .append(footer(chunk)).toString().trim();
        }
        sb.append(a.statements().size()).append(" Statements, ").append(a.groups().size())
                .append(" verschiedene, zusammen ").append(a.totalMillis()).append(" ms; ")
                .append(a.statements().getFirst().displayTime()).append(" bis ")
                .append(a.statements().getLast().displayTime()).append('\n');
        if (a.commits() + a.rollbacks() > 0) {
            sb.append(a.commits()).append(" Commits, ").append(a.rollbacks()).append(" Rollbacks\n");
        }

        SpyAnalysis.Order order;
        try {
            order = SpyAnalysis.Order.valueOf((orderBy == null ? "total" : orderBy.strip()).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("orderBy: total, count oder max – nicht " + orderBy);
        }
        int n = limit == null || limit < 1 ? 15 : Math.min(limit, 200);
        sb.append("\nTeuerste Statements (nach ").append(orderBy == null ? "total" : orderBy.strip()).append("):\n");
        for (Group g : a.top(order, n)) {
            sb.append(g.count()).append("×  Summe ").append(g.totalMillis()).append(" ms  Mittel ")
                    .append(g.meanMillis()).append(" ms  Max ").append(g.max().millis()).append(" ms (")
                    .append(g.max().displayTime()).append(", conn ").append(g.max().connection()).append(")\n  ")
                    .append(g.key()).append('\n');
            if (!g.key().equals(g.max().sql())) {
                sb.append("  Beispiel: ").append(g.max().text()).append('\n');
            }
        }

        int min = repeats == null || repeats < 2 ? 5 : repeats;
        int maxGap = gap == null || gap < 0 ? 10 : gap;
        List<Repeat> runs = a.repeats(min, maxGap);
        if (!runs.isEmpty()) {
            sb.append("\nN+1-Verdacht (dasselbe Statement mit Tabellenzugriff ≥ ").append(min)
                    .append("× auf einer Connection, höchstens ").append(maxGap)
                    .append(" andere dazwischen):\n");
            runs.stream().limit(n).forEach(r -> sb.append("bis zu ").append(r.inARow()).append("× in Folge, ")
                    .append(r.group().count()).append("× insgesamt, ")
                    .append(r.identical() ? "immer dieselben Werte (Cache-Kandidat)"
                            : r.group().variants() + " verschiedene Werte")
                    .append("\n  ").append(r.group().key()).append('\n'));
        }
        return sb.append(footer(chunk)).toString().trim();
    }

    private static StringBuilder header(Chunk chunk) {
        StringBuilder sb = new StringBuilder();
        if (chunk.rotated()) {
            sb.append("Hinweis: Datei ist kürzer als since – vermutlich neu angelegt, von vorn gelesen.\n");
        }
        if (chunk.skipped()) {
            sb.append("Hinweis: nur die letzten ").append((chunk.end() - chunk.start()) / 1024)
                    .append(" KB gelesen (Einstellung „Max. gelesene MB“), Älteres übersprungen.\n");
        }
        return sb;
    }

    private static String footer(Chunk chunk) {
        return "\nNur Neues beim nächsten Aufruf: since=" + chunk.end();
    }
}
