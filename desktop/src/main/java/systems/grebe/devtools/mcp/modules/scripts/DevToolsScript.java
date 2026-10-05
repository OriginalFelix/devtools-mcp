package systems.grebe.devtools.mcp.modules.scripts;

import java.io.File;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import groovy.lang.Closure;
import groovy.lang.DelegatesTo;
import groovy.lang.Script;
import groovy.transform.stc.ClosureParams;
import groovy.transform.stc.FromString;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.core.ToolProgress;

/**
 * Basisklasse jedes Groovy-Skripts (über {@code CompilerConfiguration#setScriptBaseClass}) und damit die DSL:
 *
 * <pre>{@code
 * module {
 *     name 'Jira-Helfer'                      // Anzeigename (Standard: Skriptname)
 *     description 'Eigene Jira-Abfragen'      // Pflicht
 *     instructions 'Wann das LLM die Tools nutzen soll'
 *     setting 'baseUrl', 'Basis-URL', URL, required: true
 *     setting 'token', 'API-Token', SECRET
 * }
 *
 * tool('open_issues') {
 *     description 'Offene Issues eines Projekts'
 *     param 'project', String, 'Projektschlüssel'
 *     param 'limit', Integer, 'Höchstens so viele', required: false
 *     readOnly true
 *     execute { args, cfg ->
 *         progress "Frage ${cfg.baseUrl} ab …"
 *         "Issues für ${args.project}"
 *     }
 * }
 * }</pre>
 *
 * Feldtypen ({@code STRING}, {@code SECRET}, {@code INT}, {@code BOOLEAN}, {@code URL}, {@code DIRECTORY}, …) sind
 * statisch importiert; Java-Klassen ({@code String}, {@code Integer}, {@code Boolean}, {@code List} …) gehen ebenso.
 * In Tool-Closures stehen {@link #progress} und {@link #getLog() log} zur Verfügung.
 *
 * <p>Die DSL-Methoden tragen {@code @DelegatesTo}/{@code @ClosureParams}, damit Skripte mit der Anweisung
 * {@code // devtools: compileStatic} (bzw. {@code typeChecked}) vollständig statisch geprüft werden können – siehe
 * {@link ScriptCompiler.Mode}.
 */
public abstract class DevToolsScript extends Script {

    static final Pattern TOOL_NAME = Pattern.compile("[a-z][a-z0-9_]{0,47}");
    private static final Pattern SETTING_KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");

    private final Collector collector = new Collector();
    private Logger log;

    /** Modul-Angaben: Name, Beschreibung, Instructions und Einstellungsfelder. */
    public void module(@DelegatesTo(value = ModuleSpec.class, strategy = Closure.DELEGATE_FIRST) Closure<?> body) {
        collector.requireDefining("module");
        ModuleSpec spec = collector.module;
        body.setResolveStrategy(Closure.DELEGATE_FIRST);
        body.setDelegate(spec);
        body.call(spec);
    }

    /** Ein Tool; der Name bekommt das Modul-Präfix ({@code open_issues} → {@code jira_open_issues}). */
    public void tool(String name,
                     @DelegatesTo(value = ToolSpec.class, strategy = Closure.DELEGATE_FIRST) Closure<?> body) {
        collector.requireDefining("tool");
        String n = name == null ? "" : name.strip();
        if (!TOOL_NAME.matcher(n).matches()) {
            throw new IllegalArgumentException("Ungültiger Tool-Name '" + n + "': Kleinbuchstaben, Ziffern und '_', "
                    + "beginnend mit einem Buchstaben, max. 48 Zeichen (z.B. 'open_issues').");
        }
        if (collector.tools.stream().anyMatch(t -> t.name.equals(n))) {
            throw new IllegalArgumentException("Tool '" + n + "' ist im Skript doppelt definiert.");
        }
        ToolSpec spec = new ToolSpec(n);
        body.setResolveStrategy(Closure.DELEGATE_FIRST);
        body.setDelegate(spec);
        body.call(spec);
        collector.tools.add(spec);
    }

