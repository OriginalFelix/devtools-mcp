package systems.grebe.devtools.mcp.modules.window.platform;

import java.awt.Rectangle;

/**
 * Ein sichtbares Top-Level-Fenster.
 *
 * @param id       natives Handle (HWND, CGWindowID, X11 Window)
 * @param pid      Prozess, dem das Fenster gehört
 * @param threadId UI-Thread des Fensters; nur unter Windows bekannt, sonst {@code null}
 * @param bounds   Grenzen in Java-Bildschirmkoordinaten (User-Space, wie {@link java.awt.Robot})
 */
public record NativeWindow(long id, long pid, Long threadId, String title, Rectangle bounds, boolean minimized) {

    /** Fenster-ID, wie sie die Tools ausgeben ({@code 0x…}). */
    public String hexId() {
        return "0x" + Long.toHexString(id).toUpperCase(java.util.Locale.ROOT);
    }

    public static long parseId(String value) {
        String v = value.strip();
        if (v.startsWith("0x") || v.startsWith("0X")) {
            return Long.parseUnsignedLong(v.substring(2), 16);
        }
        return Long.parseLong(v);
    }
}
