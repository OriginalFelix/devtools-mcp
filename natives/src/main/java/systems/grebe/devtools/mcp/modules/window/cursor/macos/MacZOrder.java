package systems.grebe.devtools.mcp.modules.window.cursor.macos;

import com.sun.jna.Pointer;

import static systems.grebe.devtools.mcp.modules.window.cursor.macos.MacNatives.cls;
import static systems.grebe.devtools.mcp.modules.window.cursor.macos.MacNatives.send;

/**
 * Ordnet ein eigenes Fenster dieser App in der Fensterreihenfolge direkt über ein fremdes Fenster – auf dieselbe
 * Ebene statt über alle Fenster ({@code -[NSWindow orderWindow:relativeTo:]} mit der globalen Fensternummer des Ziels).
 * Beide Fenster müssen auf derselben Fensterebene liegen (normale Fenster, Level 0).
 */
public final class MacZOrder {

    private static final long NS_WINDOW_ABOVE = 1;
    private static final long NS_NORMAL_WINDOW_LEVEL = 0;

    private MacZOrder() {
    }

    /**
     * Legt das eigene Fenster {@code ownWindowNumber} direkt über {@code targetWindowNumber}; asynchron auf dem
     * AppKit-Hauptthread (kehrt sofort zurück).
     */
    public static void stackAbove(long ownWindowNumber, long targetWindowNumber) {
        MacNatives.submit(() -> {
            Pointer app = send(cls("NSApplication"), "sharedApplication");
            Pointer own = send(app, "windowWithWindowNumber:", ownWindowNumber);
            if (own != null) {
                // Utility-Fenster schweben sonst über allen normalen Fenstern; geordnet wird nur innerhalb einer Ebene
                send(own, "setLevel:", NS_NORMAL_WINDOW_LEVEL);
                send(own, "orderWindow:relativeTo:", NS_WINDOW_ABOVE, targetWindowNumber);
            }
            return null;
        });
    }

    /**
     * Lässt Mausereignisse durch das eigene Fenster an das Fenster darunter gehen ({@code setIgnoresMouseEvents:}) –
     * asynchron auf dem AppKit-Hauptthread.
     */
    public static void ignoreMouse(long ownWindowNumber) {
        MacNatives.submit(() -> {
            Pointer app = send(cls("NSApplication"), "sharedApplication");
            Pointer own = send(app, "windowWithWindowNumber:", ownWindowNumber);
            if (own != null) {
                send(own, "setIgnoresMouseEvents:", 1L);
            }
            return null;
        });
    }
}
