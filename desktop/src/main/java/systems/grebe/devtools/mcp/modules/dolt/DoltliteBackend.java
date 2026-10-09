package systems.grebe.devtools.mcp.modules.dolt;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import systems.grebe.devtools.mcp.core.CommandRunner;
import systems.grebe.devtools.mcp.core.Text;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Doltlite (SQLite-Fork mit Dolt-Versionierung) über das Programm {@code doltlite}: Für Java gibt es keine Bindings,
 * und der JDBC-Treiber von SQLite bringt seine eigene Engine mit. Je Abfrage ein Aufruf (etwa 40 ms); das SQL geht
 * über die Standardeingabe, damit unter Windows nichts gequotet werden muss.
 *
 * <p>Jede Verbindung hat bei Doltlite ihren eigenen Branch; wohin eine neue Verbindung ohne {@code datei@branch}
 * zeigt, steht in der Datei und wird mit {@code dolt_default_branch(name)} umgestellt.
 */
final class DoltliteBackend implements DoltBackend {

    private static final JsonMapper JSON = JsonMapper.shared();

    private final DoltDatabase db;
    private final String program;
    private final Path file;
    private final Duration timeout;

    private DoltliteBackend(DoltDatabase db, String program, Path file, Duration timeout) {
        this.db = db;
        this.program = program;
        this.file = file;
        this.timeout = timeout;
    }

    static DoltliteBackend open(DoltDatabase db, String program, int timeoutSeconds) {
        Path file = db.file();
        if (!Files.isRegularFile(file)) {
            // doltlite legt eine fehlende Datei stillschweigend neu an
            throw new IllegalStateException("Datenbank '" + db.name() + "': Doltlite-Datei " + file + " nicht gefunden.");
        }
        return new DoltliteBackend(db, program == null || program.isBlank() ? "doltlite" : program.strip(), file,
                Duration.ofSeconds(Math.max(5, timeoutSeconds)));
    }

    @Override
    public String version() {
        String v = value("SELECT dolt_version()");
        return "Doltlite" + (v == null || v.isBlank() ? "" : " " + v);
    }

    @Override
    public String defaultBranch() {
        try {
            return value("SELECT dolt_default_branch()");
        } catch (IllegalStateException e) {
            return null;
        }
    }

    @Override
    public List<String> branches() {
        List<String> out = new ArrayList<>();
        for (JsonNode row : query("SELECT name FROM dolt_branches ORDER BY name")) {
            out.add(row.path("name").asString(""));
        }
        return out;
    }

    @Override
    public void create(String branch, String from) {
        query("SELECT dolt_branch(" + DoltServerBackend.literal(branch) + ", " + DoltServerBackend.literal(from) + ")");
    }

    @Override
    public boolean setDefault(String branch) {
        query("SELECT dolt_default_branch(" + DoltServerBackend.literal(branch) + ")");
        return true;
    }

    @Override
    public String connectHint(String branch) {
        return file + "@" + branch;
    }

    @Override
    public void close() {
        // je Abfrage ein Prozess – nichts offen
    }

    // ------------------------------------------------------------------ intern

    /** Erste Spalte der ersten Zeile. */
    private String value(String sql) {
        List<JsonNode> rows = query(sql);
        if (rows.isEmpty()) {
            return null;
        }
        var it = rows.getFirst().properties().iterator();
        return it.hasNext() ? it.next().getValue().asString(null) : null;
    }

    /** Zeilen der Abfrage als JSON-Objekte ({@code -json}); {@code -bail} bricht beim ersten Fehler ab. */
    private List<JsonNode> query(String sql) {
        CommandRunner.Result r;
        try {
            r = CommandRunner.run(List.of(program, "-bail", "-json", file.toString()), timeout, StandardCharsets.UTF_8,
                    null, Map.of(), sql + ";\n");
        } catch (CommandRunner.NotStartable e) {
            throw new IllegalStateException("Datenbank '" + db.name() + "': Programm '" + program + "' nicht startbar – "
                    + "doltlite installieren (github.com/dolthub/doltlite/releases) und im Modul den Pfad eintragen "
                    + "oder in den PATH legen.", e);
        }
        String out = r.output().strip();
        if (r.timedOut()) {
            throw new IllegalStateException("Datenbank '" + db.name() + "': doltlite antwortet nicht (Zeitlimit "
                    + timeout.toSeconds() + " s) – hält eine Anwendung die Datei mit einer Schreibtransaktion?");
        }
        if (r.exitCode() != 0 || out.matches("(?s)^(?:Parse |Runtime )?[Ee]rror.*")) {
            // z.B. „Error near line 1: branch 'x' not found“
            throw new IllegalStateException("Datenbank '" + db.name() + "' (Doltlite, " + file + "): "
                    + Text.firstLine(out.replaceFirst("^(?:Parse |Runtime )?[Ee]rror[^:]*:\\s*", "")));
        }
        if (out.isEmpty()) {
            return List.of();
        }
        try {
            JsonNode root = JSON.readTree(out);
            List<JsonNode> rows = new ArrayList<>();
            root.forEach(rows::add);
            return rows;
        } catch (RuntimeException e) {
            throw new IllegalStateException("Datenbank '" + db.name() + "': unerwartete Ausgabe von doltlite: "
                    + Text.firstLine(out), e);
        }
    }
}
