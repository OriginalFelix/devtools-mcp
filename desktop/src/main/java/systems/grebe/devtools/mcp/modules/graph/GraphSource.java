package systems.grebe.devtools.mcp.modules.graph;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Edge;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Kind;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Node;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Relation;
import systems.grebe.devtools.mcp.modules.graph.GraphReader.Direction;

/**
 * Liest Quelltext gezielt über den Code-Graphen: nur eine Methode, einen Typ, eine Gliederung (Typen und Member mit
 * Zeilenbereich, ohne Rümpfe) oder einen Zeilenbereich – statt ganzer Dateien. Gelesen wird die Datei im
 * Arbeitsverzeichnis; die Zeilen stammen aus dem Graphen des ausgecheckten Branches.
 */
final class GraphSource {

    /** Typen/Dateien bis zu dieser Länge werden ohne {@code outline} vollständig gelesen, längere als Gliederung. */
    static final int AUTO_OUTLINE_LINES = 150;

    private static final Set<Relation> CONTAINS = EnumSet.of(Relation.CONTAINS);
    private static final Pattern RANGE = Pattern.compile("\\s*(\\d+)\\s*(?:(-|\\+|:)\\s*(\\d+)?)?\\s*");
    private static final int MAX_DOC = 100;

    private final GraphReader g;
    private final GraphQueries q;
    private final Path root;
    private final Map<String, List<String>> cache = new HashMap<>();

    GraphSource(GraphReader g, Path root) {
        this.g = g;
        this.q = new GraphQueries(g);
        this.root = root.toAbsolutePath().normalize();
    }

