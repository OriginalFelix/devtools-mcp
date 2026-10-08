package systems.grebe.devtools.mcp.modules.graph;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Community;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Confidence;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Edge;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.FileEntry;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.GraphFile;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Kind;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Node;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Relation;
import systems.grebe.devtools.mcp.config.AtomicFiles;

/**
 * Liest und schreibt {@code devtools-fileinfo.graph} im Projektwurzelverzeichnis.
 *
 * <p>Format: JSON, ein Eintrag je Zeile (Diffs bleiben lesbar, zeilenweise durchsuchbar). Damit auch große Projekte
 * handlich bleiben, ist die Datei kompakt:
 * <ul>
 *   <li>Knoten sind Objekte; Standardwerte fehlen (bei Membern die Datei des Typs, {@code name} wenn aus der ID
 *       ablesbar).</li>
 *   <li>Kanten sind Arrays {@code [von, nach, relation, sicherheit?, score?, anzahl?, zeile?]}; {@code von}/{@code nach}
 *       sind Indizes in {@code nodes}, fehlende Werte am Ende entfallen, dazwischen stehen {@code null}.</li>
 * </ul>
 * Geladene Graphen werden je Datei (Pfad, Änderungszeit, Größe) zwischengespeichert.
 */
final class GraphStore {

    static final String FILE_NAME = "devtools-fileinfo.graph";

    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private record Cached(FileTime modified, long size, CodeGraph graph) {
    }