    /** Meldet einen Zwischenstand an den Client (MCP-Progress), sofern er einen angefordert hat; sonst wirkungslos. */
    public void progress(Object message) {
        ToolProgress.report(message == null ? null : message.toString());
    }

    /** Logger des Skripts ({@code log.info "…"}); landet im Log der App. */
    public Logger getLog() {
        if (log == null) {
            log = LoggerFactory.getLogger("script." + getClass().getSimpleName());
        }
        return log;
    }

    Collector collector() {
        return collector;
    }

    // ------------------------------------------------------------------ DSL-Teile

    /** Delegate von {@code module { … }}. */
    public static final class ModuleSpec {
        private String displayName;
        private String description;
        private String instructions;
        private boolean enabledByDefault = true;
        private final List<ConfigField> settings = new ArrayList<>();

        public void name(Object value) {
            displayName = text(value);
        }

        public void description(Object value) {
            description = text(value);
        }

        public void instructions(Object value) {
            instructions = text(value);
        }

        /** Ob das Modul beim ersten Laden aktiv ist (Standard {@code true}). */
        public void enabledByDefault(boolean value) {
            enabledByDefault = value;
        }

        public void setting(String key, String label) {
            setting(Map.of(), key, label, FieldType.STRING);
        }

        public void setting(String key, String label, Object type) {
            setting(Map.of(), key, label, type);
        }

        public void setting(Map<String, Object> options, String key, String label) {
            setting(options, key, label, FieldType.STRING);
        }

        /**
         * Einstellungsfeld im Modul-Formular der App. Optionen: {@code required}, {@code defaultValue} (auch
         * {@code default}), {@code help}, {@code options} (Werte für {@code ENUM}).
         */
        public void setting(Map<String, Object> options, String key, String label, Object type) {
            String k = key == null ? "" : key.strip();
            if (!SETTING_KEY.matcher(k).matches()) {
                throw new IllegalArgumentException("Ungültiger Einstellungsschlüssel '" + k + "': Buchstaben, Ziffern "
                        + "und '_', beginnend mit einem Buchstaben (z.B. 'baseUrl').");
            }
            if (settings.stream().anyMatch(f -> f.key().equals(k))) {
                throw new IllegalArgumentException("Einstellung '" + k + "' ist doppelt definiert.");
            }
            Map<String, Object> o = options == null ? Map.of() : options;
            checkOptions(o, Set.of("required", "defaultValue", "default", "help", "options"), "setting '" + k + "'");
            FieldType fieldType = fieldType(type, k);
            ConfigField f = ConfigField.of(k, label == null || label.isBlank() ? k : label.strip(), fieldType);
            if (Boolean.TRUE.equals(o.get("required"))) {
                f = f.asRequired();
            }
            Object def = o.containsKey("defaultValue") ? o.get("defaultValue") : o.get("default");
            if (def != null) {
                f = f.withDefault(def.toString());
            }
            if (o.get("help") != null) {
                f = f.withHelp(o.get("help").toString());
            }
            List<String> values = strings(o.get("options"));
            if (!values.isEmpty()) {
                f = f.withOptions(values.toArray(String[]::new));
            } else if (fieldType == FieldType.ENUM) {
                throw new IllegalArgumentException("Einstellung '" + k + "' vom Typ ENUM braucht options: [...].");
            }
            settings.add(f);
        }
    }

    /** Delegate von {@code tool('name') { … }}. */
    public static final class ToolSpec {
        private final String name;
        private String description;
        private final List<ScriptDefinition.Param> params = new ArrayList<>();
        private Boolean readOnly;
        private Boolean destructive;
        private Boolean idempotent;
        private Boolean openWorld;
        private Closure<?> body;

        ToolSpec(String name) {
            this.name = name;
        }

        public void description(Object value) {
            description = text(value);
        }

        public void param(String name, String description) {
            param(Map.of(), name, String.class, description);
        }

