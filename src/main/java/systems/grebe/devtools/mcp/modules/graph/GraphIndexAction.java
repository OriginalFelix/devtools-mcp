package systems.grebe.devtools.mcp.modules.graph;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import systems.grebe.devtools.mcp.core.ModuleAction;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.modules.graph.GraphStorage.Key;
import systems.grebe.devtools.mcp.modules.graph.GraphStorage.Stored;

/** UI-Aktion „Indizieren“: baut den Code-Graphen eines Projekts für den ausgecheckten Branch. */
final class GraphIndexAction implements ModuleAction {

    static final String ID = "index";
    static final String FORCE = "force";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
            .withZone(ZoneId.systemDefault());

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String label() {
        return "Indizieren";
    }

    @Override
    public String description() {
        return "Baut den Code-Graphen des gewählten Projekts für den ausgecheckten Git-Branch und speichert ihn (Neo4j "
                + "oder Datei, siehe Ablage). Ohne „Komplett neu“ nur, wenn sich Quelldateien geändert haben. Graphen "
                + "gelöschter Branches werden dabei entfernt.";
    }

    @Override
    public List<String> targets(ModuleConfig config) {
        return new ArrayList<>(new GraphService(config).projects().all().keySet());
    }

    @Override
    public List<Flag> flags() {
        return List.of(new Flag(FORCE, "Komplett neu"));
    }

    @Override
    public String describe(ModuleConfig config, String target) {
        if (target == null) {
            return null;
        }
        GraphService service = new GraphService(config);
        Key key;
        try {
            key = service.key(target, null);
        } catch (RuntimeException e) {
            return e.getMessage();
        }
        String head = key.root() + " – Branch " + key.branchLabel();
        try {
            List<Stored> stored = service.storage().branches(key.root());
            Stored current = stored.stream().filter(s -> java.util.Objects.equals(s.branch(), key.branch()))
                    .findFirst().orElse(null);
            String others = stored.size() > (current == null ? 0 : 1)
                    ? " (weitere gespeichert: " + String.join(", ", stored.stream().filter(s -> s != current)
                    .map(s -> s.branch() == null ? "(ohne Git)" : s.branch()).toList()) + ")" : "";
            if (current == null) {
                return head + " – noch kein Graph" + others;
            }
            String when = current.builtAt() == null ? "?" : TIME.format(Instant.parse(current.builtAt()));
            return head + " – Graph vom " + when + ", " + current.files() + " Dateien, " + current.nodes() + " Knoten"
                    + others;
        } catch (RuntimeException e) {
            return head + " – Ablage nicht lesbar: " + GraphModule.rootMessage(e);
        }
    }

    static String size(long bytes) {
        return bytes >= 1 << 20 ? (bytes >> 20) + " MB" : Math.max(1, bytes >> 10) + " KB";
    }

    @Override
    public ActionResult run(ModuleConfig config, String target, Set<String> flags, Progress progress) {
        if (target == null || target.isBlank()) {
            return ActionResult.failed("Bitte ein Projekt wählen (Projekte in der Konfiguration eintragen und speichern).");
        }
        GraphService.BuildResult r = new GraphService(config).build(target, null, flags.contains(FORCE), progress);
        Map<String, Object> s = r.graph().info().stats();
        String removed = r.removedBranches().isEmpty() ? ""
                : " Entfernt (Branch gelöscht): " + String.join(", ", r.removedBranches()) + ".";
        if (!r.rebuilt()) {
            return ActionResult.ok("Graph ist aktuell (Branch " + r.key().branchLabel() + ", keine Quelldatei geändert) – "
                    + s.get("files") + " Dateien, " + s.get("nodes") + " Knoten." + removed);
        }
        return ActionResult.ok("Graph gebaut in " + String.format("%.1f", r.duration().toMillis() / 1000.0) + " s (Branch "
                + r.key().branchLabel() + "): " + s.get("files") + " Dateien, " + s.get("nodes") + " Knoten, "
                + s.get("edges") + " Kanten, " + s.get("communities") + " Communities → " + r.graph().info().location()
                + "." + removed);
    }
}
