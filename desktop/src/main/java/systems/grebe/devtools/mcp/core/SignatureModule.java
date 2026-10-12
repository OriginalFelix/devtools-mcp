package systems.grebe.devtools.mcp.core;

import java.util.List;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

/**
 * Kennzeichnung von Texten, die Tools im Namen des Nutzers veröffentlichen – Kommentare und Antworten an Tickets und
 * Pull Requests, ihre Beschreibungen. Stellt selbst keine Tools bereit; die {@link ToolRegistry} reicht die Vorlage an
 * die Module mit {@link ToolModule#signsTexts()} weiter.
 */
@Component
public class SignatureModule implements ToolModule {

    public static final String ID = "signature";
    public static final String ACTIVE = "active";
    public static final String TEMPLATE = "template";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Kennzeichnung";
    }

    @Override
    public String description() {
        return "Text, der unter jeden Kommentar, jede Antwort und jede Beschreibung gesetzt wird, die ein KI-Client über "
                + "die Tools veröffentlicht (Tickets, Pull Requests).";
    }

    @Override
    public boolean hasTools() {
        return false;
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
                ConfigField.of(ACTIVE, "Kennzeichnung anhängen", FieldType.BOOLEAN).withDefault("true")
                        .withHelp("Aus = Texte gehen unverändert hinaus (eigene Kennzeichnungen in den Modulen gelten weiter)."),
                ConfigField.of(TEMPLATE, "Text", FieldType.STRING).withDefault(Signature.DEFAULT_TEMPLATE)
                        .withHelp(Signature.MODEL + " wird durch den Namen des KI-Modells ersetzt (vom Client gemeldet, "
                                + "sonst der Name des Clients). Markdown möglich, z.B. „_Erstellt mit " + Signature.MODEL
                                + "_“. Gilt für Tickets und Pull Requests, sofern dort keine eigene Kennzeichnung "
                                + "eingetragen ist."));
    }

    @Override
    public List<ToolCallback> createTools(ModuleConfig config) {
        return List.of();
    }

    @Override
    public ConnectionTestResult testConnection(ModuleConfig config) {
        String template = template(config);
        return ConnectionTestResult.ok(template.isEmpty() ? "Keine Kennzeichnung."
                : "Beispiel:\n\n" + Signature.sign("Kommentartext", template, "Claude Sonnet 4.5"));
    }

    /** Wirksame Vorlage aus den Einstellungen dieses Moduls; leer, wenn abgeschaltet. */
    public static String template(ModuleConfig config) {
        return config.get(ACTIVE).map(Boolean::parseBoolean).orElse(true)
                ? config.getString(TEMPLATE, Signature.DEFAULT_TEMPLATE) : "";
    }

    /**
     * Modellname für {@link Signature#MODEL}: was das LLM angibt, sonst der Name des MCP-Clients aus dem Tool-Kontext
     * (z.B. „Claude Code“), sonst {@code null}.
     */
    public static String modelName(String aiModel, ToolContext toolContext) {
        if (aiModel != null && !aiModel.isBlank()) {
            return aiModel.strip();
        }
        Object exchange = toolContext == null ? null
                : toolContext.getContext().get(McpToolUtils.TOOL_CONTEXT_MCP_EXCHANGE_KEY);
        return exchange instanceof McpSyncServerExchange e && e.getClientInfo() != null
                ? Signature.clientName(e.getClientInfo().name()) : null;
    }
}
