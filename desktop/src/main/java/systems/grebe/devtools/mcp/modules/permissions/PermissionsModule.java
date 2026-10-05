package systems.grebe.devtools.mcp.modules.permissions;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolBeans;
import systems.grebe.devtools.mcp.core.ToolModule;
import systems.grebe.devtools.mcp.core.ToolRegistry;
import systems.grebe.devtools.mcp.core.UserConfirmation;

/**
 * Berechtigungen für das LLM: welche Module, Schalter, Tools und Verzeichnisse freigegeben sind und was fehlt – nur
 * lesend. Fehlt etwas, fragt {@code permissions_request} den Nutzer (MCP-Elicitation oder Dialog der App); erteilen kann
 * nur er, und nur, was der Administrator nicht gesperrt hat.
 */
@Component
public class PermissionsModule implements ToolModule {

    public static final String ID = "permissions";
    static final String SHOW_VALUES = "showValues";
    static final String ALLOW_REQUEST = "allowRequest";
    static final String PROMPT_VIA = "promptVia";

    private final ObjectProvider<ToolRegistry> registry;
    private final UserConfirmation confirmation;

    public PermissionsModule(ObjectProvider<ToolRegistry> registry, UserConfirmation confirmation) {
        this.registry = registry;
        this.confirmation = confirmation;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Berechtigungen";
    }

    @Override
    public String description() {
        return "Zeigt dem LLM, was es darf: Module, Schalter, abgeschaltete Tools, Einstellungen (ohne Geheimnisse) und "
                + "freigegebene Verzeichnisse – nur lesend. Fehlt eine Berechtigung, fragt das LLM den Nutzer, der sie "
                + "im MCP-Client oder in dieser App erteilen kann.";
    }

    @Override
    public String instructions() {
        return """
                Wenn `permissions_check` angeboten wird: Fehlt ein Tool, meldet ein Tool „nicht freigegeben“ oder ist \
                unklar, ob etwas erlaubt ist – zuerst `permissions_check` (`tool` bzw. `path`) statt zu raten oder auf \
                die Shell auszuweichen. `permissions_overview` zeigt Module und Schalter, mit `module` die Details \
                eines Moduls (welcher Schalter welche Tools freischaltet, Einstellungen ohne Geheimnisse).
                - Fehlt eine Berechtigung, die für den Auftrag nötig ist: mit `permissions_request` beim Nutzer \
                anfragen und in `reason` kurz begründen. Der Nutzer bestätigt selbst (im Client oder in der \
                DevTools-App); danach ändert sich die Tool-Liste.
                - Abgelehnt oder vom Administrator gesperrt: nicht erneut fragen und nicht per Shell umgehen, sondern \
                dem Nutzer sagen, was fehlt.""";
    }

    @Override
    public boolean enabledByDefault() {
        return true;
    }

    @Override
    public int order() {
        return 2;
    }

    @Override
    public List<ConfigField> configSchema() {
        return List.of(
                ConfigField.of(SHOW_VALUES, "Einstellungswerte zeigen", FieldType.BOOLEAN).withDefault("true")
                        .withHelp("permissions_overview zeigt je Modul auch die Werte (Verzeichnisse, Server, Grenzen). "
                                + "Geheimnisse erscheinen nie, nur ob sie gesetzt sind. Aus: nur Module, Schalter und "
                                + "Tools."),
                ConfigField.of(ALLOW_REQUEST, "Berechtigungen anfragen erlauben", FieldType.BOOLEAN).withDefault("true")
                        .withHelp("permissions_request: das LLM bittet um ein Modul, einen Schalter, ein abgeschaltetes "
                                + "Tool oder ein Verzeichnis. Geändert wird nur, wenn du zustimmst; vom Administrator "
                                + "gesperrte Einstellungen bleiben unverändert."),
                ConfigField.of(PROMPT_VIA, "Rückfrage über", FieldType.ENUM).withOptions("auto", "client", "app")
                        .withDefault("auto")
                        .withHelp("auto = im MCP-Client (Elicitation, z.B. Claude Code), kann er das nicht, als Dialog "
                                + "dieser App. client = nur im Client. app = immer als Dialog dieser App."));
    }

    @Override
    public List<ToolCallback> createTools(ModuleConfig config) {
        ToolRegistry tools = registry.getObject();
        Permissions permissions = new Permissions(tools, config.getBoolean(SHOW_VALUES));
        List<Object> beans = new ArrayList<>(List.of(new PermissionTools(permissions)));
        if (config.getBoolean(ALLOW_REQUEST)) {
            beans.add(new PermissionRequestTools(permissions, confirmation, channel(config), tools));
        }
        return ToolBeans.callbacks(beans.toArray());
    }

    static UserConfirmation.Channel channel(ModuleConfig config) {
        try {
            return UserConfirmation.Channel.valueOf(config.getString(PROMPT_VIA, "auto").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return UserConfirmation.Channel.AUTO;
        }
    }
}
