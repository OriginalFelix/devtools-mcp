package systems.grebe.devtools.mcp.modules.java;

import java.util.List;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.ConnectionTestResult;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolModule;

/**
 * Gemeinsame Grundeinstellungen der Java-Diagnosemodule (JVM, JFR, async-profiler, VisualVM, Debugger).
 * Stellt selbst keine Tools bereit.
 */
@Component
public class JavaSettingsModule implements ToolModule {

    public static final String ID = "java";

    private final org.springframework.beans.factory.ObjectProvider<JavaEnvironmentProvider> envProvider;

    @org.springframework.beans.factory.annotation.Autowired
    public JavaSettingsModule(org.springframework.beans.factory.ObjectProvider<JavaEnvironmentProvider> envProvider) {
        this.envProvider = envProvider;
    }

    /** Für Tests (nur Schema). */
    public JavaSettingsModule() {
        this(null);
    }

    static final String JDK_HOME = "jdkHome";
    static final String ARTIFACT_DIR = "artifactDir";
    static final String MAX_ARTIFACTS = "maxArtifacts";
    static final String INCLUDE = "includeProcesses";
    static final String EXCLUDE = "excludeProcesses";
    static final String JMX_TARGETS = "jmxTargets";
    static final String JMX_USER = "jmxUser";
    static final String JMX_PASSWORD = "jmxPassword";
    static final String ALLOW_INVASIVE = "allowInvasive";
    static final String DEBUG_HOSTS = "debugHosts";
    static final String MAX_LINES = "maxOutputLines";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Java-Grundeinstellungen";
    }

    @Override
    public String description() {
        return "Gemeinsame Einstellungen für JVM-Diagnose, Flight Recorder, async-profiler, VisualVM und Debugger: "
                + "welche JVMs erreichbar sind (lokal, Container, JMX) und wohin Aufzeichnungen und Dumps geschrieben werden.";
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
        return 200;
    }

    @Override
    public List<ConfigField> configSchema() {
        return List.of(
                ConfigField.of(JDK_HOME, "JDK", FieldType.DIRECTORY)
                        .withHelp("JDK für jcmd/Attach. Leer = JDK, mit dem diese App läuft."),
                ConfigField.of(ARTIFACT_DIR, "Ablageordner", FieldType.DIRECTORY)
                        .withHelp("Für JFR-Aufzeichnungen, Heap-Dumps, Flame Graphs, Snapshots. Leer = ~/.devtools-mcp/diagnostics"),
                ConfigField.of(MAX_ARTIFACTS, "Max. Anzahl Artefakte", FieldType.INT).withDefault("50")
                        .withHelp("Ältere Dateien werden automatisch gelöscht."),
                ConfigField.of(INCLUDE, "Nur diese Prozesse", FieldType.STRING)
                        .withHelp("Regulärer Ausdruck auf Hauptklasse/Kommandozeile. Leer = alle JVMs des Benutzers."),
                ConfigField.of(EXCLUDE, "Prozesse ausschließen", FieldType.STRING)
                        .withDefault("com\\.intellij\\.idea\\.Main|org\\.gradle\\.launcher\\.daemon|org\\.jetbrains\\.")
                        .withHelp("Regulärer Ausdruck. Diese App selbst ist immer ausgeschlossen. Container-Laufzeit und "
                                + "erlaubte Container werden im Modul 'Container (OCI)' eingestellt."),
                ConfigField.of(JMX_TARGETS, "JMX-Ziele", FieldType.STRING_LIST)
                        .withHelp("Eine Zeile je Ziel: alias=host:port (z.B. test=srv01:9010). Ansprechbar als jmx:<alias>."),
                ConfigField.of(JMX_USER, "JMX-Benutzer", FieldType.STRING),
                ConfigField.of(JMX_PASSWORD, "JMX-Passwort", FieldType.SECRET),
                ConfigField.of(DEBUG_HOSTS, "Erlaubte Debug-Hosts", FieldType.STRING).withDefault("localhost|127\\.0\\.0\\.1")
                        .withHelp("Regulärer Ausdruck für Hosts, an deren JDWP-Port sich der Debugger hängen darf."),
                ConfigField.of(ALLOW_INVASIVE, "Invasive Operationen erlauben", FieldType.BOOLEAN).withDefault("true")
                        .withHelp("Heap-Dumps, GC erzwingen, beliebige jcmd-Befehle. Heap-Dumps können vertrauliche Daten enthalten."),
                ConfigField.of(MAX_LINES, "Max. Ausgabezeilen", FieldType.INT).withDefault("400"));
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
        JavaEnvironment env = envProvider == null ? new JavaEnvironment(config) : envProvider.getObject().with(config);
        StringBuilder sb = new StringBuilder();
        sb.append("JDK: ").append(env.jdkHome()).append(env.jcmdAvailable() ? " (jcmd gefunden)" : " – jcmd FEHLT").append('\n');
        var procs = env.processes().list();
        sb.append("Lokale JVMs: ").append(procs.size()).append('\n');
        String cli = env.containers().cli();
        sb.append("Container-Laufzeit (Modul 'Container'): ").append(cli == null ? "keine erreichbar" : cli).append('\n');
        sb.append("JMX-Ziele: ").append(env.jmxTargets().isEmpty() ? "keine" : String.join(", ", env.jmxTargets().keySet()));
        return env.jcmdAvailable() ? ConnectionTestResult.ok(sb.toString()) : ConnectionTestResult.failed(sb.toString());
    }
}
