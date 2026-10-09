package systems.grebe.devtools.mcp.modules.scripts;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import groovy.lang.Closure;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Ein Tool aus einem Groovy-Skript: Eingabeschema aus den {@code param}-Angaben, Aufruf der {@code execute}-Closure mit den
 * Argumenten (und den Einstellungen des Moduls) unter Zeitlimit. Ergebnisse: Text unverändert, {@code null} → „OK“,
 * alles andere als JSON.
 */
final class ScriptToolCallback implements ToolCallback {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String scriptName;
    private final ScriptDefinition.Tool tool;
    private final Map<String, Object> settings;
    private final Supplier<Duration> timeout;
    private final ToolDefinition definition;

    ScriptToolCallback(String scriptName, ScriptDefinition.Tool tool, Map<String, Object> settings,
                       Supplier<Duration> timeout) {
        this.scriptName = scriptName;
        this.tool = tool;
        this.settings = settings;
        this.timeout = timeout;
        this.definition = ToolDefinition.builder().name(tool.name()).description(tool.description())
                .inputSchema(inputSchema(tool.params())).build();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public String call(String toolInput) {
        Map<String, Object> args = arguments(tool.params(), toolInput);
        Closure<?> body = tool.body();
        Object result = ScriptTimeout.run("Tool '" + tool.name() + "' (Skript '" + scriptName + "')", timeout.get(),
                () -> {
                    try {
                        return switch (body.getMaximumNumberOfParameters()) {
                            case 0 -> body.call();
                            case 1 -> body.call(args);
                            default -> body.call(args, settings);
                        };
                    } catch (ScriptTimeout.Exceeded e) {
                        throw e;
                    } catch (RuntimeException e) {
                        throw new IllegalStateException("Fehler im Skript '" + scriptName + "': "
                                + ScriptCompiler.describe(e, scriptName), e);
                    }
                });
        return render(result);
    }

    // ------------------------------------------------------------------ Ein- und Ausgabe

    static String inputSchema(List<ScriptDefinition.Param> params) {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ArrayNode required = JSON.createArrayNode();
        for (ScriptDefinition.Param p : params) {
            ObjectNode prop = props.putObject(p.name());
            prop.put("type", p.jsonType());
            if ("array".equals(p.jsonType())) {
                prop.putObject("items");
            }
            if (!p.description().isEmpty()) {
                prop.put("description", p.description());
            }
            if (!p.options().isEmpty()) {
                ArrayNode values = prop.putArray("enum");
                p.options().forEach(values::add);
            }
            if (p.required()) {
                required.add(p.name());
            }
        }
        if (!required.isEmpty()) {
            schema.set("required", required);
        }
        schema.put("additionalProperties", false);
        return JSON.writeValueAsString(schema);
    }

    /** Argumente des Aufrufs nach den Parametern: Pflicht prüfen, Typen angleichen, erlaubte Werte prüfen. */
    static Map<String, Object> arguments(List<ScriptDefinition.Param> params, String toolInput) {
        Map<String, Object> raw = toolInput == null || toolInput.isBlank() ? Map.of()
                : JSON.readValue(toolInput, JSON.getTypeFactory().constructMapType(Map.class, String.class,
                Object.class));
        Map<String, Object> args = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (ScriptDefinition.Param p : params) {
            Object v = raw.get(p.name());
            if (v == null || v instanceof String s && s.isEmpty() && !"string".equals(p.jsonType())) {
                if (p.required()) {
                    missing.add(p.name());
                }
                args.put(p.name(), null);
                continue;
            }
            Object converted = convert(p, v);
            if (!p.options().isEmpty() && !p.options().contains(String.valueOf(converted))) {
                throw new IllegalArgumentException("'" + p.name() + "' muss einer dieser Werte sein: "
                        + String.join(", ", p.options()) + ".");
            }
            args.put(p.name(), converted);
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Pflichtparameter fehlt: " + String.join(", ", missing) + ".");
        }
        return args;
    }

    /** Nimmt es mit Zahlen und Wahrheitswerten als Text nicht so genau – LLMs schicken beides. */
    private static Object convert(ScriptDefinition.Param p, Object v) {
        try {
            return switch (p.jsonType()) {
                case "integer" -> v instanceof Number n ? (Object) n.longValue() : Long.parseLong(v.toString().strip());
                case "number" -> v instanceof Number n ? n : Double.parseDouble(v.toString().strip());
                case "boolean" -> v instanceof Boolean b ? b : parseBoolean(v.toString());
                case "string" -> v instanceof String ? v : JSON.writeValueAsString(v);
                case "array" -> v instanceof Collection<?> ? v : List.of(v);
                default -> v;
            };
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("'" + p.name() + "' erwartet " + p.jsonType() + ", erhalten: " + v);
        }
    }

    private static Boolean parseBoolean(String s) {
        return switch (s.strip().toLowerCase(java.util.Locale.ROOT)) {
            case "true", "ja", "yes", "1" -> true;
            case "false", "nein", "no", "0" -> false;
            default -> throw new IllegalArgumentException(s);
        };
    }

    static String render(Object result) {
        if (result == null) {
            return "OK";
        }
        if (result instanceof CharSequence || result instanceof Number || result instanceof Boolean) {
            return result.toString();
        }
        try {
            // kompakt: eingerücktes JSON kostet das LLM ein Vielfaches an Tokens, lesbar ist es auch so
            return JSON.writeValueAsString(plain(result));
        } catch (RuntimeException e) {
            return result.toString();
        }
    }

    /** GStrings und Groovy-Collections in einfache Java-Werte, damit Jackson sie wie erwartet schreibt. */
    private static Object plain(Object v) {
        if (v instanceof CharSequence cs) {
            return cs.toString();
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, x) -> out.put(String.valueOf(k), plain(x)));
            return out;
        }
        if (v instanceof Collection<?> c) {
            return c.stream().map(ScriptToolCallback::plain).toList();
        }
        if (v instanceof Object[] a) {
            return java.util.Arrays.stream(a).map(ScriptToolCallback::plain).toList();
        }
        return v;
    }
}
