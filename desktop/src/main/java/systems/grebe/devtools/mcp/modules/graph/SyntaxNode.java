package systems.grebe.devtools.mcp.modules.graph;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.treesitter.jtreesitter.internal.TSPoint;
import io.github.treesitter.jtreesitter.internal.TreeSitter;

/**
 * Schlanke Java-Kopie eines tree-sitter-Syntaxbaums.
 *
 * <p>Die komfortable jtreesitter-API ({@code Node}, {@code TreeCursor#getCurrentNode()}) legt für jedes Knotenobjekt
 * eine eigene automatische Arena samt Cleaner an. Beim Durchlaufen großer Projekte entstehen so Millionen davon, die
 * der Cleaner nicht schnell genug abräumt – der Heap läuft voll (gemessen: eGECKO, ~10.800 Dateien, OOM bei 1 GB).
 * Deshalb wird hier direkt über die von jextract erzeugten Bindings ({@code jtreesitter.internal.TreeSitter}, Version
 * fest in build.gradle.kts) gearbeitet: eine begrenzte Arena je Datei, ein wiederverwendeter Puffer für Knoten und
 * Punkte, Typ- und Feldnamen je Zeiger zwischengespeichert. Der native Baum ist nach {@link #parse} freigegeben.
 *
 * <p>Übernommen werden benannte Knoten sowie die unbenannten Kinder von {@code modifiers} (Schlüsselwörter wie
 * {@code public}, {@code static}).
 */
final class SyntaxNode {

    /** Typ- und Feldnamen sind statische Zeichenketten der Grammatik – je Adresse einmal lesen. */
    private static final Map<Long, String> NAMES = new ConcurrentHashMap<>();
    /** Größe von {@code TSNode} (4×uint32 + 2 Zeiger) – großzügig, der Puffer wird wiederverwendet. */
    private static final long SCRATCH = 64;

    final String type;
    /** Feldname relativ zum Elternknoten ({@code name}, {@code body} …) oder {@code null}. */
    final String field;
    final boolean named;
    final int startByte;
    final int endByte;
    /** 0-basiert. */
    final int startRow;
    final int endRow;
    final SyntaxNode parent;
    final List<SyntaxNode> children = new ArrayList<>(4);

    private SyntaxNode(String type, String field, boolean named, int startByte, int endByte, int startRow, int endRow,
                       SyntaxNode parent) {
        this.type = type;
        this.field = field;
        this.named = named;
        this.startByte = startByte;
        this.endByte = endByte;
        this.startRow = startRow;
        this.endRow = endRow;
        this.parent = parent;
    }

    /** Ergebnis eines Parse-Laufs: kopierter Baum und ob tree-sitter Syntaxfehler gefunden hat. */
    record Parsed(SyntaxNode root, boolean hasError) {
    }

