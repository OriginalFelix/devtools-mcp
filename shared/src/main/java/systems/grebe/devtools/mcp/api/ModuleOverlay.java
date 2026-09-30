package systems.grebe.devtools.mcp.api;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import systems.grebe.devtools.mcp.config.ModuleSettings;

/**
 * Was der Server für ein Modul vorgibt: die Ebenen Global → Benutzer → Profil zusammengefasst. Enthält nur, was dort
 * gesetzt ist; alles andere bleibt, wie es in der Desktop-App eingestellt ist.
 *
 * @param enabled Modul an/aus, {@code null} = lokale Einstellung
 * @param tools   einzelne Tools an ({@code true}) oder aus ({@code false}); nicht genannte bleiben lokal
 * @param values  Feldwerte (Geheimnisse entschlüsselt)
 * @param locked  vom Administrator gesperrte Schlüssel (Feld, {@code @enabled}, {@code @tools}) – nur zur Anzeige
 */
public record ModuleOverlay(Boolean enabled, Map<String, Boolean> tools, Map<String, String> values,
                            Set<String> locked) {

    public static final ModuleOverlay NONE = new ModuleOverlay(null, Map.of(), Map.of(), Set.of());

    public ModuleOverlay {
        tools = tools == null ? Map.of() : Map.copyOf(tools);
        values = values == null ? Map.of() : Map.copyOf(values);
        locked = locked == null ? Set.of() : Set.copyOf(locked);
    }

    public boolean isEmpty() {
        return enabled == null && tools.isEmpty() && values.isEmpty();
    }

    /** Legt die Vorgaben über die lokalen Einstellungen. */
    public ModuleSettings applyTo(ModuleSettings local) {
        boolean on = enabled != null ? enabled : local.enabled();
        Set<String> disabled = new LinkedHashSet<>(local.disabledTools());
        tools.forEach((t, active) -> {
            if (active) {
                disabled.remove(t);
            } else {
                disabled.add(t);
            }
        });
        Map<String, String> merged = new LinkedHashMap<>(local.values());
        merged.putAll(values);
        return new ModuleSettings(on, disabled, merged);
    }
}
