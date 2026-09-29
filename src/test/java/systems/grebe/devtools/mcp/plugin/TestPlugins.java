package systems.grebe.devtools.mcp.plugin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * Baut für Tests echte Plugin-Jars (Quelltext → javac → Jar mit {@code plugin.yml}) und Maven-Repositories im
 * Maven-2-Layout inkl. {@code maven-metadata.xml} und SHA-1-Prüfsummen.
 */
public final class TestPlugins {

    private TestPlugins() {
    }

    /** Jar-Inhalt: Quelltexte (voll qualifizierter Klassenname → Quelltext) und weitere Dateien. */
    public static final class JarSpec {
        final Map<String, String> sources = new LinkedHashMap<>();
        final Map<String, String> files = new LinkedHashMap<>();
        final List<Path> classpath = new ArrayList<>();

        public JarSpec source(String className, String code) {
            sources.put(className, code);
            return this;
        }

        public JarSpec file(String name, String content) {
            files.put(name, content);
            return this;
        }

        public JarSpec pluginYml(String yml) {
            return file(PluginDescriptor.FILE_NAME, yml);
        }

        /** Zusätzlicher Classpath zum Kompilieren (z.B. Jar einer Bibliothek oder eines anderen Plugins). */
        public JarSpec classpath(Path jar) {
            classpath.add(jar);
            return this;
        }

        public Path build(Path jar) {
            try {
                Path work = Files.createTempDirectory("plugin-build");
                Path src = Files.createDirectories(work.resolve("src"));
                Path out = Files.createDirectories(work.resolve("classes"));
                List<String> files = new ArrayList<>();
                for (var e : sources.entrySet()) {
                    Path f = src.resolve(e.getKey().replace('.', '/') + ".java");
                    Files.createDirectories(f.getParent());
                    Files.writeString(f, e.getValue());
                    files.add(f.toString());
                }
                if (!files.isEmpty()) {
                    JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
                    StringBuilder cp = new StringBuilder(System.getProperty("java.class.path"));
                    classpath.forEach(p -> cp.append(java.io.File.pathSeparator).append(p));
                    List<String> args = new ArrayList<>(List.of("-d", out.toString(), "-cp", cp.toString(),
                            "-encoding", "UTF-8", "-parameters", "-proc:none"));
                    args.addAll(files);
                    ByteArrayOutputStream err = new ByteArrayOutputStream();
                    int rc = javac.run(null, err, err, args.toArray(String[]::new));
                    if (rc != 0) {
                        throw new IllegalStateException("javac fehlgeschlagen:\n" + err);
                    }
                }
                Files.createDirectories(jar.toAbsolutePath().getParent());
                try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar))) {
                    for (var e : this.files.entrySet()) {
                        jos.putNextEntry(new JarEntry(e.getKey()));
                        jos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                        jos.closeEntry();
                    }
                    try (Stream<Path> walk = Files.walk(out)) {
                        for (Path p : walk.filter(Files::isRegularFile).sorted().toList()) {
                            jos.putNextEntry(new JarEntry(out.relativize(p).toString().replace('\\', '/')));
                            Files.copy(p, jos);
                            jos.closeEntry();
                        }
                    }
                }
                return jar;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    public static JarSpec jar() {
        return new JarSpec();
    }

    /**
     * Plugin mit einem Modul {@code moduleId} und einem Tool {@code <moduleId>_echo}, das {@code prefix + text}
     * zurückgibt. Paket {@code test.<name>}.
     */
    public static JarSpec echoPlugin(String name, String version, String moduleId, String prefix, String extraYml) {
        String pkg = "test." + name.replace('-', '_');
        return jar()
                .pluginYml("name: " + name + "\nversion: " + version + "\nmain: " + pkg + ".Main\n"
                        + "description: Echo-Plugin " + name + "\nauthor: Test\n" + (extraYml == null ? "" : extraYml))
                .source(pkg + ".EchoTools", """
                        package %s;
                        import org.springframework.ai.tool.annotation.Tool;
                        import org.springframework.ai.tool.annotation.ToolParam;
                        public class EchoTools {
                            @Tool(name = "echo", description = "Gibt den Text zurück.")
                            public String echo(@ToolParam(description = "Text") String text) {
                                return "%s" + text;
                            }
                        }
                        """.formatted(pkg, prefix))
                .source(pkg + ".Main", """
                        package %s;
                        import java.util.List;
                        import org.springframework.ai.support.ToolCallbacks;
                        import org.springframework.ai.tool.ToolCallback;
                        import systems.grebe.devtools.mcp.core.ModuleConfig;
                        import systems.grebe.devtools.mcp.core.ToolModule;
                        import systems.grebe.devtools.mcp.plugin.DevToolsPlugin;
                        public class Main extends DevToolsPlugin {
                            void log(String s) {
                                try {
                                    java.nio.file.Files.writeString(dataFolder().resolve("lifecycle.log"), s + "\\n",
                                            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                                } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
                            }
                            @Override public void onLoad() { log("load " + descriptor().version()); }
                            @Override public void onEnable() {
                                log("enable " + descriptor().version());
                                registerModule(new ToolModule() {
                                    public String id() { return "%s"; }
                                    public String displayName() { return "Echo %s"; }
                                    public String description() { return "Echo aus Plugin %s"; }
                                    public String instructions() { return "Echo-Hinweis %s"; }
                                    public boolean enabledByDefault() { return true; }
                                    public List<ToolCallback> createTools(ModuleConfig c) {
                                        return List.of(ToolCallbacks.from(new EchoTools()));
                                    }
                                });
                            }
                            @Override public void onDisable() { log("disable " + descriptor().version()); }
                        }
                        """.formatted(pkg, moduleId, name, name, name));
    }

