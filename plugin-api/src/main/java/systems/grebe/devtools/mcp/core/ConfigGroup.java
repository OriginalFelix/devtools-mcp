package systems.grebe.devtools.mcp.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Einzeln aktivierbarer Teil eines Moduls, typischerweise ein Provider (Jira im Modul Tickets, Podman im Modul
 * Container). Seine Felder liegen unter {@code <id>.<feld>}, sein Schalter unter {@code <id>.enabled}. Die Desktop-UI
 * zeigt statt der Felder aller Gruppen eine Mehrfachauswahl der aktiven und die Einstellungen der jeweils gewählten.
 *
 * <pre>{@code
 * ConfigGroup jira = new ConfigGroup("jira", "Jira");
 * fields.addAll(jira.fields(false, provider.configFields()));   // jira.enabled, jira.baseUrl, …
 * }</pre>
 *
 * @param id    technische ID, Präfix der Schlüssel
 * @param label Anzeigename, steht in den Beschriftungen der Felder vorn („Jira: Server-URL“)
 */
public record ConfigGroup(String id, String label) {

    public ConfigGroup {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(label, "label");
    }

    public String key(String field) {
        return id + "." + field;
    }

    /** Schlüssel des Schalters, der die Gruppe aktiviert. */
    public String enabledKey() {
        return key("enabled");
    }

    /**
     * Schalter und Felder der Gruppe für ein Modul-Schema: Schlüssel mit {@code <id>.} davor, Beschriftungen mit
     * {@code <label>: } davor – Meldungen und Listen (Validierung, gesperrte Felder) bleiben so eindeutig.
     */
    public List<ConfigField> fields(boolean enabledByDefault, List<ConfigField> fields) {
        List<ConfigField> out = new ArrayList<>();
        out.add(new ConfigField(enabledKey(), label + ": aktiv", FieldType.BOOLEAN, false,
                String.valueOf(enabledByDefault), null, List.of(), List.of(), this));
        for (ConfigField f : fields) {
            out.add(new ConfigField(key(f.key()), label + ": " + f.label(), f.type(), f.required(), f.defaultValue(),
                    f.help(), f.options(), f.columns(), this));
        }
        return out;
    }

    /** Beschriftung eines Feldes der Gruppe ohne den Anzeigenamen davor. */
    public String shortLabel(ConfigField field) {
        String prefix = label + ": ";
        return field.label().startsWith(prefix) ? field.label().substring(prefix.length()) : field.label();
    }
}