    /**
     * @param specs    Knotenangaben (Typ, {@code Typ#methode} – alle Überladungen –, Datei)
     * @param lines    Zeilenbereich {@code von-bis}, {@code von+anzahl} oder {@code von} in der Datei des Knotens
     * @param outline  {@code true} = Gliederung, {@code false} = Quelltext, {@code null} = nach Länge
     * @param context  zusätzliche Zeilen vor und nach einem Knoten
     * @param maxLines Obergrenze ausgegebener Quelltextzeilen insgesamt
     */
    String read(List<String> specs, String lines, Boolean outline, int context, int maxLines) {
        List<String> given = specs == null ? List.of() : specs.stream().filter(s -> s != null && !s.isBlank()).toList();
        if (given.isEmpty()) {
            throw new IllegalArgumentException("node fehlt, z.B. ['OrderService#save'], ['OrderService'] oder "
                    + "['OrderService.java'].");
        }
        int[] budget = {maxLines};
        StringBuilder sb = new StringBuilder();
        if (lines != null && !lines.isBlank()) {
            if (given.size() != 1) {
                throw new IllegalArgumentException("lines gilt für genau einen Knoten bzw. eine Datei.");
            }
            String file = singleFile(q.resolveAll(given.getFirst()), given.getFirst());
            List<String> text = text(file);
            int[] r = parseRange(lines, text.size());
            header(sb, file, null, r[0], r[1], text.size());
            appendLines(sb, text, r[0], r[1], budget);
            return sb.toString().stripTrailing();
        }
        for (String spec : given) {
            for (Node n : q.resolveAll(spec)) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                if (budget[0] <= 0) {
                    sb.append("… weitere Knoten ausgelassen (maxLines erreicht): ").append(n.id()).append('\n');
                    continue;
                }
                appendNode(sb, n, outline, context, budget);
            }
        }
        return sb.toString().stripTrailing();
    }

    private void appendNode(StringBuilder sb, Node n, Boolean outline, int context, int[] budget) {
        if (n.kind() == Kind.EXTERNAL || n.kind() == Kind.PACKAGE || n.file() == null) {
            throw new IllegalArgumentException(n.id() + " hat keinen Quelltext im Projekt ("
                    + (n.kind() == Kind.EXTERNAL ? "externer Typ" : n.kind().label()) + ").");
        }
        List<String> text = text(n.file());
        int start = n.kind() == Kind.FILE ? 1 : n.line() == null ? 1 : n.line();
        int end = n.kind() == Kind.FILE ? text.size() : n.endLine() == null ? start : n.endLine();
        boolean asOutline = !n.kind().isMember() && (outline != null ? outline : end - start + 1 > AUTO_OUTLINE_LINES);
        if (asOutline) {
            appendOutline(sb, n, text);
            return;
        }
        int from = Math.max(1, start - context);
        int to = Math.min(text.size(), end + context);
        header(sb, n.file(), n, from, to, text.size());
        stale(sb, n, text);
        appendLines(sb, text, from, to, budget);
    }

    // ------------------------------------------------------------------ Gliederung

    private void appendOutline(StringBuilder sb, Node n, List<String> text) {
        sb.append(n.file()).append("  (").append(text.size()).append(" Z.)");
        if (n.kind() != Kind.FILE) {
            sb.append("  Gliederung ").append(n.id());
        } else {
            sb.append("  Gliederung");
        }
        sb.append('\n');
        stale(sb, n, text);
        if (n.kind() != Kind.FILE) {
            appendOutlineLine(sb, n, 0);
        }
        appendChildren(sb, n.id(), n.kind() == Kind.FILE ? 0 : 1);
        sb.append("Einzelne Stellen lesen: graph_read mit 'Typ#methode' oder lines='von-bis'.\n");
    }

    private void appendChildren(StringBuilder sb, String id, int indent) {
        List<Edge> contains = g.edges(id, Direction.OUT, CONTAINS);
        List<Node> children = new ArrayList<>(g.nodes(contains.stream().map(Edge::to).toList()).values());
        children.sort(Comparator.comparing((Node c) -> c.line() == null ? Integer.MAX_VALUE : c.line())
                .thenComparing(Node::id));
        for (Node c : children) {
            appendOutlineLine(sb, c, indent);
            if (c.kind().isType()) {
                appendChildren(sb, c.id(), indent + 1);
            }
        }
    }

    private static void appendOutlineLine(StringBuilder sb, Node n, int indent) {
        sb.append("  ".repeat(indent)).append(GraphFiles.range(n)).append(' ');
        if (n.kind().isType()) {
            sb.append(n.modifiers() == null ? "" : n.modifiers() + " ").append(n.kind().label()).append(' ')
                    .append(n.name());
        } else {
            String modifiers = n.modifiers() == null ? "" : n.modifiers() + " ";
            sb.append(modifiers).append(n.signature() != null ? n.signature() : n.name());
        }
        if (n.doc() != null) {
            String doc = n.doc().length() > MAX_DOC ? n.doc().substring(0, MAX_DOC) + "…" : n.doc();
            sb.append("  – ").append(doc);
        }
        sb.append('\n');
    }

    // ------------------------------------------------------------------ Quelltext

    private static void header(StringBuilder sb, String file, Node n, int from, int to, int total) {
        sb.append(file).append(':').append(from).append('-').append(to).append("  (").append(total).append(" Z.)");
        if (n != null && n.kind() != Kind.FILE) {
            sb.append("  ").append(n.id()).append(" [").append(n.kind().label()).append(']');
        }
        sb.append('\n');
    }

    private static void appendLines(StringBuilder sb, List<String> text, int from, int to, int[] budget) {
        int last = Math.min(to, from + budget[0] - 1);
        for (int i = from; i <= last; i++) {
            sb.append(i).append('\t').append(text.get(i - 1)).append('\n');
        }
        budget[0] -= last - from + 1;
        if (last < to) {
            sb.append("… abgeschnitten nach Zeile ").append(last).append(" (maxLines erhöhen oder mit lines='")
                    .append(last + 1).append('-').append(to).append("' weiterlesen)\n");
        }
    }

    /** Warnt, wenn die Datei seit dem Aufbau des Graphen geändert wurde – dann können Zeilen verschoben sein. */
    private void stale(StringBuilder sb, Node n, List<String> text) {
        Node file = n.kind() == Kind.FILE ? n : g.node(GraphBuilder.fileId(n.file()));
        boolean changed = file != null && file.endLine() != null && file.endLine() != text.size();
        if (!changed && n.kind().isMember() && n.line() != null && n.line() <= text.size()) {
            int end = Math.min(text.size(), n.endLine() == null ? n.line() : n.endLine());
            changed = text.subList(n.line() - 1, end).stream().noneMatch(l -> l.contains(n.name()));
        } else if (!changed && n.line() != null && n.line() > text.size()) {
            changed = true;
        }
        if (changed) {
            sb.append("Achtung: Datei wurde seit graph_build geändert – Zeilen können verschoben sein (graph_build "
                    + "aufrufen).\n");
        }
    }

    private String singleFile(List<Node> nodes, String spec) {
        List<String> files = nodes.stream().map(Node::file).filter(f -> f != null).distinct().toList();
        if (files.size() != 1) {
            throw new IllegalArgumentException(files.isEmpty() ? "'" + spec + "' hat keine Datei im Projekt."
                    : "'" + spec + "' liegt in mehreren Dateien: " + files + " – Datei angeben.");
        }
        return files.getFirst();
    }

    static int[] parseRange(String spec, int total) {
        Matcher m = RANGE.matcher(spec);
        if (!m.matches()) {
            throw new IllegalArgumentException("lines als 'von-bis', 'von+anzahl' oder 'von' angeben, z.B. '120-180'.");
        }
        int from = Integer.parseInt(m.group(1));
        int to;
        if (m.group(2) == null) {
            to = from;
        } else if (m.group(3) == null) {
            to = total; // '120-' = bis zum Ende
        } else if (m.group(2).equals("+")) {
            to = from + Integer.parseInt(m.group(3)) - 1;
        } else {
            to = Integer.parseInt(m.group(3));
        }
        if (from < 1 || from > total || to < from) {
            throw new IllegalArgumentException("Zeilenbereich " + spec.strip() + " liegt außerhalb der Datei (1-" + total
                    + ").");
        }
        return new int[] {from, Math.min(to, total)};
    }

    /** Zeilen der Datei aus dem Arbeitsverzeichnis (UTF-8, sonst ISO-8859-1), gezählt wie beim Aufbau des Graphen. */
    private List<String> text(String file) {
        return cache.computeIfAbsent(file, f -> {
            Path path = root.resolve(f).normalize();
            if (!path.startsWith(root)) {
                throw new IllegalArgumentException("Pfad liegt außerhalb des Projekts: " + f);
            }
            if (!Files.isRegularFile(path)) {
                throw new IllegalArgumentException("Datei " + f + " gibt es im Arbeitsverzeichnis nicht (mehr) – "
                        + "graph_build aufrufen.");
            }
            try {
                byte[] bytes = Files.readAllBytes(path);
                for (int i = 0; i < Math.min(bytes.length, GraphBuilder.BINARY_PROBE); i++) {
                    if (bytes[i] == 0) {
                        throw new IllegalArgumentException("Datei " + f + " ist binär (" + bytes.length
                                + " Bytes) – kein Text zum Lesen.");
                    }
                }
                String content = GraphBuilder.decode(bytes);
                // wie tree-sitter zählen: ein abschließender Zeilenumbruch ergibt eine leere letzte Zeile
                return Arrays.stream(content.split("\n", -1))
                        .map(l -> l.endsWith("\r") ? l.substring(0, l.length() - 1) : l).toList();
            } catch (IOException e) {
                throw new UncheckedIOException("Datei " + f + " nicht lesbar: " + e.getMessage(), e);
            }
        });
    }
}
