package systems.grebe.devtools.mcp.modules.debug;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;


/**
 * Hält die offenen Debug-Sitzungen eines Benutzers/Profils über Konfigurationsänderungen hinweg; liegt im
 * {@link systems.grebe.devtools.mcp.core.ToolScope} und trennt beim Schließen des Scopes (Profilwechsel, Beenden).
 */
public class DebugSessions implements AutoCloseable {

    private final Map<String, DebugSession> sessions = new ConcurrentHashMap<>();

    public DebugSession add(DebugSession s) {
        sessions.put(s.id(), s);
        return s;
    }

    public List<DebugSession> all() {
        return List.copyOf(sessions.values());
    }

    /** Sitzung per ID; leer = die einzige offene. */
    public DebugSession get(String id) {
        if (id == null || id.isBlank()) {
            if (sessions.size() == 1) {
                return sessions.values().iterator().next();
            }
            throw new IllegalArgumentException(sessions.isEmpty()
                    ? "Keine Debug-Sitzung offen – zuerst debug_attach."
                    : "Mehrere Sitzungen offen, bitte session angeben: " + sessions.keySet());
        }
        return Optional.ofNullable(sessions.get(id))
                .orElseThrow(() -> new IllegalArgumentException("Sitzung '" + id + "' nicht gefunden: " + sessions.keySet()));
    }

    public boolean close(String id) {
        DebugSession s = sessions.remove(id);
        if (s == null) {
            return false;
        }
        s.close();
        return true;
    }

    @Override
    public void close() {
        closeAll();
    }

    public void closeAll() {
        sessions.values().forEach(DebugSession::close);
        sessions.clear();
    }
}
