package systems.grebe.devtools.mcp.modules.scripts;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.ai.tool.ToolCallback;
import systems.grebe.devtools.mcp.core.ToolRegistry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Ruft Tools über die {@link ToolRegistry} auf: nur aktive Tools (Modul- und Tool-Schalter gelten), mit Eintrag im
 * Aufrufprotokoll, im Thread des Aufrufers – Scope, Freigaben und Fortschrittsmeldungen des äußeren Aufrufs gelten
 * also weiter.
 *
 * <p>Text-Argumente werden nach dem Eingabeschema des Ziel-Tools umgewandelt ({@code "5"} → 5, {@code "ja"} → true,
 * {@code "a, b"} → Liste), unbekannte Parameter abgelehnt. Skripte, die sich gegenseitig aufrufen, brechen nach
 * {@value #MAX_DEPTH} Ebenen ab.
 */
final class RegistryToolCaller implements ToolCaller {

    static final int MAX_DEPTH = 5;

    private static final JsonMapper JSON = JsonMapper.shared();
    private static final ScopedValue<Integer> DEPTH = ScopedValue.newInstance();

    private final Supplier<ToolRegistry> registry;

    RegistryToolCaller(Supplier<ToolRegistry> registry) {
        this.registry = registry;
    }

    @Override
    public boolean isActive(String name) {
        try {
            return registry.get().activeTool(name).isPresent();
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public String call(String name, Map<String, Object> args) {
        ToolCallback tool = registry.get().activeTool(name).orElseThrow(() -> new IllegalArgumentException(
                "Tool '" + name + "' gibt es nicht oder es ist abgeschaltet (Modul- oder Tool-Schalter in der App)."));
        String input = JSON.writeValueAsString(convert(name, tool.getToolDefinition().inputSchema(), args));
        int depth = DEPTH.orElse(0);
        if (depth >= MAX_DEPTH) {
            throw new IllegalStateException("Skripte rufen sich mehr als " + MAX_DEPTH + " Ebenen tief gegenseitig "
                    + "auf – ein Kreislauf? Zuletzt: " + name);
        }
        return ScopedValue.where(DEPTH, depth + 1).call(() -> tool.call(input));
    }

    /** Text-Werte nach dem JSON-Schema des Tools in Zahl, Wahrheitswert, Liste oder Objekt umwandeln. */
    static Map<String, Object> convert(String tool, String inputSchema, Map<String, Object> args) {
        JsonNode properties = inputSchema == null || inputSchema.isBlank() ? null
                : JSON.readTree(inputSchema).get("properties");
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : args.entrySet()) {
            JsonNode prop = properties == null ? null : properties.get(e.getKey());
            if (properties != null && prop == null) {
                throw new IllegalArgumentException("Tool '" + tool + "' kennt keinen Parameter '" + e.getKey()
                        + "' – vorhanden: " + (properties.propertyNames().isEmpty() ? "keine"
                        : String.join(", ", properties.propertyNames())) + ".");
            }
            out.put(e.getKey(), e.getValue() instanceof String s && prop != null ? typed(tool, e.getKey(), type(prop), s)
                    : e.getValue());
        }
        return out;
    }

    private static String type(JsonNode prop) {
        JsonNode type = prop.get("type");
        if (type == null) {
            return "string";
        }
        if (type.isArray()) { // z.B. ["integer", "null"]
            for (JsonNode t : type.values()) {
                if (!"null".equals(t.asString())) {
                    return t.asString();
                }
            }
            return "string";
        }
        return type.asString();
    }

    private static Object typed(String tool, String param, String type, String value) {
        String v = value.strip();
        try {
            return switch (type) {
                case "integer" -> Long.parseLong(v);
                case "number" -> Double.parseDouble(v.replace(',', '.'));
                case "boolean" -> switch (v.toLowerCase(Locale.ROOT)) {
                    case "true", "ja", "yes", "1" -> true;
                    case "false", "nein", "no", "0" -> false;
                    default -> throw new IllegalArgumentException(v);
                };
                case "array" -> v.startsWith("[") ? JSON.readValue(v, List.class) : list(v);
                case "object" -> JSON.readValue(v, Map.class);
                default -> value;
            };
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Parameter '" + param + "' von Tool '" + tool + "' erwartet " + type
                    + ", erhalten: " + value);
        }
    }

    /** {@code a, b, c} → Liste; leer → leere Liste. */
    private static List<String> list(String v) {
        return v.isEmpty() ? List.of()
                : new ArrayList<>(Arrays.stream(v.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList());
    }
}
