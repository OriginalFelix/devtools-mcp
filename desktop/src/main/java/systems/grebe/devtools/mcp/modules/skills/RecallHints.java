package systems.grebe.devtools.mcp.modules.skills;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.core.ToolCallListener;
import systems.grebe.devtools.mcp.core.ToolRegistry;
import systems.grebe.devtools.mcp.modules.memories.MemoriesModule;
import systems.grebe.devtools.mcp.modules.memories.MemoryBackend;
import systems.grebe.devtools.mcp.modules.memories.MemoryViews;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Bringt Skills und Memories dorthin, wo das LLM sie braucht – ohne dass es selbst daran denken muss. Hängt an
 * Tool-Ergebnisse höchstens ein paar kurze Zeilen an, und nur bei einem Treffer:
 * <ul>
 *   <li><b>Skill-Registrierung:</b> Ruft das LLM ein Tool auf, für das ein Skill registriert ist ({@code triggers},
 *   z.B. {@code ticket_get}), nennt der Server den Skill.</li>
 *   <li><b>Frühere Aktionen:</b> Steht in den Argumenten ein Bezug, zu dem es Memories gibt (z.B. {@code ABC-123}),
 *   nennt der Server diese Memories.</li>
 *   <li><b>Querverweise:</b> {@code skills_view} zeigt frühere Durchläufe des Skills, {@code skills_list} passende
 *   Memories, {@code memories_search} passende Skills.</li>
 * </ul>
 * Jeder Skill und jede Memory wird je Session nur einmal genannt; geladene Skills gar nicht mehr. Registrierungen und
 * Bezüge liegen im Speicher und werden bei jeder Änderung neu geladen – ein Tool-Aufruf ohne Treffer kostet keinen
 * Zugriff aufs Backend.
 */
@Component
public class RecallHints implements ToolCallListener {

