package systems.grebe.devtools.mcp.modules.scripts.assist;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import systems.grebe.devtools.mcp.modules.scripts.ScriptViews.Language;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Autovervollständigung ohne Oberfläche: Groovy-DSL und Typen, Java über javac, Gherkin-Schritte und Tools. Die
 * Schreibmarke steht im Quelltext als {@code ‸}.
 */
class ScriptAssistTest {

    private static final ToolInfo GIT_STATUS = new ToolInfo("git_status", "Status des Arbeitsverzeichnisses. Mehr.",
            List.of(new ToolInfo.Param("repository", "string", "Repository-Name", true),
                    new ToolInfo.Param("short", "boolean", "Kurzform", false)));

    private final ScriptAssist assist = new ScriptAssist(() -> List.of(GIT_STATUS,
            new ToolInfo("build_test", "Tests ausführen", List.of())));

    private static final String GROOVY = """
            module {
                description 'Begrüßungen'
                setting 'baseUrl', 'Basis-URL', URL, required: true
                setting 'limit', 'Limit', INT
            }

            tool('hello') {
                description 'Begrüßt jemanden'
                param 'who', String, 'Wen begrüßen'
                param 'count', Integer, 'Wie oft', required: false
                execute { args, cfg ->
                    ‸
                }
            }
            """;

    private static final String JAVA = """
            import java.util.List;
            import systems.grebe.devtools.mcp.core.ModuleConfig;

            public class Demo {
                public String run(ModuleConfig config) {
                    String name = "x";
                    List<String> items = List.of();
                    ‸
                    return name;
                }
            }
            """;

    private static final String GHERKIN = """
            # language: de
            Funktionalität: Schnellcheck
              Prüft ein Repository.

              Szenario: Branch prüfen
                Prüft das Arbeitsverzeichnis.
                <repo>: Repository-Name

                ‸
            """;

    @BeforeAll
    static void loadIndex() {
        assertThat(ClassIndex.shared().await(120_000)).isTrue();
    }

    // ------------------------------------------------------------------ Groovy

    @Test
    void groovyOffersTheDslMatchingTheBlock() {
        assertThat(labels(Language.GROOVY, "‸")).contains("module", "tool");
        assertThat(labels(Language.GROOVY, "module {\n    ‸\n}")).contains("description", "setting", "name")
                .doesNotContain("param", "execute");
        assertThat(labels(Language.GROOVY, "tool('a') {\n    ‸\n}")).contains("param", "execute", "readOnly")
                .doesNotContain("setting");
        assertThat(labels(Language.GROOVY, "module {\n    setting 'token', 'Token', SE‸\n}").getFirst())
                .isEqualTo("SECRET");
        assertThat(labels(Language.GROOVY, "tool('a') {\n    param 'limit', ‸\n}"))
                .contains("String", "Integer", "Boolean", "required");
        assertThat(labels(Language.GROOVY, "tool('a') {\n    readOnly ‸\n}")).containsExactly("true", "false");
    }

    @Test
    void groovySnippetPlacesTheCaret() {
        CompletionResult r = complete(Language.GROOVY, "tool('a') {\n    exe‸\n}");
        Completion execute = r.rank("exe").getFirst().item();
        assertThat(execute.insert()).isEqualTo("execute { args, cfg ->\n    \n}");
        assertThat(execute.caretOffset()).isEqualTo("execute { args, cfg ->\n    ".length());
    }

    @Test
    void groovyKnowsArgsAndCfgOfTheTool() {
        List<String> args = labels(Language.GROOVY, GROOVY.replace("‸", "args.‸"));
        assertThat(args.subList(0, 2)).containsExactlyInAnyOrder("count", "who");
        assertThat(args).contains("get", "containsKey", "each");
        assertThat(labels(Language.GROOVY, GROOVY.replace("‸", "cfg.‸")).subList(0, 2))
                .containsExactlyInAnyOrder("baseUrl", "limit");
        assertThat(labels(Language.GROOVY, GROOVY.replace("‸", "args.who.toU‸"))).contains("toUpperCase");
        assertThat(labels(Language.GROOVY, GROOVY.replace("‸", "cfg.limit.‸"))).contains("intValue");
        assertThat(labels(Language.GROOVY, GROOVY.replace("‸", "ar‸")).getFirst()).isEqualTo("args");
    }

