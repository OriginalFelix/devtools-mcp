package systems.grebe.devtools.mcp.syntax;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Function;

import com.dylibso.chicory.compiler.InterpreterFallback;
import com.dylibso.chicory.compiler.MachineFactoryCompiler;
import com.dylibso.chicory.runtime.ByteArrayMemory;
import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.ImportFunction;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Machine;
import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.ExternalType;
import com.dylibso.chicory.wasm.types.FunctionImport;
import com.dylibso.chicory.wasm.types.Import;

/**
 * Syntaxbäume für viele Sprachen in reinem Java: tree-sitter samt Grammatik ist je Sprache ein WebAssembly-Modul
 * ({@code src/main/resources/tree-sitter}, gebaut mit {@code natives/build-tree-sitter-wasm.sh}), das Chicory ausführt.
 * Der Chicory-Compiler übersetzt das Modul beim ersten Gebrauch der Sprache in JVM-Bytecode (einmal je Prozess);
 * zur Laufzeit wird keine native Bibliothek geladen – nichts, was Windows (Smart App Control, WDAC, AppLocker)
 * blockieren könnte, und kein Absturz der JVM bei vollem Heap.
 *
 * <p>Je Datei genügt ein Aufruf ins Modul: die Brücke ({@code natives/tree-sitter-wasm/ast.c}) parst und legt den
 * Baum flach in einen Puffer, der hier am Stück gelesen und zu {@link SyntaxNode}s wird. Wasm-Instanzen sind nicht
 * threadsicher – jeder Aufruf leiht sich eine aus einem Pool je Sprache, parallele Aufrufe (Graph-Aufbau mit mehreren
 * Threads) bekommen eigene.
 *
 * <pre>{@code
 * SyntaxTree tree = SyntaxEngine.parse(Language.PYTHON, source);
 * for (SyntaxNode fn : tree.root().findByType("function_definition")) {
 *     System.out.println(fn.child("name").text() + " in Zeile " + fn.line());
 * }
 * }</pre>
 */
public final class SyntaxEngine {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(SyntaxEngine.class);

    /** uint32 je Knoten im Puffer, Reihenfolge wie in ast.c. */
    private static final int FIELDS = 10;
    private static final int SYMBOL = 0;
    private static final int FIELD = 1;
    private static final int FLAGS = 2;
    private static final int PARENT = 3;
    private static final int START_BYTE = 4;
    private static final int END_BYTE = 5;
    private static final int START_ROW = 6;
    private static final int START_COL = 7;
    private static final int END_ROW = 8;
    private static final int END_COL = 9;

    /**
     * Wasm-Speicher wächst nur und schrumpft nie: eine Instanz, die für eine riesige Datei mehr als das gebraucht hat,
     * wird danach verworfen statt in den Pool zurückgelegt (256 MiB).
     */
    private static final int MAX_POOLED_PAGES = 4096;

    private static final Map<Language, Runtime> RUNTIMES = new EnumMap<>(Language.class);

    private SyntaxEngine() {
    }

    /** Parst Quelltext; übernommen werden benannte Knoten und Schlüsselwörter. */
    public static SyntaxTree parse(Language language, String source) {
        return parse(language, source.getBytes(StandardCharsets.UTF_8), false);
    }

    /**
     * Parst Quelltext. Mit {@code allTokens} enthält der Baum auch Satzzeichen und Operatoren ({@code (}, {@code ;},
     * {@code +=} …), sonst nur benannte Knoten und Schlüsselwörter.
     */
    public static SyntaxTree parse(Language language, String source, boolean allTokens) {
        return parse(language, source.getBytes(StandardCharsets.UTF_8), allTokens);
    }

    /** Parst UTF-8-Quelltext (das Array wird übernommen, nicht kopiert). */
    public static SyntaxTree parse(Language language, byte[] utf8, boolean allTokens) {
        Runtime runtime = runtime(language);
        WasmParser parser = runtime.acquire();
        boolean reusable = false;
        try {
            SyntaxTree tree = parser.parse(utf8, allTokens);
            reusable = parser.memory.pages() <= MAX_POOLED_PAGES;
            return tree;
        } finally {
            if (reusable) {
                runtime.release(parser);
            }
        }
    }

    /** Ist die Grammatik der Sprache in diesem Build enthalten? */
    public static boolean isAvailable(Language language) {
        return SyntaxEngine.class.getClassLoader().getResource(language.resource()) != null;
    }

