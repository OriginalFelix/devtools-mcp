package systems.grebe.devtools.mcp.modules.p6spy;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.LocalFiles;
import systems.grebe.devtools.mcp.core.ToolHints;
import systems.grebe.devtools.mcp.modules.p6spy.P6SpyLog.Chunk;
import systems.grebe.devtools.mcp.modules.p6spy.P6SpyLog.Entry;

/** Auswertung eines p6spy-Protokolls: Statements filtern, teure und wiederholte Statements finden. */
@ToolHints(readOnly = true, idempotent = true)
public class P6SpyTools {

    private static final String PATH_PARAM = "Pfad zum p6spy-Protokoll (Einstellung logfile in spy.properties, "
            + "Standard spy.log im Arbeitsverzeichnis der Anwendung)";
    private static final String SINCE_PARAM = "Nur Einträge ab dieser Byte-Position – den Wert since aus dem "
            + "vorigen Ergebnis übergeben, um nur das seither Protokollierte zu sehen (z.B. vor und nach einer Aktion)";
    private static final Set<String> SQL = Set.of("statement", "batch");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault());

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
        Chunk chunk = P6SpyLog.read(files.readable(path), since, maxBytes);
        String needle = contains == null || contains.isBlank() ? null : contains.toLowerCase(Locale.ROOT);
        List<Entry> hits = chunk.entries().stream()
                .filter(e -> minMillis == null || e.millis() >= minMillis)
                .filter(e -> category == null || category.isBlank() || e.category().equalsIgnoreCase(category.strip()))
                .filter(e -> connection == null || connection.isBlank() || e.connection().equals(connection.strip()))
                .filter(e -> needle == null || text(e).toLowerCase(Locale.ROOT).contains(needle))
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
            sb.append(time(e.time())).append("  ").append(e.millis()).append(" ms  ").append(e.category())
                    .append("  conn ").append(e.connection());
            String t = text(e);
            sb.append(t.isEmpty() ? "" : "\n  " + t).append('\n');
        }
        return sb.append(footer(chunk)).toString().trim();
    }

    @Tool(name = "analyze", description = "Wertet das p6spy-Protokoll einer Anwendung aus: Anzahl und Gesamtdauer der "
            + "Statements, die teuersten Statements gruppiert nach SQL ohne Werte (Anzahl, Summe, Mittel, Maximum, "
            + "Beispiel mit Werten) und N+1-Verdacht – dasselbe Statement viele Male direkt hintereinander auf einer "
            + "Connection. Mit since nur das seit dem letzten Aufruf Protokollierte, etwa eine einzelne Aktion.")
    public String analyze(
            @ToolParam(description = PATH_PARAM) String path,
            @ToolParam(required = false, description = SINCE_PARAM) Long since,
            @ToolParam(required = false, description = "Sortierung: total (Gesamtdauer, Standard), count (Anzahl) "
                    + "oder max (langsamste Ausführung)") String orderBy,
            @ToolParam(required = false, description = "Ab so vielen Wiederholungen direkt hintereinander gilt ein "
                    + "Statement als N+1-Verdacht (Standard 5)") Integer repeats,
            @ToolParam(required = false, description = "Anzahl der gezeigten Statements (Standard 15, max. 200)") Integer limit) {
        Chunk chunk = P6SpyLog.read(files.readable(path), since, maxBytes);
        List<Entry> sql = chunk.entries().stream().filter(e -> SQL.contains(e.category()) && !text(e).isEmpty())
                .toList();
        StringBuilder sb = header(chunk);
        if (sql.isEmpty()) {
            return sb.append("Keine Statements (Kategorie statement/batch) im gelesenen Bereich.\n")
                    .append(footer(chunk)).toString().trim();
        }

        Map<String, Group> groups = new LinkedHashMap<>();
        for (Entry e : sql) {
            groups.computeIfAbsent(e.key(), k -> new Group(e)).add(e);
        }
        long total = sql.stream().mapToLong(Entry::millis).sum();
        sb.append(sql.size()).append(" Statements, ").append(groups.size()).append(" verschiedene, zusammen ")
                .append(total).append(" ms; ").append(time(sql.getFirst().time())).append(" bis ")
                .append(time(sql.getLast().time())).append('\n');
        long commits = chunk.entries().stream().filter(e -> e.category().equals("commit")).count();
        long rollbacks = chunk.entries().stream().filter(e -> e.category().equals("rollback")).count();
        if (commits + rollbacks > 0) {
            sb.append(commits).append(" Commits, ").append(rollbacks).append(" Rollbacks\n");
        }

        Comparator<Group> order = switch (orderBy == null ? "total" : orderBy.strip().toLowerCase(Locale.ROOT)) {
            case "count" -> Comparator.comparingLong(g -> g.count);
            case "max" -> Comparator.comparingLong(g -> g.max.millis());
            case "total" -> Comparator.comparingLong(g -> g.total);
            default -> throw new IllegalArgumentException("orderBy: total, count oder max – nicht " + orderBy);
        };
        int n = limit == null || limit < 1 ? 15 : Math.min(limit, 200);
        List<Group> top = groups.values().stream().sorted(order.reversed()).limit(n).toList();
        sb.append("\nTeuerste Statements (nach ").append(orderBy == null ? "total" : orderBy.strip()).append("):\n");
        for (Group g : top) {
            sb.append(g.count).append("×  Summe ").append(g.total).append(" ms  Mittel ")
                    .append(g.total / g.count).append(" ms  Max ").append(g.max.millis()).append(" ms (")
                    .append(time(g.max.time())).append(", conn ").append(g.max.connection()).append(")\n  ")
                    .append(g.key).append('\n');
            if (!g.key.equals(g.max.sql())) {
                sb.append("  Beispiel: ").append(text(g.max)).append('\n');
            }
        }

        int min = repeats == null || repeats < 2 ? 5 : repeats;
        Map<String, Integer> runs = repeatedRuns(sql, min);
        if (!runs.isEmpty()) {
            sb.append("\nN+1-Verdacht (dasselbe Statement ≥ ").append(min)
                    .append("× direkt hintereinander auf einer Connection):\n");
            runs.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(n)
                    .forEach(r -> sb.append("bis zu ").append(r.getValue()).append("× in Folge, ")
                            .append(groups.get(r.getKey()).count).append("× insgesamt\n  ").append(r.getKey())
                            .append('\n'));
        }
        return sb.append(footer(chunk)).toString().trim();
    }

    /** Längste Folge desselben Statements je Connection, nur Folgen ab {@code min}. */
    static Map<String, Integer> repeatedRuns(List<Entry> sql, int min) {
        Map<String, String> lastKey = new HashMap<>();
        Map<String, Integer> run = new HashMap<>();
        Map<String, Integer> longest = new HashMap<>();
        for (Entry e : sql) {
            String key = e.key();
            int len = key.equals(lastKey.put(e.connection(), key)) ? run.get(e.connection()) + 1 : 1;
            run.put(e.connection(), len);
            if (len >= min) {
                longest.merge(key, len, Math::max);
            }
        }
        return longest;
    }

    private static final class Group {
        final String key;
        long count;
        long total;
        Entry max;

        Group(Entry first) {
            key = first.key();
            max = first;
        }

        void add(Entry e) {
            count++;
            total += e.millis();
            if (e.millis() > max.millis()) {
                max = e;
            }
        }
    }

    private static String text(Entry e) {
        return e.sql().isEmpty() ? e.prepared() : e.sql();
    }

    /** p6spy schreibt die Zeit standardmäßig als Epoch-Millisekunden, mit {@code dateformat} formatiert. */
    private static String time(String raw) {
        return raw.matches("\\d{12,14}") ? TIME.format(Instant.ofEpochMilli(Long.parseLong(raw))) : raw;
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
