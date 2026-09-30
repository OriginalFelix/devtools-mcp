package systems.grebe.devtools.mcp.modules.skills;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.remote.TeamServer;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Skills zentral auf dem Team-Server ({@code /api/skills/**}): Der Server arbeitet mit dem {@link SkillService} als
 * der per Desktop-Token angemeldete Benutzer (Konto-E-Mail); fachliche Fehler kommen als
 * {@link IllegalArgumentException} zurück wie lokal.
 */
@Component
public class RemoteSkillBackend implements SkillBackend {

    private static final TypeReference<List<SkillViews.Summary>> SUMMARIES = new TypeReference<>() {
    };

    private final TeamServer team;
    private final JsonMapper json = JsonMapper.builder().build();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public RemoteSkillBackend(TeamServer team) {
        this.team = team;
    }

    @Override
    public void addChangeListener(Runnable listener) {
        listeners.add(listener);
    }

    @Override
    public List<SkillViews.Summary> overview() {
        return json.treeToValue(team.client().call("GET", "/skills", null), SUMMARIES);
    }

    @Override
    public Optional<SkillViews.Details> details(String name) {
        JsonNode n = team.client().call("POST", "/skills/details", Map.of("name", name));
        return n == null || n.isNull() || n.isMissingNode() ? Optional.empty()
                : Optional.of(json.treeToValue(n, SkillViews.Details.class));
    }

    @Override
    public String publish(String name) {
        return write("publish", args("name", name));
    }

    @Override
    public String unpublish(String name) {
        return write("unpublish", args("name", name));
    }

    @Override
    public String list(String query, String category) {
        return text("list", args("query", query, "category", category));
    }

    @Override
    public String view(String name, String filePath) {
        return text("view", args("name", name, "filePath", filePath));
    }

    @Override
    public String history(String name, Integer revision) {
        return text("history", args("name", name, "revision", revision));
    }

    @Override
    public int visibleCount() {
        return team.client().call("POST", "/skills/count", Map.of()).path("count").asInt();
    }

    @Override
    public String create(String name, String description, String content, String category, List<String> tags,
                         int maxContentChars) {
        return write("create", args("name", name, "description", description, "content", content,
                "category", category, "tags", tags, "maxContentChars", maxContentChars));
    }

    @Override
    public String update(String name, String description, String content, String category, List<String> tags,
                         String note, Integer expectedRevision, int maxContentChars) {
        return write("update", args("name", name, "description", description, "content", content,
                "category", category, "tags", tags, "note", note, "expectedRevision", expectedRevision,
                "maxContentChars", maxContentChars));
    }

    @Override
    public String patch(String name, String oldString, String newString, Boolean replaceAll, String filePath,
                        String note, Integer expectedRevision, int maxContentChars) {
        return write("patch", args("name", name, "oldString", oldString, "newString", newString,
                "replaceAll", replaceAll, "filePath", filePath, "note", note, "expectedRevision", expectedRevision,
                "maxContentChars", maxContentChars));
    }

    @Override
    public String writeFile(String name, String filePath, String content, String note, int maxContentChars) {
        return write("writeFile", args("name", name, "filePath", filePath, "content", content, "note", note,
                "maxContentChars", maxContentChars));
    }

    @Override
    public String removeFile(String name, String filePath, String note) {
        return write("removeFile", args("name", name, "filePath", filePath, "note", note));
    }

    @Override
    public String delete(String name) {
        return write("delete", args("name", name));
    }

    private String text(String op, Map<String, Object> args) {
        return team.client().call("POST", "/skills/" + op, args).path("text").asString();
    }

    private String write(String op, Map<String, Object> args) {
        String result = text(op, args);
        listeners.forEach(Runnable::run);
        return result;
    }

    /** Argumente als Map; {@code null}-Werte bleiben weg. */
    private static Map<String, Object> args(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            if (kv[i + 1] != null) {
                m.put((String) kv[i], kv[i + 1]);
            }
        }
        return m;
    }
}