    /** Sprachen, deren Grammatik in diesem Build enthalten ist. */
    public static List<Language> availableLanguages() {
        List<Language> out = new ArrayList<>();
        for (Language l : Language.values()) {
            if (isAvailable(l)) {
                out.add(l);
            }
        }
        return out;
    }

    /** Lädt und übersetzt die Grammatik vorab (sonst beim ersten {@link #parse}). */
    public static void warmUp(Language language) {
        Runtime runtime = runtime(language);
        runtime.release(runtime.acquire());
    }

    private static Runtime runtime(Language language) {
        synchronized (RUNTIMES) {
            return RUNTIMES.computeIfAbsent(language, Runtime::new);
        }
    }

    // ------------------------------------------------------------------ je Sprache

    /** Geladenes, übersetztes Modul einer Sprache und der Pool seiner Instanzen. */
    private static final class Runtime {
        private final Language language;
        private final Object initLock = new Object();
        private final Deque<WasmParser> idle = new ConcurrentLinkedDeque<>();
        private final int maxIdle = Math.max(2, java.lang.Runtime.getRuntime().availableProcessors());
        private volatile WasmModule module;
        private Function<Instance, Machine> machine;
        private ImportValues imports;
        /** Typnamen je Symbol und Feldnamen je ID – gleiche String-Objekte für alle Bäume der Sprache. */
        private String[] symbols;
        private String[] fields;

        Runtime(Language language) {
            this.language = language;
        }

        /** Lädt und übersetzt beim ersten Gebrauch – nicht im Konstruktor, damit andere Sprachen nicht warten. */
        private void init() {
            if (module != null) {
                return;
            }
            synchronized (initLock) {
                if (module != null) {
                    return;
                }
                long t0 = System.nanoTime();
                WasmModule m;
                try (InputStream in = SyntaxEngine.class.getClassLoader().getResourceAsStream(language.resource())) {
                    if (in == null) {
                        throw new IllegalStateException("Grammatik für " + language.displayName() + " fehlt ("
                                + language.resource() + ", natives/build-tree-sitter-wasm.sh)");
                    }
                    m = Parser.parse(in);
                } catch (IOException e) {
                    throw new UncheckedIOException("Grammatik für " + language.displayName() + " nicht lesbar", e);
                }
                // Sehr große Funktionen (Lexer mancher Grammatiken) passen nicht in eine JVM-Methode (64 KiB) –
                // die laufen dann im Interpreter von Chicory, alles andere als Bytecode.
                machine = MachineFactoryCompiler.builder(m).withInterpreterFallback(InterpreterFallback.SILENT).compile();
                imports = stubImports(m);
                WasmParser first = new WasmParser(this, m);
                symbols = first.names("ast_symbol_count", "ast_symbol_name", 0);
                fields = first.names("ast_field_count", "ast_field_name", 1);
                idle.push(first);
                module = m;
                LOG.debug("tree-sitter {}: Grammatik geladen und übersetzt in {} ms", language.id(),
                        (System.nanoTime() - t0) / 1_000_000);
            }
        }

        WasmParser acquire() {
            init();
            WasmParser p = idle.poll();
            if (p != null) {
                return p;
            }
            return new WasmParser(this, module);
        }

        /** Zurück in den Pool; überzählige Instanzen (nach einer Spitze paralleler Aufrufe) räumt der GC ab. */
        void release(WasmParser p) {
            if (idle.size() < maxIdle) {
                idle.push(p);
            }
        }
    }

    /**
     * Das Modul braucht keine Funktionen von außen – übrig gebliebene WASI-Importe der C-Laufzeit (Ausgabe einer
     * Fehlermeldung, abort bei erschöpftem Speicher) brechen den Aufruf ab, statt etwas zu tun.
     */
    private static ImportValues stubImports(WasmModule module) {
        List<ImportFunction> functions = new ArrayList<>();
        for (int i = 0; i < module.importSection().importCount(); i++) {
            Import imp = module.importSection().getImport(i);
            if (imp.importType() == ExternalType.FUNCTION) {
                FunctionImport f = (FunctionImport) imp;
                String name = f.module() + "." + f.name();
                functions.add(new HostFunction(f.module(), f.name(), module.typeSection().getType(f.typeIndex()),
                        (instance, args) -> {
                            throw new IllegalStateException("tree-sitter (Wasm) abgebrochen: " + name
                                    + " aufgerufen (meist: Speicher erschöpft)");
                        }));
            }
        }
        return ImportValues.builder().withFunctions(functions).build();
    }

