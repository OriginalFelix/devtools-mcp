package systems.grebe.devtools.mcp.remote;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.modules.scripts.ScriptBackend;
import systems.grebe.devtools.mcp.modules.scripts.ScriptViews;

/**
 * Groovy-Skripte im Backend über GraphQL – eingebettet oder auf dem Team-Server, als der angemeldete Benutzer.
 * Änderungen (auch von anderen Desktop-Apps) meldet die Subscription {@code scriptsChanged}.
 */
@Primary // eingebettet ist auch der ScriptService des Backends ein ScriptBackend – die App geht immer über GraphQL
@Component
public class BackendScripts implements ScriptBackend {

    private static final String SUMMARY = "name description scope revision updatedAt updatedBy";

    private final BackendConnection backend;

    public BackendScripts(BackendConnection backend) {
        this.backend = backend;
    }

    @Override
    public void addChangeListener(Runnable listener) {
        backend.addScriptListener(listener);
    }

    @Override
    public List<ScriptViews.Summary> overview() {
        return backend.queryList("{ scripts { " + SUMMARY + " } }", "scripts", ScriptViews.Summary.class);
    }

    @Override
    public Optional<ScriptViews.Details> details(String name) {
        return Optional.ofNullable(backend.query("query($n: String!) { script(name: $n) { summary { " + SUMMARY
                        + " } content createdAt revisions { revision action note changedBy changedAt content } } }",
                Map.of("n", name), "script", ScriptViews.Details.class));
    }

    @Override
    public String save(String name, String description, String content, String note, Integer expectedRevision) {
        return text("""
                mutation($name: String!, $description: String!, $content: String!, $note: String, $rev: Int) { \
                saveScript(name: $name, description: $description, content: $content, note: $note, \
                expectedRevision: $rev) }""", "saveScript", args("name", name, "description", description,
                "content", content, "note", note, "rev", expectedRevision));
    }

    @Override
    public String delete(String name) {
        return text("mutation($name: String!) { deleteScript(name: $name) }", "deleteScript", args("name", name));
    }

    @Override
    public String publish(String name) {
        return text("mutation($name: String!) { publishScript(name: $name) }", "publishScript", args("name", name));
    }

    @Override
    public String unpublish(String name) {
        return text("mutation($name: String!) { unpublishScript(name: $name) }", "unpublishScript",
                args("name", name));
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