    /** Parst den Quelltext und kopiert den Baum; alle nativen Ressourcen sind danach freigegeben. */
    static Parsed parse(String source) {
        MemorySegment language = TreeSitterNatives.javaLanguage();
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment parser = TreeSitter.ts_parser_new();
            try {
                if (!TreeSitter.ts_parser_set_language(parser, language)) {
                    throw new IllegalStateException("tree-sitter: Java-Grammatik passt nicht zur Bibliotheksversion");
                }
                MemorySegment text = arena.allocate(Math.max(1, bytes.length));
                MemorySegment.copy(bytes, 0, text, ValueLayout.JAVA_BYTE, 0, bytes.length);
                MemorySegment tree = TreeSitter.ts_parser_parse_string(parser, MemorySegment.NULL, text, bytes.length);
                if (tree.equals(MemorySegment.NULL)) {
                    throw new IllegalStateException("tree-sitter hat die Datei nicht geparst");
                }
                try {
                    MemorySegment root = TreeSitter.ts_tree_root_node(arena, tree);
                    boolean errors = TreeSitter.ts_node_has_error(root);
                    MemorySegment cursor = TreeSitter.ts_tree_cursor_new(arena, root);
                    try {
                        Scratch scratch = new Scratch(SegmentAllocator.prefixAllocator(arena.allocate(SCRATCH)),
                                SegmentAllocator.prefixAllocator(arena.allocate(SCRATCH)));
                        return new Parsed(copy(cursor, scratch), errors);
                    } finally {
                        TreeSitter.ts_tree_cursor_delete(cursor);
                    }
                } finally {
                    TreeSitter.ts_tree_delete(tree);
                }
            } finally {
                TreeSitter.ts_parser_delete(parser);
            }
        }
    }

    private static SyntaxNode copy(MemorySegment c, Scratch scratch) {
        SyntaxNode root = current(c, null, scratch);
        SyntaxNode parent = root;
        if (!TreeSitter.ts_tree_cursor_goto_first_child(c)) {
            return root;
        }
        while (true) {
            SyntaxNode n = current(c, parent, scratch);
            boolean keep = n.named || "modifiers".equals(parent.type);
            if (keep) {
                parent.children.add(n);
                if (TreeSitter.ts_tree_cursor_goto_first_child(c)) {
                    parent = n;
                    continue;
                }
            }
            while (!TreeSitter.ts_tree_cursor_goto_next_sibling(c)) {
                if (!TreeSitter.ts_tree_cursor_goto_parent(c)) {
                    return root;
                }
                parent = parent.parent;
                if (parent == null) {
                    return root;
                }
            }
        }
    }

    /** Zwei getrennte, immer wieder überschriebene Puffer: einer für den Knoten, einer für Rückgabe-Punkte. */
    private record Scratch(SegmentAllocator node, SegmentAllocator point) {
    }

    private static SyntaxNode current(MemorySegment c, SyntaxNode parent, Scratch scratch) {
        MemorySegment node = TreeSitter.ts_tree_cursor_current_node(scratch.node(), c);
        String type = name(TreeSitter.ts_node_type(node));
        boolean named = TreeSitter.ts_node_is_named(node);
        int start = TreeSitter.ts_node_start_byte(node);
        int end = TreeSitter.ts_node_end_byte(node);
        MemorySegment fieldPtr = TreeSitter.ts_tree_cursor_current_field_name(c);
        String field = fieldPtr.equals(MemorySegment.NULL) ? null : name(fieldPtr);
        int startRow = TSPoint.row(TreeSitter.ts_node_start_point(scratch.point(), node));
        int endRow = TSPoint.row(TreeSitter.ts_node_end_point(scratch.point(), node));
        return new SyntaxNode(type, field, named, start, end, startRow, endRow, parent);
    }

    private static String name(MemorySegment ptr) {
        return NAMES.computeIfAbsent(ptr.address(), a -> ptr.reinterpret(Long.MAX_VALUE).getString(0));
    }

    // ------------------------------------------------------------------ Navigation

    String getType() {
        return type;
    }

    /** Erstes Kind mit diesem Feldnamen oder {@code null}. */
    SyntaxNode field(String name) {
        for (SyntaxNode c : children) {
            if (name.equals(c.field)) {
                return c;
            }
        }
        return null;
    }

    /** Benannte Kinder (ohne die Schlüsselwörter unter {@code modifiers}). */
    List<SyntaxNode> namedChildren() {
        if ("modifiers".equals(type)) {
            List<SyntaxNode> out = new ArrayList<>(children.size());
            for (SyntaxNode c : children) {
                if (c.named) {
                    out.add(c);
                }
            }
            return out;
        }
        return children;
    }

    /** Alle übernommenen Kinder (bei {@code modifiers} inkl. Schlüsselwörter). */
    List<SyntaxNode> allChildren() {
        return children;
    }

    int namedChildCount() {
        return "modifiers".equals(type) ? namedChildren().size() : children.size();
    }

    /** Vorheriges benanntes Geschwister oder {@code null}. */
    SyntaxNode prevNamedSibling() {
        if (parent == null) {
            return null;
        }
        List<SyntaxNode> siblings = parent.children;
        for (int i = siblings.indexOf(this) - 1; i >= 0; i--) {
            if (siblings.get(i).named) {
                return siblings.get(i);
            }
        }
        return null;
    }
}
