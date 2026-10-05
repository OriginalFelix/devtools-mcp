package systems.grebe.devtools.mcp.modules.scripts;

import java.util.Map;

/** Ruft vorhandene Tools für Skripte auf – in der App über die {@code ToolRegistry} ({@link RegistryToolCaller}). */
interface ToolCaller {

    /**
     * Ruft das aktive Tool mit vollem Namen auf (z.B. {@code git_status}).
     *
     * @param args Text-Werte werden nach dem Eingabeschema des Tools umgewandelt (Zahl, Wahrheitswert, Liste …),
     *             andere Werte gehen unverändert durch
     * @return das Ergebnis des Tools als Text
     * @throws RuntimeException mit der Meldung des Tools, wenn es fehlschlägt oder nicht aktiv ist
     */
    String call(String name, Map<String, Object> args);

    /** Ob das Tool gerade aktiv ist – nur für Hinweise beim Speichern. */
    boolean isActive(String name);
}