    /**
     * Zuletzt verwendete Graphen, höchstens {@value #MAX_CACHED} – ein großes Projekt belegt mehrere hundert MB
     * (eGECKO, ~10.800 Dateien: ~280 MB), deshalb nicht jedes jemals abgefragte Projekt im Speicher halten.
     */
    private static final int MAX_CACHED = 2;
    private static final Map<Path, Cached> CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(4, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Path, Cached> eldest) {
                    return size() > MAX_CACHED;
                }
            });

    private GraphStore() {
    }

    static Path fileFor(Path projectRoot) {
        return projectRoot.resolve(FILE_NAME);
    }

    /**
     * Datei für einen Branch: ohne Git {@value #FILE_NAME}, sonst {@code devtools-fileinfo@<branch>.graph} (Zeichen
     * außer Buchstaben, Ziffern, '.', '-' und '_' werden zu '_').
     */
    static Path fileFor(Path projectRoot, String branch) {
        if (branch == null) {
            return fileFor(projectRoot);
        }
        return projectRoot.resolve(BRANCH_PREFIX + branch.replaceAll("[^A-Za-z0-9._-]", "_") + BRANCH_SUFFIX);
    }

    static final String BRANCH_PREFIX = "devtools-fileinfo@";
    static final String BRANCH_SUFFIX = ".graph";

    /** Graph-Dateien des Projekts (ohne Git und je Branch). */
    static List<Path> files(Path projectRoot) {
        List<Path> out = new ArrayList<>();
        if (Files.exists(fileFor(projectRoot))) {
            out.add(fileFor(projectRoot));
        }
        try (var s = Files.list(projectRoot)) {
            s.filter(p -> {
                String n = p.getFileName().toString();
                return n.startsWith(BRANCH_PREFIX) && n.endsWith(BRANCH_SUFFIX);
            }).sorted().forEach(out::add);
        } catch (IOException ignored) {
            // Verzeichnis nicht lesbar -> keine Dateien
        }
        return out;
    }

    // ------------------------------------------------------------------ Laden

    /** Geladener Graph oder {@code null}, wenn die Datei fehlt oder ein älteres Format hat. */
    static CodeGraph load(Path projectRoot) {
        return loadFile(fileFor(projectRoot));
    }

    /** Wie {@link #load(Path)}, für eine bestimmte Graph-Datei. */
    static CodeGraph loadFile(Path file) {
        try {
            FileTime modified = Files.getLastModifiedTime(file);
            long size = Files.size(file);
            Cached c = CACHE.get(file);
            if (c != null && c.modified().equals(modified) && c.size() == size) {
                return c.graph();
            }
            GraphFile data = read(file);
            if (data == null) {
                return null;
            }
            CodeGraph graph = new CodeGraph(data);
            CACHE.put(file, new Cached(modified, size, graph));
            return graph;
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException("Graph-Datei nicht lesbar: " + file, e);
        } catch (IllegalStateException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalStateException("Graph-Datei " + file + " ist beschädigt (" + e.getMessage()
                    + "). Mit graph_build(force=true) neu erzeugen.", e);
        }
    }

    /** Liest zeilenweise: Kopf als JSON, dann je Abschnitt ein Eintrag pro Zeile. */
    private static GraphFile read(Path file) throws IOException {
        StringBuilder head = new StringBuilder();
        List<Community> communities = new ArrayList<>();
        List<FileEntry> files = new ArrayList<>();
        List<Node> nodes = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        Map<String, String> fileOfType = new HashMap<>();
        Map<String, Object> header = null;
        String section = null;
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                String s = line.strip();
                if (section == null) {
                    if (s.endsWith("[") && s.startsWith("\"")) {
                        section = s.substring(1, s.indexOf('"', 1));
                        // Kopf abschließen und prüfen, bevor der große Teil gelesen wird
                        header = header(file, head.append("\"_\":0}").toString());
                        if (header == null) {
                            return null;
                        }
                    } else {
                        head.append(line).append('\n');
                    }
                    continue;
                }
                if (s.startsWith("]")) {
                    section = "";
                    continue;
                }
                if (s.startsWith("\"") && s.endsWith("[")) {
                    section = s.substring(1, s.indexOf('"', 1));
                    continue;
                }
                if (s.isEmpty() || s.equals("}")) {
                    continue;
                }
                String json = s.endsWith(",") ? s.substring(0, s.length() - 1) : s;
                switch (section) {
                    case "communities" -> communities.add(JSON.readValue(json, Community.class));
                    case "files" -> files.add(JSON.readValue(json, FileEntry.class));
                    case "nodes" -> nodes.add(node(JSON.readTree(json), fileOfType));
                    case "edges" -> edges.add(edge(JSON.readTree(json), nodes));
                    default -> { }
                }
            }
        }
        if (header == null) { // alle Abschnitte leer ("[]" in einer Zeile) – die ganze Datei ist der Kopf
            header = header(file, head.toString());
            if (header == null) {
                return null;
            }
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> stats = (Map<String, Object>) header.get("stats");
        return new GraphFile((String) header.get("format"), CodeGraph.VERSION, (String) header.get("project"),
                (String) header.get("root"), (String) header.get("branch"), (String) header.get("commit"),
                (String) header.get("builtAt"), (String) header.get("generator"),
                stats, files, communities, nodes, edges);
    }

    /** Kopf lesen und prüfen; {@code null} = anderes Format, neu bauen. */
    private static Map<String, Object> header(Path file, String json) {
        Map<String, Object> header = JSON.readValue(json, new TypeReference<>() { });
        if (!CodeGraph.FORMAT.equals(header.get("format"))) {
            throw new IllegalStateException(file + " ist keine Graph-Datei dieses Servers (format="
                    + header.get("format") + "). Mit graph_build(force=true) neu erzeugen.");
        }
        return Integer.valueOf(CodeGraph.VERSION).equals(header.get("version")) ? header : null;
    }

    private static Node node(JsonNode o, Map<String, String> fileOfType) {
        String id = o.get("id").asString();
        Kind kind = Kind.valueOf(o.get("kind").asString().toUpperCase(Locale.ROOT));
        String file = text(o, "file");
        if (kind == Kind.FILE) {
            file = id.substring("file:".length());
        } else if (file == null && kind.isMember()) {
            file = fileOfType.get(CodeGraph.ownerOf(id)); // Typ steht vor seinen Membern
        } else if (kind.isType()) {
            fileOfType.put(id, file);
        }
        String name = text(o, "name");
        if (name == null) {
            name = defaultName(id, kind);
        }
        return new Node(id, kind, name, file, integer(o, "line"), integer(o, "endLine"), text(o, "modifiers"),
                text(o, "signature"), text(o, "doc"), integer(o, "community"));
    }

    private static Edge edge(JsonNode a, List<Node> nodes) {
        ArrayNode arr = (ArrayNode) a;
        String from = nodes.get(arr.get(0).asInt()).id();
        String to = nodes.get(arr.get(1).asInt()).id();
        Relation rel = Relation.valueOf(arr.get(2).asString().toUpperCase(Locale.ROOT));
        Confidence conf = arr.size() > 3 && !arr.get(3).isNull() ? Confidence.valueOf(arr.get(3).asString())
                : Confidence.EXTRACTED;
        Double score = arr.size() > 4 && !arr.get(4).isNull() ? arr.get(4).asDouble() : null;
        Integer count = arr.size() > 5 && !arr.get(5).isNull() ? arr.get(5).asInt() : null;
        Integer line = arr.size() > 6 && !arr.get(6).isNull() ? arr.get(6).asInt() : null;
        return new Edge(from, to, rel, conf, score, count, line);
    }

    private static String text(JsonNode o, String field) {
        JsonNode v = o.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }

    private static Integer integer(JsonNode o, String field) {
        JsonNode v = o.get(field);
        return v == null || v.isNull() ? null : v.asInt();
    }

    // ------------------------------------------------------------------ Schreiben

    /** Name, der sich aus der ID ergibt und deshalb nicht gespeichert wird. */
    static String defaultName(String id, Kind kind) {
        return switch (kind) {
            case PACKAGE -> id.substring(id.indexOf(':') + 1);
            case FILE -> id.substring("file:".length());
            case CONSTRUCTOR -> {
                String owner = CodeGraph.ownerOf(id);
                yield owner.substring(owner.lastIndexOf('.') + 1);
            }
            case METHOD, FIELD -> {
                String member = id.substring(id.indexOf('#') + 1);
                int paren = member.indexOf('(');
                yield paren < 0 ? member : member.substring(0, paren);
            }
            default -> id.substring(id.lastIndexOf('.') + 1);
        };
    }

    /** Schreibt atomar (temporäre Datei + Umbenennen) und legt den Graphen in den Cache. */
    static CodeGraph write(Path projectRoot, GraphFile data) {
        return writeFile(fileFor(projectRoot), data);
    }

    /** Wie {@link #write(Path, GraphFile)}, in eine bestimmte Graph-Datei. */
    static CodeGraph writeFile(Path file, GraphFile data) {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                w.write("{\n");
                field(w, "format", data.format());
                field(w, "version", data.version());
                field(w, "project", data.project());
                field(w, "root", data.root());
                if (data.branch() != null) {
                    field(w, "branch", data.branch());
                }
                if (data.commit() != null) {
                    field(w, "commit", data.commit());
                }
                field(w, "builtAt", data.builtAt());
                field(w, "generator", data.generator());
                field(w, "stats", data.stats());
                field(w, "edgeFormat", List.of("from", "to", "rel", "conf", "score", "count", "line"));
                array(w, "communities", data.communities().stream().map(JSON::writeValueAsString).toList(), false);
                array(w, "files", data.files().stream().map(JSON::writeValueAsString).toList(), false);
                Map<String, Integer> index = new HashMap<>(data.nodes().size() * 2);
                Map<String, String> fileOfType = new HashMap<>();
                List<String> nodeLines = new ArrayList<>(data.nodes().size());
                for (Node n : data.nodes()) {
                    index.put(n.id(), index.size());
                    if (n.kind().isType()) {
                        fileOfType.put(n.id(), n.file());
                    }
                    nodeLines.add(nodeJson(n, fileOfType));
                }
                array(w, "nodes", nodeLines, false);
                nodeLines.clear();
                w.write("  \"edges\": [");
                boolean first = true;
                for (Edge e : data.edges()) {
                    w.write(first ? "\n    " : ",\n    ");
                    first = false;
                    w.write(edgeJson(e, index));
                }
                w.write(first ? "]\n" : "\n  ]\n");
                w.write("}\n");
            }
            AtomicFiles.replace(tmp, file);
            CodeGraph graph = new CodeGraph(data);
            CACHE.put(file, new Cached(Files.getLastModifiedTime(file), Files.size(file), graph));
            return graph;
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // nichts zu retten
            }
            throw new UncheckedIOException("Graph-Datei nicht schreibbar: " + file, e);
        }
    }

    private static String nodeJson(Node n, Map<String, String> fileOfType) {
        ObjectNode o = JSON.createObjectNode();
        o.put("id", n.id());
        o.put("kind", n.kind().label());
        if (!n.name().equals(defaultName(n.id(), n.kind()))) {
            o.put("name", n.name());
        }
        boolean fileImplied = n.kind() == Kind.FILE
                || (n.kind().isMember() && n.file() != null && n.file().equals(fileOfType.get(CodeGraph.ownerOf(n.id()))));
        if (n.file() != null && !fileImplied) {
            o.put("file", n.file());
        }
        putIfNotNull(o, "line", n.line());
        putIfNotNull(o, "endLine", n.endLine());
        if (n.modifiers() != null) {
            o.put("modifiers", n.modifiers());
        }
        if (n.signature() != null) {
            o.put("signature", n.signature());
        }
        if (n.doc() != null) {
            o.put("doc", n.doc());
        }
        putIfNotNull(o, "community", n.community());
        return JSON.writeValueAsString(o);
    }

    private static void putIfNotNull(ObjectNode o, String field, Integer v) {
        if (v != null) {
            o.put(field, v);
        }
    }

    private static String edgeJson(Edge e, Map<String, Integer> index) {
        Object[] values = {index.get(e.from()), index.get(e.to()), e.rel().label(),
                e.conf() == Confidence.EXTRACTED ? null : e.conf().name(), e.score(), e.count(), e.line()};
        int last = values.length - 1;
        while (last > 2 && values[last] == null) {
            last--;
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i <= last; i++) {
            if (i > 0) {
                sb.append(',');
            }
            Object v = values[i];
            sb.append(v == null ? "null" : v instanceof String s ? "\"" + s + "\"" : v.toString());
        }
        return sb.append(']').toString();
    }

    private static void field(Writer w, String name, Object value) throws IOException {
        w.write("  \"" + name + "\": " + JSON.writeValueAsString(value) + ",\n");
    }

    private static void array(Writer w, String name, List<String> lines, boolean last) throws IOException {
        w.write("  \"" + name + "\": [");
        for (int i = 0; i < lines.size(); i++) {
            w.write(i == 0 ? "\n    " : ",\n    ");
            w.write(lines.get(i));
        }
        w.write(lines.isEmpty() ? "]" : "\n  ]");
        w.write(last ? "\n" : ",\n");
    }

    /**
     * Nur den Kopf der Datei lesen (bis zum ersten Abschnitt) – schnell auch bei großen Graphen. Liefert
     * {@code builtAt}, {@code project} und die Zahlen aus {@code stats} ({@code files}, {@code nodes} …).
     */
    static Map<String, Object> header(Path projectRoot) throws IOException {
        return headerFile(fileFor(projectRoot));
    }

    /** Wie {@link #header(Path)}, für eine bestimmte Graph-Datei; zusätzlich {@code branch}, {@code commit}, {@code root}. */
    static Map<String, Object> headerFile(Path file) throws IOException {
        StringBuilder head = new StringBuilder();
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                String s = line.strip();
                if (s.startsWith("\"") && s.endsWith("[")) {
                    break;
                }
                head.append(line).append('\n');
            }
        }
        Map<String, Object> raw = JSON.readValue(head.append("\"_\":0}").toString(), new TypeReference<>() { });
        Map<String, Object> out = new HashMap<>();
        out.put("builtAt", raw.get("builtAt"));
        out.put("project", raw.get("project"));
        out.put("branch", raw.get("branch"));
        out.put("commit", raw.get("commit"));
        out.put("root", raw.get("root"));
        out.put("generator", raw.get("generator"));
        if (raw.get("stats") instanceof Map<?, ?> stats) {
            stats.forEach((k, v) -> out.put(String.valueOf(k), v));
        }
        return out;
    }

    /** Nur für Tests: Zwischenspeicher leeren, damit die Datei neu gelesen wird. */
    static void clearCache() {
        CACHE.clear();
    }
}