    @Test
    void groovyResolvesTypesOfVariablesLiteralsAndChains() {
        assertThat(labels(Language.GROOVY, GROOVY.replace("‸", "'abc'.trim().‸")))
                .contains("toUpperCase", "capitalize", "length", "empty");
        assertThat(labels(Language.GROOVY, GROOVY.replace("‸", "def list = new ArrayList<String>()\nlist.‸")))
                .contains("add", "each", "collect", "size");
        CompletionResult typed = complete(Language.GROOVY, GROOVY.replace("‸", "List<String> names = []\nnames.g‸"));
        Completion get = typed.items().stream().filter(c -> c.label().equals("get")).findFirst().orElseThrow();
        assertThat(get.detail()).isEqualTo("String");
        assertThat(labels(Language.GROOVY, GROOVY.replace("‸", "log.‸"))).contains("info", "debug", "warn");
        assertThat(labels(Language.GROOVY, GROOVY.replace("‸", "new File('x').‸"))).contains("exists", "name");
        assertThat(labels(Language.GROOVY, GROOVY.replace("‸", "[1, 2].‸"))).contains("sum", "first");
        assertThat(labels(Language.GROOVY, "import java.net.http.HttpClient\n" + GROOVY.replace("‸",
                "HttpClient.‸"))).contains("newHttpClient", "newBuilder");
        assertThat(labels(Language.GROOVY, GROOVY.replace("‸", "Collections.‸"))).contains("emptyList");
        assertThat(labels(Language.GROOVY, GROOVY.replace("‸", "java.util.‸"))).contains("Map", "concurrent");
    }

    @Test
    void groovyClassNamesComeWithImport() {
        CompletionResult r = complete(Language.GROOVY, GROOVY.replace("‸", "JsonSlu‸"));
        Completion c = r.rank("JsonSlu").getFirst().item();
        assertThat(c.label()).isEqualTo("JsonSlurper");
        assertThat(c.importName()).isEqualTo("groovy.json.JsonSlurper");
        Completion list = complete(Language.GROOVY, GROOVY.replace("‸", "ArrayLi‸")).items().stream()
                .filter(i -> i.label().equals("ArrayList") && " (java.util)".equals(i.tail())).findFirst()
                .orElseThrow();
        assertThat(list.importName()).isNull(); // java.util ist Standard-Import
    }

    @Test
    void groovyStaysQuietInStringsAndCompletesTheDirective() {
        assertThat(complete(Language.GROOVY, "module {\n    description 'Hal‸lo'\n}").items()).isEmpty();
        assertThat(complete(Language.GROOVY, "// Kommentar mit wo‸").items()).isEmpty();
        assertThat(labels(Language.GROOVY, "// devtools: comp‸")).containsExactly("compileStatic");
        assertThat(labels(Language.GROOVY, GROOVY.replace("‸", "\"Hallo ${args.‸}\""))).contains("who");
    }

    // ------------------------------------------------------------------ Java

    @Test
    void javaCompletesMembersLocalsAndClassesSemantically() {
        long t0 = System.nanoTime();
        assertThat(labels(Language.JAVA, JAVA.replace("‸", "config.getS‸"))).contains("getString");
        long first = System.nanoTime() - t0;
        t0 = System.nanoTime();
        assertThat(labels(Language.JAVA, JAVA.replace("‸", "name.‸"))).contains("length", "isEmpty", "chars");
        long second = System.nanoTime() - t0;
        System.out.printf("Java-Vervollständigung: erste %d ms, zweite %d ms%n", first / 1_000_000,
                second / 1_000_000);

        Completion get = complete(Language.JAVA, JAVA.replace("‸", "items.‸")).items().stream()
                .filter(c -> c.label().equals("get")).findFirst().orElseThrow();
        assertThat(get.detail()).isEqualTo("String");
        assertThat(labels(Language.JAVA, JAVA.replace("‸", "na‸")).getFirst()).isEqualTo("name");
        assertThat(labels(Language.JAVA, JAVA.replace("‸", "List.‸"))).contains("of", "copyOf", "class");

        Completion http = complete(Language.JAVA, JAVA.replace("‸", "HttpCl‸")).rank("HttpCl").getFirst().item();
        assertThat(http.label()).isEqualTo("HttpClient");
        assertThat(http.importName()).isEqualTo("java.net.http.HttpClient");
        assertThat(labels(Language.JAVA, "import java.net.ht‸\n" + JAVA.replace("‸", ""))).contains("http");
        assertThat(labels(Language.JAVA, JAVA.replace("    public String run", "    @Overr‸\n    public String run")
                .replace("‸\n        return", "\n        return"))).contains("Override");
        assertThat(complete(Language.JAVA, JAVA.replace("‸", "String s = \"na‸\";")).items()).isEmpty();
    }

