package systems.grebe.devtools.mcp.modules.pr;

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
import systems.grebe.devtools.mcp.modules.pr.spi.GitServer;
import systems.grebe.devtools.mcp.modules.pr.spi.GitServerProvider;

/**
 * Pull/Merge Requests auf Git-Servern über austauschbare Provider ({@link GitServerProvider}, per ServiceLoader):
 * auflisten, lesen, Diff und Kommentar-Threads; je Schalter anlegen, kommentieren, Threads auflösen, mergen und den
 * Branch pushen. Mitgeliefert: GitHub, GitLab, Bitbucket (Cloud und Data Center).
 */
@Component
public class PrModule implements ToolModule {

    public static final String ID = "pr";

    static final String REPOSITORIES = "repositories";
    static final String DEFAULT_REPOSITORY = "defaultRepository";
    static final String REMOTE = "remote";
    static final String DEFAULT_PROVIDER = "defaultProvider";
    static final String TIMEOUT = "timeoutSeconds";
    static final String MAX_LINES = "maxOutputLines";
    static final String ALLOW_CREATE = "allowCreate";
    static final String ALLOW_COMMENT = "allowComment";
    static final String ALLOW_RESOLVE = "allowResolve";
    static final String ALLOW_MERGE = "allowMerge";
    static final String ALLOW_PUSH = "allowPush";
    static final String PUSH_TIMEOUT = "pushTimeoutSeconds";
    static final String WRITE_PROJECTS = "writeProjects";
    static final String COMMENT_SUFFIX = "commentSuffix";

    private final GitServerProviders providers;

    public PrModule(GitServerProviders providers) {
        this.providers = providers;
    }

    public GitServerProviders providers() {
        return providers;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Pull Requests";
    }

    @Override
    public String description() {
        return "GitHub, GitLab, Bitbucket und weitere Git-Server (erweiterbar per ServiceLoader): Pull/Merge Requests "
                + "auflisten, lesen, Diff, CI-Status und Kommentar-Threads; optional anlegen, kommentieren, beantworten, "
                + "Threads auflösen, mergen und den Branch pushen (einzeln schaltbar, je Repository freigebbar).";
    }

    @Override
    public String instructions() {
        return """
                Für Pull/Merge Requests (GitHub, GitLab, Bitbucket) diese Tools statt `gh pr`, `glab mr`, `curl` gegen die \
                REST-APIs oder eines Browsers verwenden. Server und Repository ergeben sich aus dem Remote des lokalen \
                Repositories (`repository` wie bei git_*), sonst `project`/`provider` angeben; ein Pull Request ist eine \
                Nummer, ein voller Schlüssel (owner/repo#12, gruppe/projekt!12) oder seine URL.
                - `pr_providers`: aktive Server, angemeldeter Benutzer, Formate; `pr_list`: Pull Requests filtern \
                (Status, Autor `me`, Quell-/Ziel-Branch – z.B. den PR zum aktuellen Branch finden).
                - `pr_get`: Titel, Beschreibung, Branches, Reviewer, Freigaben, Merge-Status und CI-Checks; `pr_diff`: \
                geänderte Dateien mit Diff; `pr_comments`: alle Kommentare als Threads mit ID, Datei/Zeile und Status \
                (`unresolved=true` für die offenen).
                - Pull Request anlegen (`pr_create`, falls angeboten): Branch mit git_* committen, mit `pr_push` pushen \
                (falls angeboten, sonst den Nutzer bitten), Titel und Beschreibung aus git_log/git_diff zusammenfassen.
                - Review-Kommentare abarbeiten: `pr_comments unresolved=true` → Code ändern und committen → `pr_push` → je \
                Thread mit `pr_reply` erklären, was geändert wurde → `pr_resolve`. Nur auflösen, was wirklich erledigt ist; \
                Rückfragen beantworten statt auflösen.
                - Schreiben (`pr_create`, `pr_update`, `pr_comment`, `pr_reply`, `pr_resolve`, `pr_merge`, `pr_push`) nur \
                auf Anweisung des Nutzers und das Ergebnis mit Link melden. Fehlt ein Tool, ist es in der App abgeschaltet: \
                den Schalter nennen, nicht per `gh`/`glab`/`curl`/`git push` ausweichen. `pr_merge` nur auf ausdrücklichen \
                Auftrag.""";
    }

