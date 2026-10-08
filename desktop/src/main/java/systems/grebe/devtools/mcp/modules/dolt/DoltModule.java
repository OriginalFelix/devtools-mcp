package systems.grebe.devtools.mcp.modules.dolt;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.ConnectionTestResult;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.core.ModuleAction;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolBeans;
import systems.grebe.devtools.mcp.core.ToolModule;

/**
 * Datenbank-Branches zum Git-Branch: Dolt, Doltgres und Doltlite versionieren Daten wie Git. Wechselt der Branch eines
 * eingetragenen Git-Arbeitsverzeichnisses – per git_checkout, in der IDE oder in der Shell –, stellt das Modul die
 * Datenbank auf den gleichnamigen Branch um und legt ihn an, wenn es ihn noch nicht gibt. Den Wechsel bemerkt
 * {@link DoltBranchWatcher}; abgeglichen wird in {@link DoltBranches}.
 */
@Component
public class DoltModule implements ToolModule {

    public static final String ID = "dolt";

    static final String DATABASES = "databases";
    static final String DOLTLITE = "doltlitePath";
    static final String TIMEOUT = "timeoutSeconds";

    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final DoltBranches branches;

    public DoltModule(DoltBranches branches) {
        this.branches = branches;
    }

    /** Eingetragene Datenbanken; ohne Namen übergangen, bei doppelten Namen gilt der erste Eintrag. */
    static List<DoltDatabase> databases(ModuleConfig config) {
        List<DoltDatabase> out = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Map<String, String> r : config.getRecords(DATABASES)) {
            DoltDatabase db = DoltDatabase.of(r);
            if (!db.name().isEmpty() && names.add(db.name().toLowerCase(Locale.ROOT))) {
                out.add(db);
            }
        }
        return List.copyOf(out);
    }

    static DoltBranches.Settings settings(ModuleConfig config) {
        return new DoltBranches.Settings(Math.max(1, config.getInt(TIMEOUT, 5)), config.getString(DOLTLITE, ""));
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Datenbank-Branches (Dolt)";
    }

    @Override
    public String description() {
        return "Dolt-, Doltgres- und Doltlite-Datenbanken folgen dem Git-Branch eines Projekts: beim Wechsel des Branches "
                + "wird der gleichnamige Datenbank-Branch ausgecheckt und, falls nötig, angelegt.";
    }

    @Override
    public String instructions() {
        return """
                Für Dolt-, Doltgres- und Doltlite-Datenbanken, die in der DevTools-App mit einem Git-Arbeitsverzeichnis \
                verknüpft sind, stellt DevTools den Datenbank-Branch selbst um, sobald der Git-Branch wechselt \
                (git_checkout, git_create_branch, IDE, Shell): fehlt der gleichnamige Datenbank-Branch, wird er vom \
                aktuellen angelegt, danach landen neue Verbindungen auf ihm. Nach git_*-Aufrufen steht im Ergebnis, \
                was umgestellt wurde. Nicht selbst `CALL dolt_checkout(…)`, `SET PERSIST …_default_branch` oder \
                `doltlite` in der Shell verwenden.
                - `dolt_status`: Git-Branch, Standard-Branch und Branches jeder verknüpften Datenbank, letzter Abgleich.
                - `dolt_sync`: sofort abgleichen, z.B. wenn der Datenbank-Server beim Wechsel nicht lief.
                Eine bereits laufende Anwendung behält ihre offenen Verbindungen auf dem alten Branch, bis sie neu \
                verbindet (Neustart). Daten lesen und schreiben weiter über die jdbc_*-Tools.""";
    }

    @Override
    public int order() {
        return 173;
    }

    @Override
    public List<ConfigField> configSchema() {
        return List.of(
                ConfigField.records(DATABASES, "Datenbanken",
                                ConfigField.of(DoltDatabase.NAME, "Name", FieldType.STRING).asRequired()
                                        .withHelp("Eindeutiger Name, z.B. app-dev."),
                                ConfigField.of(DoltDatabase.KIND, "Art", FieldType.ENUM)
                                        .withOptions(DoltDatabase.Kind.keys()).withDefault("dolt")
                                        .withHelp("dolt = Dolt-SQL-Server (MySQL-Protokoll), doltgres = Doltgres "
                                                + "(PostgreSQL-Protokoll), doltlite = Doltlite-Datei (SQLite mit "
                                                + "Versionierung)."),
                                ConfigField.of(DoltDatabase.REPOSITORY, "Git-Arbeitsverzeichnis", FieldType.DIRECTORY)
                                        .asRequired()
                                        .withHelp("Repository oder Worktree, dessen Branch die Datenbank folgt."),
                                ConfigField.of(DoltDatabase.LOCATION, "Ort", FieldType.STRING).asRequired()
                                        .withHelp("Dolt/Doltgres: host[:port]/datenbank, z.B. localhost:3306/app bzw. "
                                                + "localhost:5432/app, optional ?parameter für den JDBC-Treiber. "
                                                + "Doltlite: Pfad der Datei, z.B. C:\\projekt\\data\\app.db."),
                                ConfigField.of(DoltDatabase.USERNAME, "Benutzer", FieldType.STRING)
                                        .withHelp("Dolt/Doltgres, z.B. root bzw. postgres."),
                                ConfigField.of(DoltDatabase.PASSWORD, "Passwort", FieldType.SECRET)
                                        .withHelp("Wird verschlüsselt gespeichert und nie an das LLM gegeben."),
                                ConfigField.of(DoltDatabase.BASE_BRANCH, "Startpunkt neuer Branches", FieldType.STRING)
                                        .withHelp("Leer = der Branch, auf dem die Datenbank beim Wechsel steht (wie "
                                                + "git checkout -b). Sonst fest, z.B. main."))
                        .withHelp("Je Datenbank: Art, Git-Arbeitsverzeichnis, Ort und Zugang. Wechselt dort der "
                                + "Git-Branch, wird der gleichnamige Datenbank-Branch ausgecheckt (fehlt er, wird er "
                                + "angelegt). Das LLM sieht nie Passwörter."),
                ConfigField.of(DOLTLITE, "Programm doltlite", FieldType.STRING)
                        .withHelp("Nur für Doltlite: Pfad zu doltlite bzw. doltlite.exe "
                                + "(github.com/dolthub/doltlite/releases); leer = aus dem PATH."),
                ConfigField.of(TIMEOUT, "Zeitlimit je Datenbank (Sekunden)", FieldType.INT).withDefault("5")
                        .withHelp("Verbindungsaufbau und Anweisungen; ein nicht laufender Server verzögert "
                                + "git_*-Aufrufe höchstens so lange."));
    }

    @Override
    public List<ToolCallback> createTools(ModuleConfig config) {
        return ToolBeans.callbacks(new DoltTools(branches, databases(config), settings(config)));
    }

    @Override
    public ConnectionTestResult testConnection(ModuleConfig config) {
        List<String> errors = config.validate();
        if (!errors.isEmpty()) {
            return ConnectionTestResult.failed(String.join("\n", errors));
        }
        List<DoltDatabase> dbs = databases(config);
        if (dbs.isEmpty()) {
            return ConnectionTestResult.failed("Keine Datenbank eingetragen.");
        }
        DoltTools tools = new DoltTools(branches, dbs, settings(config));
        StringBuilder sb = new StringBuilder();
        boolean ok = true;
        for (DoltDatabase db : dbs) {
            DoltTools.Report r = tools.report(db);
            ok &= r.ok();
            sb.append(r.text()).append('\n');
        }
        String msg = sb.toString().strip();
        return ok ? ConnectionTestResult.ok(msg) : ConnectionTestResult.failed(msg);
    }

    @Override
    public List<ModuleAction> actions() {
        return List.of(new ModuleAction() {
            @Override
            public String id() {
                return "sync";
            }

            @Override
            public String label() {
                return "Jetzt abgleichen";
            }

            @Override
            public String description() {
                return "Stellt die Datenbank sofort auf den aktuellen Git-Branch um und legt ihn bei Bedarf an.";
            }

            @Override
            public List<String> targets(ModuleConfig config) {
                return databases(config).stream().map(DoltDatabase::name).toList();
            }

            @Override
            public String describe(ModuleConfig config, String target) {
                return databases(config).stream().filter(d -> d.name().equalsIgnoreCase(target)).findFirst()
                        .flatMap(branches::last)
                        .map(o -> "Letzter Abgleich " + TIME.format(o.at()) + ": " + o.message())
                        .orElse("Noch nicht abgeglichen.");
            }

            @Override
            public ActionResult run(ModuleConfig config, String target, Set<String> flags, Progress progress) {
                DoltDatabase db = databases(config).stream().filter(d -> d.name().equalsIgnoreCase(target)).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Datenbank wählen."));
                progress.update("Gleiche " + db.name() + " ab …", -1);
                DoltBranches.Outcome o = branches.sync(db, settings(config));
                return o.ok() ? ActionResult.ok(o.describe()) : ActionResult.failed(o.describe());
            }
        });
    }
}
