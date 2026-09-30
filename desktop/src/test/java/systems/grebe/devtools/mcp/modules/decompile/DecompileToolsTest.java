package systems.grebe.devtools.mcp.modules.decompile;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** decompile_* gegen das JDK, ein Klassenverzeichnis und JARs in einem Maven-artigen Suchpfad. */
class DecompileToolsTest {

    private static final String SAMPLE = DecompileSample.class.getName();
    private static final String SAMPLE_PATH = SAMPLE.replace('.', '/');

    @TempDir
    static Path repo;

    static Path classFile;
    static Path classesRoot;
    static DecompileTools tools;

    @BeforeAll
    static void setUp() throws Exception {
        classFile = Path.of(DecompileSample.class.getResource("DecompileSample.class").toURI());
        classesRoot = root(classFile);
        // gleiche Klasse in zwei Versionen, wie im lokalen Maven-Repository abgelegt
        jar(repo.resolve("com/example/sample/1.0/sample-1.0.jar"));
        jar(repo.resolve("com/example/sample/2.0/sample-2.0.jar"));
        tools = new DecompileTools(List.of(repo), 1500, 10);
    }

    private static Path root(Path file) {
        Path root = file;
        for (int i = SAMPLE_PATH.split("/").length; i > 0; i--) {
            root = root.getParent();
        }
        return root;
    }

    private static void jar(Path jar) throws IOException {
        Files.createDirectories(jar.getParent());
        try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out);
             Stream<Path> files = Files.list(classFile.getParent())) {
            for (Path f : files.filter(p -> p.getFileName().toString().startsWith("DecompileSample")).toList()) {
                zip.putNextEntry(new ZipEntry(classesRoot.relativize(f).toString().replace('\\', '/')));
                zip.write(Files.readAllBytes(f));
                zip.closeEntry();
            }
        }
    }

    @Test
    void decompilesJdkClassWithoutSource() {
        String out = tools.decompileClass("java.util.AbstractList", null, null, null);
        assertThat(out).contains("// Quelle: JDK").contains("jrt:/java.base")
                .contains("public abstract class AbstractList").contains("verschachtelte");
    }

    @Test
    void nestedClassNameWithDotsResolvesToOuterClass() {
        String out = tools.decompileClass("java.util.Map.Entry", "jdk", null, null);
        assertThat(out).contains("public interface Map").contains("enthält java.util.Map$Entry").contains("interface Entry");
    }

    @Test
    void findsAllVersionsAndDecompilesHighest() {
        String found = tools.find(SAMPLE, null);
        assertThat(found).contains("2 Fundstelle(n)").contains("1.0").contains("2.0").contains("sample-2.0.jar");

        String bySimpleName = tools.find("DecompileSample", null);
        assertThat(bySimpleName).contains(SAMPLE).contains("sample-1.0.jar");

        String out = tools.decompileClass(SAMPLE, null, null, null);
        assertThat(out).contains("sample-2.0.jar").contains("Weitere Fundstellen: 1")
                .contains("class DecompileSample").contains("\"marker\"").contains("class Inner").contains("record Point");
    }

    @Test
    void decompilesClassFileAndDirectory() {
        String out = tools.decompileClass(null, classFile.toString(), true, null);
        assertThat(out).contains("// Quelle: " + classesRoot).contains("class DecompileSample").contains("class Inner");

        String inner = tools.decompileClass(SAMPLE + "$Inner", classesRoot.toString(), null, null);
        assertThat(inner).contains("class DecompileSample").contains("enthält " + SAMPLE + "$Inner");
    }

    @Test
    void listsClassesOfJarAndJdk() {
        String list = tools.list(repo.resolve("com/example/sample/1.0/sample-1.0.jar").toString(), null, null);
        assertThat(list).contains("1 Klasse(n)").contains("verschachtelte").contains(SAMPLE).doesNotContain("$Inner");

        String jdk = tools.list("jdk", "java.util.concurrent.atomic", 5);
        assertThat(jdk).contains("java.util.concurrent.atomic.AtomicInteger").contains("weitere");
        assertThatThrownBy(() -> tools.list("jdk", null, null)).hasMessageContaining("Modul");
    }

    @Test
    void pagesLongOutput() {
        DecompileTools small = new DecompileTools(List.of(), 50, 10);
        String first = small.decompileClass("java.util.ArrayList", null, null, null);
        assertThat(first).contains("weiter mit startLine=51");
        String second = small.decompileClass("java.util.ArrayList", null, null, 51);
        assertThat(second).contains("[Zeilen 51–100 von");
    }

    @Test
    void reportsMissingClasses() throws URISyntaxException {
        assertThatThrownBy(() -> tools.decompileClass("org.example.DoesNotExist", null, null, null))
                .hasMessageContaining("weder im JDK noch in den Suchpfaden");
        assertThatThrownBy(() -> tools.decompileClass("org.example.DoesNotExist", classesRoot.toString(), null, null))
                .hasMessageContaining("decompile_list");
        assertThatThrownBy(() -> tools.decompileClass("x.Y", repo.resolve("missing.jar").toString(), null, null))
                .hasMessageContaining("Nicht gefunden");
        assertThat(tools.find("org.example.DoesNotExist", null)).startsWith("Keine Fundstelle");
    }

    @Test
    void candidatesCoverNestedNotation() {
        assertThat(DecompileTools.candidates("a.b.C.D")).containsExactly("a/b/C/D", "a/b/C$D", "a/b$C$D");
        assertThat(DecompileTools.normalize("a/b/C.class")).isEqualTo("a/b/C");
        assertThat(ClassFinder.version(Path.of("g/a/1.2/0123456789abcdef0123456789abcdef01234567/a-1.2.jar"))).isEqualTo("1.2");
        assertThat(ClassFinder.version(Path.of("g/a/1.3/a-1.3.jar"))).isEqualTo("1.3");
    }
}
