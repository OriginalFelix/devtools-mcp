package systems.grebe.devtools.mcp.modules.permissions;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.core.ToolHints;

/** Lesende Tools: was ist erlaubt, was fehlt. */
@ToolHints(readOnly = true, openWorld = false)
class PermissionTools {

    private final Permissions permissions;

    PermissionTools(Permissions permissions) {
        this.permissions = permissions;
    }

    @Tool(name = "overview", description = "Zeigt, was das LLM in DevTools darf. Ohne module: alle Module mit an/aus, "
            + "Anzahl aktiver Tools, in der App abgeschalteten Tools und Schaltern. Mit module: Details eines Moduls – "
            + "aktive Tools, je Schalter die Tools, die er freischaltet, Einstellungen (Geheimnisse nur als "
            + "gesetzt/leer) und was der Administrator gesperrt hat. Ändert nichts." + ShellHints.PERMISSIONS)
    public String overview(
            @ToolParam(required = false, description = "Modul-ID, z.B. git, container, ssh (Präfix der Tool-Namen)")
            String module) {
        return module == null || module.isBlank() ? permissions.overview() : permissions.module(module);
    }

    @Tool(name = "check", description = "Prüft, ob ein Tool angeboten wird bzw. ein Verzeichnis freigegeben ist – "
            + "und falls nicht, was dafür fehlt (Modul aus, Tool in der App abgeschaltet, Schalter aus, Verzeichnis "
            + "nicht freigegeben, vom Administrator gesperrt). Vor dem Ausweichen auf die Shell aufrufen. Ändert nichts; "
            + "fehlende Berechtigungen mit permissions_request beim Nutzer anfragen." + ShellHints.PERMISSIONS)
    public String check(
            @ToolParam(required = false, description = "Vollständiger Tool-Name, z.B. git_push") String tool,
            @ToolParam(required = false, description = "Absoluter Pfad eines Verzeichnisses oder einer Datei") String path) {
        boolean hasTool = tool != null && !tool.isBlank();
        boolean hasPath = path != null && !path.isBlank();
        if (!hasTool && !hasPath) {
            throw new IllegalArgumentException("tool oder path angeben.");
        }
        StringBuilder sb = new StringBuilder();
        if (hasTool) {
            sb.append(permissions.describe(permissions.tool(tool))).append("\n\n");
        }
        if (hasPath) {
            sb.append(permissions.path(path));
        }
        return sb.toString().strip();
    }
}
