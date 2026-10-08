package systems.grebe.devtools.mcp.modules.context;

import java.util.Map;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.ObjectProvider;
import systems.grebe.devtools.mcp.core.ManagedToolCallback;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.core.ToolHints;
import systems.grebe.devtools.mcp.core.ToolRegistry;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code context_call}: ruft ein aktives Tool auf, das nicht direkt angeboten wird (Einstellung „Nur Such- und
 * Aufruf-Tools anbieten“). Läuft wie ein direkter Aufruf – mit Protokoll, Hinweisen und Kürzen.
 */
public class ContextCallTool {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ObjectProvider<ToolRegistry> registry;

    ContextCallTool(ObjectProvider<ToolRegistry> registry) {
        this.registry = registry;
    }

    @Tool(name = "call", description = "Ruft ein aktives DevTools-Tool auf, das nicht direkt angeboten wird – Name "
            + "und Parameter liefert context_find. Verhält sich wie der direkte Aufruf (gleiche Rechte, gleiches "
            + "Ergebnis)." + ShellHints.CONTEXT)
    @ToolHints(destructive = true, openWorld = true)
    public String call(
            @ToolParam(description = "voller Tool-Name, z.B. container_logs") String name,
            @ToolParam(required = false, description = "Parameter als Objekt, z.B. {\"container\": \"db\"}")
            Map<String, Object> arguments,
            ToolContext toolContext) {
        String n = name == null ? "" : name.strip();
        if (n.startsWith("context_")) {
            throw new IllegalArgumentException(n + " direkt aufrufen, nicht über context_call.");
        }
        ManagedToolCallback tool = registry.getObject().enabledTool(n).orElseThrow(() -> new IllegalArgumentException(
                "Tool '" + n + "' ist nicht aktiv – context_find(query=…) sucht passende Tools."));
        String input = JSON.writeValueAsString(arguments == null ? Map.of() : arguments);
        return tool.call(input, toolContext);
    }
}
