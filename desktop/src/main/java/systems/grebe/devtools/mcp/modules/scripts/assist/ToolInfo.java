package systems.grebe.devtools.mcp.modules.scripts.assist;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Ein aufrufbares Tool für die Vervollständigung in Gherkin-Skripten: Name, Beschreibung und Parameter aus dem
 * Eingabeschema.
 */
public record ToolInfo(String name, String description, List<Param> params) {

    /** Parameter mit JSON-Typ ({@code string}, {@code integer} …). */
    public record Param(String name, String type, String description, boolean required) {
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public static ToolInfo of(ToolDefinition definition) {
        List<Param> params = new ArrayList<>();
        String schema = definition.inputSchema();
        if (schema != null && !schema.isBlank()) {
            try {
                JsonNode root = JSON.readTree(schema);
                Set<String> required = new HashSet<>();
                JsonNode req = root.get("required");
                if (req != null && req.isArray()) {
                    for (JsonNode r : req.values()) {
                        required.add(r.asString());
                    }
                }
                JsonNode properties = root.get("properties");
                if (properties != null) {
                    for (String p : properties.propertyNames()) {
                        JsonNode node = properties.get(p);
                        JsonNode description = node.get("description");
                        params.add(new Param(p, type(node), description == null ? "" : description.asString(),
                                required.contains(p)));
                    }
                }
            } catch (RuntimeException e) {
                // Schema nicht lesbar: Tool ohne Parameter anbieten
            }
        }
        return new ToolInfo(definition.name(), definition.description() == null ? "" : definition.description(),
                List.copyOf(params));
    }

    private static String type(JsonNode property) {
        JsonNode type = property.get("type");
        if (type == null) {
            return "string";
        }
        if (type.isArray()) {
            for (JsonNode t : type.values()) {
                if (!"null".equals(t.asString())) {
                    return t.asString();
                }
            }
            return "string";
        }
        return type.asString();
    }
}
