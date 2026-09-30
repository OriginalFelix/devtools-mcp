package systems.grebe.devtools.mcp.modules.maven;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.classfile.ClassBuilder;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.AnnotationDefaultAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import systems.grebe.devtools.mcp.core.ModuleConfig;

import static java.lang.classfile.ClassFile.ACC_ABSTRACT;
import static java.lang.classfile.ClassFile.ACC_ANNOTATION;
import static java.lang.classfile.ClassFile.ACC_FINAL;
import static java.lang.classfile.ClassFile.ACC_INTERFACE;
import static java.lang.classfile.ClassFile.ACC_PUBLIC;
import static java.lang.classfile.ClassFile.ACC_STATIC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Maven-Tools gegen einen lokalen Repository- und GitHub-Stub; die JARs werden im Test per ClassFile-API erzeugt. */
class MavenToolsTest {

    private static final String DIR = "/com/example/lib/";

    HttpServer server;
    final Map<String, byte[]> files = new HashMap<>();
    MavenModule module = new MavenModule();
    MavenTools tools;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        ModuleConfig config = ModuleConfig.of(module.configSchema(), Map.of(
                MavenModule.REPOSITORY_URL, base + "/repo",
                MavenModule.GITHUB_API_URL, base + "/gh"));
        tools = new MavenTools(() -> MavenModule.repository(config), () -> MavenModule.github(config));

        put("/repo" + DIR + "maven-metadata.xml", """
                <metadata><groupId>com.example</groupId><artifactId>lib</artifactId><versioning>
                  <latest>2.1.0-RC1</latest><release>2.1.0-RC1</release>
                  <versions><version>1.0.0</version><version>1.1.0</version><version>2.0.0</version>
                    <version>2.1.0-RC1</version></versions>
                  <lastUpdated>20260915103000</lastUpdated></versioning></metadata>""");
        put("/repo/com/example/parent/7/parent-7.pom", """
                <project><groupId>com.example</groupId><artifactId>parent</artifactId><version>7</version>
                  <packaging>pom</packaging>
                  <licenses><license><name>Apache-2.0</name></license></licenses>
                  <properties><maven.compiler.release>17</maven.compiler.release><slf4j.version>2.0.16</slf4j.version></properties>
                  <dependencyManagement><dependencies>
                    <dependency><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId><version>${slf4j.version}</version></dependency>
                  </dependencies></dependencyManagement></project>""");
        put("/repo" + DIR + "1.0.0/lib-1.0.0.pom", pom("1.0.0", "<maven.compiler.release>11</maven.compiler.release>",
                "<dependency><groupId>commons-io</groupId><artifactId>commons-io</artifactId><version>2.16.1</version></dependency>"));
        put("/repo" + DIR + "2.0.0/lib-2.0.0.pom", pom("2.0.0", "", ""));
        files.put("/repo" + DIR + "1.0.0/lib-1.0.0.jar", jar(v1()));
        files.put("/repo" + DIR + "2.0.0/lib-2.0.0.jar", jar(v2()));
        put("/gh/repos/example/lib/releases", """
                [{"tag_name":"v2.1.0-RC1","name":"2.1.0-RC1","published_at":"2026-09-15T10:00:00Z","body":"BREAKING: kommt später"},
                 {"tag_name":"v2.0.0","name":"2.0.0","published_at":"2026-08-01T10:00:00Z","html_url":"https://github.com/example/lib/releases/v2.0.0",
                  "body":"## Highlights\\nSchneller\\n## ⚠ Breaking Changes\\n- `Gone` entfernt\\n- `Api#bar` nimmt long\\n## Fixes\\n- NPE behoben"},
                 {"tag_name":"v1.1.0","name":"1.1.0","published_at":"2026-05-01T10:00:00Z","body":"Nur Fixes"},
                 {"tag_name":"v1.0.0","name":"1.0.0","published_at":"2026-01-01T10:00:00Z","body":"Removed everything"}]""");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        byte[] body = files.get(ex.getRequestURI().getPath());
        if (body == null) {
            ex.sendResponseHeaders(404, -1);
            ex.close();
            return;
        }
        ex.getResponseHeaders().add("Last-Modified", "Sat, 01 Aug 2026 10:00:00 GMT");
        if (ex.getRequestMethod().equals("HEAD")) {
            ex.sendResponseHeaders(200, -1);
            ex.close();
            return;
        }
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    private void put(String path, String content) {
        files.put(path, content.getBytes(StandardCharsets.UTF_8));
    }

