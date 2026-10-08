package systems.grebe.devtools.mcp.modules.container;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.ConfigGroup;
import systems.grebe.devtools.mcp.core.ConnectionTestResult;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolModule;
import systems.grebe.devtools.mcp.core.Workspaces;
import systems.grebe.devtools.mcp.modules.container.spi.ContainerRuntime;
import systems.grebe.devtools.mcp.modules.container.spi.ContainerRuntimeProvider;
import systems.grebe.devtools.mcp.core.ProviderSchema;

/**
 * OCI-Container über austauschbare Laufzeiten ({@link ContainerRuntimeProvider}, per ServiceLoader).
 * Die Einstellungen gelten auch für {@code container:}-Ziele der Java-Diagnosemodule.
 */
@Component
public class ContainerModule implements ToolModule {

    public static final String ID = "container";

    static final String DEFAULT_RUNTIME = "defaultRuntime";
    static final String ALLOWED_CONTAINERS = "allowedContainers";
    static final String ALLOWED_IMAGES = "allowedImages";
    static final String ALLOW_EXEC = "allowExec";
    static final String EXEC_ALLOWLIST = "execAllowlist";
    static final String EXEC_TIMEOUT = "execTimeoutSeconds";
    static final String ALLOW_LIFECYCLE = "allowLifecycle";
    static final String ALLOW_COPY = "allowCopy";
    static final String HOST_DIRS = "hostDirectories";
    static final String ALLOW_CREATE = "allowCreate";
    static final String LOCAL_PORTS_ONLY = "publishLocalOnly";
    static final String ALLOW_REMOVE = "allowRemove";
    static final String ONLY_OWN = "removeOnlyOwn";
    static final String ALLOW_COMPOSE = "allowCompose";
    static final String COMPOSE_PROJECTS = "composeProjects";
    static final String MASK_SECRETS = "maskSecrets";
    static final String LOG_TAIL = "logTail";
    static final String MAX_LINES = "maxOutputLines";

    /** Label, mit dem über das LLM angelegte Container markiert werden. */
    public static final String OWN_LABEL = "devtools-mcp";

    private final ContainerRuntimes runtimes;

    public ContainerModule(ContainerRuntimes runtimes) {
        this.runtimes = runtimes;
    }

    public ContainerRuntimes runtimes() {
        return runtimes;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Container (OCI)";
    }

    @Override
    public String description() {
        return "Docker, Podman und weitere Laufzeiten (erweiterbar per ServiceLoader): Container und Images auflisten, "
                + "inspizieren, Logs und Statistiken lesen; optional exec, Start/Stop, Anlegen/Löschen und Compose. "
                + "Die Freigaben gelten auch für container:-Ziele der Java-Diagnose.";
    }

    @Override
    public String instructions() {
        return """
                Für Docker/Podman diese Tools statt `docker`/`podman` in der Shell verwenden:
                - Lesen: `container_list` (statt `ps -a`), `container_inspect`, `container_logs`, `container_stats`, `container_top`, \
                `container_diff`, `container_images`, `container_networks`, `container_volumes`; `container_runtimes` zeigt die Laufzeiten.
                - Schreiben, nur wenn angeboten (einzeln in der App schaltbar): `container_start`/`stop`/`restart`, \
                `container_exec`, `container_copy_from`/`copy_to`, `container_run`, `container_pull`, `container_rm`, `container_rmi`, \
                `container_compose_*`.
                Fehlt ein schreibendes Tool, ist es in der DevTools-App abgeschaltet: dem Nutzer den Schalter nennen und nachfragen, \
                bevor du dieselbe Aktion per Shell ausführst. `container_rm` löscht standardmäßig nur per `container_run` angelegte \
                Container. Geheimnisse in Umgebungsvariablen sind maskiert – nicht per Shell auslesen.""";
    }

    @Override
    public boolean enabledByDefault() {
        return true;
    }

    @Override
    public int order() {
        return 150;
    }

