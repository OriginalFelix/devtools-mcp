package systems.grebe.devtools.mcp.modules.scripts.assist;

import java.util.List;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import systems.grebe.devtools.mcp.modules.scripts.ScriptViews;

/**
 * Autovervollständigung für den Skript-Editor – je Sprache: Groovy (DSL, Typen über javac), Java (vollständig über
 * javac) und Gherkin (Schritte, Tools, Platzhalter). Rechnet ohne Oberfläche und ohne das Skript auszuführen; Aufrufe
 * aus einem Hintergrund-Thread, da Java-Analysen einige hundert Millisekunden brauchen können.
 */
public final class ScriptAssist {

    private static final Logger LOG = LoggerFactory.getLogger(ScriptAssist.class);

    private final JavaModel model = JavaModel.shared();
    private final ClassIndex classes = ClassIndex.shared();
    private final GroovyAssist groovy = new GroovyAssist(model, classes);
    private final JavaAssist java = new JavaAssist(model, classes);
    private final GherkinAssist gherkin;

    /** @param tools die gerade aufrufbaren Tools (für Gherkin-Schritte) */
    public ScriptAssist(Supplier<List<ToolInfo>> tools) {
        this.gherkin = new GherkinAssist(tools);
    }

    /** Vorschläge an der Schreibmarke; bei unerwarteten Fehlern keine. */
    public CompletionResult complete(ScriptViews.Language language, String text, int caret) {
        try {
            return switch (language == null ? ScriptViews.Language.GROOVY : language) {
                case GROOVY -> groovy.complete(text, caret);
                case JAVA -> java.complete(text, caret);
                case GHERKIN -> gherkin.complete(text, caret);
            };
        } catch (RuntimeException | StackOverflowError e) {
            LOG.debug("Keine Vervollständigung an Position {}: {}", caret, e.toString());
            return CompletionResult.none(caret);
        }
    }

    /**
     * Ob nach dem gerade getippten Zeichen automatisch vervollständigt wird (wie IntelliJ): in Code nach einem Punkt
     * und beim ersten Buchstaben eines Wortes, in Java nach {@code @}; in Gherkin nach einem Schritt-Schlüsselwort,
     * in {@code Tool "}, nach {@code <}, {@code ${}, {@code @}, in der ersten Tabellenspalte und beim ersten Buchstaben
     * einer Zeile.
     *
     * @param caret Schreibmarke nach dem eingefügten Zeichen
     */
    public static boolean autoTrigger(ScriptViews.Language language, String text, int caret, char typed) {
        if (caret <= 0 || caret > text.length()) {
            return false;
        }
        char before = caret >= 2 ? text.charAt(caret - 2) : '\n';
        if (language == ScriptViews.Language.GHERKIN) {
            int lineStart = text.lastIndexOf('\n', caret - 1) + 1;
            String line = text.substring(lineStart, caret).stripLeading();
            if (typed == ' ') {
                return line.equals("| ") || SyntaxHighlighter.stepKeywords(SyntaxHighlighter.dialect(text)).stream()
                        .anyMatch(k -> !k.isBlank() && line.equals(k));
            }
            if (typed == '"') {
                return line.matches("(?i).*\\btool\\s+\"");
            }
            if (typed == '<' || typed == '{' && before == '$' || typed == '|' && line.equals("|")) {
                return true;
            }
            if (typed == '@') {
                return line.matches("(@\\S*\\s+)*@");
            }
            return Character.isLetter(typed) && line.length() == 1;
        }
        if (SyntaxHighlighter.inLiteral(language, text, caret)) {
            return false;
        }
        if (typed == '.') {
            return before != '.' && !Character.isDigit(before);
        }
        if (typed == '@') {
            return language == ScriptViews.Language.JAVA;
        }
        return Character.isJavaIdentifierStart(typed) && typed != '$' && !Character.isJavaIdentifierPart(before);
    }

    /**
     * Lädt Klassenindex und Symbolmodell vor (im Hintergrund), damit die erste Vervollständigung nicht wartet – beim
     * ersten Öffnen des Editors aufrufen.
     *
     * @return der Hintergrund-Thread (Tests warten darauf)
     */
    public Thread warmUp() {
        classes.startLoading();
        return Thread.ofVirtual().name("script-assist-warmup").start(() -> {
            try {
                long t0 = System.nanoTime();
                // die häufigsten Empfänger einmal durchrechnen: javac-Symbole, GDK, JIT
                for (String receiver : List.of("args", "'x'", "[1]", "log", "new File('x')")) {
                    String text = "tool('a') {\n execute { args, cfg ->\n  " + receiver + ".\n }\n}";
                    groovy.complete(text, text.indexOf(receiver + ".") + receiver.length() + 1);
                }
                String source = "class A { void f(String s) { s. } }";
                java.complete(source, source.indexOf("s.") + 2);
                LOG.debug("Skript-Vervollständigung vorbereitet in {} ms", (System.nanoTime() - t0) / 1_000_000);
            } catch (RuntimeException e) {
                LOG.debug("Vorbereiten der Skript-Vervollständigung fehlgeschlagen: {}", e.toString());
            }
        });
    }
}
