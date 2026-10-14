package systems.grebe.devtools.mcp.modules.ide;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.core.ToolHints;
import systems.grebe.devtools.mcp.modules.ide.spi.Ide;
import systems.grebe.devtools.mcp.modules.ide.spi.Ide.Capability;
import systems.grebe.devtools.mcp.modules.ide.spi.IdeProvider;

/**
 * Tools des Moduls IDE. Sie wirken auf dem Bildschirm des Nutzers (Fenster öffnen, Cursor setzen) und verändern keine
 * Dateien.
 */
@ToolHints(readOnly = false, destructive = false, idempotent = true, openWorld = false)
public class IdeTools {

    static final String IDE = "IDE (ID aus ide_providers, z.B. intellij, vscode, eclipse). Leer = Standard-IDE bzw. "
            + "die aktive IDE, deren Spuren im Projekt liegen (.idea, .vscode, *.sln …).";
    static final String PROJECT = "Projekt: Name (wie in projects_list bzw. git_list_repositories) oder absoluter Pfad "
            + "eines freigegebenen Projekts bzw. eines Verzeichnisses darin. Leer = das einzige Projekt.";

    private final IdeEnvironment env;

    IdeTools(IdeEnvironment env) {
        this.env = env;
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