    // ------------------------------------------------------------------ Gherkin

    @Test
    void gherkinOffersStepsInTheRightWordOrder() {
        List<String> when = labels(Language.GHERKIN, GHERKIN.replace("‸", "Wenn ‸"));
        assertThat(when.getFirst()).isEqualTo("ich das Tool \"…\" aufrufe");
        List<String> then = labels(Language.GHERKIN, GHERKIN.replace("‸", "Dann ‸"));
        assertThat(then.getFirst()).startsWith("enthält das Ergebnis");
        assertThat(labels(Language.GHERKIN, GHERKIN.replace("‸", "Und ich merke mir ‸")))
                .contains("ich merke mir das Ergebnis als name");

        GherkinAssist.Expanded wait = GherkinAssist.expand("ich warte {int} Sekunde(n)");
        assertThat(wait.insert()).isEqualTo("ich warte 10 Sekunden");
        assertThat(wait.caret()).isEqualTo("ich warte ".length());
        assertThat(wait.select()).isEqualTo(2);
    }

    @Test
    void gherkinCompletesToolsParametersPlaceholdersAndVariables() {
        assertThat(labels(Language.GHERKIN, GHERKIN.replace("‸", "Wenn ich das Tool \"git‸")))
                .containsExactly("git_status");
        CompletionResult table = complete(Language.GHERKIN, GHERKIN.replace("‸",
                "Wenn ich das Tool \"git_status\" aufrufe:\n      | ‸"));
        assertThat(table.items()).extracting(Completion::label).containsExactly("repository", "short");
        assertThat(table.items().getFirst().insert()).isEqualTo("repository |  |");
        assertThat(labels(Language.GHERKIN, GHERKIN.replace("‸", "Dann enthält das Ergebnis \"<r‸")))
                .containsExactly("repo");
        assertThat(labels(Language.GHERKIN, GHERKIN.replace("‸",
                "Und ich merke mir das Ergebnis als status\n    Und ich gebe \"${st‸")))
                .containsExactly("status");
        assertThat(labels(Language.GHERKIN, GHERKIN.replace("‸", "We‸")).getFirst()).isEqualTo("Wenn");
        assertThat(labels(Language.GHERKIN, "‸")).contains("Funktionalität:");
        assertThat(labels(Language.GHERKIN, GHERKIN.replace("  Szenario", "  @re‸\n  Szenario")))
                .containsExactly("readOnly");
        assertThat(labels(Language.GHERKIN, "# language: e‸").getFirst()).isEqualTo("en");
    }

    @Test
    void popupOpensByItselfWhereIntellijWould() {
        assertThat(ScriptAssist.autoTrigger(Language.GROOVY, "args.", 5, '.')).isTrue();
        assertThat(ScriptAssist.autoTrigger(Language.GROOVY, "1.", 2, '.')).isFalse();
        assertThat(ScriptAssist.autoTrigger(Language.GROOVY, "x = a", 5, 'a')).isTrue();
        assertThat(ScriptAssist.autoTrigger(Language.GROOVY, "x = ab", 6, 'b')).isFalse(); // Liste filtert nur
        assertThat(ScriptAssist.autoTrigger(Language.GROOVY, "'a", 2, 'a')).isFalse(); // in einer Zeichenkette
        assertThat(ScriptAssist.autoTrigger(Language.JAVA, "@", 1, '@')).isTrue();
        assertThat(ScriptAssist.autoTrigger(Language.GHERKIN, "  Wenn ", 7, ' ')).isTrue();
        assertThat(ScriptAssist.autoTrigger(Language.GHERKIN, "  Wenn ich das Tool \"\"", 21, '"')).isTrue();
        assertThat(ScriptAssist.autoTrigger(Language.GHERKIN, "  | ", 4, ' ')).isTrue();
        assertThat(ScriptAssist.autoTrigger(Language.GHERKIN, "  Prüft x", 9, 'x')).isFalse();
    }

    // ------------------------------------------------------------------ Hilfen

    private CompletionResult complete(Language language, String marked) {
        int caret = marked.indexOf('‸');
        return assist.complete(language, marked.replace("‸", ""), caret);
    }

    private List<String> labels(Language language, String marked) {
        int caret = marked.indexOf('‸');
        String text = marked.replace("‸", "");
        CompletionResult r = assist.complete(language, text, caret);
        return r.rank(text.substring(r.from(), caret)).stream().map(x -> x.item().label()).toList();
    }
}
