package systems.grebe.devtools.mcp.modules.ci;

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
import systems.grebe.devtools.mcp.modules.ci.spi.CiProvider;
import systems.grebe.devtools.mcp.modules.ci.spi.CiSystem;

/**
 * CI/CD-Systeme über austauschbare Provider ({@link CiProvider}, per ServiceLoader): Builds bzw. Pipelines auflisten,
 * Status mit Jobs/Stages und Logs lesen; je Schalter starten, abbrechen und wiederholen. Mitgeliefert: Jenkins,
 * GitLab CI/CD, GitHub Actions.
 */
@Component
public class CiModule implements ToolModule {

    public static final String ID = "ci";

    static final String REPOSITORIES = "repositories";
    static final String DEFAULT_REPOSITORY = "defaultRepository";
    static final String REMOTE = "remote";
    static final String DEFAULT_PROVIDER = "defaultProvider";
    static final String TIMEOUT = "timeoutSeconds";
    static final String MAX_LINES = "maxOutputLines";
    static final String LOG_LINES = "logLines";
    static final String ALLOW_START = "allowStart";
    static final String ALLOW_CANCEL = "allowCancel";
    static final String ALLOW_RETRY = "allowRetry";
    static final String WRITE_PROJECTS = "writeProjects";

    private final CiProviders providers;

    public CiModule(CiProviders providers) {
        this.providers = providers;
    }

    public CiProviders providers() {
        return providers;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "CI/CD";
    }

    @Override
    public String description() {
        return "Jenkins, GitLab CI/CD, GitHub Actions und weitere CI-Systeme (erweiterbar per ServiceLoader): Builds und "
                + "Pipelines auflisten, Status mit Jobs/Stages, Logs, startbare Jobs/Workflows; optional starten, "
                + "abbrechen und wiederholen (einzeln schaltbar, je Projekt freigebbar).";
    }

    @Override
    public String instructions() {
        return """
                Für CI/CD (Jenkins, GitLab CI/CD, GitHub Actions) diese Tools statt `gh run`, `glab ci`, `curl` gegen \
                die REST-APIs oder eines Browsers verwenden. System und Projekt ergeben sich aus dem Remote des lokalen \
                Repositories (`repository` wie bei git_*; Jenkins über die Job-Zuordnung in der App), sonst \
                `project`/`provider` angeben; ein Build ist eine Nummer/ID, ein voller Schlüssel (owner/repo#123, \
                Ordner/Job#42) oder seine URL.
                - `ci_providers`: aktive Systeme, angemeldeter Benutzer, Formate; `ci_list`: Builds filtern (Status, \
                Branch – `branch=current` für den aktuellen Branch); `ci_workflows`: startbare Jenkins-Jobs bzw. \
                GitHub-Workflows.
                - `ci_get`: Status, Branch, Commit, Auslöser, Dauer und Jobs/Stages; ohne `build` der neueste Build des \
                aktuellen Branches. `ci_log`: Log eines Jobs (ohne `job` der erste fehlgeschlagene), standardmäßig die \
                letzten Zeilen – `grep` grenzt ein.
                - Fehlgeschlagenen Build untersuchen: `ci_get` → `ci_log` des fehlgeschlagenen Jobs → Ursache im Code \
                suchen. Auf laufende Builds nicht in einer Schleife warten, sondern später erneut `ci_get` aufrufen.
                - Steuern (`ci_start`, `ci_cancel`, `ci_retry`) nur auf Anweisung des Nutzers und das Ergebnis mit Link \
                melden. Fehlt ein Tool, ist es in der App abgeschaltet: den Schalter nennen, nicht per \
                `gh`/`glab`/`curl` ausweichen.""";
    }

    @Override
    public String briefInstructions() {
        return "CI/CD-Builds über ci_* (System und Projekt aus dem Git-Remote): ci_get/ci_log für Status und Fehler; "
                + "Starten, Abbrechen, Wiederholen nur auf Anweisung, Ergebnis mit Link melden.";
    }

    @Override
    public int order() {
        return 166;
    }

    static String key(String providerId, String field) {
        return providerId + "." + field;
    }

    static String enabledKey(String providerId) {
        return key(providerId, "enabled");
    }

    @Override
    public Set<String> sharedDirectoryFields() {
        return Set.of(REPOSITORIES);
    }