    /**
     * Plugin ganz im Spring-Stil: Modul und Service als {@code @Component} (Konstruktor-Injektion), eine
     * {@code @Bean}-Methode in der Hauptklasse, {@code @Value} aus der App-Umgebung, {@code @PostConstruct}/
     * {@code @PreDestroy} und optional eine App-Bean ({@code appBeanType}, z.B. {@code SettingsStore}) per
     * {@code @Autowired(required = false)}. Das Tool {@code <moduleId>_info} meldet, was angekommen ist; der
     * Lebenszyklus steht in {@code plugins/<name>/lifecycle.log}.
     */
    public static JarSpec springPlugin(String name, String version, String moduleId, String appBeanType) {
        String pkg = "spring." + name.replace('-', '_');
        return jar()
                .pluginYml("name: " + name + "\nversion: " + version + "\nmain: " + pkg + ".Main\n")
                .source(pkg + ".Main", """
                        package %1$s;
                        import org.springframework.context.annotation.Bean;
                        import systems.grebe.devtools.mcp.plugin.DevToolsPlugin;
                        public class Main extends DevToolsPlugin {
                            @Bean Greeting greeting() { return new Greeting("Hallo aus @Bean"); }
                            @Override public void onEnable() { Log.write(this, "onEnable"); }
                            @Override public void onDisable() { Log.write(this, "onDisable"); }
                        }
                        """.formatted(pkg))
                .source(pkg + ".Greeting", "package " + pkg + "; public record Greeting(String text) {}")
                .source(pkg + ".Log", """
                        package %1$s;
                        import java.nio.file.*;
                        import systems.grebe.devtools.mcp.plugin.*;
                        public final class Log {
                            public static void write(DevToolsPlugin p, String s) { write(p.dataFolder(), s); }
                            public static void write(PluginContext c, String s) { write(c.dataFolder(), s); }
                            static void write(Path dir, String s) {
                                try {
                                    Files.writeString(dir.resolve("lifecycle.log"), s + "\\n",
                                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                                } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
                            }
                        }
                        """.formatted(pkg))
                .source(pkg + ".service.Counter", """
                        package %1$s.service;
                        import java.util.concurrent.atomic.AtomicInteger;
                        import org.springframework.stereotype.Service;
                        @Service
                        public class Counter {
                            private final AtomicInteger n = new AtomicInteger();
                            public int next() { return n.incrementAndGet(); }
                        }
                        """.formatted(pkg))
                .source(pkg + ".InfoModule", """
                        package %1$s;
                        import java.util.List;
                        import jakarta.annotation.PostConstruct;
                        import jakarta.annotation.PreDestroy;
                        import org.springframework.ai.support.ToolCallbacks;
                        import org.springframework.ai.tool.ToolCallback;
                        import org.springframework.ai.tool.annotation.Tool;
                        import org.springframework.beans.factory.ObjectProvider;
                        import org.springframework.beans.factory.annotation.Value;
                        import org.springframework.stereotype.Component;
                        import systems.grebe.devtools.mcp.core.ModuleConfig;
                        import systems.grebe.devtools.mcp.core.ToolModule;
                        import systems.grebe.devtools.mcp.plugin.PluginContext;
                        import %1$s.service.Counter;
                        @Component
                        public class InfoModule implements ToolModule {
                            private final Counter counter;
                            private final Greeting greeting;
                            private final PluginContext plugin;
                            private final String appName;
                            private final String appBean;
                            public InfoModule(Counter counter, Greeting greeting, PluginContext plugin,
                                              @Value("${spring.application.name:ohne-app}") String appName,
                                              ObjectProvider<%2$s> appBean) {
                                this.counter = counter;
                                this.greeting = greeting;
                                this.plugin = plugin;
                                this.appName = appName;
                                Object b = appBean.getIfAvailable();
                                this.appBean = b == null ? "keine" : b.getClass().getSimpleName();
                            }
                            @PostConstruct void init() { Log.write(plugin, "PostConstruct"); }
                            @PreDestroy void destroy() { Log.write(plugin, "PreDestroy"); }
                            public String id() { return "%3$s"; }
                            public String displayName() { return "Spring %3$s"; }
                            public String description() { return "Spring-Plugin"; }
                            public String instructions() { return "Spring-Hinweis %3$s " + plugin.descriptor().version(); }
                            public boolean enabledByDefault() { return true; }
                            public List<ToolCallback> createTools(ModuleConfig c) {
                                return List.of(ToolCallbacks.from(this));
                            }
                            @Tool(name = "info", description = "Was per Spring angekommen ist.")
                            public String info() {
                                return greeting.text() + " | app=" + appName + " | bean=" + appBean
                                        + " | zähler=" + counter.next();
                            }
                        }
                        """.formatted(pkg, appBeanType == null ? "java.lang.Runnable" : appBeanType, moduleId));
    }