    @Override
    public Set<String> sharedDirectoryFields() {
        return Set.of(COMPOSE_PROJECTS);
    }

    @Override
    public List<ConfigField> configSchema() {
        List<ConfigField> fields = new ArrayList<>();
        fields.add(ProviderSchema.defaultProviderField(DEFAULT_RUNTIME, "Standard-Laufzeit", runtimes.providers(),
                "Wird verwendet, wenn ein Tool ohne 'runtime' aufgerufen wird. 'auto' = erste erreichbare."));
        for (ContainerRuntimeProvider p : runtimes.providers()) {
            fields.addAll(new ConfigGroup(p.id(), p.displayName()).fields(true, p.configFields()));
        }
        fields.addAll(List.of(
                ConfigField.of(ALLOWED_CONTAINERS, "Erlaubte Container", FieldType.STRING).withDefault(".*")
                        .withHelp("Regulärer Ausdruck auf den Containernamen. Gilt für alle Tools und für container:-Ziele."),
                ConfigField.of(ALLOWED_IMAGES, "Erlaubte Images", FieldType.STRING).withDefault(".*")
                        .withHelp("Regulärer Ausdruck für run, pull und Image löschen, z.B. (docker\\.io/library/)?(postgres|redis).*"),
                ConfigField.of(MASK_SECRETS, "Geheimnisse maskieren", FieldType.BOOLEAN).withDefault("true")
                        .withHelp("Umgebungsvariablen mit PASSWORD, SECRET, TOKEN, KEY … in inspect-Ausgaben ausblenden."),
                ConfigField.of(ALLOW_EXEC, "Befehle im Container ausführen (exec)", FieldType.BOOLEAN).withDefault("false"),
                ConfigField.of(EXEC_ALLOWLIST, "exec: erlaubte Programme", FieldType.STRING_LIST)
                        .withHelp("Ein Programm je Zeile (erstes Wort des Befehls, z.B. ls, cat, psql). Leer = alle."),
                ConfigField.of(EXEC_TIMEOUT, "exec: Timeout (s)", FieldType.INT).withDefault("60"),
                ConfigField.of(ALLOW_LIFECYCLE, "Starten/Stoppen/Neustarten erlauben", FieldType.BOOLEAN).withDefault("false"),
                ConfigField.of(ALLOW_COPY, "Dateien kopieren erlauben (cp)", FieldType.BOOLEAN).withDefault("false"),
                ConfigField.of(HOST_DIRS, "Freigegebene Host-Verzeichnisse", FieldType.DIRECTORY_LIST)
                        .withHelp("Für cp und Bind-Mounts bei run. Andere Host-Pfade werden abgewiesen."),
                ConfigField.of(ALLOW_CREATE, "Container anlegen und Images laden (run, pull)", FieldType.BOOLEAN).withDefault("false"),
                ConfigField.of(LOCAL_PORTS_ONLY, "Ports nur auf 127.0.0.1 veröffentlichen", FieldType.BOOLEAN).withDefault("true")
                        .withHelp("run: Portangaben ohne IP werden an 127.0.0.1 gebunden, andere IPs abgewiesen."),
                ConfigField.of(ALLOW_REMOVE, "Container und Images löschen (rm, rmi)", FieldType.BOOLEAN).withDefault("false"),
                ConfigField.of(ONLY_OWN, "Nur selbst angelegte Container löschen", FieldType.BOOLEAN).withDefault("true")
                        .withHelp("Nur Container mit dem Label '" + OWN_LABEL + "' (von container_run angelegt)."),
                ConfigField.of(COMPOSE_PROJECTS, "Compose-Projekte", FieldType.DIRECTORY_LIST)
                        .withHelp("Verzeichnisse mit compose.yaml/docker-compose.yml oder Sammelordner. Lesen: ps, logs."),
                ConfigField.of(ALLOW_COMPOSE, "Compose up/down erlauben", FieldType.BOOLEAN).withDefault("false"),
                ConfigField.of(LOG_TAIL, "Log-Zeilen (Standard)", FieldType.INT).withDefault("200"),
                ConfigField.of(MAX_LINES, "Max. Ausgabezeilen", FieldType.INT).withDefault("400")));
        return fields;
    }

