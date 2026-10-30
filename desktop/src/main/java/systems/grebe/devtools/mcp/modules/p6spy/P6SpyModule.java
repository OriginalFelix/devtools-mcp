package systems.grebe.devtools.mcp.modules.p6spy;

import java.util.List;
import java.util.Set;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.core.LocalFiles;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolBeans;
import systems.grebe.devtools.mcp.core.ToolModule;

/** Liest die SQL-Protokolle von p6spy ({@code spy.log}), die eine Anwendung mit dem p6spy-JDBC-Proxy schreibt. */
@Component
public class P6SpyModule implements ToolModule {

    static final String LOG_DIRS = "logDirectories";
    static final String MAX_MB = "maxMb";

    @Override
    public String id() {
        return "p6spy";
    }

    @Override
    public String displayName() {
        return "SQL-Protokoll (p6spy)";
    }

    @Override
    public String description() {
        return "Wertet das p6spy-Protokoll (spy.log) einer Anwendung aus: ausgeführte SQL-Statements mit Dauer, "
                + "teuerste Statements und N+1-Verdacht.";
    }

    @Override
    public String instructions() {
        return """
                Welche SQL-Statements eine Anwendung mit p6spy ausgeführt hat, mit `p6spy_statements` (Liste, \
                filterbar) und `p6spy_analyze` (teuerste Statements, N+1-Verdacht) lesen statt spy.log per Shell. \
                Für eine einzelne Aktion: vorher since aus einem Aufruf merken, Aktion auslösen, dann mit since \
                nur das Neue auswerten. Schreibt die Anwendung kein spy.log: p6spy einbinden (Treiber \
                com.p6spy.engine.spy.P6SpyDriver, URL jdbc:p6spy:… bzw. Spring Boot p6spy-spring-boot-starter) \
                und in spy.properties appender=com.p6spy.engine.spy.appender.FileLogger setzen.""";
    }

    @Override
    public List<ConfigField> configSchema() {
        return List.of(
                ConfigField.of(LOG_DIRS, "Verzeichnisse mit p6spy-Protokollen", FieldType.DIRECTORY_LIST)
                        .withHelp("Aus diesen Verzeichnissen (inkl. Unterverzeichnissen) darf das LLM spy.log-Dateien "
                                + "lesen; dazu die globalen „Freigaben“."),
                ConfigField.of(MAX_MB, "Max. gelesene MB", FieldType.INT).withDefault("32")
                        .withHelp("Größere Protokolle werden nur vom Ende her bis zu dieser Größe gelesen."));
    }

    @Override
    public Set<String> sharedDirectoryFields() {
        return Set.of(LOG_DIRS);
    }

    @Override
    public List<ToolCallback> createTools(ModuleConfig config) {
        LocalFiles files = new LocalFiles(config.getList(LOG_DIRS),
                "Module → SQL-Protokoll (p6spy) → „Verzeichnisse mit p6spy-Protokollen“ oder global unter „Freigaben“");
        // Obergrenze 1 GB: der gelesene Bereich liegt am Stück im Speicher
        long maxBytes = Math.clamp(config.getInt(MAX_MB, 32), 1, 1024) * 1024L * 1024L;
        return ToolBeans.callbacks(new P6SpyTools(files, maxBytes));
    }
}
