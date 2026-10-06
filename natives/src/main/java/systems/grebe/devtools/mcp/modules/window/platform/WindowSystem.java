package systems.grebe.devtools.mcp.modules.window.platform;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Zugriff auf die Fenster des Betriebssystems: welche Top-Level-Fenster es gibt, zu welchem Prozess sie gehören,
 * welches im Vordergrund liegt, und Aktivieren. Eingaben selbst laufen über {@code java.awt.Robot}.
 *
 * <p>Implementierungen rufen die nativen APIs per FFM auf und sind threadsicher.
 */
public interface WindowSystem {

    /** Name für Ausgaben, z.B. {@code Windows (user32)}. */
    String name();

    /** Warum das System hier nicht nutzbar ist; leer, wenn es nutzbar ist. */
    Optional<String> unsupportedReason();

    /** Alle sichtbaren Top-Level-Fenster aller Prozesse, vorderstes zuerst, soweit das System das liefert. */
    List<NativeWindow> windows();

    /** Das Fenster mit dieser ID, neu ermittelt; leer, wenn es nicht (mehr) existiert oder unsichtbar ist. */
    default Optional<NativeWindow> window(long id) {
        return windows().stream().filter(w -> w.id() == id).findFirst();
    }

    /** ID des Fensters im Vordergrund, sofern bekannt. */
    OptionalLong foreground();

    /** Holt das Fenster in den Vordergrund (stellt es bei Bedarf wieder her). Ob es geklappt hat, prüft der Aufrufer. */
    void activate(long id);

    /**
     * Bild des Fensters, ohne es nach vorn zu holen – auch wenn andere Fenster darüber liegen. Leer, wenn das System
     * das nicht kann (dann bleibt nur der Bildschirmausschnitt).
     */
    default Optional<java.awt.image.BufferedImage> captureInBackground(NativeWindow window) {
        return Optional.empty();
    }

    /**
     * Ob {@link #stackAbove} unterstützt wird. Sonst liegen Rahmen und Hinweis über allen Fenstern (immer im
     * Vordergrund).
     */
    default boolean canStackAbove() {
        return false;
    }

    /**
     * Legt eigene Anzeige-Fenster (alle Teile eines Rahmens) in der Z-Reihenfolge direkt über {@code target} – auf
     * dieselbe Ebene: Fenster, die über dem Ziel liegen, verdecken auch die Anzeige. Liegen sie schon dort, bleibt
     * alles unverändert. Nur auf dem EDT aufrufen; regelmäßig nachführen.
     *
     * @return ob die Fenster jetzt auf der Ebene des Ziels liegen; {@code false}, wenn das (noch) nicht feststeht –
     *         z.B. weil ein Fenster erst auf dem Bildschirm sein muss
     */
    default boolean stackAbove(List<java.awt.Window> overlays, NativeWindow target) {
        return false;
    }

    /**
     * Macht ein eigenes Anzeige-Fenster (Rahmen, Hinweis) durchklickbar: Maus-Eingaben gehen an das Fenster darunter –
     * ein Hinweis über den Titelleisten-Knöpfen eines maximierten Fensters darf sie nicht verdecken. Nur auf dem EDT,
     * nach {@code addNotify}; darf wiederholt aufgerufen werden (wirkt nur, wo nötig).
     */
    default void passThrough(java.awt.Window overlay) {
    }

    /** Wirft mit verständlicher Meldung, wenn Eingaben per Robot nicht ankommen würden (fehlende Berechtigung). */
    default void requireInputPermission() {
    }

    /** Wirft mit verständlicher Meldung, wenn Bildschirmaufnahmen nicht erlaubt sind. */
    default void requireCapturePermission() {
    }

    /** Hinweise zu fehlenden Berechtigungen o.ä. für {@code window_list} und den Verbindungstest. */
    default List<String> warnings() {
        return List.of();
    }

    /** Das Fenstersystem dieses Rechners (einmal erzeugt). */
    static WindowSystem current() {
        return Holder.INSTANCE;
    }

    final class Holder {
        static final WindowSystem INSTANCE = create();

        private Holder() {
        }

        private static WindowSystem create() {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            try {
                if (os.contains("win")) {
                    return new Win32WindowSystem();
                }
                if (os.contains("mac")) {
                    return new MacWindowSystem();
                }
                if (os.contains("linux") || os.contains("bsd")) {
                    if (System.getenv("DISPLAY") == null || System.getenv("DISPLAY").isBlank()) {
                        return new UnsupportedWindowSystem(System.getenv("WAYLAND_DISPLAY") != null
                                ? "Wayland ohne X-Server (XWayland) wird nicht unterstützt – Fenster anderer Anwendungen "
                                + "sind dort für Programme nicht zugänglich."
                                : "Kein X-Server (Umgebungsvariable DISPLAY fehlt).");
                    }
                    return new X11WindowSystem();
                }
                return new UnsupportedWindowSystem("Betriebssystem " + os + " wird nicht unterstützt.");
            } catch (RuntimeException | LinkageError e) {
                return new UnsupportedWindowSystem("Native Fenster-API nicht verfügbar: " + e.getMessage());
            }
        }
    }
}
