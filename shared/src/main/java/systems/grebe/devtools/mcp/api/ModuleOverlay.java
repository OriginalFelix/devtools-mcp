package systems.grebe.devtools.mcp.api;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import systems.grebe.devtools.mcp.config.ModuleSettings;

/**
 * Was das Backend für ein Modul vorgibt: die Ebenen Global → Benutzer → Profil zusammengefasst. Enthält nur, was dort
 * gesetzt ist; alles andere gilt wie vom Modul vorbelegt. Listen statt Maps, weil GraphQL keine Maps kennt.
 *
 * @param enabled Modul an/aus, {@code null} = Vorbelegung des Moduls
 * @param tools   einzelne Tools an oder aus; nicht genannte bleiben an
 * @param values  Feldwerte (Geheimnisse entschlüsselt)
 * @param locked  vom Administrator gesperrte Schlüssel (Feld, {@code @enabled}, {@code @tools})
 */
public record ModuleOverlay(String moduleId, Boolean enabled, List<ToolSwitch> tools, List<Entry> values,
                            List<String> locked) {

    /** Ein Tool an oder aus. */
    public record ToolSwitch(String name, boolean enabled) {
    }

    /** Ein Feldwert. */
    public record Entry(String key, String value) {
    }

    public ModuleOverlay {
        tools = tools == null ? List.of() : List.copyOf(tools);
        values = values == null ? List.of() : List.copyOf(values);
        locked = locked == null ? List.of() : List.copyOf(locked);
    }

    public static ModuleOverlay none(String moduleId) {
        return new ModuleOverlay(moduleId, null, List.of(), List.of(), List.of());
    }

    public static ModuleOverlay of(String moduleId, Boolean enabled, Map<String, Boolean> tools,
                                   Map<String, String> values, Set<String> locked) {
        return new ModuleOverlay(moduleId, enabled,
                tools.entrySet().stream().map(e -> new ToolSwitch(e.getKey(), e.getValue())).toList(),
                values.entrySet().stream().map(e -> new Entry(e.getKey(), e.getValue())).toList(),
                List.copyOf(locked));
    }

    public Map<String, Boolean> toolMap() {
        Map<String, Boolean> m = new LinkedHashMap<>();
        tools.forEach(t -> m.put(t.name(), t.enabled()));
        return m;
    }

    public Map<String, String> valueMap() {
        Map<String, String> m = new LinkedHashMap<>();
        values.forEach(e -> m.put(e.key(), e.value() == null ? "" : e.value()));
        return m;
    }

    public Set<String> lockedKeys() {
        return new LinkedHashSet<>(locked);
    }

    public boolean isEmpty() {
        return enabled == null && tools.isEmpty() && values.isEmpty();
    }

    /** Legt die Vorgaben über die Vorbelegung des Moduls. */
    public ModuleSettings applyTo(ModuleSettings base) {
        boolean on = enabled != null ? enabled : base.enabled();
        Set<String> disabled = new LinkedHashSet<>(base.disabledTools());
        tools.forEach(t -> {
            if (t.enabled()) {
                disabled.remove(t.name());
            } else {
                disabled.add(t.name());
            }
        });
        Map<String, String> merged = new LinkedHashMap<>(base.values());
        merged.putAll(valueMap());
        return new ModuleSettings(on, disabled, merged);
    }
}