    private static String pom(String version, String props, String deps) {
        return """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>7</version></parent>
                  <artifactId>lib</artifactId><version>%s</version>
                  <name>Example Lib</name><description>Eine Beispiel-
                    Bibliothek</description>
                  <scm><url>https://github.com/example/lib</url></scm>
                  <properties>%s</properties>
                  <dependencies>
                    <dependency><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId></dependency>
                    <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>5.11.0</version><scope>test</scope></dependency>
                    %s
                  </dependencies>
                </project>""".formatted(version, props, deps);
    }

    // ------------------------------------------------------------------ Tests

    @Test
    void latestVersionIgnoresPrereleasesAndAssessesUpdate() {
        String out = tools.latestVersion("com.example:lib", "1.1.0", null, null);
        assertThat(out).contains("Neueste Release-Version: 2.0.0 (2026-08-01)", "Neueste Vorabversion: 2.1.0-RC1",
                "Metadaten aktualisiert: 2026-09-15", "Update verfügbar: 1.1.0 → 2.0.0 (1 neuere Release-Versionen)",
                "MAJOR-Sprung 1 → 2", "maven_breaking_changes mit fromVersion=1.1.0");
        assertThat(out.substring(out.indexOf("Letzte"))).contains("2.0.0", "1.0.0").doesNotContain("RC1");

        assertThat(tools.latestVersion("com.example:lib", "2.0.0", true, 2))
                .contains("ist die neueste Version", "Letzte 2 von 4", "2.1.0-RC1  (Vorabversion)");
    }

    @Test
    void unknownArtifactGivesHelpfulError() {
        assertThatThrownBy(() -> tools.latestVersion("com.example:nope", null, null, null))
                .hasMessageContaining("com.example:nope nicht gefunden");
        assertThatThrownBy(() -> tools.latestVersion("nur-ein-teil", null, null, null))
                .hasMessageContaining("groupId:artifactId");
    }

    @Test
    void artifactInfoMergesParentPom() {
        String out = tools.artifactInfo("com.example:lib:1.0.0", null, null);
        assertThat(out).contains("com.example:lib:1.0.0  [jar]", "Name: Example Lib",
                "Beschreibung: Eine Beispiel- Bibliothek", "Veröffentlicht: 2026-08-01",
                "Neueste Release-Version: 2.0.0 – MAJOR-Sprung", "SCM: https://github.com/example/lib",
                "Lizenzen: Apache-2.0", "Java (Compiler-Ziel): 11", "Parent: com.example:parent:7",
                "org.slf4j:slf4j-api:2.0.16", "org.junit.jupiter:junit-jupiter:5.11.0  [test]",
                "commons-io:commons-io:2.16.1");
        // ohne Version: neueste Release-Version, Java-Ziel aus dem Parent
        assertThat(tools.artifactInfo("com.example:lib", null, false))
                .contains("com.example:lib:2.0.0", "(diese)", "Java (Compiler-Ziel): 17")
                .doesNotContain("Abhängigkeiten");
    }

