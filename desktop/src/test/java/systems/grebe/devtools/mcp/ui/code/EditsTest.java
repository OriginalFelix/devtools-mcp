package systems.grebe.devtools.mcp.ui.code;

import org.junit.jupiter.api.Test;
import systems.grebe.devtools.mcp.modules.scripts.ScriptViews.Language;
import systems.grebe.devtools.mcp.modules.scripts.assist.Completion;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Editierverhalten des Skript-Editors ohne JavaFX. Im Text steht {@code ‸} für die Schreibmarke, {@code «…»} für eine
 * Auswahl – vorher wie nachher.
 */
class EditsTest {

    private static final Language G = Language.GROOVY;

    @Test
    void enterIndentsLikeIntellij() {
        assertThat(edit("module {‸}", (t, s, e) -> Edits.newline(G, t, s, e))).isEqualTo("module {\n    ‸\n}");
        assertThat(edit("    foo()‸", (t, s, e) -> Edits.newline(G, t, s, e))).isEqualTo("    foo()\n    ‸");
        assertThat(edit("execute { args ->‸", (t, s, e) -> Edits.newline(G, t, s, e)))
                .isEqualTo("execute { args ->\n    ‸");
        assertThat(edit("  Szenario: Prüfen‸", (t, s, e) -> Edits.newline(Language.GHERKIN, t, s, e)))
                .isEqualTo("  Szenario: Prüfen\n    ‸");
        assertThat(edit("    Wenn ich das Tool \"x\" aufrufe:‸", (t, s, e) -> Edits.newline(Language.GHERKIN, t, s, e)))
                .isEqualTo("    Wenn ich das Tool \"x\" aufrufe:\n      ‸");
    }

    @Test
    void pairsAndOvertyping() {
        assertThat(typed("foo‸", '(')).isEqualTo("foo(‸)");
        assertThat(typed("foo(‸)", ')')).isEqualTo("foo()‸");
        assertThat(typed("def x = ‸", '"')).isEqualTo("def x = \"‸\"");
        assertThat(typed("def x = \"ab‸\"", '"')).isEqualTo("def x = \"ab\"‸");
        assertThat(typed("\"Hallo $‸\"", '{')).isEqualTo("\"Hallo ${‸}\"");
        assertThat(Edits.typed(G, "it's", 2, '\'')).isNull(); // nach einem Buchstaben kein Paar
        assertThat(typed("if (x) {\n        ‸", '}')).isEqualTo("if (x) {\n    }‸");
        assertThat(edit("(‸)", (t, s, e) -> Edits.deletePair(G, t, s))).isEqualTo("‸");
    }

    @Test
    void tabShiftCommentDuplicate() {
        assertThat(edit("ab‸", (t, s, e) -> Edits.tab(G, t, s, e))).isEqualTo("ab  ‸");
        assertThat(edit("«a\nb»", (t, s, e) -> Edits.tab(G, t, s, e))).isEqualTo("«    a\n    b»");
        assertThat(edit("    foo‸", (t, s, e) -> Edits.shift(G, t, s, e, false))).isEqualTo("foo‸");
        assertThat(edit("    foo‸\nbar", (t, s, e) -> Edits.toggleComment(G, t, s, e)))
                .isEqualTo("    // foo\n‸bar");
        assertThat(edit("«    // a\n    // b»", (t, s, e) -> Edits.toggleComment(G, t, s, e)))
                .isEqualTo("«    a\n    b»");
        assertThat(edit("  Wenn x‸", (t, s, e) -> Edits.toggleComment(Language.GHERKIN, t, s, e)))
                .isEqualTo("  # Wenn x‸");
        assertThat(edit("ab‸c", (t, s, e) -> Edits.duplicate(t, s, e))).isEqualTo("abc\nab‸c");
    }

    @Test
    void acceptingACompletion() {
        Completion execute = Completion.of(Completion.Kind.METHOD, "execute")
                .withTemplate("execute { args ->\n    |\n}");
        assertThat(accept("    exe‸", 4, false, execute)).isEqualTo("    execute { args ->\n        ‸\n    }");
        Completion get = Completion.of(Completion.Kind.METHOD, "get").withInsert("get()", 4, 0);
        assertThat(accept("foo.ge‸(x)", 4, false, get)).isEqualTo("foo.get‸(x)");
        assertThat(accept("foo.ge‸tX", 4, true, get)).isEqualTo("foo.get(‸)");
        Completion wait = Completion.of(Completion.Kind.STEP, "ich warte 10 Sekunden")
                .withInsert("ich warte 10 Sekunden", 10, 2);
        assertThat(accept("Und ich w‸", 4, false, wait)).isEqualTo("Und ich warte «10» Sekunden");
    }

    @Test
    void importsGoWhereIntellijPutsThem() {
        assertThat(imported(G, "// devtools: compileStatic\nmodule {}", "groovy.json.JsonSlurper"))
                .isEqualTo("// devtools: compileStatic\nimport groovy.json.JsonSlurper\n\nmodule {}");
        assertThat(imported(G, "import java.time.Instant\n\nmodule {}", "groovy.json.JsonSlurper"))
                .isEqualTo("import java.time.Instant\nimport groovy.json.JsonSlurper\n\nmodule {}");
        assertThat(Edits.addImport(G, "import groovy.json.*\n", "groovy.json.JsonSlurper")).isNull();
        assertThat(Edits.addImport(Language.JAVA, "import java.util.List;\n", "java.util.List")).isNull();
        assertThat(imported(Language.JAVA, "package a;\n\npublic class A {}", "java.net.http.HttpClient"))
                .isEqualTo("package a;\n\nimport java.net.http.HttpClient;\n\npublic class A {}");
        assertThat(imported(Language.JAVA, "public class A {}", "java.net.http.HttpClient"))
                .isEqualTo("import java.net.http.HttpClient;\n\npublic class A {}");
    }

    // ------------------------------------------------------------------ Hilfen

    @FunctionalInterface
    private interface Op {
        Edits.Edit apply(String text, int start, int end);
    }

    private static String edit(String marked, Op op) {
        String text = marked.replace("‸", "").replace("«", "").replace("»", "");
        int start;
        int end;
        if (marked.contains("«")) {
            start = marked.indexOf('«');
            end = marked.indexOf('»') - 1;
        } else {
            start = end = marked.indexOf('‸');
        }
        Edits.Edit e = op.apply(text, start, end);
        return e == null ? null : show(e.applyTo(text), e.anchor(), e.caret());
    }

    private static String typed(String marked, char c) {
        return edit(marked, (t, s, e) -> Edits.typed(G, t, s, c));
    }

    private static String accept(String marked, int from, boolean replaceWord, Completion c) {
        return edit(marked, (t, s, e) -> Edits.accept(t, from, s, replaceWord, c));
    }

    private static String imported(Language language, String text, String name) {
        Edits.Edit e = Edits.addImport(language, text, name);
        return e.applyTo(text);
    }

    private static String show(String text, int anchor, int caret) {
        if (anchor == caret) {
            return text.substring(0, caret) + "‸" + text.substring(caret);
        }
        int a = Math.min(anchor, caret);
        int b = Math.max(anchor, caret);
        return text.substring(0, a) + "«" + text.substring(a, b) + "»" + text.substring(b);
    }
}