    @Override
    public List<ToolCallback> createTools(ModuleConfig config) {
        ContainerEnvironment env = new ContainerEnvironment(runtimes, config);
        List<Object> beans = new ArrayList<>(List.of(new ContainerReadTools(env)));
        boolean compose = !env.composeProjects().isEmpty() || Workspaces.unrestricted();
        if (compose) {
            beans.add(new ComposeReadTools(env));
        }
        if (config.getBoolean(ALLOW_EXEC)) {
            beans.add(new ContainerExecTools(env));
        }
        if (config.getBoolean(ALLOW_COPY)) {
            beans.add(new ContainerCopyTools(env));
        }
        if (config.getBoolean(ALLOW_LIFECYCLE)) {
            beans.add(new ContainerLifecycleTools(env));
        }
        if (config.getBoolean(ALLOW_CREATE)) {
            beans.add(new ContainerCreateTools(env));
        }
        if (config.getBoolean(ALLOW_REMOVE)) {
            beans.add(new ContainerRemoveTools(env));
        }
        if (config.getBoolean(ALLOW_COMPOSE) && compose) {
            beans.add(new ComposeWriteTools(env));
        }
        return List.of(ToolCallbacks.from(beans.toArray()));
    }

    @Override
    public ConnectionTestResult testConnection(ModuleConfig config) {
        ConnectionTestResult invalid = ConnectionTestResult.invalid(config);
        if (invalid != null) {
            return invalid;
        }
        ContainerEnvironment env;
        try {
            env = new ContainerEnvironment(runtimes, config);
        } catch (RuntimeException e) {
            return ConnectionTestResult.failed(e.getMessage());
        }
        StringBuilder sb = new StringBuilder();
        boolean any = false;
        for (ContainerEnvironment.Entry e : env.entries()) {
            ContainerRuntime.Availability a = env.availability(e.provider().id(), true);
            sb.append(e.provider().displayName()).append(": ");
            if (a.available()) {
                any = true;
                sb.append("Version ").append(a.version());
                try {
                    int n = e.runtime().list(false).size();
                    sb.append(", ").append(n).append(" laufende(r) Container");
                    sb.append(e.runtime().supportsCompose() ? ", Compose verfügbar" : ", kein Compose");
                } catch (RuntimeException ex) {
                    sb.append(" – ").append(ex.getMessage());
                }
            } else {
                sb.append("nicht erreichbar (").append(a.message()).append(')');
            }
            sb.append('\n');
        }
        if (env.entries().isEmpty()) {
            sb.append("Keine Laufzeit aktiviert.\n");
        }
        sb.append("Compose-Projekte: ").append(env.composeProjects().isEmpty() ? "keine"
                : String.join(", ", env.composeProjects().keySet()));
        return any ? ConnectionTestResult.ok(sb.toString().strip()) : ConnectionTestResult.failed(sb.toString().strip());
    }

    /** Übernimmt die Container-Einstellungen, die früher in den Java-Grundeinstellungen lagen. */
    @Override
    public Map<String, String> initialValues(Function<String, Map<String, String>> savedValues) {
        Map<String, String> java = savedValues.apply("java");
        Map<String, String> out = new LinkedHashMap<>();
        String cli = java.get("containerCli");
        if (cli != null) {
            if ("aus".equals(cli)) {
                runtimes.providers().forEach(p -> out.put(ProviderSchema.enabledKey(p.id()), "false"));
            } else if (runtimes.provider(cli) != null) {
                out.put(DEFAULT_RUNTIME, cli);
            }
        }
        String allowed = java.get("allowedContainers");
        if (allowed != null && !allowed.isBlank()) {
            out.put(ALLOWED_CONTAINERS, allowed);
        }
        return out;
    }
}
