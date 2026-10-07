package systems.grebe.devtools.mcp.syntax;

import java.nio.charset.StandardCharsets;

/**
 * Syntaxbaum einer Datei: Wurzel, Quelltext (UTF-8) und ob der Parser Syntaxfehler gefunden hat. tree-sitter liefert
 * auch bei Fehlern einen vollständigen Baum – fehlerhafte Stellen stehen als {@code ERROR}- bzw. fehlende Knoten darin.
 */
public final class SyntaxTree {

    private final Language language;
    private final byte[] source;
    private final boolean hasError;
    private SyntaxNode root;

    SyntaxTree(Language language, byte[] source, boolean hasError) {
        this.language = language;
        this.source = source;
        this.hasError = hasError;
    }

    void root(SyntaxNode root) {
        this.root = root;
    }

    public Language language() {
        return language;
    }

    public SyntaxNode root() {
        return root;
    }

    /** Syntaxfehler irgendwo in der Datei. */
    public boolean hasError() {
        return hasError;
    }

    /** Quelltext als UTF-8-Bytes (Offsets der Knoten beziehen sich darauf); nicht verändern. */
    public byte[] source() {
        return source;
    }

    /** Anzahl Zeilen (Endzeile der Wurzel + 1). */
    public int lines() {
        return root.endRow() + 1;
    }

    /** Quelltext zwischen zwei Byte-Offsets. */
    public String text(int startByte, int endByte) {
        return new String(source, startByte, endByte - startByte, StandardCharsets.UTF_8);
    }

    /** Quelltext eines Knotens; {@code ""} für {@code null}. */
    public String text(SyntaxNode node) {
        return node == null ? "" : text(node.startByte(), node.endByte());
    }

    /** Durchläuft den ganzen Baum in Vorordnung. */
    public void walk(SyntaxVisitor visitor) {
        root.walk(visitor);
    }
}
