package systems.grebe.devtools.mcp.core;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Unveränderliche Sicht auf die Konfigurationswerte eines Moduls, inkl. Defaults aus dem Schema. */
public final class ModuleConfig {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Map<String, ConfigField> schema = new LinkedHashMap<>();
    private final Map<String, String> values;

    private ModuleConfig(List<ConfigField> fields, Map<String, String> values) {
        fields.forEach(f -> schema.put(f.key(), f));
        this.values = Map.copyOf(values == null ? Map.of() : stripNulls(values));
    }

    public static ModuleConfig of(List<ConfigField> schema, Map<String, String> values) {
        return new ModuleConfig(schema, values);
    }

    /** Wert oder Default; leere Werte gelten als nicht gesetzt. */
    public Optional<String> get(String key) {
        String v = values.get(key);
        if (v != null && !v.isBlank()) {
            return Optional.of(v.trim());
        }
        ConfigField f = schema.get(key);
        if (f != null && f.defaultValue() != null && !f.defaultValue().isBlank()) {
            return Optional.of(f.defaultValue().trim());
        }
        return Optional.empty();
    }

    public String getString(String key, String fallback) {
        return get(key).orElse(fallback);
    }

    /** Pflichtwert; wirft mit einer für das LLM verständlichen Meldung, wenn er fehlt. */
    public String require(String key) {
        return get(key).orElseThrow(() -> new IllegalStateException(
                "Konfiguration unvollständig: '" + label(key) + "' ist nicht gesetzt. "
                        + "Bitte in der DevTools-MCP-App unter Module konfigurieren."));
    }

    public boolean getBoolean(String key) {
        return get(key).map(Boolean::parseBoolean).orElse(false);
    }

    public int getInt(String key, int fallback) {
        try {
            return get(key).map(Integer::parseInt).orElse(fallback);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Mehrzeiliger Wert als Liste (eine Zeile je Eintrag, leere Zeilen ignoriert). */
    public List<String> getList(String key) {
        return get(key).map(ModuleConfig::splitLines).orElse(List.of());
    }

    /**
     * Datensätze eines {@link FieldType#RECORD_LIST}-Feldes, je Datensatz Feld → Wert (fehlende Felder mit ihrem Default).
     * Ungültiges JSON ergibt eine leere Liste; {@link #validate()} meldet es.
     */
    public List<Map<String, String>> getRecords(String key) {
        ConfigField f = schema.get(key);
        try {
            return get(key).map(v -> parseRecords(v, f == null ? List.of() : f.columns())).orElse(List.of());
        } catch (IllegalArgumentException e) {
            return List.of();
        }
    }

    /** Rohwerte ohne Defaults (so wie gespeichert). */
    public Map<String, String> rawValues() {
        return values;
    }

    /**
     * Prüft Pflichtfelder und Formate. Liefert deutschsprachige Fehlermeldungen. Felder inaktiver
     * {@link ConfigGroup Gruppen} werden nicht verwendet und daher nicht geprüft.
     */
    public List<String> validate() {
        List<String> errors = new ArrayList<>();
        for (ConfigField f : schema.values()) {
            if (f.group() != null && !getBoolean(f.group().enabledKey())) {
                continue;
            }
            Optional<String> value = get(f.key());
            if (value.isEmpty()) {
                if (f.required()) {
                    errors.add("'" + f.label() + "' ist ein Pflichtfeld.");
                }
                continue;
            }
            String v = value.get();
            switch (f.type()) {
                case INT -> {
                    try {
                        Integer.parseInt(v);
                    } catch (NumberFormatException e) {
                        errors.add("'" + f.label() + "' muss eine ganze Zahl sein.");
                    }
                }
                case URL -> {
                    try {
                        URI uri = URI.create(v);
                        if (uri.getScheme() == null || !uri.getScheme().matches("https?") || uri.getHost() == null) {
                            errors.add("'" + f.label() + "' muss eine http(s)-URL sein.");
                        }
                    } catch (IllegalArgumentException e) {
                        errors.add("'" + f.label() + "' ist keine gültige URL.");
                    }
                }
                case ENUM -> {
                    if (!f.options().isEmpty() && !f.options().contains(v)) {
                        errors.add("'" + f.label() + "' muss einer von " + f.options() + " sein.");
                    }
                }
                case DIRECTORY -> checkDirectory(f, v, errors);
                case DIRECTORY_LIST -> splitLines(v).forEach(d -> checkDirectory(f, d, errors));
                case RECORD_LIST -> checkRecords(f, v, errors);
                default -> { }
            }
        }
        return errors;
    }

    /** Jeder Datensatz wird mit dem Spalten-Schema geprüft; Meldungen nennen Liste und Nummer des Datensatzes. */
    private static void checkRecords(ConfigField f, String value, List<String> errors) {
        List<Map<String, String>> records;
        try {
            records = parseRecords(value, f.columns());
        } catch (IllegalArgumentException e) {
            errors.add("'" + f.label() + "': " + e.getMessage());
            return;
        }
        for (int i = 0; i < records.size(); i++) {
            String prefix = "'" + f.label() + "', Eintrag " + (i + 1) + ": ";
            ModuleConfig.of(f.columns(), records.get(i)).validate().forEach(err -> errors.add(prefix + err));
        }
    }

    /** JSON-Array von Objekten → Datensätze; Felder ohne Wert erhalten ihren Default. */
    public static List<Map<String, String>> parseRecords(String json, List<ConfigField> columns) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("kein gültiges JSON-Array von Datensätzen.", e);
        }
        if (!root.isArray()) {
            throw new IllegalArgumentException("kein gültiges JSON-Array von Datensätzen.");
        }
        List<Map<String, String>> out = new ArrayList<>();
        for (JsonNode n : root) {
            Map<String, String> record = new LinkedHashMap<>();
            for (ConfigField c : columns) {
                String v = n.path(c.key()).isValueNode() ? n.path(c.key()).asString("") : "";
                record.put(c.key(), v.isBlank() && c.defaultValue() != null ? c.defaultValue() : v);
            }
            out.add(Collections.unmodifiableMap(record));
        }
        return List.copyOf(out);
    }

    /** Datensätze → gespeicherter Wert (JSON-Array, leere Werte ausgelassen). */
    public static String formatRecords(List<Map<String, String>> records) {
        ArrayNode arr = JSON.createArrayNode();
        for (Map<String, String> r : records) {
            ObjectNode n = arr.addObject();
            r.forEach((k, v) -> {
                if (k != null && v != null && !v.isBlank()) {
                    n.put(k, v);
                }
            });
        }
        return records.isEmpty() ? "" : JSON.writeValueAsString(arr);
    }

    private static void checkDirectory(ConfigField f, String dir, List<String> errors) {
        try {
            if (!Files.isDirectory(Path.of(dir))) {
                errors.add("'" + f.label() + "': Verzeichnis existiert nicht: " + dir);
            }
        } catch (InvalidPathException e) {
            errors.add("'" + f.label() + "': ungültiger Pfad: " + dir);
        }
    }

    private String label(String key) {
        ConfigField f = schema.get(key);
        return f == null ? key : f.label();
    }

    public static List<String> splitLines(String v) {
        return Arrays.stream(v.split("\\R")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static Map<String, String> stripNulls(Map<String, String> in) {
        Map<String, String> out = new LinkedHashMap<>();
        in.forEach((k, v) -> {
            if (k != null && v != null) {
                out.put(k, v);
            }
        });
        return out;
    }
}