    // ------------------------------------------------------------------ eine Instanz

    /** Eine Wasm-Instanz mit Parser – nur von einem Thread gleichzeitig benutzt. */
    private static final class WasmParser {
        private final Runtime runtime;
        private final Instance instance;
        private final Memory memory;
        private final ExportFunction parse;
        private final ExportFunction malloc;
        private final ExportFunction free;
        /** Wiederverwendeter Puffer für den Quelltext im Wasm-Speicher. */
        private int sourcePtr;
        private int sourceCapacity;

        WasmParser(Runtime runtime, WasmModule module) {
            this.runtime = runtime;
            this.instance = Instance.builder(module)
                    .withMachineFactory(runtime.machine)
                    .withImportValues(runtime.imports)
                    .withMemoryFactory(ByteArrayMemory::new)
                    .withStart(false)
                    .build();
            // Reactor-Modul (wasi-libc): _initialize richtet die C-Laufzeit ein
            for (int i = 0; i < module.exportSection().exportCount(); i++) {
                if (module.exportSection().getExport(i).name().equals("_initialize")) {
                    instance.export("_initialize").apply();
                }
            }
            this.memory = instance.memory();
            this.parse = instance.export("ast_parse");
            this.malloc = instance.export("ast_malloc");
            this.free = instance.export("ast_free");
        }

        String[] names(String countFn, String nameFn, int first) {
            int count = (int) instance.export(countFn).apply()[0];
            ExportFunction name = instance.export(nameFn);
            String[] out = new String[count + first];
            for (int i = first; i < out.length; i++) {
                int ptr = (int) name.apply(i)[0];
                out[i] = ptr == 0 ? null : memory.readCString(ptr, StandardCharsets.UTF_8).intern();
            }
            return out;
        }

        SyntaxTree parse(byte[] utf8, boolean allTokens) {
            int length = utf8.length;
            if (length + 1 > sourceCapacity) {
                if (sourcePtr != 0) {
                    free.apply(sourcePtr);
                }
                int capacity = Math.max(64 * 1024, Integer.highestOneBit(length + 1) << 1);
                sourcePtr = (int) malloc.apply(capacity)[0];
                if (sourcePtr == 0) {
                    sourceCapacity = 0;
                    throw new IllegalStateException("tree-sitter (Wasm): kein Speicher für " + length + " Bytes Quelltext");
                }
                sourceCapacity = capacity;
            }
            memory.write(sourcePtr, utf8, 0, length);
            int result = (int) parse.apply(sourcePtr, length, allTokens ? 1 : 0)[0];
            if (result == 0) {
                throw new IllegalStateException("tree-sitter (Wasm): Datei nicht geparst (Speicher erschöpft?)");
            }
            int count = memory.readInt(result);
            boolean hasError = memory.readInt(result + 4) != 0;
            int nodesPtr = memory.readInt(result + 8);
            IntBuffer data = ByteBuffer.wrap(memory.readBytes(nodesPtr, count * FIELDS * Integer.BYTES))
                    .order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
            return build(utf8, hasError, count, data);
        }

        private SyntaxTree build(byte[] utf8, boolean hasError, int count, IntBuffer d) {
            SyntaxTree tree = new SyntaxTree(runtime.language, utf8, hasError);
            String[] symbols = runtime.symbols;
            String[] fields = runtime.fields;
            SyntaxNode[] nodes = new SyntaxNode[count];
            for (int i = 0; i < count; i++) {
                int o = i * FIELDS;
                int parentIndex = d.get(o + PARENT);
                SyntaxNode parent = parentIndex < 0 ? null : nodes[parentIndex];
                int symbol = d.get(o + SYMBOL);
                int field = d.get(o + FIELD);
                SyntaxNode n = new SyntaxNode(tree,
                        symbol < symbols.length ? symbols[symbol] : "?" + symbol,
                        field > 0 && field < fields.length ? fields[field] : null,
                        d.get(o + FLAGS), d.get(o + START_BYTE), d.get(o + END_BYTE),
                        d.get(o + START_ROW), d.get(o + START_COL), d.get(o + END_ROW), d.get(o + END_COL),
                        parent, parent == null ? 0 : parent.childCount());
                if (parent != null) {
                    parent.addChild(n);
                }
                nodes[i] = n;
            }
            for (SyntaxNode n : nodes) {
                n.seal();
            }
            tree.root(nodes[0]);
            return tree;
        }
    }
}
