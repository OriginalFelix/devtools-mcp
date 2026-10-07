package systems.grebe.devtools.mcp.syntax;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class SyntaxEngineTest {

    /** Je Sprache: kleines Beispiel, Typ der Wurzel und ein Knotentyp, der darin vorkommen muss. */
    private record Sample(String source, String root, String contains) {
    }

    private static Sample sample(Language l) {
        return switch (l) {
            case JAVA -> new Sample("class A { void m() { int x = 1; } }", "program", "method_declaration");
            case KOTLIN -> new Sample("fun main() { println(\"hi\") }", "source_file", "function_declaration");
            case SCALA -> new Sample("object A { def m(): Int = 1 }", "compilation_unit", "function_definition");
            case PYTHON -> new Sample("def f(x):\n    return x\n", "module", "function_definition");
            case JAVASCRIPT -> new Sample("function f(a) { return a + 1; }", "program", "function_declaration");
            case TYPESCRIPT -> new Sample("function f(a: number): number { return a; }", "program", "function_declaration");
            case TSX -> new Sample("const x = <div>{1}</div>;", "program", "jsx_element");
            case GO -> new Sample("package main\nfunc main() {}\n", "source_file", "function_declaration");
            case RUST -> new Sample("fn main() { let x = 1; }", "source_file", "function_item");
            case C -> new Sample("int main(void) { return 0; }", "translation_unit", "function_definition");
            case CPP -> new Sample("class A { public: int f(); };", "translation_unit", "class_specifier");
            case CSHARP -> new Sample("class A { void M() {} }", "compilation_unit", "method_declaration");
            case PHP -> new Sample("<?php function f($a) { return $a; }", "program", "function_definition");
            case RUBY -> new Sample("def f(a)\n  a\nend\n", "program", "method");
            case BASH -> new Sample("echo hi | grep h\n", "program", "pipeline");
        };
    }

    @ParameterizedTest
    @EnumSource(Language.class)
    void parsesEveryLanguage(Language language) {
        assertThat(SyntaxEngine.isAvailable(language)).as("Grammatik " + language.id() + " im Build").isTrue();
        Sample s = sample(language);
        SyntaxTree tree = SyntaxEngine.parse(language, s.source());
        assertThat(tree.language()).isEqualTo(language);
        assertThat(tree.hasError()).as(tree.root().toSExpression(0)).isFalse();
        assertThat(tree.root().type()).isEqualTo(s.root());
        assertThat(tree.root().findByType(s.contains())).as(tree.root().toSExpression(0)).isNotEmpty();
    }

    @Test
    void javaTreeWithFieldsKeywordsAndPositions() {
        String src = """
                package a;
                /** Doc. */
                public non-sealed class Grüße extends Base {
                    static int zähler = 1;
                    void m(String s) { foo(s, "ä"); }
                }
                """;
        SyntaxTree tree = SyntaxEngine.parse(Language.JAVA, src);
        SyntaxNode cls = tree.root().findByType("class_declaration").getFirst();
        assertThat(cls.child("name").text()).isEqualTo("Grüße");
        assertThat(cls.line()).isEqualTo(3);
        assertThat(cls.child("superclass").text()).isEqualTo("extends Base");
        // Schlüsselwörter bleiben als unbenannte Kinder erhalten, auch mit Bindestrich
        SyntaxNode mods = cls.firstChildOfType("modifiers");
        assertThat(mods.children()).extracting(SyntaxNode::type).containsExactly("public", "non-sealed");
        assertThat(mods.namedChildren()).isEmpty();
        assertThat(cls.prevNamedSibling().type()).isEqualTo("block_comment");

        SyntaxNode call = tree.root().findByType("method_invocation").getFirst();
        assertThat(call.child("name").text()).isEqualTo("foo");
        assertThat(call.child("arguments").namedChildren()).extracting(SyntaxNode::text).containsExactly("s", "\"ä\"");
        assertThat(call.ancestor("method_declaration").child("name").text()).isEqualTo("m");
        // Offsets in UTF-8-Bytes: Text über Umlaute hinweg stimmt
        SyntaxNode field = tree.root().findByType("variable_declarator").getFirst();
        assertThat(field.text()).isEqualTo("zähler = 1");
        assertThat(tree.root().namedDescendantAt(4, 24).text()).isEqualTo("foo");
    }

    @Test
    void punctuationOnlyWithAllTokens() {
        String src = "class A { int f() { return 1 + 2; } }";
        SyntaxNode plain = SyntaxEngine.parse(Language.JAVA, src).root();
        assertThat(plain.find(n -> n.type().equals("+"))).isEmpty();
        assertThat(plain.find(n -> n.type().equals("return"))).hasSize(1);

        SyntaxNode all = SyntaxEngine.parse(Language.JAVA, src, true).root();
        SyntaxNode plus = all.find(n -> n.type().equals("+")).getFirst();
        assertThat(plus.isNamed()).isFalse();
        assertThat(plus.field()).isEqualTo("operator");
        assertThat(plus.parent().type()).isEqualTo("binary_expression");
    }

    @Test
    void syntaxErrorsAreMarked() {
        SyntaxTree tree = SyntaxEngine.parse(Language.PYTHON, "def f(:\n    pass\n");
        assertThat(tree.hasError()).isTrue();
        assertThat(tree.root().hasError()).isTrue();
        assertThat(tree.root().find(n -> n.isError() || n.isMissing())).isNotEmpty();
        // Der Rest der Datei bleibt lesbar
        SyntaxTree broken = SyntaxEngine.parse(Language.JAVA, "package a;\nclass Broken { void ok() {} void kaputt( { }\n");
        assertThat(broken.hasError()).isTrue();
        assertThat(broken.root().findByType("class_declaration")).extracting(n -> n.child("name").text())
                .containsExactly("Broken");
    }

    @Test
    void walkSkipsSubtreesAndSExpression() {
        SyntaxNode root = SyntaxEngine.parse(Language.GO, "package main\nfunc a() { b() }\nfunc c() {}\n").root();
        List<String> names = new ArrayList<>();
        root.walk(n -> {
            if (n.type().equals("function_declaration")) {
                names.add(n.child("name").text());
                return false; // Rumpf nicht betreten
            }
            return true;
        });
        assertThat(names).containsExactly("a", "c");
        assertThat(root.toSExpression(2)).isEqualTo("(source_file (package_clause …) (function_declaration …) (function_declaration …))");
    }

    @Test
    void deeplyNestedSourceDoesNotOverflow() {
        String expr = "1" + " + 1".repeat(20_000);
        SyntaxNode root = SyntaxEngine.parse(Language.JAVASCRIPT, "x = " + expr + ";").root();
        int[] count = {0};
        root.forEach(n -> count[0]++);
        assertThat(count[0]).isGreaterThan(40_000);
    }

    @Test
    void parallelParsingUsesSeparateInstances() throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 64; i++) {
                int n = i;
                results.add(pool.submit(() -> SyntaxEngine.parse(Language.JAVA,
                        "class C" + n + " { void m" + n + "() {} }").root()
                        .findByType("method_declaration").getFirst().child("name").text()));
            }
            for (int i = 0; i < results.size(); i++) {
                assertThat(results.get(i).get()).isEqualTo("m" + i);
            }
        }
    }

    @Test
    void languageByFileAndName() {
        assertThat(Language.forFile(Path.of("src/Foo.java"))).contains(Language.JAVA);
        assertThat(Language.forFileName("x.hpp")).contains(Language.CPP);
        assertThat(Language.forFileName("x.h")).contains(Language.C);
        assertThat(Language.forFileName("App.tsx")).contains(Language.TSX);
        assertThat(Language.forFileName("App.mts")).contains(Language.TYPESCRIPT);
        assertThat(Language.forFileName("build.gradle.kts")).contains(Language.KOTLIN);
        assertThat(Language.forFileName("README.md")).isEmpty();
        assertThat(Language.byName("C#")).contains(Language.CSHARP);
        assertThat(Language.byName("c++")).contains(Language.CPP);
        assertThat(Language.byName("TypeScript")).contains(Language.TYPESCRIPT);
    }
}
