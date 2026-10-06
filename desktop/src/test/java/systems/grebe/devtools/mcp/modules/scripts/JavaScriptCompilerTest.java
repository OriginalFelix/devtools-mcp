package systems.grebe.devtools.mcp.modules.scripts;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.McpToolHints;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Java-Skripte ohne Spring: javac im Speicher, ToolModule-API, Fehler mit Zeile, Klassenpfad aus dem Boot-Jar. */
class JavaScriptCompilerTest {

    private final JavaScriptCompiler compiler = new JavaScriptCompiler();

    private static ScriptViews.Summary summary(String name) {
        return new ScriptViews.Summary(name, "d", ScriptViews.Scope.OWN, ScriptViews.Language.JAVA, 1, Instant.now(),
                null);
    }

    private static String template(String description) {
        return ScriptTemplates.JAVA.replace(ScriptTemplates.PLACEHOLDER_DESCRIPTION, description);
    }

    @Test
    void templateCompilesToAModuleWithToolsSettingsAndHints() {
        try (JavaScriptCompiler.Compiled c = compiler.compile("hello", template("Java-Begrüßungen"))) {
            assertThat(c.displayName()).isEqualTo("Begrüßungen");
            assertThat(c.description()).isEqualTo("Java-Begrüßungen");
            assertThat(c.settings()).extracting(f -> f.key()).containsExactly("greeting");
            assertThat(c.toolNames()).containsExactly("hello");

            ScriptToolModule module = ScriptToolModule.of(summary("hello"), c, () -> Duration.ofSeconds(5));
            assertThat(module.id()).isEqualTo("hello");
            ToolCallback hello = module.createTools(ModuleConfig.of(module.configSchema(),
                    Map.of("greeting", "Moin"))).getFirst();
            assertThat(McpToolHints.annotations(hello).readOnlyHint()).isTrue();
            assertThat(hello.getToolDefinition().inputSchema()).contains("\"who\"", "Wen begrüßen");
            assertThat(hello.call("{\"who\":\"Welt\"}")).contains("Moin Welt!");
        }
    }

    @Test
    void idIsAlwaysTheScriptName() {
        try (JavaScriptCompiler.Compiled c = compiler.compile("other", template("x"))) {
            assertThat(ScriptToolModule.of(summary("other"), c, () -> Duration.ofSeconds(5)).id()).isEqualTo("other");
        }
    }

    @Test
    void errorsNameTheLine() {
        assertThatThrownBy(() -> compiler.compile("bad", template("x").replace("return greeting", "return grreting")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nicht übersetzen")
                .hasMessageContaining("Zeile").hasMessageContaining("grreting");
        assertThatThrownBy(() -> compiler.compile("bad", "public class X { }"))
                .hasMessageContaining("muss ToolModule implementieren");
        assertThatThrownBy(() -> compiler.compile("bad", "class X { }"))
                .hasMessageContaining("public class");
        assertThatThrownBy(() -> compiler.compile("bad", template(" ")))
                .hasMessageContaining("description()");
        assertThatThrownBy(() -> compiler.compile("bad", template("x").replace("public class Hello implements",
                "public class Hello implements").replace("    @Override\n    public String id()",
                "    public Hello(int unused) { }\n\n    @Override\n    public String id()")))
                .hasMessageContaining("Konstruktor ohne Parameter");
    }

    @Test
    void runtimeErrorsCarryTheLineAndLongRunsAreInterrupted() {
        String failing = template("x").replace("return greeting + \" \" + who + \"!\";",
                "if (who.equals(\"loop\")) { while (!Thread.currentThread().isInterrupted()) { } return \"weg\"; }\n"
                        + "            throw new IllegalStateException(\"kaputt\");");
        try (JavaScriptCompiler.Compiled c = compiler.compile("fail", failing)) {
            ToolCallback t = ScriptToolModule.of(summary("fail"), c, () -> Duration.ofMillis(300))
                    .createTools(ModuleConfig.of(c.settings(), Map.of())).getFirst();
            assertThatThrownBy(() -> t.call("{\"who\":\"x\"}")).hasMessageContaining("kaputt")
                    .hasMessageContaining("Zeile");
            // Java-Code wird nicht instrumentiert: wer auf Interrupts achtet, endet nach dem Zeitlimit
            assertThatThrownBy(() -> t.call("{\"who\":\"loop\"}")).hasMessageContaining("Zeitlimit");
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
        }
    }

    @Test
    void bootJarLibrariesAreExtractedForJavac(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("app.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (String name : List.of("BOOT-INF/classes/a/A.class", "BOOT-INF/lib/one.jar", "BOOT-INF/lib/two.jar",
                    "org/springframework/boot/loader/Launcher.class")) {
                out.putNextEntry(new JarEntry(name));
                out.write(1);
                out.closeEntry();
            }
        }
        Path plain = Files.createDirectories(dir.resolve("classes"));
        String cp = JavaClasspath.build(jar + File.pathSeparator + plain, dir.resolve("extract"));
        List<String> entries = List.of(cp.split(File.pathSeparator));
        assertThat(entries).hasSize(4).last().isEqualTo(plain.toString());
        assertThat(entries.getFirst()).endsWith("classes");
        assertThat(Path.of(entries.getFirst()).resolve("a/A.class")).exists();
        assertThat(entries.get(1)).endsWith("one.jar");
        // zweites Mal: wiederverwendet, nicht neu entpackt
        assertThat(JavaClasspath.build(jar.toString(), dir.resolve("extract"))).startsWith(entries.getFirst());
    }
}
