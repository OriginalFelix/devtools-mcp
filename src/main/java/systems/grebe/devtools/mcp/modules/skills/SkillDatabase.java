package systems.grebe.devtools.mcp.modules.skills;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.JdbcSettings;
import org.hibernate.cfg.SchemaToolingSettings;
import org.hibernate.jpa.HibernatePersistenceConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Hält die Hibernate-{@link SessionFactory} des Skill-Speichers. Sie wird erst beim ersten Zugriff aufgebaut (das
 * Erzeugen der Tools darf laut {@code ToolModule#createTools} nicht werfen) und neu aufgebaut, sobald sich die
 * Verbindungsdaten in der UI ändern. Spring Boot konfiguriert bewusst keine eigene DataSource – der Speicher gehört
 * allein diesem Modul.
 */
@Component
public class SkillDatabase {

    private static final Logger LOG = LoggerFactory.getLogger(SkillDatabase.class);

    /** Verbindungsdaten; {@code schemaAction} ist ein Wert von {@code hibernate.hbm2ddl.auto}. */
    public record Connection(String jdbcUrl, String username, String password, String schemaAction) {
        public Connection {
            Objects.requireNonNull(jdbcUrl, "jdbcUrl");
            username = username == null ? "" : username;
            password = password == null ? "" : password;
            schemaAction = schemaAction == null || schemaAction.isBlank() ? "update" : schemaAction;
        }

        @Override
        public String toString() {
            return jdbcUrl + " (Benutzer " + username + ", Schema " + schemaAction + ")";
        }
    }

    private Connection current;
    private HikariDataSource dataSource;
    private SessionFactory sessionFactory;

    /** Führt {@code work} in einer Transaktion aus; bei einer Exception wird zurückgerollt. */
    public <R> R inTransaction(Connection connection, Function<Session, R> work) {
        return sessionFactory(connection).fromTransaction(work);
    }

    synchronized SessionFactory sessionFactory(Connection connection) {
        if (sessionFactory != null && connection.equals(current)) {
            return sessionFactory;
        }
        close();
        HikariDataSource ds = null;
        try {
            ds = createDataSource(connection, "devtools-skills");
            sessionFactory = buildSessionFactory(ds, connection.schemaAction());
        } catch (RuntimeException e) {
            if (ds != null) {
                ds.close();
            }
            throw new IllegalStateException("Skill-Datenbank konnte nicht geöffnet werden (" + connection.jdbcUrl()
                    + "): " + rootMessage(e), e);
        }
        dataSource = ds;
        current = connection;
        LOG.info("Skill-Datenbank geöffnet: {}", connection);
        return sessionFactory;
    }

    /** Öffnet die Datenbank einmalig ohne den gemeinsamen Zustand zu verändern – für „Verbindung testen“. */
    static <R> R withTemporary(Connection connection, Function<Session, R> work) {
        try (HikariDataSource ds = createDataSource(connection, "devtools-skills-test");
             SessionFactory sf = buildSessionFactory(ds, connection.schemaAction())) {
            return sf.fromTransaction(work);
        }
    }

    private static HikariDataSource createDataSource(Connection connection, String poolName) {
        HikariConfig cfg = new HikariConfig();
        cfg.setPoolName(poolName);
        cfg.setJdbcUrl(connection.jdbcUrl());
        cfg.setUsername(connection.username());
        cfg.setPassword(connection.password());
        cfg.setMaximumPoolSize(4);
        cfg.setMinimumIdle(0);
        // Nicht beim Start verbinden: Fehler sollen erst beim Tool-Aufruf als verständliche Meldung auftauchen.
        cfg.setInitializationFailTimeout(-1);
        cfg.setConnectionTimeout(10_000);
        return new HikariDataSource(cfg);
    }

    private static SessionFactory buildSessionFactory(HikariDataSource ds, String schemaAction) {
        return new HibernatePersistenceConfiguration("devtools-skills")
                .managedClasses(Skill.class, SkillFile.class, SkillRevision.class)
                .properties(Map.of(
                        JdbcSettings.JAKARTA_NON_JTA_DATASOURCE, ds,
                        SchemaToolingSettings.HBM2DDL_AUTO, schemaAction))
                .createEntityManagerFactory();
    }

    static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }

    @PreDestroy
    public synchronized void close() {
        if (sessionFactory != null) {
            try {
                sessionFactory.close();
            } catch (RuntimeException e) {
                LOG.warn("SessionFactory der Skill-Datenbank ließ sich nicht schließen", e);
            }
            sessionFactory = null;
        }
        if (dataSource != null) {
            dataSource.close();
            dataSource = null;
        }
        current = null;
    }
}
