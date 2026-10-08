package systems.grebe.devtools.mcp.remote;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.api.MediaTypes;
import systems.grebe.devtools.mcp.modules.skills.SkillBackend;
import systems.grebe.devtools.mcp.modules.skills.SkillViews;

/**
 * Skills im Backend über GraphQL – eingebettet oder auf dem Team-Server. Das Backend arbeitet als der angemeldete
 * Benutzer (Konto-E-Mail); fachliche Fehler kommen als {@link IllegalArgumentException} wie früher lokal.
 * Änderungen (auch von anderen Desktop-Apps) meldet die Subscription {@code skillsChanged}.
 */
@Primary // eingebettet ist auch der SkillService des Backends ein SkillBackend – die App geht immer über GraphQL
@Component
public class BackendSkills implements SkillBackend {

    private static final String SUMMARY = "name description category tags revision useCount lastUsedAt updatedAt "
            + "fileCount scope templateRevision currentTemplateRevision triggers";

    private static final String FILE = "path content updatedAt size mediaType blob";

    private final BackendConnection backend;
    private final BackendFiles files;

    public BackendSkills(BackendConnection backend, BackendFiles files) {
        this.backend = backend;
        this.files = files;
    }

    @Override
    public void addChangeListener(Runnable listener) {
        backend.addSkillListener(listener);
    }

    @Override
    public List<SkillViews.Summary> overview() {
        if (!backend.signedIn()) {
            return List.of();
        }
        return backend.queryList("{ skills { " + SUMMARY + " } }", "skills", SkillViews.Summary.class);
    }

    @Override
    public Optional<SkillViews.Details> details(String name) {
        return Optional.ofNullable(backend.query("query($n: String!) { skill(name: $n) { summary { " + SUMMARY
                        + " } content createdAt files { " + FILE + " } revisions { revision action note "
                        + "changedBy changedAt description content } } }", Map.of("n", name), "skill",
                SkillViews.Details.class));
    }

    @Override
    public String publish(String name) {
        return text("mutation($name: String!) { publishSkill(name: $name) }", "publishSkill", args("name", name));
    }

    @Override
    public String unpublish(String name) {
        return text("mutation($name: String!) { unpublishSkill(name: $name) }", "unpublishSkill", args("name", name));
    }

    @Override
    public String list(String query, String category) {
        return text("query($query: String, $category: String) { skillList(query: $query, category: $category) }",
                "skillList", args("query", query, "category", category));
    }

    @Override
    public String view(String name, String filePath) {
        return text("query($name: String!, $filePath: String) { skillView(name: $name, filePath: $filePath) }",
                "skillView", args("name", name, "filePath", filePath));
    }

    @Override
    public String history(String name, Integer revision) {
        return text("query($name: String!, $revision: Int) { skillHistory(name: $name, revision: $revision) }",
                "skillHistory", args("name", name, "revision", revision));
    }

    @Override
    public int visibleCount() {
        if (!backend.signedIn()) {
            return 0;
        }
        Integer n = backend.query("{ skillCount }", Map.of(), "skillCount", Integer.class);
        return n == null ? 0 : n;
    }

    @Override
    public String create(String name, String description, String content, String category, List<String> tags,
                         List<String> triggers, int maxContentChars) {
        return text("""
                mutation($name: String!, $description: String!, $content: String!, $category: String, \
                $tags: [String!], $triggers: [String!], $max: Int) { createSkill(name: $name, \
                description: $description, content: $content, category: $category, tags: $tags, \
                triggers: $triggers, maxContentChars: $max) }""", "createSkill",
                args("name", name, "description", description, "content", content, "category", category,
                        "tags", tags, "triggers", triggers, "max", maxContentChars));
    }

    @Override
    public String update(String name, String description, String content, String category, List<String> tags,
                         List<String> triggers, String note, Integer expectedRevision, int maxContentChars) {
        return text("""
                mutation($name: String!, $description: String, $content: String, $category: String, $tags: [String!], \
                $triggers: [String!], $note: String, $rev: Int, $max: Int) { updateSkill(name: $name, \
                description: $description, content: $content, category: $category, tags: $tags, \
                triggers: $triggers, note: $note, expectedRevision: $rev, maxContentChars: $max) }""", "updateSkill",
                args("name", name, "description", description, "content", content, "category", category,
                        "tags", tags, "triggers", triggers, "note", note, "rev", expectedRevision,
                        "max", maxContentChars));
    }

    @Override
    public String patch(String name, String oldString, String newString, Boolean replaceAll, String filePath,
                        String note, Integer expectedRevision, int maxContentChars) {
        return text("""
                mutation($name: String!, $old: String!, $new: String!, $all: Boolean, $file: String, $note: String, \
                $rev: Int, $max: Int) { patchSkill(name: $name, oldString: $old, newString: $new, replaceAll: $all, \
                filePath: $file, note: $note, expectedRevision: $rev, maxContentChars: $max) }""", "patchSkill",
                args("name", name, "old", oldString, "new", newString, "all", replaceAll, "file", filePath,
                        "note", note, "rev", expectedRevision, "max", maxContentChars));
    }

    @Override
    public String writeFile(String name, String filePath, String content, String note, int maxContentChars) {
        return text("""
                mutation($name: String!, $file: String!, $content: String!, $note: String, $max: Int) { \
                writeSkillFile(name: $name, filePath: $file, content: $content, note: $note, maxContentChars: $max) }""",
                "writeSkillFile", args("name", name, "file", filePath, "content", content, "note", note,
                        "max", maxContentChars));
    }

    /** Inhalt über {@code /blobs} hochladen, dann per GraphQL anhängen. */
    @Override
    public String attachFile(String name, String filePath, Path source, String mediaType, String note) {
        String blob = files.upload(source);
        return text("""
                mutation($name: String!, $file: String!, $blob: String!, $type: String, $note: String) { \
                attachSkillFile(name: $name, filePath: $file, blob: $blob, mediaType: $type, note: $note) }""",
                "attachSkillFile", args("name", name, "file", filePath, "blob", blob, "type", mediaType,
                        "note", note));
    }

    @Override
    public Optional<SkillViews.File> file(String name, String filePath) {
        return Optional.ofNullable(backend.query("query($n: String!, $f: String!) { skillFile(name: $n, filePath: $f) "
                + "{ " + FILE + " } }", Map.of("n", name, "f", filePath), "skillFile", SkillViews.File.class));
    }

    @Override
    public String exportFile(String name, String filePath, Path target) {
        SkillViews.File f = file(name, filePath).orElseThrow(() -> new IllegalArgumentException("Skill '" + name
                + "' hat keine Datei '" + filePath + "' – skills_view(name) zeigt die Zusatzdateien."));
        if (f.inline()) {
            try {
                Files.createDirectories(target.toAbsolutePath().getParent());
                Files.writeString(target, f.content() == null ? "" : f.content(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("Datei " + target + " nicht schreibbar: " + e.getMessage(), e);
            }
        } else {
            files.download(f.blob(), target);
        }
        return "'" + f.path() + "' aus Skill '" + name + "' gespeichert: " + target + " (" + f.mediaType() + ", "
                + MediaTypes.size(f.size()) + ").";
    }

    @Override
    public String removeFile(String name, String filePath, String note) {
        return text("""
                mutation($name: String!, $file: String!, $note: String) { removeSkillFile(name: $name, \
                filePath: $file, note: $note) }""", "removeSkillFile", args("name", name, "file", filePath,
                "note", note));
    }

    @Override
    public String delete(String name) {
        return text("mutation($name: String!) { deleteSkill(name: $name) }", "deleteSkill", args("name", name));
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
