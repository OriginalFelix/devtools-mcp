package systems.grebe.devtools.mcp.modules.decompile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Manifest;

import org.jetbrains.java.decompiler.main.decompiler.BaseDecompiler;
import org.jetbrains.java.decompiler.main.extern.IContextSource;
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger;
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;
import org.jetbrains.java.decompiler.main.extern.IResultSaver;

/**
 * Dekompiliert eine Top-Level-Klasse samt verschachtelter Klassen mit Vineflower (Fernflower-Fork). Die Klassen
 * liegen im Speicher; die übrige Herkunft (JAR, Verzeichnis) und das JDK dienen als Bibliothek, damit Typen,
 * Generics und überschriebene Methoden aufgelöst werden. Es wird nichts auf die Platte geschrieben.
 */
final class Vineflower {

    /** Quelltext und Meldungen des Decompilers (Warnungen/Fehler, z.B. nicht dekompilierbare Methoden). */
    record Result(String source, List<String> messages) {
    }

    private Vineflower() {
    }

    static Result decompile(Map<String, byte[]> classes, List<ClassSource> libraries, boolean lineNumbers,
                            int maxSecondsPerMethod) {
        Map<String, Object> options = new HashMap<>();
        options.put(IFernflowerPreferences.INDENT_STRING, "    ");
        options.put(IFernflowerPreferences.MAX_PROCESSING_METHOD, String.valueOf(maxSecondsPerMethod));
        options.put(IFernflowerPreferences.THREADS, "1");
        if (lineNumbers) {
            options.put(IFernflowerPreferences.DUMP_ORIGINAL_LINES, "1");
            options.put(IFernflowerPreferences.BYTECODE_SOURCE_MAPPING, "1");
        }
        Collector logger = new Collector();
        MemorySource source = new MemorySource(classes);
        BaseDecompiler decompiler = new BaseDecompiler(NO_SAVER, options, logger);
        decompiler.addSource(source);
        libraries.forEach(lib -> decompiler.addLibrary(new LibrarySource(lib)));
        decompiler.decompileContext();
        return new Result(String.join("\n", source.output.values()).strip(), logger.messages);
    }

    /** Die zu dekompilierenden Klassen; das Ergebnis landet über die Output-Sink in {@link #output}. */
    private static final class MemorySource implements IContextSource {

        private final Map<String, byte[]> classes;
        private final Map<String, String> output = new LinkedHashMap<>();

        MemorySource(Map<String, byte[]> classes) {
            this.classes = classes;
        }

        @Override
        public String getName() {
            return "decompile";
        }

        @Override
        public Entries getEntries() {
            return new Entries(classes.keySet().stream().map(Entry::atBase).toList(), List.of(), List.of());
        }

        @Override
        public InputStream getInputStream(String resource) {
            String name = resource.endsWith(CLASS_SUFFIX) ? resource.substring(0, resource.length() - CLASS_SUFFIX.length()) : resource;
            byte[] bytes = classes.get(name);
            return bytes == null ? null : new ByteArrayInputStream(bytes);
        }

        @Override
        public IOutputSink createOutputSink(IResultSaver saver) {
            return new IOutputSink() {
                @Override
                public void begin() {
                }

                @Override
                public void acceptClass(String qualifiedName, String fileName, String content, int[] mapping) {
                    if (content != null) {
                        output.put(qualifiedName, content);
                    }
                }

                @Override
                public void acceptDirectory(String directory) {
                }

                @Override
                public void acceptOther(String path) {
                }

                @Override
                public void close() {
                }
            };
        }
    }

    /** Bibliothek zur Typauflösung; Klassen werden erst bei Bedarf gelesen. */
    private record LibrarySource(ClassSource source) implements IContextSource {

        @Override
        public String getName() {
            return source.label();
        }

        @Override
        public Entries getEntries() {
            return Entries.EMPTY;
        }

        @Override
        public boolean isLazy() {
            return true;
        }

        @Override
        public boolean hasClass(String className) throws IOException {
            return getClassBytes(className) != null;
        }

        @Override
        public byte[] getClassBytes(String className) throws IOException {
            return source.read(className);
        }

        @Override
        public InputStream getInputStream(String resource) throws IOException {
            String name = resource.endsWith(CLASS_SUFFIX) ? resource.substring(0, resource.length() - CLASS_SUFFIX.length()) : resource;
            byte[] bytes = source.read(name);
            return bytes == null ? null : new ByteArrayInputStream(bytes);
        }
    }

    private static final class Collector extends IFernflowerLogger {

        private final List<String> messages = new ArrayList<>();

        @Override
        public void writeMessage(String message, Severity severity) {
            if (severity == Severity.WARN || severity == Severity.ERROR) {
                messages.add(severity.name() + ": " + message);
            }
        }

        @Override
        public void writeMessage(String message, Severity severity, Throwable t) {
            writeMessage(t == null ? message : message + " (" + t + ")", severity);
        }
    }

    /** Vineflower verlangt einen Saver; die Ausgabe läuft aber über die Output-Sink der Quelle. */
    private static final IResultSaver NO_SAVER = new IResultSaver() {
        @Override
        public void saveFolder(String path) {
        }

        @Override
        public void copyFile(String source, String path, String entryName) {
        }

        @Override
        public void saveClassFile(String path, String qualifiedName, String entryName, String content, int[] mapping) {
        }

        @Override
        public void createArchive(String path, String archiveName, Manifest manifest) {
        }

        @Override
        public void saveDirEntry(String path, String archiveName, String entryName) {
        }

        @Override
        public void copyEntry(String source, String path, String archiveName, String entry) {
        }

        @Override
        public void saveClassEntry(String path, String archiveName, String qualifiedName, String entryName, String content) {
        }

        @Override
        public void closeArchive(String path, String archiveName) {
        }
    };
}
