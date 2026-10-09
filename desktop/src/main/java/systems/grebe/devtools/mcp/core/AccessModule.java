package systems.grebe.devtools.mcp.core;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

/**
 * Globale Freigaben: Verzeichnisse, die alle Module mit Projektlisten (Git, Build, Code-Graph, Pull Requests, Compose)
 * zusätzlich zu ihren eigenen bekommen, und ein Schalter, der die Beschränkung auf freigegebene Verzeichnisse ganz
 * aufhebt. Stellt selbst keine Tools bereit; angewendet von der {@link ToolRegistry}.
 */
@Component
public class AccessModule implements ToolModule {

    public static final String ID = "access";
    public static final String DIRECTORIES = "directories";
    public static final String UNRESTRICTED = "unrestricted";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Freigaben";
    }

    @Override
    public String description() {
        return "Verzeichnisse, die für alle Tools mit Projektlisten freigegeben sind (Git, Build, Code-Graph, Pull "
                + "Requests, Compose) – zusätzlich zu den Freigaben im jeweiligen Modul. Optional lässt sich die "
                + "Beschränkung ganz aufheben.";
    }

    @Override
    public boolean hasTools() {
        return false;
    }

    @Override
    public boolean enabledByDefault() {
        return true;
    }

    @Override
    public int order() {
        return 1;
    }

    @Override
    public List<ConfigField> configSchema() {
        return List.of(
                ConfigField.of(DIRECTORIES, "Für alle Tools freigegeben", FieldType.DIRECTORY_LIST)
                        .withHelp("Projektverzeichnisse oder Sammelordner. Jedes Modul übernimmt, was zu ihm passt "
                                + "(Git-Repositories, Gradle-/Maven-Projekte, Java-Projekte, Compose-Projekte)."),
                ConfigField.of(UNRESTRICTED, "Beschränkung aufheben – alle Verzeichnisse erlauben", FieldType.BOOLEAN)
                        .withDefault("false")
                        .withHelp("Die Tools akzeptieren dann jeden absoluten Pfad auf diesem Rechner, nicht nur "
                                + "freigegebene Verzeichnisse. Was der Team-Server nur lesend freigibt, bleibt "
                                + "schreibgeschützt; die Schalter der Module (Schreiben, Push …) gelten weiter."));
    }

    @Override
    public List<ToolCallback> createTools(ModuleConfig config) {
        return List.of();
    }

    @Override
    public ConnectionTestResult testConnection(ModuleConfig config) {
        ConnectionTestResult invalid = ConnectionTestResult.invalid(config);
        if (invalid != null) {
            return invalid;
        }
        List<String> lines = new ArrayList<>();
        List<String> dirs = config.getList(DIRECTORIES);
        lines.add(dirs.isEmpty() ? "Keine globalen Freigaben." : dirs.size() + " Verzeichnis(se) für alle Tools freigegeben.");
        if (config.getBoolean(UNRESTRICTED)) {
            lines.add("Beschränkung aufgehoben: Tools dürfen jedes Verzeichnis verwenden.");
        }
        return ConnectionTestResult.ok(String.join("\n", lines));
    }
}
