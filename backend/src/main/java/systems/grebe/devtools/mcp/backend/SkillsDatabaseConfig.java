package systems.grebe.devtools.mcp.backend;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;

import com.zaxxer.hikari.HikariDataSource;
import org.hibernate.cfg.SchemaToolingSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import systems.grebe.devtools.mcp.backend.skills.Skill;
import systems.grebe.devtools.mcp.backend.skills.SkillRepository;
import systems.grebe.devtools.mcp.backend.skills.SkillSchemaMigration;

/**
 * Skill-Datenbank des Servers (Spring Data JPA): Standard ist die H2-Datei {@code skills.mv.db} im
 * {@link BackendHome Server-Verzeichnis}; für PostgreSQL o.ä. {@code devtools.skills.datasource.url/username/password}
 * setzen. Eine bisher von den Desktop-Apps gemeinsam genutzte Skill-Datenbank lässt sich so direkt übernehmen – das
 * Schema ist dasselbe.
 *
 * <p>{@code @Primary}: Spring Boots JPA-Autokonfiguration nimmt die primäre DataSource; die Core-Datenbank
 * ({@code CoreDatabaseConfig}) läuft daneben über {@code JdbcClient}.
 */
@Configuration(proxyBeanMethods = false)
@EntityScan(basePackageClasses = Skill.class)
@EnableJpaRepositories(basePackageClasses = SkillRepository.class)
public class SkillsDatabaseConfig {

    private static final Logger LOG = LoggerFactory.getLogger(SkillsDatabaseConfig.class);

    @Bean
    @Primary
    HikariDataSource dataSource(BackendHome home,
                                @Value("${devtools.skills.datasource.url:}") String url,
                                @Value("${devtools.skills.datasource.username:sa}") String username,
                                @Value("${devtools.skills.datasource.password:}") String password) {
        String jdbcUrl = url.isBlank()
                ? "jdbc:h2:file:" + home.resolve("skills").toString().replace('\\', '/') : url;
        LOG.info("Skill-Datenbank: {}", jdbcUrl);
        HikariDataSource ds = DataSourceBuilder.create().type(HikariDataSource.class)
                .url(jdbcUrl).username(username).password(password).build();
        ds.setPoolName("devtools-skills");
        ds.setMaximumPoolSize(8);
        ds.setMinimumIdle(0);
        try (Connection con = ds.getConnection()) {
            // ältere Skill-Datenbanken ohne Eigentümer-Spalte anheben (siehe SkillSchemaMigration)
            SkillSchemaMigration.migrate(con, Optional::empty);
        } catch (SQLException e) {
            throw new IllegalStateException("Skill-Datenbank " + jdbcUrl + " nicht nutzbar: " + e.getMessage(), e);
        }
        return ds;
    }

    /** Tabellen anlegen bzw. ergänzen; ohne diesen Customizer legte Spring Boot für die H2-Datei nichts an. */
    @Bean
    HibernatePropertiesCustomizer skillsSchemaAction(
            @Value("${devtools.skills.schema-action:update}") String schemaAction) {
        return props -> props.put(SchemaToolingSettings.HBM2DDL_AUTO, schemaAction);
    }
}
