package systems.grebe.devtools.mcp.backend.skills;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import systems.grebe.devtools.mcp.backend.BackendHome;
import systems.grebe.devtools.mcp.backend.SkillsDatabaseConfig;
import systems.grebe.devtools.mcp.backend.memories.MemoryService;
import systems.grebe.devtools.mcp.backend.scripts.ScriptService;

/**
 * Schlanker Spring-Kontext für die Skill- und Skript-Ablage: genau die Persistenz-Konfiguration des Backends
 * ({@link SkillsDatabaseConfig}) gegen eine echte H2-Datei, ohne Test-Transaktion – Commit, Rollback und Bulk-Updates
 * wirken wie im Betrieb. Eigentümer ist ein fester Testbenutzer statt des GraphQL-Aufrufers.
 */
public final class SkillTestSupport {

    /** Benutzer der Tests. */
    public static final String USER = "felix@example.com";

    private SkillTestSupport() {
    }

    @SpringBootConfiguration
    @ImportAutoConfiguration({HibernateJpaAutoConfiguration.class, DataJpaRepositoriesAutoConfiguration.class,
            TransactionAutoConfiguration.class})
    @Import({BackendHome.class, SkillsDatabaseConfig.class, SkillService.class, MemoryService.class, ScriptService.class})
    static class SkillsOnly {
    }

    /** Fester Eigentümer. */
    public record TestOwner(String address, boolean admin) implements SkillOwner {

        @Override
        public String email() {
            return emailIfKnown().orElseThrow(() -> new IllegalStateException("Kein Benutzer für die Skills"));
        }

        @Override
        public Optional<String> emailIfKnown() {
            return Optional.ofNullable(address).map(a -> a.strip().toLowerCase(Locale.ROOT));
        }
    }

    public static ConfigurableApplicationContext start(Path home) {
        return start(home, null, USER, false, null);
    }

    /**
     * @param jdbcUrl     {@code null} = Standard ({@code skills.mv.db} in {@code home})
     * @param legacyOwner Eigentümer für Skills aus der Zeit vor dem User-Scoping
     */
    public static ConfigurableApplicationContext start(Path home, String jdbcUrl, String email, boolean admin,
                                                       String legacyOwner) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("devtools.server.home", home.toString());
        if (jdbcUrl != null) {
            props.put("devtools.skills.datasource.url", jdbcUrl);
        }
        if (legacyOwner != null) {
            props.put("devtools.skills.legacy-owner", legacyOwner);
        }
        return new SpringApplicationBuilder(SkillsOnly.class)
                .web(WebApplicationType.NONE)
                .properties(props)
                .initializers(ctx -> ctx.getBeanFactory().registerSingleton("skillOwner", new TestOwner(email, admin)))
                .run();
    }
}
