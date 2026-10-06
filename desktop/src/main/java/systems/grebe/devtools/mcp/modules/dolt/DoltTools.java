package systems.grebe.devtools.mcp.modules.dolt;

import java.io.IOException;
import java.util.List;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.core.ToolHints;

/** Stand und sofortiger Abgleich der Datenbank-Branches. */
public class DoltTools {

    static final String DATABASE = "Name der Datenbank (siehe dolt_status); leer = alle eingetragenen";
    private static final int MAX_BRANCHES = 30;

    /** Zustand einer Datenbank als Text; {@code ok}, wenn Git und Datenbank erreichbar sind. */
    record Report(boolean ok, String text) {
    }

    private final DoltBranches branches;
    private final List<DoltDatabase> databases;
    private final DoltBranches.Settings settings;

    DoltTools(DoltBranches branches, List<DoltDatabase> databases, DoltBranches.Settings settings) {
        this.branches = branches;
        this.databases = databases;
        this.settings = settings;
    }

    @Tool(name = "status", description = "Zeigt für die Datenbanken, die dem Git-Branch eines Projekts folgen (Dolt, "
            + "Doltgres, Doltlite): Git-Arbeitsverzeichnis und aktuellen Git-Branch, Standard-Branch der Datenbank (auf "
            + "ihm landen neue Verbindungen), ob beide übereinstimmen, die Datenbank-Branches und den letzten Abgleich."
            + ShellHints.DOLT)
    @ToolHints(readOnly = true)
    public String status(@ToolParam(required = false, description = DATABASE) String database) {
        StringBuilder sb = new StringBuilder();
        for (DoltDatabase db : select(database)) {
            sb.append(report(db).text()).append("\n\n");
        }
        return sb.toString().strip();
    }

    @Tool(name = "sync", description = "Gleicht Datenbank-Branches sofort mit dem aktuellen Git-Branch ab: fehlt der "
            + "gleichnamige Datenbank-Branch, wird er vom aktuellen (oder vom eingetragenen Startpunkt) angelegt; danach "
            + "landen neue Verbindungen auf ihm. Passiert beim Branch-Wechsel automatisch – nötig nur, wenn der Server "
            + "dabei nicht lief oder jemand den Standard-Branch verstellt hat." + ShellHints.DOLT)
    @ToolHints(destructive = false, idempotent = true)
    public String sync(@ToolParam(required = false, description = DATABASE) String database) {
        StringBuilder sb = new StringBuilder();
        for (DoltDatabase db : select(database)) {
            sb.append(branches.sync(db, settings).describe()).append('\n');
        }
        return sb.toString().strip();
    }

    /** Zustand einer Datenbank: Git-Seite, Datenbank-Seite (live) und letzter Abgleich. */
    Report report(DoltDatabase db) {
        boolean ok = true;
        StringBuilder sb = new StringBuilder(db.name()).append(" (").append(db.kind().label).append(", ")
                .append(db.safeLocation()).append(")\n  Git: ").append(db.repository());
        String git = null;
        try {
            git = GitHead.branch(GitHead.gitDir(db.repositoryPath()));
            sb.append(git == null ? " – HEAD losgelöst (Rebase, Bisect, Tag …)" : " – Branch " + git);
        } catch (IOException | IllegalStateException e) {
            ok = false;
            sb.append(" – nicht lesbar: ").append(e.getMessage());
        }
        sb.append("\n  Datenbank: ");
        try (DoltBackend b = branches.open(db, settings)) {
            String current = b.defaultBranch();
            List<String> all = b.branches();
            sb.append(b.version()).append(", Standard-Branch ").append(current == null ? "nicht auflösbar" : current);
            if (git != null) {
                sb.append(git.equals(current) ? " (= Git-Branch)" : all.contains(git)
                        ? " (≠ Git-Branch " + git + " – dolt_sync stellt um)"
                        : " (≠ Git-Branch " + git + ", den es in der Datenbank noch nicht gibt – dolt_sync legt ihn an)");
            }
            sb.append("\n  Branches (").append(all.size()).append("): ")
                    .append(String.join(", ", all.subList(0, Math.min(all.size(), MAX_BRANCHES))))
                    .append(all.size() > MAX_BRANCHES ? ", …" : "");
        } catch (RuntimeException e) {
            ok = false;
            sb.append("nicht erreichbar – ").append(e.getMessage());
        }
        branches.last(db).ifPresent(o -> sb.append("\n  Letzter Abgleich ").append(DoltModule.TIME.format(o.at()))
                .append(": ").append(o.message()));
        return new Report(ok, sb.toString());
    }

    private List<DoltDatabase> select(String name) {
        if (databases.isEmpty()) {
            throw new IllegalStateException("Keine Datenbank eingetragen – der Nutzer verknüpft sie in der DevTools-App "
                    + "unter Module → Datenbank-Branches (Dolt) mit einem Git-Arbeitsverzeichnis.");
        }
        if (name == null || name.isBlank()) {
            return databases;
        }
        return databases.stream().filter(d -> d.name().equalsIgnoreCase(name.strip())).findFirst().map(List::of)
                .orElseThrow(() -> new IllegalArgumentException("Unbekannte Datenbank '" + name + "'. Eingetragen: "
                        + databases.stream().map(DoltDatabase::name).toList() + "."));
    }
}