    static final String PREFIX = "[DevTools] ";
    static final int MAX_SKILLS = 2;
    static final int MAX_MEMORIES = 3;
    static final Set<String> MEMORY_WRITES = Set.of("memories_save", "memories_update", "memories_delete");
    private static final int MAX_SESSIONS = 256;
    private static final int MAX_TOKENS = 200;
    /** Zahlen-Argumente zählen nur unter solchen Namen als Bezug (nicht limit, days, line …). */
    private static final Pattern ID_KEY = Pattern.compile("(?i).*(id|number|nr|pr|issue|key|ticket|ref).*");
    private static final Pattern SPLIT = Pattern.compile("[\\s,;()\\[\\]{}\"'<>|]+");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneId.systemDefault());
    private static final JsonMapper JSON = JsonMapper.shared();
    private static final Logger LOG = LoggerFactory.getLogger(RecallHints.class);

    /** Was in einer Client-Session schon genannt oder geladen wurde. */
    static final class Session {
        final Set<String> viewedSkills = new HashSet<>();
        final Set<String> hintedSkills = new HashSet<>();
        final Set<String> hintedReferences = new HashSet<>();
        final Set<Long> hintedMemories = new HashSet<>();
    }

    private final ObjectProvider<ToolRegistry> registry;
    private final ObjectProvider<SkillBackend> skills;
    private final ObjectProvider<MemoryBackend> memories;
    private final Map<String, Session> sessions = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Session> eldest) {
            return size() > MAX_SESSIONS;
        }
    };
    private volatile List<SkillViews.Summary> skillCache;
    /** Normalisierter Bezug → Bezug wie gespeichert (klein). */
    private volatile Map<String, String> referenceCache;

    public RecallHints(ObjectProvider<ToolRegistry> registry, ObjectProvider<SkillBackend> skills,
                       ObjectProvider<MemoryBackend> memories) {
        this.registry = registry;
        this.skills = skills;
        this.memories = memories;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        SkillBackend s = skills.getIfAvailable();
        if (s != null) {
            s.addChangeListener(() -> skillCache = null);
        }
        MemoryBackend m = memories.getIfAvailable();
        if (m != null) {
            m.addChangeListener(() -> referenceCache = null);
        }
    }

    @Override
    public String afterSuccess(ToolCall call, String result) {
        String tool = call.toolName();
        // eigene Änderungen sofort berücksichtigen – die Subscription meldet sie erst etwas später
        if (SkillReviewTracker.WRITES.contains(tool)) {
            skillCache = null;
        } else if (MEMORY_WRITES.contains(tool)) {
            referenceCache = null;
        }
        JsonNode args = parse(call.input());
        Session session = session(call.sessionId());
        boolean skillsOn = active(SkillsModule.ID, "skills_view");
        boolean memoriesOn = active(MemoriesModule.ID, "memories_view");
        List<String> lines = new ArrayList<>();
        switch (tool) {
            case "skills_view" -> {
                String name = text(args, "name");
                if (name != null && text(args, "file_path") == null) {
                    synchronized (sessions) {
                        session.viewedSkills.add(name);
                    }
                    if (memoriesOn) {
                        memoryLine("Frühere Durchläufe von " + name + ": ",
                                memories.getObject().related(null, name, MAX_MEMORIES), session, lines);
                    }
                }
            }
            case "skills_list" -> {
                String query = text(args, "query");
                if (query != null && memoriesOn) {
                    memoryLine("Passende Memories (frühere Aktionen): ",
                            memories.getObject().overview(query, null, null, MAX_MEMORIES), session, lines);
                }
            }
            case "memories_search" -> {
                String query = text(args, "query");
                if (query != null && skillsOn) {
                    skillLine("Passende Skills (Ablauf): ", matchingSkills(query), session, lines);
                }
            }
            default -> {
                if (tool.startsWith("skills_") || tool.startsWith("memories_")) {
                    break;
                }
                if (skillsOn) {
                    List<SkillViews.Summary> registered = skills().stream().filter(s -> s.triggeredBy(tool)).toList();
                    skillLine("Registrierter Skill für " + tool + ": ", registered, session, lines);
                }
                if (memoriesOn) {
                    referenceLine(args, session, lines);
                }
            }
        }
        if (lines.isEmpty()) {
            return result;
        }
        return result + "\n\n---\n" + PREFIX + String.join("\n" + PREFIX, lines);
    }

    // ------------------------------------------------------------------ Skills

    private void skillLine(String intro, List<SkillViews.Summary> candidates, Session session, List<String> lines) {
        List<SkillViews.Summary> fresh;
        synchronized (sessions) {
            fresh = candidates.stream()
                    .filter(s -> !session.viewedSkills.contains(s.name()) && !session.hintedSkills.contains(s.name()))
                    .limit(MAX_SKILLS).toList();
            fresh.forEach(s -> session.hintedSkills.add(s.name()));
        }
        if (!fresh.isEmpty()) {
            lines.add(intro + String.join("; ", fresh.stream()
                    .map(s -> s.name() + " – " + shorten(s.description(), 120)).toList())
                    + " (per skills_view ladbar)");
        }
    }

    /** Skills, deren Name, Beschreibung oder Tags Suchbegriffe enthalten – meiste Treffer zuerst. */
    List<SkillViews.Summary> matchingSkills(String query) {
        List<String> terms = List.of(query.toLowerCase(Locale.ROOT).split("\\s+"));
        Map<SkillViews.Summary, Integer> score = new HashMap<>();
        for (SkillViews.Summary s : skills()) {
            String hay = (s.name() + " " + s.description() + " " + String.join(" ", s.tags())).toLowerCase(Locale.ROOT);
            int n = (int) terms.stream().filter(t -> !t.isBlank() && hay.contains(t)).count();
            if (n > 0) {
                score.put(s, n);
            }
        }
        return score.entrySet().stream()
                .sorted(Map.Entry.<SkillViews.Summary, Integer>comparingByValue().reversed()
                        .thenComparing(e -> e.getKey().name()))
                .map(Map.Entry::getKey).toList();
    }

    private List<SkillViews.Summary> skills() {
        List<SkillViews.Summary> c = skillCache;
        if (c == null) {
            try {
                c = List.copyOf(skills.getObject().overview());
            } catch (RuntimeException e) {
                LOG.debug("Skills für Hinweise nicht geladen: {}", e.getMessage());
                return List.of();
            }
            skillCache = c;
        }
        return c;
    }

    // ------------------------------------------------------------------ Memories

    private void referenceLine(JsonNode args, Session session, List<String> lines) {
        Map<String, String> known = references();
        if (known.isEmpty() || args == null) {
            return;
        }
        LinkedHashSet<String> hits = new LinkedHashSet<>();
        for (String token : tokens(args)) {
            String ref = known.get(token);
            if (ref != null) {
                hits.add(ref);
            }
        }
        synchronized (sessions) {
            hits.removeAll(session.hintedReferences);
            session.hintedReferences.addAll(hits);
        }
        if (!hits.isEmpty()) {
            memoryLine("Frühere Aktionen zu " + String.join(", ", hits) + ": ",
                    memories.getObject().related(List.copyOf(hits), null, MAX_MEMORIES), session, lines);
        }
    }

    private void memoryLine(String intro, List<MemoryViews.Entry> candidates, Session session, List<String> lines) {
        List<MemoryViews.Entry> fresh;
        synchronized (sessions) {
            fresh = candidates.stream().filter(m -> !session.hintedMemories.contains(m.id()))
                    .limit(MAX_MEMORIES).toList();
            fresh.forEach(m -> session.hintedMemories.add(m.id()));
        }
        if (!fresh.isEmpty()) {
            lines.add(intro + String.join("; ", fresh.stream()
                    .map(m -> "#" + m.id() + " " + DAY.format(m.createdAt()) + " " + shorten(m.title(), 100)).toList())
                    + " (per memories_view ladbar)");
        }
    }

    private Map<String, String> references() {
        Map<String, String> c = referenceCache;
        if (c == null) {
            try {
                Map<String, String> m = new HashMap<>();
                memories.getObject().references().forEach(r -> m.putIfAbsent(normalize(r), r));
                m.remove("");
                c = Map.copyOf(m);
            } catch (RuntimeException e) {
                LOG.debug("Memory-Bezüge für Hinweise nicht geladen: {}", e.getMessage());
                return Map.of();
            }
            referenceCache = c;
        }
        return c;
    }

    /** Kandidaten für Bezüge aus den Argumenten: ganze Texte und ihre Wörter, Zahlen nur unter ID-artigen Namen. */
    static Set<String> tokens(JsonNode args) {
        Set<String> out = new LinkedHashSet<>();
        collect(null, args, out);
        return out;
    }

    private static void collect(String key, JsonNode node, Set<String> out) {
        if (out.size() >= MAX_TOKENS || node == null) {
            return;
        }
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                collect(e.getKey(), e.getValue(), out);
            }
        } else if (node.isArray()) {
            for (JsonNode n : node) {
                collect(key, n, out);
            }
        } else if (node.isString()) {
            String v = node.asString();
            if (v.length() <= 200) {
                out.add(normalize(v));
            }
            for (String t : SPLIT.split(v.length() > 2_000 ? v.substring(0, 2_000) : v)) {
                if (out.size() >= MAX_TOKENS) {
                    break;
                }
                String n = normalize(t);
                if (n.length() >= 2) {
                    out.add(n);
                }
            }
        } else if (node.isIntegralNumber() && key != null && ID_KEY.matcher(key).matches()) {
            out.add(node.asString());
        }
    }

    /** Klein, ohne führendes {@code #} und abschließende Satzzeichen. */
    static String normalize(String s) {
        String v = s.strip().toLowerCase(Locale.ROOT);
        while (v.startsWith("#")) {
            v = v.substring(1);
        }
        while (!v.isEmpty() && ".:!?".indexOf(v.charAt(v.length() - 1)) >= 0) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }

    // ------------------------------------------------------------------ intern

    boolean active(String moduleId, String tool) {
        ToolRegistry r = registry.getIfAvailable();
        return r != null && r.isToolActive(moduleId, tool);
    }

    private Session session(String sessionId) {
        synchronized (sessions) {
            return sessions.computeIfAbsent(sessionId == null || sessionId.isBlank() ? "_" : sessionId,
                    k -> new Session());
        }
    }

    private static JsonNode parse(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        try {
            return JSON.readTree(input);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String text(JsonNode args, String field) {
        if (args == null) {
            return null;
        }
        JsonNode n = args.path(field);
        return n.isString() && !n.asString().isBlank() ? n.asString().trim() : null;
    }

    static String shorten(String text, int max) {
        String t = text == null ? "" : text.strip().replaceAll("\\s+", " ");
        return t.length() <= max ? t : t.substring(0, max - 1).stripTrailing() + "…";
    }

    /** Für Tests: Caches leeren. */
    void invalidate() {
        skillCache = null;
        referenceCache = null;
    }
}
