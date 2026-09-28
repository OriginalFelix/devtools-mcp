package systems.grebe.devtools.mcp.modules.skills;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
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
 * bestehenden. Persistenz über Spring Data JPA (Hibernate), standardmäßig eine lokale H2-Datei im
 * Einstellungsordner; die DataSource baut {@link SkillsPersistenceConfig} beim Start aus diesen Einstellungen.
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

    private final SkillService service;
    private final SkillsPersistenceConfig.Status status;
    private final Path home;

    public SkillsModule(SkillService service, SkillsPersistenceConfig.Status status, SettingsStore store) {
        this.service = service;
        this.status = status;
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
                + "oder verbessern – mit Zusatzdateien und Änderungshistorie. Gespeichert per Spring Data JPA, "
                + "standardmäßig in einer lokalen H2-Datenbank. Verbindungsänderungen gelten nach Neustart.";
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
        return schema(home);
    }

    /** Schema ohne Modul-Instanz – {@link SkillsPersistenceConfig} braucht es, bevor die Beans stehen. */
    static List<ConfigField> schema(Path home) {
        return List.of(
                ConfigField.of(JDBC_URL, "JDBC-URL", FieldType.STRING).asRequired().withDefault(defaultJdbcUrl(home))
                        .withHelp("Standard: lokale H2-Datei im Einstellungsordner. Andere Datenbanken brauchen ihren "
                                + "JDBC-Treiber auf dem Classpath. Änderungen an Verbindung und Schema gelten nach "
                                + "einem Neustart der App."),
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

    static String defaultJdbcUrl(Path home) {
        return "jdbc:h2:file:" + home.resolve("skills").toString().replace('\\', '/');
    }

    @Override
    public List<ToolCallback> createTools(ModuleConfig config) {
        if (!status.available()) {
            // Erscheint in der UI als Modulfehler; die übrigen Module laufen weiter.
            throw new IllegalStateException("Skill-Datenbank " + status.configured().jdbcUrl()
                    + " war beim Start nicht erreichbar: " + status.error()
                    + ". Einstellungen prüfen (Verbindung testen) und die App neu starten.");
        }
        List<ToolCallback> tools = new ArrayList<>(List.of(ToolCallbacks.from(new SkillReadTools(service))));
        if (config.getBoolean(ALLOW_WRITE)) {
            tools.addAll(List.of(ToolCallbacks.from(new SkillWriteTools(service, maxContent(config)))));
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
        SkillsPersistenceConfig.Connection c = SkillsPersistenceConfig.Connection.from(config);
        SkillsPersistenceConfig.Connection active = status.configured();
        if (status.available() && c.equals(active)) {
            try {
                return ConnectionTestResult.ok("Verbunden mit " + c.jdbcUrl() + " – " + service.count()
                        + " Skill(s) gespeichert.");
            } catch (RuntimeException e) {
                return ConnectionTestResult.failed("Verbindung fehlgeschlagen: " + rootMessage(e));
            }
        }
        // Neue Werte: ohne Pool und ohne Hibernate prüfen, ob die Datenbank erreichbar ist. Die laufende
        // DataSource bleibt unberührt, die neuen Werte gelten erst nach einem Neustart.
        try {
            SimpleDriverDataSource ds = DataSourceBuilder.create().type(SimpleDriverDataSource.class)
                    .url(c.jdbcUrl()).username(c.username()).password(c.password()).build();
            Boolean hasTable = new JdbcTemplate(ds).execute((java.sql.Connection con) -> {
                try (ResultSet rs = con.getMetaData().getTables(null, null, "%", new String[] {"TABLE"})) {
                    while (rs.next()) {
                        if ("skill".equalsIgnoreCase(rs.getString("TABLE_NAME"))) {
                            return true;
                        }
                    }
                    return false;
                }
            });
            return ConnectionTestResult.ok("Verbindung zu " + c.jdbcUrl() + " möglich"
                    + (Boolean.TRUE.equals(hasTable) ? " (Skill-Tabellen vorhanden)" : " (Tabellen werden beim Start angelegt)")
                    + ". Wirksam nach einem Neustart der App" + (status.available()
                    ? " – aktiv ist noch " + active.jdbcUrl() + "." : "."));
        } catch (RuntimeException e) {
            return ConnectionTestResult.failed("Verbindung fehlgeschlagen: " + rootMessage(e));
        }
    }

    static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }

    private static int maxContent(ModuleConfig config) {
        return Math.max(1_000, Math.min(Skill.CONTENT_COLUMN, config.getInt(MAX_CONTENT, 100_000)));
    }
}
