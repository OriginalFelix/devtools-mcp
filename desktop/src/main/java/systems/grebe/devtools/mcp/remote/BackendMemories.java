package systems.grebe.devtools.mcp.remote;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.modules.memories.MemoryBackend;
import systems.grebe.devtools.mcp.modules.memories.MemoryViews;

/**
 * Memories im Backend über GraphQL – eingebettet oder auf dem Team-Server, wie {@link BackendSkills}. Änderungen (auch
 * von anderen Desktop-Apps) meldet die Subscription {@code memoriesChanged}.
 */
@Primary // eingebettet ist auch der MemoryService des Backends ein MemoryBackend – die App geht immer über GraphQL
@Component
public class BackendMemories implements MemoryBackend {

    private static final String ENTRY = "id title content project skill reference tags createdAt updatedAt";

    private final BackendConnection backend;

    public BackendMemories(BackendConnection backend) {
        this.backend = backend;
    }

    @Override
    public void addChangeListener(Runnable listener) {
        backend.addMemoryListener(listener);
    }

    @Override
    public List<MemoryViews.Entry> overview(String query, String project, String skill, int limit) {
        return backend.queryList("""
                query($query: String, $project: String, $skill: String, $limit: Int) { memories(query: $query, \
                project: $project, skill: $skill, limit: $limit) { %s } }""".formatted(ENTRY),
                args("query", query, "project", project, "skill", skill, "limit", limit), "memories",
                MemoryViews.Entry.class);
    }

    @Override
    public Optional<MemoryViews.Entry> details(long id) {
        return Optional.ofNullable(backend.query("query($id: Int!) { memory(id: $id) { " + ENTRY + " } }",
                Map.of("id", id), "memory", MemoryViews.Entry.class));
    }

    @Override
    public int count() {
        Integer n = backend.query("{ memoryCount }", Map.of(), "memoryCount", Integer.class);
        return n == null ? 0 : n;
    }

    @Override
    public Set<String> references() {
        return Set.copyOf(backend.queryList("{ memoryReferences }", "memoryReferences", String.class));
    }

    @Override
    public List<MemoryViews.Entry> related(List<String> references, String skill, int limit) {
        return backend.queryList("""
                query($refs: [String!], $skill: String, $limit: Int) { relatedMemories(references: $refs, \
                skill: $skill, limit: $limit) { id title project skill reference tags createdAt updatedAt } }""",
                args("refs", references, "skill", skill, "limit", limit), "relatedMemories", MemoryViews.Entry.class);
    }

    @Override
    public String search(String query, String project, String skill, String tag, Integer days, Integer limit) {
        return text("""
                query($query: String, $project: String, $skill: String, $tag: String, $days: Int, $limit: Int) { \
                memorySearch(query: $query, project: $project, skill: $skill, tag: $tag, days: $days, \
                limit: $limit) }""", "memorySearch",
                args("query", query, "project", project, "skill", skill, "tag", tag, "days", days, "limit", limit));
    }

    @Override
    public String view(long id) {
        return text("query($id: Int!) { memoryView(id: $id) }", "memoryView", args("id", id));
    }

    @Override
    public String save(String title, String content, String project, String skill, String reference,
                       List<String> tags, int maxContentChars) {
        return text("""
                mutation($title: String!, $content: String!, $project: String, $skill: String, $reference: String, \
                $tags: [String!], $max: Int) { saveMemory(title: $title, content: $content, project: $project, \
                skill: $skill, reference: $reference, tags: $tags, maxContentChars: $max) }""", "saveMemory",
                args("title", title, "content", content, "project", project, "skill", skill, "reference", reference,
                        "tags", tags, "max", maxContentChars));
    }

    @Override
    public String update(long id, String title, String content, String append, String project, String skill,
                         String reference, List<String> tags, int maxContentChars) {
        return text("""
                mutation($id: Int!, $title: String, $content: String, $append: String, $project: String, \
                $skill: String, $reference: String, $tags: [String!], $max: Int) { updateMemory(id: $id, \
                title: $title, content: $content, append: $append, project: $project, skill: $skill, \
                reference: $reference, tags: $tags, maxContentChars: $max) }""", "updateMemory",
                args("id", id, "title", title, "content", content, "append", append, "project", project,
                        "skill", skill, "reference", reference, "tags", tags, "max", maxContentChars));
    }

    @Override
    public String delete(long id) {
        return text("mutation($id: Int!) { deleteMemory(id: $id) }", "deleteMemory", args("id", id));
    }

    private String text(String document, String field, Map<String, Object> args) {
        return backend.query(document, args, field, String.class);
    }

    /** Variablen; {@code null}-Werte bleiben weg. */
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