    @Override
    public List<ConfigField> configSchema() {
        List<String> options = new ArrayList<>(List.of("auto"));
        providers.providers().forEach(p -> options.add(p.id()));
        List<ConfigField> fields = new ArrayList<>();
        fields.add(ConfigField.of(REPOSITORIES, "Lokale Repositories", FieldType.DIRECTORY_LIST)
                .withHelp("Repository-Verzeichnisse oder Sammelordner wie im Modul Git. Aus ihrem Remote ergeben sich "
                        + "CI-System und Projekt, aus dem aktuellen Branch der Branch von ci_list, ci_get und ci_start."));
        fields.add(ConfigField.of(DEFAULT_REPOSITORY, "Standard-Repository", FieldType.STRING)
                .withHelp("Name (Ordnername) des Repositories, das ohne Angabe verwendet wird."));
        fields.add(ConfigField.of(REMOTE, "Remote", FieldType.STRING).withDefault("origin")
                .withHelp("Remote, dessen URL CI-System und Projekt bestimmt."));
        fields.add(ConfigField.of(DEFAULT_PROVIDER, "Standard-System", FieldType.ENUM).withDefault("auto")
                .withOptions(options.toArray(String[]::new))
                .withHelp("Für Aufrufe, bei denen weder Remote noch URL das System bestimmen. 'auto' = das einzige aktive."));
        for (CiProvider p : providers.providers()) {
            fields.addAll(new ConfigGroup(p.id(), p.displayName()).fields(false, p.configFields()));
        }
        fields.addAll(List.of(
                ConfigField.of(TIMEOUT, "Timeout (Sekunden)", FieldType.INT).withDefault("30"),
                ConfigField.of(MAX_LINES, "Max. Ausgabezeilen", FieldType.INT).withDefault("1500")
                        .withHelp("Längere Listen werden gekürzt."),
                ConfigField.of(LOG_LINES, "Logzeilen (Standard)", FieldType.INT).withDefault("200")
                        .withHelp("ci_log zeigt ohne Angabe die letzten so vielen Zeilen."),
                ConfigField.of(ALLOW_START, "Builds starten erlauben", FieldType.BOOLEAN).withDefault("false")
                        .withHelp("ci_start: Jenkins-Job, GitLab-Pipeline bzw. GitHub-Workflow (workflow_dispatch) "
                                + "mit Branch und Parametern starten."),
                ConfigField.of(ALLOW_CANCEL, "Builds abbrechen erlauben", FieldType.BOOLEAN).withDefault("false")
                        .withHelp("ci_cancel: laufenden oder wartenden Build abbrechen."),
                ConfigField.of(ALLOW_RETRY, "Builds wiederholen erlauben", FieldType.BOOLEAN).withDefault("false")
                        .withHelp("ci_retry: ganzen Build oder nur fehlgeschlagene Jobs erneut ausführen."),
                ConfigField.of(WRITE_PROJECTS, "Steuern nur in diesen Projekten", FieldType.STRING_LIST)
                        .withHelp("Ein Projekt je Zeile, optional mit System: owner/repo, github:owner/*, "
                                + "gitlab:gruppe/*, jenkins:Ordner/Job; * am Ende als Präfix. Leer = alle.")));
        return fields;
    }

    @Override
    public List<ToolCallback> createTools(ModuleConfig config) {
        CiEnvironment env = new CiEnvironment(providers, config);
        List<Object> beans = new ArrayList<>(List.of(new CiTools(env)));
        if (config.getBoolean(ALLOW_START)) {
            beans.add(new CiStartTools(env));
        }
        if (config.getBoolean(ALLOW_CANCEL)) {
            beans.add(new CiCancelTools(env));
        }
        if (config.getBoolean(ALLOW_RETRY)) {
            beans.add(new CiRetryTools(env));
        }
        return List.of(ToolCallbacks.from(beans.toArray()));
    }

    @Override
    public ConnectionTestResult testConnection(ModuleConfig config) {
        List<String> errors = config.validate();
        if (!errors.isEmpty()) {
            return ConnectionTestResult.failed(String.join("\n", errors));
        }
        CiEnvironment env;
        try {
            env = new CiEnvironment(providers, config);
        } catch (RuntimeException e) {
            return ConnectionTestResult.failed(e.getMessage());
        }
        if (env.entries().isEmpty()) {
            return ConnectionTestResult.failed("Kein CI-System aktiviert.");
        }
        StringBuilder sb = new StringBuilder();
        boolean allOk = true;
        for (CiEnvironment.Entry e : env.entries()) {
            CiSystem.Availability a = e.system().probe();
            sb.append(e.provider().displayName()).append(": ");
            if (a.available()) {
                sb.append(a.version() == null ? "erreichbar" : a.version());
                sb.append(a.user() == null ? "" : ", angemeldet als " + a.user());
                if (!"verfügbar".equals(a.message())) {
                    sb.append(" (").append(a.message()).append(')');
                }
            } else {
                allOk = false;
                sb.append("nicht erreichbar – ").append(a.message());
            }
            sb.append('\n');
        }
        sb.append(env.repositories().all().size()).append(" lokale(s) Repository(s)");
        String msg = sb.toString().strip();
        return allOk ? ConnectionTestResult.ok(msg) : ConnectionTestResult.failed(msg);
    }

    /** Übernimmt beim ersten Start die Repositories aus dem Modul Git. */
    @Override
    public Map<String, String> initialValues(Function<String, Map<String, String>> savedValues) {
        Map<String, String> git = savedValues.apply("git");
        Map<String, String> out = new LinkedHashMap<>();
        if (git.get(REPOSITORIES) != null) {
            out.put(REPOSITORIES, git.get(REPOSITORIES));
        }
        if (git.get(DEFAULT_REPOSITORY) != null) {
            out.put(DEFAULT_REPOSITORY, git.get(DEFAULT_REPOSITORY));
        }
        return out;
    }
}
