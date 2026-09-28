package systems.grebe.devtools.mcp.modules.graph;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

import io.github.treesitter.jtreesitter.NativeLibraryLookup;

/**
 * Lädt die nativen tree-sitter-Bibliotheken für die FFM-Bindings {@code jtreesitter}.
 *
 * <p>jtreesitter bringt selbst keine nativen Bibliotheken mit. Die vorkompilierten Bibliotheken für macOS, Linux und
 * Windows stammen als reine Ressourcen ({@code lib/<plattform>-tree-sitter[-java].<endung>}) aus den Artefakten
 * {@code io.github.bonede:tree-sitter} und {@code tree-sitter-java}; deren JNI-Klassen werden nicht verwendet. Grund:
 * die JNI-Bindings prüfen Objekt-Allokationen nicht auf {@code NULL} – bei vollem Heap stürzt dort die ganze JVM mit
 * SIGSEGV ab, statt einen {@link OutOfMemoryError} zu werfen. Über FFM entstehen Java-Objekte in Java-Code.
 *
 * <p>Wird von jtreesitter per {@link java.util.ServiceLoader} als {@link NativeLibraryLookup} gefunden
 * ({@code META-INF/services}).
 */
public final class TreeSitterNatives implements NativeLibraryLookup {

    private static final Object LOCK = new Object();
    private static volatile SymbolLookup core;
    private static volatile MemorySegment java;

    /** Für den ServiceLoader. */
    public TreeSitterNatives() {
    }

    @Override
    public SymbolLookup get(Arena arena) {
        return core();
    }

    static SymbolLookup core() {
        SymbolLookup c = core;
        if (c == null) {
            synchronized (LOCK) {
                if (core == null) {
                    core = SymbolLookup.libraryLookup(extract("tree-sitter"), Arena.global());
                }
                c = core;
            }
        }
        return c;
    }

    /**
     * Zeiger auf die Java-Grammatik ({@code const TSLanguage *tree_sitter_java()}); beim ersten Aufruf werden die
     * Bibliotheken entpackt und geladen. Der Zeiger ist statisch und lebt bis zum Prozessende.
     */
    static MemorySegment javaLanguage() {
        MemorySegment l = java;
        if (l == null) {
            synchronized (LOCK) {
                if (java == null) {
                    core();
                    SymbolLookup grammar = SymbolLookup.libraryLookup(extract("tree-sitter-java"), Arena.global());
                    MemorySegment fn = grammar.find("tree_sitter_java")
                            .orElseThrow(() -> new IllegalStateException("Symbol tree_sitter_java fehlt"));
                    try {
                        java = (MemorySegment) Linker.nativeLinker()
                                .downcallHandle(fn, FunctionDescriptor.of(ValueLayout.ADDRESS)).invokeExact();
                    } catch (Throwable e) {
                        throw new IllegalStateException("Java-Grammatik für tree-sitter nicht ladbar", e);
                    }
                }
                l = java;
            }
        }
        return l;
    }

    /** Ressourcenname der Bibliothek für diese Plattform, z.B. {@code lib/aarch64-macos-tree-sitter.dylib}. */
    static String resource(String name, String osName, String osArch) {
        String os = osName.toLowerCase(Locale.ROOT);
        String arch = osArch.toLowerCase(Locale.ROOT);
        String cpu = arch.equals("aarch64") || arch.equals("arm64") ? "aarch64"
                : arch.equals("amd64") || arch.equals("x86_64") ? "x86_64" : null;
        String platform;
        String ext;
        if (os.contains("mac") || os.contains("darwin")) {
            platform = cpu + "-macos";
            ext = "dylib";
        } else if (os.contains("win")) {
            platform = "x86_64".equals(cpu) ? "x86_64-windows" : null;
            ext = "dll";
        } else if (os.contains("linux")) {
            platform = cpu + "-linux-gnu";
            ext = "so";
        } else {
            platform = null;
            ext = null;
        }
        if (cpu == null || platform == null) {
            throw new IllegalStateException("tree-sitter ist für diese Plattform nicht verfügbar (" + osName + "/"
                    + osArch + "). Unterstützt: macOS und Linux (x86_64, aarch64), Windows (x86_64).");
        }
        return "lib/" + platform + "-" + name + "." + ext;
    }

    private static Path extract(String name) {
        String res = resource(name, System.getProperty("os.name"), System.getProperty("os.arch"));
        try (InputStream in = TreeSitterNatives.class.getClassLoader().getResourceAsStream(res)) {
            if (in == null) {
                throw new IllegalStateException("Native Bibliothek " + res + " fehlt auf dem Classpath "
                        + "(Abhängigkeiten io.github.bonede:tree-sitter / tree-sitter-java).");
            }
            Path dir = Files.createTempDirectory("devtools-mcp-tree-sitter");
            Path file = dir.resolve(res.substring(res.lastIndexOf('/') + 1));
            Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
            file.toFile().deleteOnExit();
            dir.toFile().deleteOnExit();
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException("Native Bibliothek " + res + " konnte nicht entpackt werden", e);
        }
    }
}
