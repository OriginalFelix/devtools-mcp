package systems.grebe.devtools.mcp.modules.permissions;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.core.ToolHints;
import systems.grebe.devtools.mcp.core.ToolRegistry;
import systems.grebe.devtools.mcp.core.UserConfirmation;

/**
 * Berechtigung beim Nutzer anfragen. Das LLM kann nichts selbst erteilen: geändert wird erst nach Zustimmung des
 * Nutzers ({@link UserConfirmation}), und nur, was der Administrator nicht gesperrt hat.
 */
@ToolHints(destructive = false, openWorld = false)
class PermissionRequestTools {

    private static final Logger LOG = LoggerFactory.getLogger(PermissionRequestTools.class);
    static final String TITLE = "DevTools MCP – Berechtigung anfragen";

    private final Permissions permissions;
    private final UserConfirmation confirmation;
    private final UserConfirmation.Channel channel;
    private final ToolRegistry registry;

    PermissionRequestTools(Permissions permissions, UserConfirmation confirmation, UserConfirmation.Channel channel,
                           ToolRegistry registry) {
        this.permissions = permissions;
        this.confirmation = confirmation;
        this.channel = channel;
        this.registry = registry;
    }

    @Tool(name = "request", description = "Bittet den Nutzer um eine fehlende Berechtigung: ein Tool verfügbar machen "
            + "(tool – schaltet je nach Bedarf Modul, Schalter oder das in der App abgeschaltete Tool ein), einen "
            + "Schalter einschalten (module + setting, z.B. git + allowSync), ein Modul einschalten (module) oder ein "
            + "Verzeichnis für alle Tools freigeben (path). Der Nutzer bestätigt oder lehnt ab – im MCP-Client oder in "
            + "der DevTools-App; das kann dauern. Nur anfragen, was für den Auftrag nötig ist; nach Ablehnung nicht "
            + "erneut fragen. Vom Administrator gesperrte Einstellungen lassen sich nicht anfragen." + ShellHints.PERMISSIONS)
    public String request(
            @ToolParam(required = false, description = "Vollständiger Tool-Name, z.B. git_push") String tool,
            @ToolParam(required = false, description = "Modul-ID, z.B. git (mit setting: Schalter dieses Moduls)")
            String module,
            @ToolParam(required = false, description = "Schlüssel des Schalters, z.B. allowSync (siehe "
                    + "permissions_overview module=<id>)") String setting,
            @ToolParam(required = false, description = "Absoluter Pfad des Verzeichnisses, das freigegeben werden soll")
            String path,
            @ToolParam(description = "Kurze Begründung für den Nutzer: wofür die Berechtigung gebraucht wird")
            String reason,
            ToolContext toolContext) {
        List<Permissions.Change> changes = changes(tool, module, setting, path);
        if (changes.isEmpty()) {
            return "Bereits erlaubt – nichts anzufragen.";
        }
        List<Permissions.Change> locked = permissions.locked(changes);
        if (!locked.isEmpty()) {
            StringBuilder sb = new StringBuilder("Nicht möglich: vom Administrator gesperrt (Vorgabe des Team-Servers), "
                    + "der Nutzer kann das nicht ändern:\n");
            permissions.appendChanges(sb, locked);
            return sb.append("Dem Nutzer sagen, dass der Administrator die Einstellung freigeben muss.").toString();
        }
        StringBuilder question = new StringBuilder("Das LLM bittet um folgende Berechtigung:\n");
        permissions.appendChanges(question, changes);
        if (reason != null && !reason.isBlank()) {
            question.append("\nBegründung: ").append(reason.strip()).append('\n');
        }
        question.append("\nGespeichert in: ").append(registry.settingsTarget());

        UserConfirmation.Result r = confirmation.ask(UserConfirmation.exchange(toolContext), channel, TITLE,
                question.toString());
        switch (r.answer()) {
            case UNAVAILABLE -> {
                StringBuilder sb = new StringBuilder("Keine Rückfrage möglich (").append(r.via()).append("). Den Nutzer "
                        + "bitten, es selbst in der DevTools-App einzustellen:\n");
                permissions.appendChanges(sb, changes);
                return sb.toString().strip();
            }
            case DECLINED -> {
                LOG.info("Berechtigung abgelehnt ({}): {}", r.via(), changes.stream().map(Permissions.Change::text).toList());
                return "Vom Nutzer abgelehnt (" + r.via() + "). Nicht erneut fragen und nicht per Shell umgehen – ohne "
                        + "diese Berechtigung weiterarbeiten oder den Nutzer fragen, wie es weitergehen soll.";
            }
            default -> {
                Set<String> before = new LinkedHashSet<>(registry.activeToolNames());
                changes.forEach(permissions::apply);
                LOG.info("Berechtigung erteilt ({}): {}", r.via(), changes.stream().map(Permissions.Change::text).toList());
                Set<String> added = new LinkedHashSet<>(registry.activeToolNames());
                added.removeAll(before);
                StringBuilder sb = new StringBuilder("Vom Nutzer erteilt (").append(r.via()).append("):\n");
                changes.forEach(c -> sb.append("- ").append(c.text()).append('\n'));
                if (!added.isEmpty()) {
                    sb.append("Neu verfügbar: ").append(String.join(", ", added)).append('\n');
                }
                return sb.append("Die Tool-Liste wurde aktualisiert (tools/list_changed).").toString();
            }
        }
    }

    private List<Permissions.Change> changes(String tool, String module, String setting, String path) {
        if (present(path)) {
            return permissions.forPath(path);
        }
        if (present(tool)) {
            Permissions.ToolStatus st = permissions.tool(tool);
            if (!st.active() && st.changes().isEmpty()) {
                throw new IllegalArgumentException(permissions.describe(st));
            }
            return st.changes();
        }
        if (present(module)) {
            return present(setting) ? permissions.forSwitch(module, setting) : permissions.forModule(module);
        }
        throw new IllegalArgumentException("tool, module (optional mit setting) oder path angeben.");
    }

    private static boolean present(String s) {
        return s != null && !s.isBlank();
    }
}
