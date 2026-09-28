package systems.grebe.devtools.mcp.modules.skills;

import java.nio.file.Path;
import java.util.Map;

import com.zaxxer.hikari.HikariDataSource;
import org.hibernate.cfg.SchemaToolingSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import systems.grebe.devtools.mcp.config.SettingsStore;
import systems.grebe.devtools.mcp.core.ModuleConfig;

/**
 * Persistenz des Skill-Moduls über Spring Data JPA: Die {@code DataSource} kommt aus den Modul-Einstellungen der App
 * (nicht aus {@code application.properties}); EntityManagerFactory, Transaktionen und Repositories stellt Spring Boot
 * bereit.
 *
 * <p>Die Verbindungsdaten werden beim Start gelesen, Änderungen in der UI gelten deshalb erst nach einem Neustart.
 * Ist die konfigurierte Datenbank beim Start nicht erreichbar, startet die App trotzdem: Die Skills laufen dann gegen
 * eine leere In-Memory-H2, und das Modul meldet den Fehler, statt Tools anzubieten (siehe {@link SkillsModule}).
 */
@Configuration(proxyBeanMethods = false)
@EntityScan(basePackageClasses = Skill.class)
@EnableJpaRepositories(basePackageClasses = SkillRepository.class)
public class SkillsPersistenceConfig {

    private static final Logger LOG = LoggerFactory.getLogger(SkillsPersistenceConfig.class);

    static final String UNAVAILABLE_URL = "jdbc:h2:mem:skills-unavailable;DB_CLOSE_DELAY=-1";

    /** Verbindungsdaten; {@code schemaAction} ist ein Wert von {@code hibernate.hbm2ddl.auto}. */
    public record Connection(String jdbcUrl, String username, String password, String schemaAction) {

        public Connection {
            username = username == null ? "" : username;
            password = password == null ? "" : password;
            schemaAction = schemaAction == null || schemaAction.isBlank() ? "update" : schemaAction;
        }

        static Connection from(ModuleConfig config) {
            return new Connection(config.require(SkillsModule.JDBC_URL), config.getString(SkillsModule.USERNAME, "sa"),
                    config.getString(SkillsModule.PASSWORD, ""), config.getString(SkillsModule.SCHEMA_ACTION, "update"));
        }

        @Override
        public String toString() {
            // Passwort bewusst nicht ausgeben (Log, Fehlermeldungen)
            return jdbcUrl + " (Benutzer " + username + ", Schema " + schemaAction + ")";
        }
    }

    /**
     * Zustand beim Start: die konfigurierte Verbindung, die tatsächlich verwendete und – falls die konfigurierte
     * nicht erreichbar war – der Grund.
     */
    public record Status(Connection configured, Connection effective, String error) {

        public boolean available() {
            return error == null;
        }
    }

    @Bean
    Status skillsDatabaseStatus(SettingsStore store, SkillUser users) {
        Path home = store.file().toAbsolutePath().getParent();
        Map<String, String> values = store.module(SkillsModule.ID).map(m -> m.values()).orElse(Map.of());
        Connection configured = Connection.from(ModuleConfig.of(SkillsModule.schema(home), values));
        try {
            probe(configured);
            if ("update".equals(configured.schemaAction())) {
                migrate(configured, users);
            }
            LOG.info("Skill-Datenbank: {}", configured);
            return new Status(configured, configured, null);
        } catch (RuntimeException e) {
            String reason = SkillsModule.rootMessage(e);
            LOG.error("Skill-Datenbank {} nicht erreichbar, Skills sind deaktiviert: {}", configured, reason);
            return new Status(configured, new Connection(UNAVAILABLE_URL, "sa", "", "update"), reason);
        }
    }

    /** Schema-Anhebung vor Hibernate (siehe {@link SkillSchemaMigration}); Fehler machen die Datenbank unbenutzbar. */
    static void migrate(Connection c, SkillUser users) {
        SimpleDriverDataSource ds = DataSourceBuilder.create().type(SimpleDriverDataSource.class)
                .url(c.jdbcUrl()).username(c.username()).password(c.password()).build();
        try (java.sql.Connection con = ds.getConnection()) {
            SkillSchemaMigration.migrate(con, users::emailIfKnown);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("Migration auf User-Scoping fehlgeschlagen: " + e.getMessage(), e);
        }
    }

    /** Öffnet einmal eine Verbindung – ohne Pool und ohne Hibernate. */
    static void probe(Connection c) {
        SimpleDriverDataSource ds = DataSourceBuilder.create().type(SimpleDriverDataSource.class)
                .url(c.jdbcUrl()).username(c.username()).password(c.password()).build();
        try (java.sql.Connection ignored = ds.getConnection()) {
            // nur prüfen
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    @Bean
    HikariDataSource dataSource(Status skillsDatabaseStatus) {
        Connection c = skillsDatabaseStatus.effective();
        HikariDataSource ds = DataSourceBuilder.create().type(HikariDataSource.class)
                .url(c.jdbcUrl()).username(c.username()).password(c.password()).build();
        ds.setPoolName("devtools-skills");
        ds.setMaximumPoolSize(4);
        ds.setMinimumIdle(0);
        return ds;
    }

    /**
     * Schema-Modus aus den Einstellungen. Ohne diesen Customizer legte Spring Boot für die H2-Datei keine Tabellen an
     * ({@code none}, da {@code jdbc:h2:file} nicht als eingebettet gilt) und für die In-Memory-Ersatzdatenbank
     * {@code create-drop}.
     */
    @Bean
    HibernatePropertiesCustomizer skillsSchemaAction(Status skillsDatabaseStatus) {
        return props -> props.put(SchemaToolingSettings.HBM2DDL_AUTO, skillsDatabaseStatus.effective().schemaAction());
    }
}
