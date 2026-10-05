package systems.grebe.devtools.mcp.modules.scripts;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Klassenpfad für {@code javac}, damit Java-Skripte gegen die Klassen der App übersetzt werden können. In der
 * Entwicklung (IDE, {@code bootRun}, Tests) ist das einfach {@code java.class.path}. Läuft die App als Spring-Boot-Jar
 * ({@code java -jar devtools-mcp.jar}), liegen die Bibliotheken verschachtelt unter {@code BOOT-INF/lib/} – damit kann
 * {@code javac} nichts anfangen. Dann werden Klassen und Bibliotheken einmal je Jar-Version in einen Ordner unter
 * {@code java.io.tmpdir} entpackt und von dort verwendet.
 */
final class JavaClasspath {

    private static final Logger LOG = LoggerFactory.getLogger(JavaClasspath.class);
    private static volatile String cached;

    private JavaClasspath() {
    }

    static String get() {
        String cp = cached;
        if (cp == null) {
            synchronized (JavaClasspath.class) {
                if (cached == null) {
                    cached = build(System.getProperty("java.class.path", ""),
                            Path.of(System.getProperty("java.io.tmpdir"), "devtools-mcp-javac"));
                }
                cp = cached;
            }
        }
        return cp;
    }

    static String build(String classPath, Path extractRoot) {
        List<String> out = new ArrayList<>();
        for (String entry : classPath.split(File.pathSeparator)) {
            if (entry.isBlank()) {
                continue;
            }
            Path p = Path.of(entry);
            if (isBootJar(p)) {
                out.addAll(extract(p, extractRoot));
            } else {
                out.add(entry);
            }
        }
        return String.join(File.pathSeparator, out);
    }

    private static boolean isBootJar(Path p) {
        if (!Files.isRegularFile(p) || !p.toString().endsWith(".jar")) {
            return false;
        }
        try (JarFile jar = new JarFile(p.toFile())) {
            return jar.stream().anyMatch(e -> e.getName().startsWith("BOOT-INF/classes/")
                    || e.getName().startsWith("BOOT-INF/lib/"));
        } catch (IOException e) {
            return false;
        }
    }

    /** Entpackt {@code BOOT-INF/classes} und {@code BOOT-INF/lib/*.jar}; bei gleicher Jar-Version wiederverwendet. */
    private static List<String> extract(Path bootJar, Path root) {
        try {
            Path dir = root.resolve(bootJar.getFileName() + "-" + Files.size(bootJar) + "-"
                    + Files.getLastModifiedTime(bootJar).toMillis());
            Path done = dir.resolve(".complete");
            if (!Files.exists(done)) {
                LOG.info("Entpacke Klassenpfad für Java-Skripte nach {}", dir);
                Files.createDirectories(dir);
                try (JarFile jar = new JarFile(bootJar.toFile())) {
                    Enumeration<JarEntry> entries = jar.entries();
                    while (entries.hasMoreElements()) {
                        JarEntry e = entries.nextElement();
                        String name = e.getName();
                        if (e.isDirectory() || name.contains("..")
                                || !(name.startsWith("BOOT-INF/classes/") || name.startsWith("BOOT-INF/lib/"))) {
                            continue;
                        }
                        Path target = dir.resolve(name.substring("BOOT-INF/".length()));
                        Files.createDirectories(target.getParent());
                        try (InputStream in = jar.getInputStream(e)) {
                            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                }
                Files.writeString(done, bootJar.toString());
            }
            List<String> out = new ArrayList<>();
            out.add(dir.resolve("classes").toString());
            Path lib = dir.resolve("lib");
            if (Files.isDirectory(lib)) {
                try (Stream<Path> jars = Files.list(lib)) {
                    jars.filter(j -> j.toString().endsWith(".jar")).sorted().forEach(j -> out.add(j.toString()));
                }
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException("Klassenpfad für Java-Skripte nicht entpackbar: " + e.getMessage(), e);
        }
    }
}