    // ------------------------------------------------------------------ Maven-Repository

    /** Legt ein Artefakt im Maven-2-Layout ab (mit POM, SHA-1 und aktualisierter maven-metadata.xml). */
    public static Path deploy(Path repo, String groupId, String artifactId, String version, String extension,
                              Path file, String dependenciesXml) {
        try {
            Path dir = repo.resolve(groupId.replace('.', '/')).resolve(artifactId).resolve(version);
            Files.createDirectories(dir);
            String base = artifactId + "-" + version;
            Path target = dir.resolve(base + "." + extension);
            Files.copy(file, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            sha1(target);
            String pom = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <project xmlns="http://maven.apache.org/POM/4.0.0">
                      <modelVersion>4.0.0</modelVersion>
                      <groupId>%s</groupId><artifactId>%s</artifactId><version>%s</version>
                      <packaging>%s</packaging>
                      %s
                    </project>
                    """.formatted(groupId, artifactId, version, extension,
                    dependenciesXml == null ? "" : "<dependencies>" + dependenciesXml + "</dependencies>");
            Path pomFile = dir.resolve(base + ".pom");
            Files.writeString(pomFile, pom);
            sha1(pomFile);
            writeMetadata(repo, groupId, artifactId);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Path deploy(Path repo, String groupId, String artifactId, String version, Path jar) {
        return deploy(repo, groupId, artifactId, version, "jar", jar, null);
    }

    private static void writeMetadata(Path repo, String groupId, String artifactId) throws IOException {
        Path dir = repo.resolve(groupId.replace('.', '/')).resolve(artifactId);
        List<String> versions;
        try (Stream<Path> s = Files.list(dir)) {
            versions = s.filter(Files::isDirectory).map(p -> p.getFileName().toString())
                    .sorted(TestPlugins::compareVersions).toList();
        }
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<metadata>\n")
                .append("  <groupId>").append(groupId).append("</groupId>\n")
                .append("  <artifactId>").append(artifactId).append("</artifactId>\n  <versioning>\n")
                .append("    <latest>").append(versions.getLast()).append("</latest>\n")
                .append("    <release>").append(versions.getLast()).append("</release>\n    <versions>\n");
        versions.forEach(v -> xml.append("      <version>").append(v).append("</version>\n"));
        xml.append("    </versions>\n    <lastUpdated>20260929120000</lastUpdated>\n  </versioning>\n</metadata>\n");
        Path meta = dir.resolve("maven-metadata.xml");
        Files.writeString(meta, xml);
        sha1(meta);
    }

    private static int compareVersions(String a, String b) {
        var scheme = new org.eclipse.aether.util.version.GenericVersionScheme();
        try {
            return scheme.parseVersion(a).compareTo(scheme.parseVersion(b));
        } catch (org.eclipse.aether.version.InvalidVersionSpecificationException e) {
            return a.compareTo(b);
        }
    }

    public static void sha1(Path file) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(file));
            Files.writeString(file.resolveSibling(file.getFileName() + ".sha1"), HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Schreibt einen Text in eine temporäre Datei (z.B. Katalog-YAML zum Deployen). */
    public static Path textFile(Path dir, String name, String content) {
        try {
            Files.createDirectories(dir);
            return Files.writeString(dir.resolve(name), content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
