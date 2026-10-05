package systems.grebe.devtools.mcp.modules.scripts;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.core.ToolHints;

/** Lesende Skript-Tools (immer registriert, solange das Modul aktiv ist). */
@ToolHints(readOnly = true, openWorld = false)
public class ScriptReadTools {

    static final String NAME = "Skriptname = Modul-ID und Tool-Präfix, z.B. 'jira'";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private final ScriptManager scripts;

    ScriptReadTools(ScriptManager scripts) {
        this.scripts = scripts;
    }

    @Tool(name = "list", description = "Listet die Groovy-Skripte, die diesen Server um eigene Tools erweitern: Name, "
            + "Herkunft (eigen/global), Revision, Zustand (aktiv, deaktiviert, Fehler) und ihre Tools."
            + ShellHints.SCRIPTS)
    public String list() {
        List<ScriptManager.Status> all = scripts.statuses();
        if (all.isEmpty()) {
            return "Noch keine Skripte. Die DSL zeigt scripts_view ohne Namen; angelegt werden Skripte im Tab "
                    + "„Skripte“ der DevTools-App oder – wenn freigegeben – mit scripts_save.";
        }
        StringBuilder sb = new StringBuilder(all.size() + " Skript(e):\n");
        for (ScriptManager.Status s : all) {
            sb.append("- ").append(s.name()).append(": ").append(s.summary().description())
                    .append("  [").append(s.summary().global() ? "global" : "eigen")
                    .append(", Revision ").append(s.summary().revision()).append("]\n    ");
            if (s.error() != null) {
                sb.append("Fehler: ").append(s.error());
            } else if (!s.enabled()) {
                sb.append("deaktiviert – Tools: ").append(String.join(", ", s.tools()));
            } else {
                sb.append("aktiv – Tools: ").append(s.activeTools().isEmpty() ? "keine"
                        : String.join(", ", s.activeTools()));
            }
            sb.append('\n');
        }
        return sb.append("Quelltext mit scripts_view(name).").toString();
    }

    @Tool(name = "view", description = "Zeigt den Groovy-Quelltext eines Skripts (optional einen früheren Stand aus "
            + "der Historie) – ohne Namen die DSL-Referenz zum Schreiben eigener Skripte." + ShellHints.SCRIPTS)
    public String view(
            @ToolParam(required = false, description = NAME + "; leer = DSL-Referenz") String name,
            @ToolParam(required = false, description = "Optional: frühere Revision statt des aktuellen Stands") Integer revision) {
        if (name == null || name.isBlank()) {
            return ScriptsModule.REFERENCE;
        }
        ScriptViews.Details d = scripts.details(name.strip()).orElseThrow(() -> new IllegalArgumentException(
                "Skript '" + name.strip() + "' gibt es nicht. Vorhandene mit scripts_list anzeigen."));
        ScriptViews.Summary s = d.summary();
        if (revision != null) {
            ScriptViews.Revision r = d.revisions().stream().filter(x -> x.revision() == revision).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Skript '" + s.name() + "' hat keine Revision "
                            + revision + " (aktuell " + s.revision() + ")."));
            return "# " + s.name() + " – Revision " + r.revision() + " (" + r.action() + ", "
                    + DATE.format(r.changedAt()) + ")\n\n```groovy\n" + r.content() + "\n```";
        }
        StringBuilder sb = new StringBuilder("---\nname: ").append(s.name())
                .append("\ndescription: ").append(s.description())
                .append("\nscope: ").append(s.global() ? "global (Vorlage – Speichern legt ein eigenes Skript an, das "
                        + "sie verdeckt)" : "eigen")
                .append("\nrevision: ").append(s.revision())
                .append("\nupdated: ").append(DATE.format(s.updatedAt()))
                .append(s.updatedBy() == null ? "" : " (" + s.updatedBy() + ")");
        scripts.status(s.name()).ifPresent(st -> sb.append("\nstatus: ").append(st.error() != null
                ? "Fehler – " + st.error() : (st.enabled() ? "aktiv" : "deaktiviert") + ", Tools: "
                + String.join(", ", st.tools())));
        sb.append("\nhistory: ").append(d.revisions().stream().map(r -> r.revision() + " " + r.action()).toList())
                .append("\n---\n\n```groovy\n").append(d.content()).append("\n```");
        return sb.toString();
    }
}
