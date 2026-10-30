package systems.grebe.devtools.mcp.modules.ide;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.core.ToolHints;
import systems.grebe.devtools.mcp.modules.ide.spi.Ide;
import systems.grebe.devtools.mcp.modules.ide.spi.Ide.Capability;
import systems.grebe.devtools.mcp.modules.ide.spi.IdeProvider;
import tools.jackson.databind.JsonNode;

/**
 * Tools des Moduls IDE. Sie wirken auf dem Bildschirm des Nutzers (Fenster öffnen, Cursor setzen) und verändern keine
 * Dateien. {@code ide_editor} und {@code ide_cursor} sprechen das DevTools-Plugin in der laufenden IDE an
 * ({@link IdeLink}), die übrigen die Befehlszeilen-Starter der Provider.
 */
@ToolHints(readOnly = false, destructive = false, idempotent = true, openWorld = false)
public class IdeTools {

    static final String IDE = "IDE (ID aus ide_providers, z.B. intellij, vscode, eclipse). Leer = Standard-IDE bzw. "
            + "die aktive IDE, deren Spuren im Projekt liegen (.idea, .vscode, *.sln …).";
    static final String PROJECT = "Projekt: Name (wie in projects_list bzw. git_list_repositories) oder absoluter Pfad "
            + "eines freigegebenen Projekts bzw. eines Verzeichnisses darin. Leer = das einzige Projekt.";

    static final String NO_PLUGIN = "Keine IDE mit DevTools-Plugin läuft. Den Nutzer bitten, das Plugin "
            + "„DevTools MCP“ zu installieren (ide-plugins/intellij, für IntelliJ IDEA und die anderen JetBrains-IDEs) "
            + "und die IDE zu starten.";

    private final IdeEnvironment env;
    private final IdeLink link;

    IdeTools(IdeEnvironment env, IdeLink link) {
        this.env = env;
        this.link = link;
    }

    @Tool(name = "providers", description = "Listet die aktiven IDEs mit gefundener Installation und dem, was jede kann "
            + "(Projekt öffnen/schließen, Code-Stelle zeigen, Dateien vergleichen), sowie die weiteren, in der App "
            + "aktivierbaren IDEs." + ShellHints.IDE)
    @ToolHints(readOnly = true, openWorld = false)
    public String providers() {
        StringBuilder sb = new StringBuilder();
        if (env.entries().isEmpty()) {
            sb.append("Keine IDE aktiviert – in der DevTools-App unter Module → IDE einschalten.\n");
        }
        for (IdeEnvironment.Entry e : env.entries()) {
            Ide.Availability a = e.ide().probe();
            sb.append(e.id()).append(" (").append(e.name()).append("): ")
                    .append(a.available() ? "gefunden – " + a.executable() : "nicht verfügbar – " + a.message())
                    .append("\n  kann: ").append(capabilities(e.ide())).append('\n');
        }
        if (!env.entries().isEmpty()) {
            sb.append("Standard: ").append(env.defaultIde()).append('\n');
        }
        List<String> inactive = env.providers().stream()
                .filter(p -> env.entries().stream().noneMatch(e -> e.id().equals(p.id())))
                .map(IdeProvider::id).toList();
        if (!inactive.isEmpty()) {
            sb.append("In der App aktivierbar: ").append(String.join(", ", inactive));
        }
        return sb.toString().strip();
    }

    @Tool(name = "open_project", description = "Öffnet ein Projekt in der IDE des Nutzers und holt sie nach vorn (ist es "
            + "schon offen, wird sein Fenster aktiv). Nur wenn der Nutzer es möchte – das öffnet ein Fenster auf seinem "
            + "Bildschirm." + ShellHints.IDE)
    public String openProject(
            @ToolParam(required = false, description = PROJECT) String project,
            @ToolParam(required = false, description = IDE) String ide) {
        Path dir = env.project(project);
        IdeEnvironment.Entry e = env.select(ide, dir);
        require(e, Capability.OPEN_PROJECT);
        return e.name() + ": " + e.ide().openProject(dir);
    }

    @Tool(name = "close_project", description = "Schließt ein in der IDE geöffnetes Projekt. Ungespeicherte Änderungen "
            + "gehen nicht verloren: die IDE lehnt ab oder fragt den Nutzer. Nicht jede IDE kann das von außen (siehe "
            + "ide_providers) – dann den Nutzer bitten, es selbst zu schließen." + ShellHints.IDE)
    public String closeProject(
            @ToolParam(required = false, description = PROJECT) String project,
            @ToolParam(required = false, description = IDE) String ide) {
        Path dir = env.project(project);
        IdeEnvironment.Entry e = env.select(ide, dir);
        require(e, Capability.CLOSE_PROJECT);
        return e.name() + ": " + e.ide().closeProject(dir);
    }

