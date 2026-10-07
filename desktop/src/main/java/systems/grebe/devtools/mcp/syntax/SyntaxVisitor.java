package systems.grebe.devtools.mcp.syntax;

/** Besucher für {@link SyntaxNode#walk}: {@code false} überspringt die Kinder des Knotens. */
@FunctionalInterface
public interface SyntaxVisitor {

    boolean visit(SyntaxNode node);
}