    @Override
    public int order() {
        return 165;
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
                        + "Server und Repository, aus dem aktuellen Branch der Quell-Branch neuer Pull Requests."));
        fields.add(ConfigField.of(DEFAULT_REPOSITORY, "Standard-Repository", FieldType.STRING)
                .withHelp("Name (Ordnername) des Repositories, das ohne Angabe verwendet wird."));
        fields.add(ConfigField.of(REMOTE, "Remote", FieldType.STRING).withDefault("origin")
                .withHelp("Remote, dessen URL den Server bestimmt und auf das pr_push pusht."));
        fields.add(ConfigField.of(DEFAULT_PROVIDER, "Standard-Server", FieldType.ENUM).withDefault("auto")
                .withOptions(options.toArray(String[]::new))
                .withHelp("Für Aufrufe, bei denen weder Remote noch URL den Server bestimmen. 'auto' = der einzige aktive."));
        for (GitServerProvider p : providers.providers()) {
            fields.addAll(new ConfigGroup(p.id(), p.displayName()).fields(false, p.configFields()));
        }
        fields.addAll(List.of(
                ConfigField.of(TIMEOUT, "Timeout (Sekunden)", FieldType.INT).withDefault("30"),
                ConfigField.of(MAX_LINES, "Max. Ausgabezeilen", FieldType.INT).withDefault("1500")
                        .withHelp("Längere Diffs und Kommentarlisten werden gekürzt."),
                ConfigField.of(ALLOW_CREATE, "Pull Requests anlegen/bearbeiten erlauben", FieldType.BOOLEAN)
                        .withDefault("false").withHelp("pr_create, pr_update"),
                ConfigField.of(ALLOW_COMMENT, "Kommentieren und antworten erlauben", FieldType.BOOLEAN)
                        .withDefault("false").withHelp("pr_comment (allgemein oder an einer Codezeile), pr_reply"),
                ConfigField.of(ALLOW_RESOLVE, "Threads auflösen erlauben", FieldType.BOOLEAN).withDefault("false")
                        .withHelp("pr_resolve: Kommentar-Threads als erledigt markieren bzw. wieder öffnen"),
                ConfigField.of(ALLOW_MERGE, "Mergen erlauben", FieldType.BOOLEAN).withDefault("false")
                        .withHelp("pr_merge – führt den Pull Request in den Ziel-Branch zusammen."),
                ConfigField.of(ALLOW_PUSH, "Branch pushen erlauben", FieldType.BOOLEAN).withDefault("false")
                        .withHelp("pr_push: 'git push -u' des aktuellen Branches mit den Git-Zugangsdaten des Rechners "
                                + "(installiertes git nötig). Nie Force-Push, nie auf den Standard-Branch."),
                ConfigField.of(PUSH_TIMEOUT, "Push-Timeout (Sekunden)", FieldType.INT).withDefault("120"),
                ConfigField.of(WRITE_PROJECTS, "Schreiben nur in diesen Repositories", FieldType.STRING_LIST)
                        .withHelp("Ein Repository je Zeile, optional mit Server: owner/repo, github:owner/*, "
                                + "gitlab:gruppe/*, bitbucket:PROJ/repo; * am Ende als Präfix. Leer = alle."),
                ConfigField.of(COMMENT_SUFFIX, "Kennzeichnung von Kommentaren", FieldType.STRING)
                        .withHelp("Wird an jeden Kommentar und jede Antwort angehängt, z.B. „(via DevTools MCP)“. "
                                + "Leer = keine.")));
        return fields;
    }

    @Override
    public List<ToolCallback> createTools(ModuleConfig config) {
        PrEnvironment env = new PrEnvironment(providers, config);
        List<Object> beans = new ArrayList<>(List.of(new PrTools(env)));
        if (config.getBoolean(ALLOW_CREATE)) {
            beans.add(new PrCreateTools(env));
        }
        if (config.getBoolean(ALLOW_COMMENT)) {
            beans.add(new PrCommentTools(env));
        }
        if (config.getBoolean(ALLOW_RESOLVE)) {
            beans.add(new PrResolveTools(env));
        }
        if (config.getBoolean(ALLOW_MERGE)) {
            beans.add(new PrMergeTools(env));
        }
        if (config.getBoolean(ALLOW_PUSH)) {
            beans.add(new PrPushTools(env));
        }
        return List.of(ToolCallbacks.from(beans.toArray()));
    }

    @Override
    public ConnectionTestResult testConnection(ModuleConfig config) {
        ConnectionTestResult invalid = ConnectionTestResult.invalid(config);
        if (invalid != null) {
            return invalid;
        }
        PrEnvironment env;
        try {
            env = new PrEnvironment(providers, config);
        } catch (RuntimeException e) {
            return ConnectionTestResult.failed(e.getMessage());
        }
        if (env.entries().isEmpty()) {
            return ConnectionTestResult.failed("Kein Git-Server aktiviert.");
        }
        StringBuilder sb = new StringBuilder();
        boolean allOk = true;
        for (PrEnvironment.Entry e : env.entries()) {
            GitServer.Availability a = e.server().probe();
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