    @Test
    void breakingChangesComparesApiPomAndReleaseNotes() {
        String out = tools.breakingChanges("com.example:lib", "1.0.0", null, null, null);
        assertThat(out).contains("com.example:lib: 1.0.0 → 2.0.0", "MAJOR-Sprung",
                // POM
                "Java-Ziel geändert: 11 → 17", "Transitive Abhängigkeit entfällt: commons-io:commons-io",
                // API
                "Klasse entfernt: com.example.Gone",
                "Methode entfernt: void com.example.Api#bar(int) – stattdessen vorhanden: void com.example.Api#bar(long)",
                "Feld entfernt: com.example.Api.LIMIT – int",
                "Klasse jetzt final: com.example.Api",
                "static geändert: void com.example.Api#helper() – Instanz → static",
                "Neue abstrakte Interface-Methode: void com.example.Service#stop()",
                "Neues Pflicht-Element in Annotation: int com.example.Config#required()",
                "1 weitere Änderungen in internen Paketen ausgeblendet",
                // Release Notes: nur 1.1.0 und 2.0.0, nicht der RC und nicht 1.0.0
                "2 Releases, 1 mit Breaking-Hinweisen", "2.0.0 (2026-08-01) https://github.com/example/lib/releases/v2.0.0",
                "## ⚠ Breaking Changes", "- `Gone` entfernt", "Ohne Hinweise: 1.1.0")
                .doesNotContain("Methode entfernt: void com.example.Api#foo()", // in Oberklasse verschoben
                        "Obertyp entfernt", "AbstractApi", "Config#optional", "Config#value",
                        "NPE behoben", "kommt später", "com.example.internal");

        assertThat(tools.breakingChanges("com.example:lib", "1.0.0", "2.0.0", true, false))
                .contains("Klasse entfernt: com.example.internal.Impl").doesNotContain("Release Notes");
    }

    @Test
    void versionHelpers() {
        assertThat(MavenVersions.isPrerelease("2.0.0-RC1")).isTrue();
        assertThat(MavenVersions.isPrerelease("6.0.0-M3")).isTrue();
        assertThat(MavenVersions.isPrerelease("1.0-SNAPSHOT")).isTrue();
        assertThat(MavenVersions.isPrerelease("33.3.1-jre")).isFalse();
        assertThat(MavenVersions.isPrerelease("5.3.39.RELEASE")).isFalse();
        assertThat(MavenVersions.latest(List.of("1.9", "1.10", "1.10-beta"), false)).isEqualTo("1.10");
        assertThat(MavenVersions.semverAssessment("0.3.1", "0.4.0")).contains("0.x");
        assertThat(MavenVersions.semverAssessment("1.2.0", "1.2.5")).startsWith("Patch");
        assertThat(GitHubReleaseNotes.versionOf("jackson-databind-2.17.0", "jackson-databind")).isEqualTo("2.17.0");
        assertThat(GitHubReleaseNotes.versionOf("release-3.1.0", "x")).isEqualTo("3.1.0");
        assertThat(GitHubReleaseNotes.versionOf("r5.11.0", "x")).isEqualTo("5.11.0");
        assertThat(GitHubReleaseNotes.versionOf("v_2.0.19", "x")).isEqualTo("2.0.19");
        assertThat(GitHubReleaseNotes.versionOf("nightly", "x")).isNull();
    }

    // ------------------------------------------------------------------ Test-JARs

    private static final ClassDesc INT = ConstantDescs.CD_int;
    private static final MethodTypeDesc VOID = MethodTypeDesc.of(ConstantDescs.CD_void);

    private static Map<String, byte[]> v1() {
        Map<String, byte[]> c = new HashMap<>();
        c.put("com/example/Base", cls("com.example.Base", ACC_PUBLIC, null, cb -> {
        }));
        c.put("com/example/Api", cls("com.example.Api", ACC_PUBLIC, "com.example.Base", cb -> {
            method(cb, "foo", VOID, ACC_PUBLIC);
            method(cb, "bar", MethodTypeDesc.of(ConstantDescs.CD_void, INT), ACC_PUBLIC);
            method(cb, "helper", VOID, ACC_PUBLIC);
            cb.withField("LIMIT", INT, ACC_PUBLIC | ACC_STATIC | ACC_FINAL);
        }));
        c.put("com/example/Gone", cls("com.example.Gone", ACC_PUBLIC, null, cb -> {
        }));
        c.put("com/example/Service", iface(cb -> cb.withMethod("run", VOID, ACC_PUBLIC | ACC_ABSTRACT, mb -> {
        })));
        c.put("com/example/internal/Impl", cls("com.example.internal.Impl", ACC_PUBLIC, null, cb -> {
        }));
        c.put("com/example/Config", annotation(cb -> cb.withMethod("value", MethodTypeDesc.of(ConstantDescs.CD_String),
                ACC_PUBLIC | ACC_ABSTRACT, mb -> {
                })));
        return c;
    }

