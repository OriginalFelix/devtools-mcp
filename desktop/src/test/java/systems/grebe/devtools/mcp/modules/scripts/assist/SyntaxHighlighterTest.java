package systems.grebe.devtools.mcp.modules.scripts.assist;

import java.util.List;

import org.junit.jupiter.api.Test;
import systems.grebe.devtools.mcp.modules.scripts.ScriptTemplates;
import systems.grebe.devtools.mcp.modules.scripts.ScriptViews.Language;

import static org.assertj.core.api.Assertions.assertThat;

/** Hervorhebung der Vorlagen aller drei Sprachen und der IntelliJ-artige Abgleich der Eingabe. */
class SyntaxHighlighterTest {

    @Test
    void groovyTemplate() {
        String text = "// devtools: compileStatic\n" + ScriptTemplates.GROOVY;
        List<SyntaxHighlighter.Span> spans = SyntaxHighlighter.spans(Language.GROOVY, text);
        assertThat(style(spans, text, "// devtools: compileStatic")).isEqualTo("directive");
        assertThat(style(spans, text, "module")).isEqualTo("dsl");
        assertThat(style(spans, text, "param")).isEqualTo("dsl");
        assertThat(style(spans, text, "'Begrüßt jemanden'")).isEqualTo("str");
        assertThat(style(spans, text, "String")).isNull();
        assertThat(style(spans, text, "true")).isEqualTo("kw");
        assertThat(style(spans, text, "${")).isEqualTo("interp");
        assertThat(style(spans, text, "who}")).isEqualTo("field");
        assertThat(style(spans, text, "\"Hallo ")).isEqualTo("str");
    }

    @Test
    void groovyEdgeCases() {
        String text = "def r = /a\\d+/\ndef x = 10 / 2\ndef s = '''mehr\nzeilig'''\nlog.info \"x\" // Ende";
        List<SyntaxHighlighter.Span> spans = SyntaxHighlighter.spans(Language.GROOVY, text);
        assertThat(style(spans, text, "/a")).isEqualTo("str");
        assertThat(style(spans, text, "10")).isEqualTo("num");
        assertThat(style(spans, text, "zeilig'''")).isEqualTo("str");
        assertThat(style(spans, text, "log")).isEqualTo("dsl");
        assertThat(style(spans, text, "// Ende")).isEqualTo("cmt");
    }

    @Test
    void javaTemplate() {
        String text = ScriptTemplates.JAVA;
        List<SyntaxHighlighter.Span> spans = SyntaxHighlighter.spans(Language.JAVA, text);
        assertThat(style(spans, text, "import")).isEqualTo("kw");
        assertThat(style(spans, text, "@Override")).isEqualTo("ann");
        assertThat(style(spans, text, "id()")).isEqualTo("decl");
        assertThat(style(spans, text, "\"hello\"")).isEqualTo("str");
        assertThat(style(spans, text, "// wird")).isEqualTo("cmt");
        assertThat(style(spans, text, "STRING")).isEqualTo("const");
    }

    @Test
    void gherkinTemplate() {
        String text = ScriptTemplates.GHERKIN;
        List<SyntaxHighlighter.Span> spans = SyntaxHighlighter.spans(Language.GHERKIN, text);
        assertThat(style(spans, text, "# language: de")).isEqualTo("directive");
        assertThat(style(spans, text, "Funktionalität:")).isEqualTo("g-kw");
        assertThat(style(spans, text, "Beispiel")).isEqualTo("g-title");
        assertThat(style(spans, text, "@readOnly")).isEqualTo("tag");
        assertThat(style(spans, text, "Wenn")).isEqualTo("g-step");
        assertThat(style(spans, text, "\"scripts_list\"")).isEqualTo("str");
        assertThat(style(spans, text, "<name>:")).isEqualTo("ph");
        assertThat(style(spans, text, "\"Skript ")).isEqualTo("str");
    }

    @Test
    void camelHumpsLikeIntellij() {
        assertThat(CamelMatcher.match("gSN", "getScriptName").quality()).isEqualTo(CamelMatcher.START);
        assertThat(CamelMatcher.match("gsn", "getScriptName")).isNotNull();
        assertThat(CamelMatcher.match("hcl", "HttpClient")).isNotNull();
        assertThat(CamelMatcher.match("str", "toString").quality()).isEqualTo(CamelMatcher.MIDDLE);
        assertThat(CamelMatcher.match("str", "toString").ranges()).containsExactly(2, 5);
        assertThat(CamelMatcher.match("get", "getString").quality()).isEqualTo(CamelMatcher.EXACT_PREFIX);
        assertThat(CamelMatcher.match("Get", "getString").quality()).isEqualTo(CamelMatcher.PREFIX);
        assertThat(CamelMatcher.match("xyz", "getString")).isNull();
        assertThat(CamelMatcher.match("gtS", "getString")).isNull(); // S nicht an einem Wortanfang nach „gt“
        assertThat(CamelMatcher.match("Tool auf", "ich rufe das Tool \"…\" auf")).isNotNull();
    }

    /** CSS-Klasse des Bereichs, der beim ersten Vorkommen von {@code part} beginnt. */
    private static String style(List<SyntaxHighlighter.Span> spans, String text, String part) {
        int at = text.indexOf(part);
        assertThat(at).as(part).isGreaterThanOrEqualTo(0);
        return spans.stream().filter(s -> s.start() <= at && at < s.end()).map(SyntaxHighlighter.Span::style)
                .findFirst().orElse(null);
    }
}
