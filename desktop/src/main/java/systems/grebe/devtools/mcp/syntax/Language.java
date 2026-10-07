package systems.grebe.devtools.mcp.syntax;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Sprachen, deren Syntaxbaum {@link SyntaxEngine} liefert – je Sprache eine tree-sitter-Grammatik als WebAssembly
 * ({@code tree-sitter/<id>.wasm}, gebaut mit {@code natives/build-tree-sitter-wasm.sh}). Knotentypen und Feldnamen
 * sind die der jeweiligen Grammatik ({@code node-types.json} im Grammatik-Repository).
 */
public enum Language {

    JAVA("java", "Java", ".java"),
    KOTLIN("kotlin", "Kotlin", ".kt", ".kts"),
    SCALA("scala", "Scala", ".scala", ".sc"),
    PYTHON("python", "Python", ".py", ".pyi"),
    JAVASCRIPT("javascript", "JavaScript", ".js", ".mjs", ".cjs", ".jsx"),
    TYPESCRIPT("typescript", "TypeScript", ".ts", ".mts", ".cts"),
    TSX("tsx", "TSX", ".tsx"),
    GO("go", "Go", ".go"),
    RUST("rust", "Rust", ".rs"),
    C("c", "C", ".c", ".h"),
    CPP("cpp", "C++", ".cpp", ".cc", ".cxx", ".c++", ".hpp", ".hh", ".hxx", ".h++", ".ipp", ".tpp"),
    CSHARP("csharp", "C#", ".cs"),
    PHP("php", "PHP", ".php", ".phtml"),
    RUBY("ruby", "Ruby", ".rb", ".rake", ".gemspec"),
    BASH("bash", "Bash", ".sh", ".bash");

    private final String id;
    private final String displayName;
    private final List<String> extensions;

    Language(String id, String displayName, String... extensions) {
        this.id = id;
        this.displayName = displayName;
        this.extensions = List.of(extensions);
    }

    /** Kurzname, z.B. {@code java}, {@code cpp}, {@code csharp} – auch Name der Wasm-Ressource. */
    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    /** Dateiendungen inkl. Punkt, kleingeschrieben. */
    public List<String> extensions() {
        return extensions;
    }

    String resource() {
        return "tree-sitter/" + id + ".wasm";
    }

    /** Sprache zu einem Kurz- oder Anzeigenamen ({@code java}, {@code C++}, {@code c#}, {@code ts} …). */
    public static Optional<Language> byName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String n = name.strip().toLowerCase(Locale.ROOT);
        String alias = switch (n) {
            case "c++", "cxx" -> "cpp";
            case "c#", "cs", "c_sharp" -> "csharp";
            case "js", "jsx", "node" -> "javascript";
            case "ts" -> "typescript";
            case "py" -> "python";
            case "kt" -> "kotlin";
            case "rb" -> "ruby";
            case "rs" -> "rust";
            case "golang" -> "go";
            case "sh", "shell" -> "bash";
            default -> n;
        };
        return Arrays.stream(values())
                .filter(l -> l.id.equals(alias) || l.displayName.toLowerCase(Locale.ROOT).equals(n))
                .findFirst();
    }

    /** Sprache anhand der Dateiendung. */
    public static Optional<Language> forFile(Path file) {
        Path name = file.getFileName();
        return name == null ? Optional.empty() : forFileName(name.toString());
    }

    /** Sprache anhand der Dateiendung, z.B. {@code Foo.java} → {@link #JAVA}. */
    public static Optional<Language> forFileName(String fileName) {
        String n = fileName.toLowerCase(Locale.ROOT);
        for (Language l : values()) {
            for (String ext : l.extensions) {
                if (n.endsWith(ext)) {
                    return Optional.of(l);
                }
            }
        }
        return Optional.empty();
    }
}