    private static Map<String, byte[]> v2() {
        Map<String, byte[]> c = new HashMap<>();
        // foo wandert in eine neue package-private Zwischenklasse – kein Bruch
        c.put("com/example/Base", cls("com.example.Base", ACC_PUBLIC, null, cb -> {
        }));
        c.put("com/example/AbstractApi", cls("com.example.AbstractApi", ACC_ABSTRACT, "com.example.Base",
                cb -> method(cb, "foo", VOID, ACC_PUBLIC)));
        c.put("com/example/Api", cls("com.example.Api", ACC_PUBLIC | ACC_FINAL, "com.example.AbstractApi", cb -> {
            method(cb, "bar", MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_long), ACC_PUBLIC);
            method(cb, "helper", VOID, ACC_PUBLIC | ACC_STATIC);
            method(cb, "added", VOID, ACC_PUBLIC);
        }));
        c.put("com/example/Service", iface(cb -> {
            cb.withMethod("run", VOID, ACC_PUBLIC | ACC_ABSTRACT, mb -> {
            });
            cb.withMethod("stop", VOID, ACC_PUBLIC | ACC_ABSTRACT, mb -> {
            });
        }));
        c.put("com/example/Added", cls("com.example.Added", ACC_PUBLIC, null, cb -> {
        }));
        c.put("com/example/Config", annotation(cb -> {
            cb.withMethod("value", MethodTypeDesc.of(ConstantDescs.CD_String), ACC_PUBLIC | ACC_ABSTRACT, mb -> {
            });
            cb.withMethod("optional", MethodTypeDesc.of(INT), ACC_PUBLIC | ACC_ABSTRACT,
                    mb -> mb.with(AnnotationDefaultAttribute.of(AnnotationValue.ofInt(1))));
            cb.withMethod("required", MethodTypeDesc.of(INT), ACC_PUBLIC | ACC_ABSTRACT, mb -> {
            });
        }));
        return c;
    }

    private static byte[] annotation(Consumer<ClassBuilder> body) {
        return ClassFile.of().build(ClassDesc.of("com.example.Config"), cb -> {
            cb.withFlags(ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT | ACC_ANNOTATION).withSuperclass(ConstantDescs.CD_Object)
                    .withInterfaceSymbols(ClassDesc.of("java.lang.annotation.Annotation"));
            body.accept(cb);
        });
    }

    private static byte[] cls(String name, int flags, String superName, Consumer<ClassBuilder> body) {
        ClassDesc sup = superName == null ? ConstantDescs.CD_Object : ClassDesc.of(superName);
        return ClassFile.of().build(ClassDesc.of(name), cb -> {
            cb.withFlags(flags).withSuperclass(sup);
            cb.withMethodBody(ConstantDescs.INIT_NAME, VOID, ACC_PUBLIC, code -> code.aload(0)
                    .invokespecial(sup, ConstantDescs.INIT_NAME, VOID).return_());
            body.accept(cb);
        });
    }

    private static byte[] iface(Consumer<ClassBuilder> body) {
        return ClassFile.of().build(ClassDesc.of("com.example.Service"), cb -> {
            cb.withFlags(ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT).withSuperclass(ConstantDescs.CD_Object);
            body.accept(cb);
        });
    }

    private static void method(ClassBuilder cb, String name, MethodTypeDesc type, int flags) {
        cb.withMethodBody(name, type, flags, code -> code.return_());
    }

    private static byte[] jar(Map<String, byte[]> classes) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bos)) {
            for (var e : classes.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey() + ".class"));
                zip.write(e.getValue());
                zip.closeEntry();
            }
        }
        return bos.toByteArray();
    }
}
