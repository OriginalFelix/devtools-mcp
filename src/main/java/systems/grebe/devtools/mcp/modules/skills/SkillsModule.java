package systems.grebe.devtools.mcp.modules.skills;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.config.SettingsStore;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.ConnectionTestResult;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolModule;

/**
 * Skill-Speicher für das LLM, angelehnt an das Skill-Management von Hermes: Das LLM sucht vor einer Aufgabe passende
 * Skills, lädt sie und legt nach einer schwierigen oder neu gelernten Aufgabe selbst einen an bzw. korrigiert einen
 * bestehenden. Persistenz über Hibernate ORM, standardmäßig eine lokale H2-Datei im Einstellungsordner.
 */
@Component
public class SkillsModule implements ToolModule {

    public static final String ID = "skills";

    static final String JDBC_URL = "jdbcUrl";
    static final String USERNAME = "username";
    static final String PASSWORD = "password";
    static final String SCHEMA_ACTION = "schemaAction";
    static final String ALLOW_WRITE = "allowWrite";
    static final String ALLOW_DELETE = "allowDelete";
    static final String MAX_CONTENT = "maxContentChars";

    private final SkillDatabase database;
    private final Path home;

    public SkillsModule(SkillDatabase database, SettingsStore store) {
        this.database = database;
        this.home = store.file().toAbsolutePath().getParent();
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Skills";
    }

    @Override
    public String description() {
        return "Wiederverwendbare Abläufe (Skills) für das LLM: suchen, laden und nach gelösten Aufgaben selbst anlegen "
                + "oder verbessern – mit Zusatzdateien und Änderungshistorie. Gespeichert per Hibernate, "
                + "standardmäßig in einer lokalen H2-Datenbank.";
    }

    @Override
    public String instructions() {
        return """
                Skills sind dein prozedurales Gedächtnis: erprobte Abläufe, Befehle, Fallstricke und Vorlieben des \
                Nutzers für wiederkehrende Aufgabentypen.

                Vor einer Aufgabe:
                - `skills_list` (mit Suchtext) aufrufen, sobald die Aufgabe über eine einfache Frage hinausgeht. Passt \
                ein Skill auch nur teilweise, ihn mit `skills_view` laden und seine Anweisungen befolgen.

                Nach einer Aufgabe – Skill anlegen oder verbessern, wenn:
                - die Aufgabe schwierig oder mehrstufig war (etwa 5+ Tool-Aufrufe) oder Fehlversuche nötig waren,
                - der Nutzer dich korrigiert oder eine Vorliebe für diese Art Arbeit genannt hat,
                - du einen nicht offensichtlichen Ablauf, Befehl oder Workaround gefunden hast, der wiederkommen wird.
                Dann: gibt es einen passenden Skill → `skills_patch` (gezielt ergänzen/korrigieren); sonst → \
                `skills_create`. War ein geladener Skill falsch oder lückenhaft, ihn sofort mit `skills_patch` \
                korrigieren. Nach größeren Aufgaben kurz anbieten, den Ablauf als Skill zu speichern.

                Inhalt: Lehren statt Protokoll – Regel plus Begründung, konkrete Befehle/Tool-Aufrufe, Prüfschritte. \
                Keine Einmal-Details (Datumsangaben, Ticketnummern, PIDs) und niemals Passwörter, Tokens oder andere \
                Geheimnisse. `description` ist ein Satz, wann der Skill greift. Lange Referenzen mit \
                `skills_write_file` nach references/ auslagern. `skills_delete` nur auf ausdrücklichen Wunsch.""";
    }

    @Override
    public boolean enabledByDefault() {
        return true;
    }

    @Override
    public int order() {
        return 50;
    }

    @Override
    public List<ConfigField> configSchema() {
        return List.of(
                ConfigField.of(JDBC_URL, "JDBC-URL", FieldType.STRING).asRequired().withDefault(defaultJdbcUrl())
                        .withHelp("Standard: lokale H2-Datei im Einstellungsordner. Andere Datenbanken brauchen ihren "
                                + "JDBC-Treiber auf dem Classpath."),
                ConfigField.of(USERNAME, "Benutzer", FieldType.STRING).withDefault("sa"),
                ConfigField.of(PASSWORD, "Passwort", FieldType.SECRET)
                        .withHelp("Wird verschlüsselt gespeichert. Für die lokale H2-Datei leer lassen."),
                ConfigField.of(SCHEMA_ACTION, "Schema", FieldType.ENUM).withDefault("update")
                        .withOptions("update", "validate", "none")
                        .withHelp("update = Tabellen anlegen/ergänzen (hibernate.hbm2ddl.auto), validate = nur prüfen."),
                ConfigField.of(ALLOW_WRITE, "Anlegen und Bearbeiten erlauben", FieldType.BOOLEAN).withDefault("true")
                        .withHelp("create, patch, update, write_file, remove_file."),
                ConfigField.of(ALLOW_DELETE, "Löschen erlauben", FieldType.BOOLEAN).withDefault("false"),
                ConfigField.of(MAX_CONTENT, "Max. Zeichen je Inhalt", FieldType.INT).withDefault("100000")
                        .withHelp("Obergrenze für Skill-Inhalt und Zusatzdateien."));
    }

    String defaultJdbcUrl() {
        return "jdbc:h2:file:" + home.resolve("skills").toString().replace('\\', '/');
    }

    @Override
    public List<ToolCallback> createTools(ModuleConfig config) {
        SkillService service = new SkillService(database, connection(config), maxContent(config));
        List<ToolCallback> tools = new ArrayList<>(List.of(ToolCallbacks.from(new SkillReadTools(service))));
        if (config.getBoolean(ALLOW_WRITE)) {
            tools.addAll(List.of(ToolCallbacks.from(new SkillWriteTools(service))));
        }
        if (config.getBoolean(ALLOW_DELETE)) {
            tools.addAll(List.of(ToolCallbacks.from(new SkillDeleteTools(service))));
        }
        return tools;
    }

    @Override
    public ConnectionTestResult testConnection(ModuleConfig config) {
        List<String> errors = config.validate();
        if (!errors.isEmpty()) {
            return ConnectionTestResult.failed(String.join("\n", errors));
        }
        SkillDatabase.Connection c = connection(config);
        try {
            long count = SkillDatabase.withTemporary(c, SkillService::count);
            return ConnectionTestResult.ok("Verbunden mit " + c.jdbcUrl() + " – " + count + " Skill(s) gespeichert.");
        } catch (RuntimeException e) {
            return ConnectionTestResult.failed("Verbindung fehlgeschlagen: " + SkillDatabase.rootMessage(e));
        }
    }

    static SkillDatabase.Connection connection(ModuleConfig config) {
        return new SkillDatabase.Connection(config.require(JDBC_URL), config.getString(USERNAME, "sa"),
                config.getString(PASSWORD, ""), config.getString(SCHEMA_ACTION, "update"));
    }

    private static int maxContent(ModuleConfig config) {
        return Math.max(1_000, Math.min(Skill.CONTENT_COLUMN, config.getInt(MAX_CONTENT, 100_000)));
    }
}
