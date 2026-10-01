package systems.grebe.devtools.mcp.modules.decompile;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.ConnectionTestResult;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolBeans;
import systems.grebe.devtools.mcp.core.ToolModule;

/** Decompiler (Vineflower, ein Fork von Fernflower): Klassen aus JDK, JARs und Klassenverzeichnissen als Quelltext. */
@Component
public class DecompileModule implements ToolModule {

    static final String SEARCH_PATHS = "searchPaths";
    static final String MAX_LINES = "maxLines";
    static final String MAX_SECONDS = "maxSecondsPerMethod";

    @Override
    public String id() {
        return "decompile";
    }

    @Override
    public String displayName() {
        return "Decompiler (Vineflower)";
    }

    @Override
    public String description() {
        return "Dekompiliert Java-Klassen aus dem JDK, aus Bibliotheks-JARs (Maven-/Gradle-Cache) und aus "
                + "Klassenverzeichnissen zu lesbarem Quelltext – mit Vineflower, einem gepflegten Fork von Fernflower.";
    }

    @Override
    public String instructions() {
        return """
                Wenn Quelltext einer Klasse nicht im Projekt liegt (Bibliothek ohne Quellen-JAR, JDK-Interna, nur kompilierte \
                Klassen), diese Tools statt `javap`, `unzip` oder eines Decompilers in der Shell verwenden: `decompile_find` \
                (wo liegt die Klasse, welche Versionen), `decompile_class` (Quelltext, ohne 'source' automatisch gesucht), \
                `decompile_list` (Klassen eines JARs/Pakets). Das Ergebnis ist rekonstruiert, nicht der Originalquelltext.""";
    }

    @Override
    public int order() {
        return 260;
    }

    @Override
    public boolean enabledByDefault() {
        return true;
    }

    @Override
    public List<ConfigField> configSchema() {
        Path home = Path.of(System.getProperty("user.home"));
        String defaults = home.resolve(".m2").resolve("repository") + "\n"
                + home.resolve(".gradle").resolve("caches").resolve("modules-2").resolve("files-2.1");
        return List.of(
                ConfigField.of(SEARCH_PATHS, "Suchpfade für JARs", FieldType.DIRECTORY_LIST).withDefault(defaults)
                        .withHelp("Hier sucht decompile_find bzw. decompile_class ohne Quelle (rekursiv nach *.jar). "
                                + "Das JDK wird immer durchsucht."),
                ConfigField.of(MAX_LINES, "Max. Ausgabezeilen", FieldType.INT).withDefault("1500")
                        .withHelp("Längere Klassen werden seitenweise geliefert (Parameter startLine)."),
                ConfigField.of(MAX_SECONDS, "Max. Sekunden je Methode", FieldType.INT).withDefault("15")
                        .withHelp("Danach gibt Vineflower die Methode auf und setzt einen Hinweis in den Quelltext."));
    }

    @Override
    public List<ToolCallback> createTools(ModuleConfig config) {
        return ToolBeans.callbacks(tools(config));
    }

    static DecompileTools tools(ModuleConfig config) {
        return new DecompileTools(searchPaths(config), Math.max(50, config.getInt(MAX_LINES, 1500)),
                Math.max(1, config.getInt(MAX_SECONDS, 15)));
    }

    static List<Path> searchPaths(ModuleConfig config) {
        List<Path> paths = new ArrayList<>();
        for (String s : config.getList(SEARCH_PATHS)) {
            try {
                paths.add(Path.of(s.strip()).toAbsolutePath().normalize());
            } catch (InvalidPathException ignored) {
                // ungültigen Eintrag überspringen
            }
        }
        return paths;
    }

    @Override
    public ConnectionTestResult testConnection(ModuleConfig config) {
        StringBuilder sb = new StringBuilder("JDK " + Runtime.version().feature() + " (jrt:/) wird immer durchsucht.\n");
        boolean any = false;
        for (Path p : searchPaths(config)) {
            boolean exists = Files.isDirectory(p);
            any |= exists;
            sb.append(exists ? "✓ " : "✗ ").append(p).append(exists ? "" : " (nicht vorhanden)").append('\n');
        }
        if (any) {
            sb.append(new ClassFinder(searchPaths(config)).jars().size()).append(" JAR(s) gefunden.");
        }
        return ConnectionTestResult.ok(sb.toString().trim());
    }
}