        public void param(String name, Object type, String description) {
            param(Map.of(), name, type, description);
        }

        public void param(Map<String, Object> options, String name, String description) {
            param(options, name, String.class, description);
        }

        /** Parameter; Optionen: {@code required} (Standard {@code true}), {@code options} (erlaubte Werte). */
        public void param(Map<String, Object> options, String name, Object type, String description) {
            String n = name == null ? "" : name.strip();
            if (!SETTING_KEY.matcher(n).matches()) {
                throw new IllegalArgumentException("Ungültiger Parametername '" + n + "' in Tool '" + this.name
                        + "': Buchstaben, Ziffern und '_', beginnend mit einem Buchstaben.");
            }
            if (params.stream().anyMatch(p -> p.name().equals(n))) {
                throw new IllegalArgumentException("Parameter '" + n + "' ist in Tool '" + this.name + "' doppelt.");
            }
            Map<String, Object> o = options == null ? Map.of() : options;
            checkOptions(o, Set.of("required", "options"), "param '" + n + "'");
            params.add(new ScriptDefinition.Param(n, jsonType(type, n), description == null ? "" : description.strip(),
                    !Boolean.FALSE.equals(o.get("required")), strings(o.get("options"))));
        }

        /** Verändert nichts in seiner Umgebung – Clients dürfen es ohne Rückfrage ausführen. */
        public void readOnly(boolean value) {
            readOnly = value;
        }

        public void destructive(boolean value) {
            destructive = value;
        }

        public void idempotent(boolean value) {
            idempotent = value;
        }

        public void openWorld(boolean value) {
            openWorld = value;
        }

        /** Code des Tools: {@code { args -> … }} oder {@code { args, cfg -> … }}; das Ergebnis geht an das LLM. */
        public void execute(@ClosureParams(value = FromString.class, options = {
                "java.util.Map<java.lang.String,java.lang.Object>",
                "java.util.Map<java.lang.String,java.lang.Object>,java.util.Map<java.lang.String,java.lang.Object>"})
                            Closure<?> code) {
            run(code);
        }

        /** Älterer Name von {@link #execute} – nur ohne Typprüfung (statisch bindet Groovy {@code run} an Closure). */
        public void run(Closure<?> code) {
            if (body != null) {
                throw new IllegalArgumentException("Tool '" + name + "' hat mehr als einen execute-Block.");
            }
            body = Objects.requireNonNull(code, "run");
        }

        ScriptDefinition.Tool build() {
            if (description == null || description.isBlank()) {
                throw new IllegalArgumentException("Tool '" + name + "' braucht eine description – daran erkennt das "
                        + "LLM, wann es das Tool verwenden soll.");
            }
            if (body == null) {
                throw new IllegalArgumentException("Tool '" + name + "' hat keinen execute { … }-Block.");
            }
            McpSchema.ToolAnnotations annotations = readOnly == null && destructive == null && idempotent == null
                    && openWorld == null ? null : annotations();
            return new ScriptDefinition.Tool(name, description, params, annotations, body);
        }

        private McpSchema.ToolAnnotations annotations() {
            boolean ro = Boolean.TRUE.equals(readOnly);
            // destructive/idempotent sind laut Spezifikation nur bei nicht lesenden Tools bedeutsam
            return new McpSchema.ToolAnnotations(null, ro, ro ? null : !Boolean.FALSE.equals(destructive),
                    ro ? null : Boolean.TRUE.equals(idempotent), !Boolean.FALSE.equals(openWorld), null);
        }
    }

    /** Sammelt die Angaben während der Auswertung; danach sind weitere DSL-Aufrufe nicht mehr erlaubt. */
    static final class Collector {
        final ModuleSpec module = new ModuleSpec();
        final List<ToolSpec> tools = new ArrayList<>();
        private boolean closed;

        void requireDefining(String what) {
            if (closed) {
                throw new IllegalStateException("'" + what + "' ist nur auf oberster Ebene des Skripts erlaubt, nicht "
                        + "beim Ausführen eines Tools.");
            }
        }

