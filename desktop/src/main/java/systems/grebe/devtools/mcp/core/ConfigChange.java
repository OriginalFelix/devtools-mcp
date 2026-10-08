package systems.grebe.devtools.mcp.core;

import java.util.Map;

/**
 * Merkt sich die zuletzt gesehenen Konfigurationswerte eines Moduls. Tools werden auch beim An- und Abschalten einzelner
 * Tools neu aufgebaut; Verbindungen, Sitzungen und Shells sollen aber nur bei geänderten Werten (anderer Host, anderes
 * Passwort …) verworfen werden.
 */
public final class ConfigChange {

    private Map<String, String> last;

    /**
     * @return {@code true}, wenn die Werte nicht denen des vorigen Aufrufs entsprechen (der erste Aufruf zählt als Änderung)
     */
    public synchronized boolean changed(ModuleConfig config) {
        Map<String, String> now = config.rawValues();
        if (now.equals(last)) {
            return false;
        }
        last = now;
        return true;
    }
}
