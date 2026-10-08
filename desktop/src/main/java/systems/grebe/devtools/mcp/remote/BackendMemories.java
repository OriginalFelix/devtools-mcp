package systems.grebe.devtools.mcp.remote;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.api.MediaTypes;
import systems.grebe.devtools.mcp.modules.memories.MemoryBackend;
import systems.grebe.devtools.mcp.modules.memories.MemoryViews;

/**
 * Memories im Backend über GraphQL – eingebettet oder auf dem Team-Server, wie {@link BackendSkills}. Änderungen (auch
 * von anderen Desktop-Apps) meldet die Subscription {@code memoriesChanged}.
 */
@Primary // eingebettet ist auch der MemoryService des Backends ein MemoryBackend – die App geht immer über GraphQL
@Component
public class BackendMemories implements MemoryBackend {

    private static final String FILE = "path size mediaType blob updatedAt";
    private static final String ENTRY = "id title content project skill reference tags type createdAt updatedAt "
            + "files { " + FILE + " }";

    private final BackendConnection backend;
    private final BackendFiles files;

    public BackendMemories(BackendConnection backend, BackendFiles files) {
        this.backend = backend;
        this.files = files;
    }

    @Override
    public void addChangeListener(Runnable listener) {
        backend.addMemoryListener(listener);
    }

    @Override
    public List<MemoryViews.Entry> overview(String query, String project, String skill, int limit) {
        if (!backend.signedIn()) {
            return List.of();
        }
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
        if (!backend.signedIn()) {
            return 0;
        }
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
                skill: $skill, limit: $limit) { id title project skill reference tags type createdAt updatedAt } }""",
                args("refs", references, "skill", skill, "limit", limit), "relatedMemories", MemoryViews.Entry.class);
    }

    @Override
    public String search(String query, String project, String skill, String tag, MemoryViews.Type type,
                         Integer days, Integer limit) {
        return text("""
                query($query: String, $project: String, $skill: String, $tag: String, $type: MemoryType, $days: Int, \
                $limit: Int) { memorySearch(query: $query, project: $project, skill: $skill, tag: $tag, type: $type, \
                days: $days, limit: $limit) }""", "memorySearch",
                args("query", query, "project", project, "skill", skill, "tag", tag, "type", name(type),
                        "days", days, "limit", limit));
    }

    @Override
    public String view(long id) {
        return text("query($id: Int!) { memoryView(id: $id) }", "memoryView", args("id", id));
    }

    @Override
    public String save(String title, String content, MemoryViews.Type type, String project, String skill,
                       String reference, List<String> tags, int maxContentChars) {
        return text("""
                mutation($title: String!, $content: String!, $project: String, $skill: String, $reference: String, \
                $tags: [String!], $type: MemoryType, $max: Int) { saveMemory(title: $title, content: $content, \
                project: $project, skill: $skill, reference: $reference, tags: $tags, type: $type, \
                maxContentChars: $max) }""", "saveMemory",
                args("title", title, "content", content, "project", project, "skill", skill, "reference", reference,
                        "tags", tags, "type", name(type), "max", maxContentChars));
    }

    @Override
    public String update(long id, String title, String content, String append, MemoryViews.Type type,
                         String project, String skill, String reference, List<String> tags, boolean temporaryOnly,
                         int maxContentChars) {
        return text("""
                mutation($id: Int!, $title: String, $content: String, $append: String, $project: String, \
                $skill: String, $reference: String, $tags: [String!], $type: MemoryType, $tempOnly: Boolean, \
                $max: Int) { updateMemory(id: $id, title: $title, content: $content, append: $append, \
                project: $project, skill: $skill, reference: $reference, tags: $tags, type: $type, \
                temporaryOnly: $tempOnly, maxContentChars: $max) }""", "updateMemory",
                args("id", id, "title", title, "content", content, "append", append, "project", project,
                        "skill", skill, "reference", reference, "tags", tags, "type", name(type),
                        "tempOnly", temporaryOnly, "max", maxContentChars));
    }

    @Override
    public String delete(long id, boolean temporaryOnly) {
        return text("mutation($id: Int!, $tempOnly: Boolean) { deleteMemory(id: $id, temporaryOnly: $tempOnly) }",
                "deleteMemory", args("id", id, "tempOnly", temporaryOnly));
    }

    /** Inhalt über {@code /blobs} hochladen, dann per GraphQL anhängen. */
    @Override
    public String attachFile(long id, String filePath, Path source, String mediaType, boolean temporaryOnly) {
        String path = filePath == null || filePath.isBlank() ? fileName(source) : filePath;
        String blob = files.upload(source);
        return text("""
                mutation($id: Int!, $file: String!, $blob: String!, $type: String, $tempOnly: Boolean) { \
                attachMemoryFile(id: $id, filePath: $file, blob: $blob, mediaType: $type, temporaryOnly: $tempOnly) }""",
                "attachMemoryFile", args("id", id, "file", path, "blob", blob, "type", mediaType,
                        "tempOnly", temporaryOnly));
    }

    @Override
    public String removeFile(long id, String filePath, boolean temporaryOnly) {
        return text("""
                mutation($id: Int!, $file: String!, $tempOnly: Boolean) { removeMemoryFile(id: $id, filePath: $file, \
                temporaryOnly: $tempOnly) }""", "removeMemoryFile", args("id", id, "file", filePath,
                "tempOnly", temporaryOnly));
    }

    @Override
    public Optional<MemoryViews.File> file(long id, String filePath) {
        return Optional.ofNullable(backend.query("query($id: Int!, $f: String!) { memoryFile(id: $id, filePath: $f) { "
                + FILE + " } }", Map.of("id", id, "f", filePath), "memoryFile", MemoryViews.File.class));
    }

    @Override
    public String viewFile(long id, String filePath) {
        return text("query($id: Int!, $f: String!) { memoryFileView(id: $id, filePath: $f) }", "memoryFileView",
                args("id", id, "f", filePath));
    }

    @Override
    public String exportFile(long id, String filePath, Path target) {
        MemoryViews.File f = file(id, filePath).orElseThrow(() -> new IllegalArgumentException("Memory #" + id
                + " hat keine Datei '" + filePath + "' – memories_view(id) zeigt die angehängten Dateien."));
        files.download(f.blob(), target);
        return "'" + f.path() + "' aus Memory #" + id + " gespeichert: " + target + " (" + f.mediaType() + ", "
                + MediaTypes.size(f.size()) + ").";
    }

    /** Dateiname als Pfad in der Memory (wie im Backend); unzulässige Zeichen werden zu {@code _}. */
    static String fileName(Path source) {
        String n = source.getFileName() == null ? "" : source.getFileName().toString();
        n = n.replaceAll("[^A-Za-z0-9._-]", "_");
        return n.isEmpty() || n.chars().allMatch(c -> c == '.') ? "datei" : n;
    }

    private static String name(MemoryViews.Type type) {
        return type == null ? null : type.name();
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
