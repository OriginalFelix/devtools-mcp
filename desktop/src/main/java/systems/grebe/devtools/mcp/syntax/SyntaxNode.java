package systems.grebe.devtools.mcp.syntax;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Knoten eines {@link SyntaxTree} – reine Java-Struktur, unabhängig vom Wasm-Speicher, beliebig lange haltbar und von
 * mehreren Threads lesbar.
 *
 * <p>Enthalten sind benannte Knoten (Ausdrücke, Deklarationen, Bezeichner, Kommentare …) und unbenannte Knoten, deren
 * Typ ein Wort ist – Schlüsselwörter wie {@code public}, {@code static}, {@code async}, {@code def} – sowie fehlende
 * Tokens der Fehlerkorrektur ({@link #isMissing}). Sonstige Satzzeichen und Operatoren nur mit
 * {@link SyntaxEngine#parse(Language, String, boolean) allTokens}. Offsets sind UTF-8-Byte-Positionen, Zeilen und
 * Spalten 0-basiert (Spalten in Bytes).
 */
public final class SyntaxNode {

    static final int NAMED = 1;
    static final int EXTRA = 2;
    static final int MISSING = 4;
    static final int HAS_ERROR = 8;
    static final int ERROR = 16;

    private final SyntaxTree tree;
    private final String type;
    private final String field;
    private final int flags;
    private final int startByte;
    private final int endByte;
    private final int startRow;
    private final int startColumn;
    private final int endRow;
    private final int endColumn;
    private final SyntaxNode parent;
    private final int index;
    private List<SyntaxNode> children = List.of();
    /** Nur die benannten Kinder; dieselbe Liste wie {@link #children}, wenn alle benannt sind. */
    private List<SyntaxNode> named = List.of();

    SyntaxNode(SyntaxTree tree, String type, String field, int flags, int startByte, int endByte, int startRow,
               int startColumn, int endRow, int endColumn, SyntaxNode parent, int index) {
        this.tree = tree;
        this.type = type;
        this.field = field;
        this.flags = flags;
        this.startByte = startByte;
        this.endByte = endByte;
        this.startRow = startRow;
        this.startColumn = startColumn;
        this.endRow = endRow;
        this.endColumn = endColumn;
        this.parent = parent;
        this.index = index;
    }

    void addChild(SyntaxNode child) {
        if (children.isEmpty()) {
            children = new ArrayList<>(4);
        }
        children.add(child);
    }

    /** Nach dem Aufbau: benannte Kinder ermitteln, Listen unveränderlich machen. */
    void seal() {
        if (children.isEmpty()) {
            return;
        }
        boolean allNamed = true;
        for (SyntaxNode c : children) {
            allNamed &= c.isNamed();
        }
        children = List.copyOf(children);
        if (allNamed) {
            named = children;
        } else {
            List<SyntaxNode> n = new ArrayList<>(children.size());
            for (SyntaxNode c : children) {
                if (c.isNamed()) {
                    n.add(c);
                }
            }
            named = List.copyOf(n);
        }
    }

    // ------------------------------------------------------------------ Eigenschaften

    /** Knotentyp der Grammatik, z.B. {@code method_declaration}, {@code identifier}, bei Schlüsselwörtern das Wort. */
    public String type() {
        return type;
    }

    /** Feldname relativ zum Elternknoten ({@code name}, {@code body} …) oder {@code null}. */
    public String field() {
        return field;
    }

    /** Benannter Knoten (sonst ein Schlüsselwort bzw. Token). */
    public boolean isNamed() {
        return (flags & NAMED) != 0;
    }

    /** Darf überall stehen, gehört nicht zur Struktur – meist Kommentare. */
    public boolean isExtra() {
        return (flags & EXTRA) != 0;
    }

    /** Vom Parser bei der Fehlerkorrektur eingefügt, steht nicht im Quelltext (Länge 0). */
    public boolean isMissing() {
        return (flags & MISSING) != 0;
    }

    /** Knoten ist selbst ein Syntaxfehler ({@code ERROR}). */
    public boolean isError() {
        return (flags & ERROR) != 0;
    }

    /** Irgendwo in diesem Teilbaum steckt ein Syntaxfehler. */
    public boolean hasError() {
        return (flags & HAS_ERROR) != 0;
    }

    public int startByte() {
        return startByte;
    }

    public int endByte() {
        return endByte;
    }

    /** 0-basiert. */
    public int startRow() {
        return startRow;
    }

    /** 0-basiert, in Bytes. */
    public int startColumn() {
        return startColumn;
    }

    /** 0-basiert. */
    public int endRow() {
        return endRow;
    }

    /** 0-basiert, in Bytes. */
    public int endColumn() {
        return endColumn;
    }

    /** 1-basierte Startzeile – wie in Editoren. */
    public int line() {
        return startRow + 1;
    }

    /** 1-basierte Endzeile. */
    public int endLine() {
        return endRow + 1;
    }

    /** Quelltext des Knotens. */
    public String text() {
        return tree.text(startByte, endByte);
    }

    public SyntaxTree tree() {
        return tree;
    }

    // ------------------------------------------------------------------ Navigation

    public SyntaxNode parent() {
        return parent;
    }

    /** Alle Kinder in Quelltext-Reihenfolge (benannte und Schlüsselwörter). */
    public List<SyntaxNode> children() {
        return children;
    }

    /** Nur die benannten Kinder. */
    public List<SyntaxNode> namedChildren() {
        return named;
    }

    public int childCount() {
        return children.size();
    }

    public int namedChildCount() {
        return named.size();
    }

    /** Erstes Kind mit diesem Feldnamen oder {@code null}. */
    public SyntaxNode child(String fieldName) {
        for (SyntaxNode c : children) {
            if (fieldName.equals(c.field)) {
                return c;
            }
        }
        return null;
    }

    /** Alle Kinder mit diesem Feldnamen (z.B. mehrere {@code argument}). */
    public List<SyntaxNode> children(String fieldName) {
        List<SyntaxNode> out = new ArrayList<>(2);
        for (SyntaxNode c : children) {
            if (fieldName.equals(c.field)) {
                out.add(c);
            }
        }
        return out;
    }

    /** Erstes benanntes Kind dieses Typs oder {@code null}. */
    public SyntaxNode firstChildOfType(String nodeType) {
        for (SyntaxNode c : named) {
            if (c.type.equals(nodeType)) {
                return c;
            }
        }
        return null;
    }

    /** Vorheriges benanntes Geschwister oder {@code null}. */
    public SyntaxNode prevNamedSibling() {
        if (parent == null) {
            return null;
        }
        List<SyntaxNode> siblings = parent.children;
        for (int i = index - 1; i >= 0; i--) {
            if (siblings.get(i).isNamed()) {
                return siblings.get(i);
            }
        }
        return null;
    }

    /** Nächstes benanntes Geschwister oder {@code null}. */
    public SyntaxNode nextNamedSibling() {
        if (parent == null) {
            return null;
        }
        List<SyntaxNode> siblings = parent.children;
        for (int i = index + 1; i < siblings.size(); i++) {
            if (siblings.get(i).isNamed()) {
                return siblings.get(i);
            }
        }
        return null;
    }

    /** Nächster Vorfahr dieses Typs oder {@code null}. */
    public SyntaxNode ancestor(String nodeType) {
        for (SyntaxNode p = parent; p != null; p = p.parent) {
            if (p.type.equals(nodeType)) {
                return p;
            }
        }
        return null;
    }

    /** Kleinster benannter Knoten, der die Zeile (0-basiert) und Spalte (Bytes) enthält. */
    public SyntaxNode namedDescendantAt(int row, int column) {
        SyntaxNode best = this;
        outer:
        while (true) {
            for (SyntaxNode c : best.named) {
                if (c.contains(row, column)) {
                    best = c;
                    continue outer;
                }
            }
            return best;
        }
    }

    private boolean contains(int row, int column) {
        boolean afterStart = row > startRow || (row == startRow && column >= startColumn);
        boolean beforeEnd = row < endRow || (row == endRow && column < endColumn);
        return afterStart && beforeEnd;
    }

    // ------------------------------------------------------------------ Durchlaufen

    /**
     * Durchläuft den Teilbaum in Vorordnung (Eltern vor Kindern) mit diesem Knoten als erstem. Iterativ – auch tief
     * verschachtelte Bäume (lange Ausdrucksketten) laufen nicht in einen {@link StackOverflowError}.
     */
    public void walk(SyntaxVisitor visitor) {
        List<SyntaxNode> stack = new ArrayList<>();
        stack.add(this);
        while (!stack.isEmpty()) {
            SyntaxNode n = stack.removeLast();
            if (visitor.visit(n)) {
                for (int i = n.children.size() - 1; i >= 0; i--) {
                    stack.add(n.children.get(i));
                }
            }
        }
    }

    /** Alle Knoten des Teilbaums in Vorordnung. */
    public void forEach(Consumer<SyntaxNode> action) {
        walk(n -> {
            action.accept(n);
            return true;
        });
    }

    /** Alle Knoten des Teilbaums, auf die die Bedingung zutrifft, in Vorordnung. */
    public List<SyntaxNode> find(Predicate<SyntaxNode> predicate) {
        List<SyntaxNode> out = new ArrayList<>();
        forEach(n -> {
            if (predicate.test(n)) {
                out.add(n);
            }
        });
        return out;
    }

    /** Alle Knoten des Teilbaums mit einem dieser Typen, in Vorordnung. */
    public List<SyntaxNode> findByType(String... nodeTypes) {
        List<String> wanted = List.of(nodeTypes);
        return find(n -> wanted.contains(n.type));
    }

    /**
     * S-Ausdruck wie {@code tree-sitter parse}, z.B. {@code (class_declaration name: (identifier) body: (class_body))}
     * – nur benannte Knoten, höchstens bis zur angegebenen Tiefe (0 = unbegrenzt).
     */
    public String toSExpression(int maxDepth) {
        StringBuilder sb = new StringBuilder();
        sexp(sb, 0, maxDepth);
        return sb.toString();
    }

    private void sexp(StringBuilder sb, int depth, int maxDepth) {
        if (field != null && depth > 0) {
            sb.append(field).append(": ");
        }
        sb.append('(').append(isMissing() ? "MISSING " : "").append(type);
        if (maxDepth > 0 && depth + 1 >= maxDepth) {
            if (!named.isEmpty()) {
                sb.append(" …");
            }
        } else {
            for (SyntaxNode c : named) {
                sb.append(' ');
                c.sexp(sb, depth + 1, maxDepth);
            }
        }
        sb.append(')');
    }

    @Override
    public String toString() {
        return type + " [" + startRow + ":" + startColumn + "-" + endRow + ":" + endColumn + "]";
    }
}