    @Tool(name = "show", description = "Zeigt dem Nutzer eine Code-Stelle in seiner IDE: öffnet die Datei, setzt den "
            + "Cursor auf Zeile/Spalte und holt die IDE nach vorn – z.B. die Ursache eines Fehlers, eine geänderte "
            + "Stelle oder worüber gerade gesprochen wird. Zum eigenen Lesen von Code nicht nötig." + ShellHints.IDE)
    public String show(
            @ToolParam(description = "Datei: absoluter Pfad oder relativ zum Projekt") String file,
            @ToolParam(required = false, description = "Zeile (ab 1)") Integer line,
            @ToolParam(required = false, description = "Spalte (ab 1); nicht jede IDE setzt sie") Integer column,
            @ToolParam(required = false, description = PROJECT + " Für relative Pfade; sonst aus der Datei bestimmt.")
            String project,
            @ToolParam(required = false, description = IDE) String ide) {
        Ide.Location l = env.location(file, line, column, project);
        IdeEnvironment.Entry e = env.select(ide, l.project());
        require(e, Capability.SHOW);
        return e.name() + ": " + e.ide().show(l);
    }

    @Tool(name = "diff", description = "Zeigt dem Nutzer zwei Dateien nebeneinander im Vergleich der IDE (z.B. Original "
            + "und Vorschlag). Beide müssen in freigegebenen Projekten liegen." + ShellHints.IDE)
    public String diff(
            @ToolParam(description = "Linke Datei (absolut oder relativ zum Projekt)") String left,
            @ToolParam(description = "Rechte Datei (absolut oder relativ zum Projekt)") String right,
            @ToolParam(required = false, description = PROJECT + " Für relative Pfade.") String project,
            @ToolParam(required = false, description = IDE) String ide) {
        Ide.Location l = env.location(left, null, null, project);
        Ide.Location r = env.location(right, null, null, project);
        IdeEnvironment.Entry e = env.select(ide, l.project());
        require(e, Capability.DIFF);
        return e.name() + ": " + e.ide().diff(l.file(), r.file());
    }

    @Tool(name = "editor", description = "Was der Nutzer gerade in seiner IDE vor sich hat: aktive Datei mit "
            + "Cursor-Position, markierter Text und weitere offene Dateien – je geöffnetem Projekt. Aufrufen, wenn er "
            + "sich auf „das hier“, „die Markierung“, „diese Datei/Methode/Zeile“ bezieht, ohne die Stelle zu nennen. "
            + "Braucht das DevTools-Plugin in der IDE (IntelliJ-Plattform)." + ShellHints.IDE)
    @ToolHints(readOnly = true, openWorld = false)
    public String editor() {
        StringBuilder sb = new StringBuilder();
        int hidden = 0;
        for (IdeLink.Instance i : linked()) {
            for (JsonNode p : link.editor(i).path("projects")) {
                Path dir = Path.of(p.path("path").asString("/"));
                if (!env.released(dir)) {
                    hidden++;
                    continue;
                }
                sb.append(i.ide()).append(" · Projekt ").append(p.path("name").asString()).append(" (").append(dir)
                        .append(')').append(p.path("active").asBoolean() ? " – zuletzt aktiv" : "").append('\n');
                String file = p.path("file").asString("");
                if (file.isEmpty()) {
                    sb.append("Keine Datei im Editor.\n");
                } else if (!env.released(Path.of(file))) {
                    sb.append("Datei außerhalb der Freigaben: ").append(Path.of(file).getFileName()).append('\n');
                } else {
                    sb.append("Datei: ").append(file).append(" – Cursor Zeile ").append(p.path("line").asInt())
                            .append(", Spalte ").append(p.path("column").asInt()).append('\n');
                    JsonNode sel = p.path("selection");
                    if (sel.isObject()) {
                        sb.append("Markiert: Zeile ").append(sel.path("startLine").asInt()).append(", Spalte ")
                                .append(sel.path("startColumn").asInt()).append(" bis Zeile ")
                                .append(sel.path("endLine").asInt()).append(", Spalte ")
                                .append(sel.path("endColumn").asInt()).append(":\n```\n")
                                .append(sel.path("text").asString().stripTrailing()).append("\n```\n");
                    }
                }
                List<String> open = p.path("openFiles").valueStream().map(JsonNode::asString)
                        .filter(f -> !f.equals(file)).map(f -> Path.of(f).startsWith(dir)
                                ? dir.relativize(Path.of(f)).toString() : Path.of(f).getFileName().toString())
                        .toList();
                if (!open.isEmpty()) {
                    sb.append("Weitere offene Dateien: ").append(String.join(", ", open)).append('\n');
                }
                sb.append('\n');
            }
        }
        if (hidden > 0) {
            sb.append(hidden).append(hidden == 1 ? " Projekt" : " Projekte").append(" außerhalb der Freigaben ausgeblendet.");
        }
        String out = sb.toString().strip();
        return out.isEmpty() ? "In der IDE ist kein Projekt geöffnet." : out;
    }

