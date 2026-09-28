package systems.grebe.devtools.mcp.modules.skills;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.core.ToolCallListener;
import systems.grebe.devtools.mcp.core.ToolRegistry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Selbstverbesserung wie bei Hermes' Skill-Nudge: zählt je MCP-Session die Tool-Aufrufe seit der letzten Skill-Pflege
 * und hängt nach {@code reviewNudgeInterval} Aufrufen einen Hinweis an das Tool-Ergebnis, einen Skill-Review mit
 * {@code skills_review} zu machen. Merkt sich außerdem, welche Skills in der Session geladen und geändert wurden –
 * das nutzt {@link SkillReview}.
 *
 * <p>Ein MCP-Server kann das Modell nicht selbst anstoßen; Tool-Ergebnisse sind der einzige Kanal, der bei jedem Client
 * sicher beim LLM ankommt.
 */
@Component
public class SkillReviewTracker implements ToolCallListener {

    /** Tools, die als Skill-Pflege zählen und den Zähler zurücksetzen. */
    static final Set<String> MAINTENANCE = Set.of("skills_create", "skills_patch", "skills_update",
            "skills_write_file", "skills_remove_file", "skills_delete", "skills_review");
    static final Set<String> WRITES = Set.of("skills_create", "skills_patch", "skills_update",
            "skills_write_file", "skills_remove_file", "skills_delete");
    static final String NO_SESSION = "_";
    private static final int MAX_SESSIONS = 256;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Zustand einer Client-Session. */
    public static final class SessionState {
        int callsSinceMaintenance;
        int totalCalls;
        final Set<String> viewed = new LinkedHashSet<>();
        final Set<String> changed = new LinkedHashSet<>();
        Instant lastSeen = Instant.now();

        public int callsSinceMaintenance() {
            return callsSinceMaintenance;
        }

        public int totalCalls() {
            return totalCalls;
        }

        public List<String> viewed() {
            return List.copyOf(viewed);
        }

        public List<String> changed() {
            return List.copyOf(changed);
        }
    }

    private final ObjectProvider<ToolRegistry> registry;
    private final Map<String, SessionState> sessions = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, SessionState> eldest) {
            return size() > MAX_SESSIONS;
        }
    };

    public SkillReviewTracker(ObjectProvider<ToolRegistry> registry) {
        this.registry = registry;
    }

    @Override
    public String afterSuccess(ToolCall call, String result) {
        String tool = call.toolName();
        String skill = tool.startsWith("skills_") ? skillName(call.input()) : null;
        int interval = nudgeInterval();
        int calls;
        synchronized (sessions) {
            SessionState s = sessions.computeIfAbsent(key(call.sessionId()), k -> new SessionState());
            s.lastSeen = Instant.now();
            s.totalCalls++;
            if (tool.equals("skills_view") && skill != null) {
                s.viewed.add(skill);
            }
            if (WRITES.contains(tool) && skill != null) {
                s.changed.add(skill);
            }
            if (MAINTENANCE.contains(tool)) {
                s.callsSinceMaintenance = 0;
                return result;
            }
            s.callsSinceMaintenance++;
            if (interval <= 0 || s.callsSinceMaintenance < interval || !reviewAvailable()) {
                return result;
            }
            calls = s.callsSinceMaintenance;
            // wie Hermes: nach dem Hinweis neu zählen, damit er nicht bei jedem Aufruf wiederkommt
            s.callsSinceMaintenance = 0;
        }
        return result + nudge(calls);
    }

    static String nudge(int calls) {
        return "\n\n---\n[DevTools-Skills] " + calls + " Tool-Aufrufe seit der letzten Skill-Pflege. Ist die aktuelle "
                + "Aufgabe (oder ein Teilschritt) abgeschlossen, jetzt skills_review aufrufen und Gelerntes mit "
                + "skills_patch oder skills_create festhalten. Läuft die Aufgabe noch, zuerst fertig machen.";
    }

    /** Zustand der Session (Kopie der Zähler, nie {@code null}). */
    public SessionState state(String sessionId) {
        synchronized (sessions) {
            SessionState s = sessions.get(key(sessionId));
            SessionState copy = new SessionState();
            if (s != null) {
                copy.callsSinceMaintenance = s.callsSinceMaintenance;
                copy.totalCalls = s.totalCalls;
                copy.viewed.addAll(s.viewed);
                copy.changed.addAll(s.changed);
                copy.lastSeen = s.lastSeen;
            }
            return copy;
        }
    }

    int nudgeInterval() {
        ToolRegistry r = registry.getIfAvailable();
        if (r == null) {
            return 0;
        }
        return r.config(SkillsModule.ID).getInt(SkillsModule.REVIEW_INTERVAL, 10);
    }

    /** Nur erinnern, wenn das LLM auch reagieren kann (Review-Tool und Schreib-Tools aktiv). */
    boolean reviewAvailable() {
        ToolRegistry r = registry.getIfAvailable();
        return r != null && r.isToolActive(SkillsModule.ID, "skills_review")
                && r.isToolActive(SkillsModule.ID, "skills_patch");
    }

    private static String key(String sessionId) {
        return sessionId == null || sessionId.isBlank() ? NO_SESSION : sessionId;
    }

    static String skillName(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        try {
            JsonNode name = JSON.readTree(input).path("name");
            return name.isString() && !name.asString().isBlank() ? name.asString().trim() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