        ScriptDefinition build(String scriptName) {
            closed = true;
            ModuleSpec m = module;
            if (m.description == null || m.description.isBlank()) {
                throw new IllegalArgumentException("Beschreibung fehlt: module { description '…' } angeben – sie "
                        + "erscheint in der Modulliste und beim LLM.");
            }
            if (tools.isEmpty()) {
                throw new IllegalArgumentException("Das Skript definiert keine Tools: mindestens ein tool('name') { … }.");
            }
            String display = m.displayName == null || m.displayName.isBlank() ? scriptName : m.displayName.strip();
            return new ScriptDefinition(display, m.description.strip(), m.instructions, m.enabledByDefault,
                    m.settings, tools.stream().map(ToolSpec::build).toList());
        }
    }

    // ------------------------------------------------------------------ Typen

    static String jsonType(Object type, String param) {
        if (type == null || type == String.class || type == CharSequence.class) {
            return "string";
        }
        if (type instanceof Class<?> c) {
            if (c == Integer.class || c == int.class || c == Long.class || c == long.class || c == Short.class
                    || c == BigInteger.class) {
                return "integer";
            }
            if (c == Double.class || c == double.class || c == Float.class || c == float.class
                    || c == BigDecimal.class || c == Number.class) {
                return "number";
            }
            if (c == Boolean.class || c == boolean.class) {
                return "boolean";
            }
            if (Collection.class.isAssignableFrom(c) || c.isArray()) {
                return "array";
            }
            if (Map.class.isAssignableFrom(c)) {
                return "object";
            }
        } else {
            String t = type.toString().strip().toLowerCase(Locale.ROOT);
            switch (t) {
                case "string", "integer", "number", "boolean", "array", "object" -> {
                    return t;
                }
                case "int", "long" -> {
                    return "integer";
                }
                case "list" -> {
                    return "array";
                }
                case "map" -> {
                    return "object";
                }
                default -> {
                    // unten
                }
            }
        }
        throw new IllegalArgumentException("Typ von Parameter '" + param + "' nicht unterstützt: " + type
                + " (String, Integer, Long, Double, Boolean, List oder Map).");
    }

    static FieldType fieldType(Object type, String key) {
        if (type == null || type == String.class) {
            return FieldType.STRING;
        }
        if (type instanceof FieldType f) {
            return f;
        }
        if (type instanceof Class<?> c) {
            if (c == Integer.class || c == int.class || c == Long.class || c == long.class) {
                return FieldType.INT;
            }
            if (c == Boolean.class || c == boolean.class) {
                return FieldType.BOOLEAN;
            }
            if (c == URL.class || c == URI.class) {
                return FieldType.URL;
            }
            if (c == File.class || Path.class.isAssignableFrom(c)) {
                return FieldType.DIRECTORY;
            }
            if (Collection.class.isAssignableFrom(c)) {
                return FieldType.STRING_LIST;
            }
        } else {
            try {
                return FieldType.valueOf(type.toString().strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                // unten
            }
        }
        throw new IllegalArgumentException("Typ der Einstellung '" + key + "' nicht unterstützt: " + type
                + " (STRING, SECRET, INT, BOOLEAN, URL, DIRECTORY, DIRECTORY_LIST, ENUM, STRING_LIST).");
    }

    private static void checkOptions(Map<String, Object> options, Set<String> allowed, String where) {
        Set<String> unknown = new LinkedHashSet<>(options.keySet());
        unknown.removeAll(allowed);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Unbekannte Option(en) " + unknown + " bei " + where + " – erlaubt: "
                    + allowed + ".");
        }
    }

    private static List<String> strings(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof Collection<?> c) {
            return c.stream().map(String::valueOf).toList();
        }
        if (value instanceof Object[] a) {
            return java.util.Arrays.stream(a).map(String::valueOf).toList();
        }
        return List.of(value.toString());
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }
}
