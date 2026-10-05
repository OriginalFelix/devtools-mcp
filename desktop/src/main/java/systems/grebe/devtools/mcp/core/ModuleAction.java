package systems.grebe.devtools.mcp.core;

import java.util.List;
import java.util.Set;

/**
 * Aktion, die ein Modul in der Oberfläche anbietet – unabhängig von MCP-Tools, z.B. „Projekt indizieren“.
 *
 * <p>Die UI zeigt je Aktion eine Auswahl ({@link #targets}), optionale Schalter ({@link #flags}), einen Start-Knopf mit
 * Fortschritt und Abbrechen sowie den Zustand des gewählten Ziels ({@link #describe}). Ausgeführt wird mit der
 * <em>gespeicherten</em> Konfiguration des Moduls in einem Hintergrund-Thread; Abbrechen unterbricht diesen Thread.
 * Das Modul muss dafür nicht aktiv sein.
 */
public interface ModuleAction {

    /** Stabile ID innerhalb des Moduls. */
    String id();

    /** Beschriftung des Start-Knopfs, z.B. „Indizieren“. */
    String label();

    /** Ein Satz für die UI. */
    String description();

    /**
     * Ob die Aktion ein Ziel aus {@link #targets} braucht. {@code false}: die UI zeigt nur den Start-Knopf, {@link #run}
     * und {@link #describe} bekommen {@code null} als Ziel.
     */
    default boolean needsTarget() {
        return true;
    }

    /**
     * Auswählbare Ziele (z.B. Projektnamen) für die Konfiguration; leer = derzeit nichts auswählbar (z.B. Konfiguration
     * unvollständig), die UI sperrt dann den Start. Darf nicht werfen. Nur für Aktionen mit {@link #needsTarget()}.
     */
    default List<String> targets(ModuleConfig config) {
        return List.of();
    }

    /**
     * Zustand eines Ziels für die Anzeige (z.B. „Graph vom …“) oder {@code null}; bei Aktionen ohne Ziel mit
     * {@code target == null}. Muss schnell sein.
     */
    default String describe(ModuleConfig config, String target) {
        return null;
    }

    /** Zusätzliche Ja/Nein-Optionen. */
    default List<Flag> flags() {
        return List.of();
    }

    /**
     * Führt die Aktion aus. Läuft nicht im UI-Thread. Abbrechen = Thread-Interrupt; die Implementierung soll ihn
     * zeitnah beachten und dann {@link ActionResult#failed} liefern oder eine Exception werfen.
     *
     * @param target gewähltes Ziel oder {@code null}
     * @param flags  Schlüssel der gesetzten {@link #flags()}
     */
    ActionResult run(ModuleConfig config, String target, Set<String> flags, Progress progress);

    /** Ja/Nein-Option einer Aktion. */
    record Flag(String key, String label) {
    }

    /** Fortschrittsmeldungen; darf aus beliebigen Threads aufgerufen werden. */
    @FunctionalInterface
    interface Progress {
        /**
         * @param message  aktueller Schritt
         * @param fraction 0..1 oder negativ, wenn unbestimmt
         */
        void update(String message, double fraction);

        Progress NONE = (message, fraction) -> { };
    }

    /** Ergebnis für die Statuszeile. */
    record ActionResult(boolean success, String message) {
        public static ActionResult ok(String message) {
            return new ActionResult(true, message);
        }

        public static ActionResult failed(String message) {
            return new ActionResult(false, message);
        }
    }
}
