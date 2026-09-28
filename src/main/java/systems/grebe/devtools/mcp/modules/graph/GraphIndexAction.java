package systems.grebe.devtools.mcp.modules.graph;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import systems.grebe.devtools.mcp.core.ModuleAction;
import systems.grebe.devtools.mcp.core.ModuleConfig;

/** UI-Aktion „Indizieren“: baut den Code-Graphen eines Projekts ({@code devtools-fileinfo.graph}). */
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
        return "Baut den Code-Graphen des gewählten Projekts und schreibt ihn nach " + GraphStore.FILE_NAME
                + ". Ohne „Komplett neu“ nur, wenn sich Quelldateien geändert haben.";
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
        Path root = new GraphService(config).resolve(target);
        Path file = GraphStore.fileFor(root);
        if (!Files.exists(file)) {
            return root + " – noch kein Graph";
        }
        try {
            Map<String, Object> head = GraphStore.header(root);
            Object built = head.get("builtAt");
            String when = built == null ? "?" : TIME.format(Instant.parse(String.valueOf(built)));
            return root + " – Graph vom " + when + ", " + head.getOrDefault("files", "?") + " Dateien, "
                    + head.getOrDefault("nodes", "?") + " Knoten, " + size(Files.size(file));
        } catch (Exception e) {
            return root + " – Graph-Datei nicht lesbar: " + e.getMessage();
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
        GraphService.BuildResult r = new GraphService(config).build(target, flags.contains(FORCE), progress);
        Map<String, Object> s = r.graph().data().stats();
        if (!r.rebuilt()) {
            return ActionResult.ok("Graph ist aktuell (keine Quelldatei geändert) – " + s.get("files") + " Dateien, "
                    + s.get("nodes") + " Knoten.");
        }
        return ActionResult.ok("Graph gebaut in " + String.format("%.1f", r.duration().toMillis() / 1000.0) + " s: "
                + s.get("files") + " Dateien, " + s.get("nodes") + " Knoten, " + s.get("edges") + " Kanten, "
                + s.get("communities") + " Communities → " + GraphStore.fileFor(r.root()));
    }
}
