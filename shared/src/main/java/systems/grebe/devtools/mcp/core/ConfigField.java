package systems.grebe.devtools.mcp.core;

import java.util.List;
import java.util.Objects;

/**
 * Beschreibung eines Konfigurationsfeldes. Die UI erzeugt daraus das passende Eingabeelement.
 *
 * <pre>{@code
 * ConfigField.of("baseUrl", "Server-URL", FieldType.URL).asRequired().withDefault("http://localhost:9000")
 * }</pre>
 *
 * @param columns nur für {@link FieldType#RECORD_LIST}: die Felder eines Datensatzes
 */
public record ConfigField(
        String key,
        String label,
        FieldType type,
        boolean required,
        String defaultValue,
        String help,
        List<String> options,
        List<ConfigField> columns) {

    public ConfigField {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(type, "type");
        options = options == null ? List.of() : List.copyOf(options);
        columns = columns == null ? List.of() : List.copyOf(columns);
    }

    public ConfigField(String key, String label, FieldType type, boolean required, String defaultValue, String help,
                       List<String> options) {
        this(key, label, type, required, defaultValue, help, options, List.of());
    }

    public static ConfigField of(String key, String label, FieldType type) {
        return new ConfigField(key, label, type, false, null, null, List.of());
    }

    /** Liste von Datensätzen mit den angegebenen Feldern (siehe {@link ModuleConfig#getRecords}). */
    public static ConfigField records(String key, String label, ConfigField... columns) {
        return new ConfigField(key, label, FieldType.RECORD_LIST, false, null, null, List.of(), List.of(columns));
    }

    public ConfigField asRequired() {
        return new ConfigField(key, label, type, true, defaultValue, help, options, columns);
    }

    public ConfigField withDefault(String value) {
        return new ConfigField(key, label, type, required, value, help, options, columns);
    }

    public ConfigField withHelp(String text) {
        return new ConfigField(key, label, type, required, defaultValue, text, options, columns);
    }

    public ConfigField withOptions(String... values) {
        return new ConfigField(key, label, type, required, defaultValue, help, List.of(values), columns);
    }

    /** Ob der Wert verschlüsselt gespeichert wird: Geheimnisse und Datensatzlisten mit einem geheimen Feld. */
    public boolean secret() {
        return type == FieldType.SECRET
                || type == FieldType.RECORD_LIST && columns.stream().anyMatch(ConfigField::secret);
    }
}