    @Tool(name = "cursor", description = "Zeigt dem Nutzer in seiner IDE, wo du gerade bist: ein eigener, beschrifteter "
            + "Cursor (wie bei Code With Me) an Zeile/Spalte, mit endLine/endColumn auch ein markierter Bereich. Die "
            + "IDE folgt ihm (öffnet die Datei, scrollt hin), solange der Nutzer „Claude folgen“ eingeschaltet hat, und "
            + "zeigt danach deine Änderungen an der Datei live mit. Setzen, bevor du eine Datei bearbeitest oder wenn "
            + "du eine Stelle erklärst; ohne file entfernen. Braucht das DevTools-Plugin in der IDE." + ShellHints.IDE)
    public String cursor(
            @ToolParam(required = false, description = "Datei: absoluter Pfad oder relativ zum Projekt. Leer = Cursor "
                    + "entfernen") String file,
            @ToolParam(required = false, description = "Zeile (ab 1), Standard 1") Integer line,
            @ToolParam(required = false, description = "Spalte (ab 1), Standard 1") Integer column,
            @ToolParam(required = false, description = "Ende eines markierten Bereichs: Zeile (ab 1)") Integer endLine,
            @ToolParam(required = false, description = "Ende des Bereichs: Spalte (ab 1), Standard Zeilenende")
            Integer endColumn,
            @ToolParam(required = false, description = "Beschriftung des Cursors, Standard „Claude“") String label,
            @ToolParam(required = false, description = PROJECT + " Für relative Pfade.") String project) {
        List<IdeLink.Instance> all = linked();
        if (IdeEnvironment.blank(file)) {
            for (IdeLink.Instance i : all) {
                try {
                    link.cursor(i, Map.of("clear", "true"));
                } catch (RuntimeException ignored) {
                    // IDE gerade nicht erreichbar – ihr Cursor verschwindet mit ihr
                }
            }
            return "Cursor entfernt.";
        }
        Ide.Location l = env.location(file, line == null ? 1 : line, column == null ? 1 : column, project);
        if (endLine != null && endLine < l.line() || endColumn != null && endColumn < 1) {
            throw new IllegalArgumentException("'endLine' liegt vor 'line' bzw. 'endColumn' zählt ab 1.");
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("file", l.file().toString());
        params.put("line", String.valueOf(l.line()));
        params.put("column", String.valueOf(Math.max(1, l.column())));
        if (endLine != null) {
            params.put("endLine", String.valueOf(endLine));
            params.put("endColumn", String.valueOf(endColumn == null ? Integer.MAX_VALUE : endColumn));
        }
        params.put("label", IdeEnvironment.blank(label) ? "Claude" : label.strip());
        IdeLink.Instance target = owner(all, l.file());
        return target.ide() + ": " + link.cursor(target, params);
    }

    private List<IdeLink.Instance> linked() {
        List<IdeLink.Instance> all = link.instances();
        if (all.isEmpty()) {
            throw new IllegalStateException(NO_PLUGIN);
        }
        return all;
    }

    /** Die IDE, die das Projekt der Datei offen hat (das speziellste), sonst die erste. */
    private IdeLink.Instance owner(List<IdeLink.Instance> all, Path file) {
        if (all.size() == 1) {
            return all.getFirst();
        }
        record Hit(IdeLink.Instance instance, int depth) {
        }
        return all.stream().<Hit>mapMulti((i, out) -> {
                    try {
                        for (JsonNode p : link.editor(i).path("projects")) {
                            Path dir = Path.of(p.path("path").asString("/"));
                            if (file.startsWith(dir)) {
                                out.accept(new Hit(i, dir.getNameCount()));
                            }
                        }
                    } catch (RuntimeException ignored) {
                        // nicht erreichbar
                    }
                }).max(Comparator.comparingInt(Hit::depth)).map(Hit::instance).orElse(all.getFirst());
    }

    private static void require(IdeEnvironment.Entry e, Capability c) {
        if (!e.ide().capabilities().contains(c)) {
            throw new IllegalStateException(e.name() + " kann „" + c.label() + "“ nicht von außen. Möglich: "
                    + capabilities(e.ide()) + (c == Capability.CLOSE_PROJECT
                    ? ". Den Nutzer bitten, das Projekt selbst zu schließen." : "."));
        }
    }

    static String capabilities(Ide ide) {
        return Arrays.stream(Capability.values()).filter(ide.capabilities()::contains).map(Capability::label)
                .collect(Collectors.joining(", "));
    }
}
