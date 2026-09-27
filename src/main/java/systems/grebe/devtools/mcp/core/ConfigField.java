package systems.grebe.devtools.mcp.core;

import java.util.List;
import java.util.Objects;

/**
 * Beschreibung eines Konfigurationsfeldes. Die UI erzeugt daraus das passende Eingabeelement.
 *
 * <pre>{@code
 * ConfigField.of("baseUrl", "Server-URL", FieldType.URL).asRequired().withDefault("http://localhost:9000")
 * }</pre>
 */
public record ConfigField(
        String key,
        String label,
        FieldType type,
        boolean required,
        String defaultValue,
        String help,
        List<String> options) {

    public ConfigField {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(type, "type");
        options = options == null ? List.of() : List.copyOf(options);
    }

    public static ConfigField of(String key, String label, FieldType type) {
        return new ConfigField(key, label, type, false, null, null, List.of());
    }

    public ConfigField asRequired() {
        return new ConfigField(key, label, type, true, defaultValue, help, options);
    }

    public ConfigField withDefault(String value) {
        return new ConfigField(key, label, type, required, value, help, options);
    }

    public ConfigField withHelp(String text) {
        return new ConfigField(key, label, type, required, defaultValue, text, options);
    }

    public ConfigField withOptions(String... values) {
        return new ConfigField(key, label, type, required, defaultValue, help, List.of(values));
    }
}
