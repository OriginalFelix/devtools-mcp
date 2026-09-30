package systems.grebe.devtools.mcp.account;

import java.nio.file.Path;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.flyway.autoconfigure.FlywayDataSource;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import systems.grebe.devtools.mcp.server.ServerHome;

/**
 * Core-Datenbank des Team-Servers: Benutzer, Tokens, Profile, Einstellungen, Modul-Katalog, Projekte.
 *
 * <p>Standard ist die H2-Datei {@code core.mv.db} im {@link ServerHome Server-Verzeichnis}; für PostgreSQL o.ä.
 * {@code devtools.core.datasource.url/username/password} setzen (z.B. per Umgebungsvariable
 * {@code DEVTOOLS_CORE_DATASOURCE_URL}). Das Schema legt Flyway an ({@code classpath:db/core}).
 *
 * <p>Zugriff über {@link JdbcClient} statt JPA: eine zweite JPA-Einheit schaltete Spring Boots Autokonfiguration für
 * die Skills ab (EntityManagerFactory und TransactionManager sind dort {@code @ConditionalOnMissingBean}).
 */
@Configuration(proxyBeanMethods = false)
public class CoreDatabaseConfig {

    private static final Logger LOG = LoggerFactory.getLogger(CoreDatabaseConfig.class);

    @Bean
    @FlywayDataSource
    HikariDataSource coreDataSource(ServerHome home,
                                    @Value("${devtools.core.datasource.url:}") String url,
                                    @Value("${devtools.core.datasource.username:sa}") String username,
                                    @Value("${devtools.core.datasource.password:}") String password) {
        String jdbcUrl = url.isBlank() ? defaultUrl(home.dir()) : url;
        LOG.info("Core-Datenbank: {}", jdbcUrl);
        HikariDataSource ds = DataSourceBuilder.create().type(HikariDataSource.class)
                .url(jdbcUrl).username(username).password(password).build();
        ds.setPoolName("devtools-core");
        ds.setMaximumPoolSize(8);
        ds.setMinimumIdle(0);
        return ds;
    }

    static String defaultUrl(Path home) {
        return "jdbc:h2:file:" + home.resolve("core").toString().replace('\\', '/');
    }

    @Bean
    JdbcClient coreJdbc(@Qualifier("coreDataSource") HikariDataSource coreDataSource) {
        return JdbcClient.create(coreDataSource);
    }

    /** Transaktionen der Core-Datenbank (bewusst keine Bean vom Typ TransactionManager, s.o.). */
    @Bean
    TransactionTemplate coreTransactions(@Qualifier("coreDataSource") HikariDataSource coreDataSource) {
        return new TransactionTemplate(new DataSourceTransactionManager(coreDataSource));
    }
}
