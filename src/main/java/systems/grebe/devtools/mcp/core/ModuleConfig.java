package systems.grebe.devtools.mcp.core;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Unveränderliche Sicht auf die Konfigurationswerte eines Moduls, inkl. Defaults aus dem Schema. */
public final class ModuleConfig {

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

    /** Rohwerte ohne Defaults (so wie gespeichert). */
    public Map<String, String> rawValues() {
        return values;
    }

    /** Prüft Pflichtfelder und Formate. Liefert deutschsprachige Fehlermeldungen. */
    public List<String> validate() {
        List<String> errors = new ArrayList<>();
        for (ConfigField f : schema.values()) {
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
                default -> { }
            }
        }
        return errors;
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
