package systems.grebe.devtools.mcp.profile;

import java.util.Map;

/**
 * Überschreibungen eines Moduls auf einer Ebene (Benutzer oder Profil). Was fehlt, wird von der Ebene darüber geerbt.
 *
 * @param enabled Modul an/aus, {@code null} = erben
 * @param tools   einzelne Tools an ({@code true}) oder aus ({@code false}); nicht genannte erben
 * @param values  Feldwerte (Geheimnisse hier entschlüsselt)
 */
public record Overrides(Boolean enabled, Map<String, Boolean> tools, Map<String, String> values) {

    public static final Overrides NONE = new Overrides(null, Map.of(), Map.of());

    /** Schlüssel für „Modul an/aus“ in Tabelle und Sperren. */
    public static final String ENABLED = "@enabled";
    /** Präfix einzelner Tool-Schalter in der Tabelle. */
    public static final String TOOL_PREFIX = "@tool:";
    /** Sperre aller Tool-Schalter eines Moduls. */
    public static final String TOOLS = "@tools";

    public Overrides {
        tools = tools == null ? Map.of() : Map.copyOf(tools);
        values = values == null ? Map.of() : Map.copyOf(values);
    }

    public boolean isEmpty() {
        return enabled == null && tools.isEmpty() && values.isEmpty();
    }

    /** Ebene der Überschreibung. */
    public enum Level {
        /** Gilt für alle Profile des Benutzers. */
        USER,
        /** Gilt nur im Profil. */
        PROFILE
    }
}
