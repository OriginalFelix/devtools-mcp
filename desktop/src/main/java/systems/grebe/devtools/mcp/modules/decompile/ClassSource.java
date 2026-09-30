package systems.grebe.devtools.mcp.modules.decompile;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Herkunft von Klassendateien: ein Archiv (JAR/WAR, auch Spring-Boot-Fat-JAR), ein Klassenverzeichnis oder die
 * Module des laufenden JDK ({@code jrt:/}). Klassen werden über ihren internen Namen angesprochen
 * ({@code java/util/HashMap}, verschachtelt {@code java/util/HashMap$Node}).
 */
interface ClassSource extends Closeable {

    String CLASS = ".class";

    /** Anzeige für die Ausgabe, z.B. Pfad des JARs. */
    String label();

    /** Bytes der Klasse oder {@code null}, wenn sie hier nicht liegt. */
    byte[] read(String internalName) throws IOException;

    /** Interne Namen aller Klassen, die mit {@code prefix} beginnen (leer = alle), sortiert. */
    List<String> classes(String prefix) throws IOException;

    @Override
    default void close() throws IOException {
    }

    /** Archiv; Klassen unter {@code BOOT-INF/classes/} bzw. {@code WEB-INF/classes/} gelten als Wurzel. */
    final class Archive implements ClassSource {

        private static final List<String> ROOTS = List.of("", "BOOT-INF/classes/", "WEB-INF/classes/");

        private final Path file;
        private final ZipFile zip;

        Archive(Path file) throws IOException {
            this.file = file;
            this.zip = new ZipFile(file.toFile());
        }

        @Override
        public String label() {
            return file.toString();
        }

        @Override
        public byte[] read(String internalName) throws IOException {
            for (String root : ROOTS) {
                ZipEntry e = zip.getEntry(root + internalName + CLASS);
                if (e != null && !e.isDirectory()) {
                    return zip.getInputStream(e).readAllBytes();
                }
            }
            return null;
        }

        @Override
        public List<String> classes(String prefix) {
            List<String> out = new ArrayList<>();
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (!name.endsWith(CLASS)) {
                    continue;
                }
                for (String root : ROOTS) {
                    if (!root.isEmpty() && name.startsWith(root)) {
                        name = name.substring(root.length());
                        break;
                    }
                }
                name = name.substring(0, name.length() - CLASS.length());
                if (!name.startsWith("META-INF/") && name.startsWith(prefix)) {
                    out.add(name);
                }
            }
            out.sort(null);
            return out;
        }

        @Override
        public void close() throws IOException {
            zip.close();
        }
    }

    /**
     * Klassenverzeichnis (z.B. {@code build/classes/java/main}). {@code flat}: nur ein Ordner mit Klassendateien
     * ohne Paketstruktur – dann zählt nur der einfache Name.
     */
    record Directory(Path root, boolean flat) implements ClassSource {

        @Override
        public String label() {
            return root.toString();
        }

        private Path file(String internalName) {
            String name = flat ? internalName.substring(internalName.lastIndexOf('/') + 1) : internalName;
            return root.resolve(name + CLASS);
        }

        @Override
        public byte[] read(String internalName) throws IOException {
            Path f = file(internalName);
            return Files.isRegularFile(f) ? Files.readAllBytes(f) : null;
        }

        @Override
        public List<String> classes(String prefix) throws IOException {
            Path base = flat ? root : root.resolve(prefix.substring(0, prefix.lastIndexOf('/') + 1));
            if (!Files.isDirectory(base)) {
                return List.of();
            }
            String simplePrefix = prefix.substring(prefix.lastIndexOf('/') + 1);
            try (Stream<Path> files = flat ? Files.list(base) : Files.walk(base)) {
                return files.filter(p -> p.getFileName().toString().endsWith(CLASS)).map(p -> {
                    String rel = (flat ? p.getFileName() : root.relativize(p)).toString().replace('\\', '/');
                    return rel.substring(0, rel.length() - CLASS.length());
                }).filter(n -> flat ? n.startsWith(simplePrefix) : n.startsWith(prefix)).sorted().toList();
            }
        }
    }

    /** Module des laufenden JDK. Ohne {@code module} wird das Modul über das Paket gefunden. */
    record Jdk(String module) implements ClassSource {

        static FileSystem jrt() {
            return FileSystems.getFileSystem(URI.create("jrt:/"));
        }

        @Override
        public String label() {
            return "JDK " + Runtime.version().feature() + " (jrt:/" + (module == null ? "" : module) + ")";
        }

        /** Module, die das Paket des internen Namens enthalten. */
        private List<String> modulesFor(String internalName) throws IOException {
            if (module != null) {
                return List.of(module);
            }
            int slash = internalName.lastIndexOf('/');
            if (slash < 0) {
                return List.of();
            }
            Path pkg = jrt().getPath("/packages", internalName.substring(0, slash).replace('/', '.'));
            if (!Files.isDirectory(pkg)) {
                return List.of();
            }
            try (Stream<Path> mods = Files.list(pkg)) {
                return mods.map(p -> p.getFileName().toString()).sorted().toList();
            }
        }

        /** Modul, in dem die Klasse liegt, oder {@code null}. */
        String moduleOf(String internalName) throws IOException {
            for (String mod : modulesFor(internalName)) {
                if (Files.isRegularFile(jrt().getPath("/modules", mod, internalName + CLASS))) {
                    return mod;
                }
            }
            return null;
        }

        @Override
        public byte[] read(String internalName) throws IOException {
            String mod = moduleOf(internalName);
            return mod == null ? null : Files.readAllBytes(jrt().getPath("/modules", mod, internalName + CLASS));
        }

        @Override
        public List<String> classes(String prefix) throws IOException {
            List<String> mods;
            if (module != null) {
                mods = List.of(module);
            } else if (!prefix.isEmpty()) {
                // Filter ist ein Paket ("java/util") oder ein Klassenpräfix ("java/util/Hash")
                mods = Stream.concat(modulesFor(prefix.endsWith("/") ? prefix + "x" : prefix + "/x").stream(),
                        modulesFor(prefix).stream()).distinct().sorted().toList();
            } else {
                throw new IllegalArgumentException("Für das JDK ein Modul (jrt:/java.base) oder ein Paket als Filter angeben.");
            }
            List<String> out = new ArrayList<>();
            for (String mod : mods) {
                Path modRoot = jrt().getPath("/modules", mod);
                Path base = modRoot.resolve(prefix.substring(0, prefix.lastIndexOf('/') + 1));
                if (!Files.isDirectory(base)) {
                    continue;
                }
                try (Stream<Path> files = Files.walk(base)) {
                    files.map(p -> modRoot.relativize(p).toString())
                            .filter(n -> n.endsWith(CLASS) && n.startsWith(prefix) && !n.equals("module-info.class"))
                            .map(n -> n.substring(0, n.length() - CLASS.length()))
                            .forEach(out::add);
                }
            }
            out.sort(null);
            return out;
        }
    }
}
