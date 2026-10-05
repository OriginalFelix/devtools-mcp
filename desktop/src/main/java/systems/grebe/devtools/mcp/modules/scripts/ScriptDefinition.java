package systems.grebe.devtools.mcp.modules.scripts;

import java.util.List;

import groovy.lang.Closure;
import io.modelcontextprotocol.spec.McpSchema;
import systems.grebe.devtools.mcp.core.ConfigField;

/**
 * Was ein Groovy-Skript beim Auswerten über die DSL ({@link DevToolsScript}) festgelegt hat: Modul-Angaben,
 * Einstellungsfelder und Tools. Die {@link Tool#body Tool-Closures} laufen erst beim Aufruf.
 */
public record ScriptDefinition(String displayName, String description, String instructions, boolean enabledByDefault,
                               List<ConfigField> settings, List<Tool> tools) {

    public ScriptDefinition {
        settings = List.copyOf(settings);
        tools = List.copyOf(tools);
    }

    /**
     * Parameter eines Tools.
     *
     * @param jsonType JSON-Schema-Typ: string, integer, number, boolean, array oder object
     * @param options  erlaubte Werte (JSON-Schema {@code enum}); leer = beliebig
     */
    public record Param(String name, String jsonType, String description, boolean required, List<String> options) {

        public Param {
            options = List.copyOf(options);
        }
    }

    /**
     * Ein Tool des Skripts.
     *
     * @param annotations MCP-Hinweise (readOnly …) oder {@code null}, wenn das Skript keine angegeben hat
     * @param body        Closure {@code { args -> … }} bzw. {@code { args, cfg -> … }}
     */
    public record Tool(String name, String description, List<Param> params, McpSchema.ToolAnnotations annotations,
                       Closure<?> body) {

        public Tool {
            params = List.copyOf(params);
        }
    }
}
