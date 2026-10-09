package systems.grebe.devtools.mcp.backend.skills;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
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
import systems.grebe.devtools.mcp.backend.blobs.BlobReferences;
import systems.grebe.devtools.mcp.backend.blobs.BlobStore;
import systems.grebe.devtools.mcp.backend.memories.MemoryService;
import systems.grebe.devtools.mcp.backend.scripts.ScriptService;
import systems.grebe.devtools.mcp.backend.shares.ShareResolver;
import systems.grebe.devtools.mcp.backend.shares.ShareStore;
import systems.grebe.devtools.mcp.modules.shares.ShareViews;

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
    @Import({BackendHome.class, SkillsDatabaseConfig.class, SkillService.class, MemoryService.class, ScriptService.class,
            BlobStore.class, BlobReferences.class, ShareStore.class})
    static class SkillsOnly {
    }

    /** Fester Eigentümer mit seinen Rollen. */
    public record TestOwner(String address, boolean admin, List<String> roles) implements SkillOwner {

        public TestOwner {
            roles = roles == null ? List.of() : List.copyOf(roles);
        }

        public TestOwner(String address, boolean admin) {
            this(address, admin, List.of());
        }

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
        return start(home, jdbcUrl, email, admin, legacyOwner, List.of());
    }

    /**
     * Ziele von Freigaben ohne Core-Datenbank: Benutzer per E-Mail (jede gilt als vorhanden), Rollen per Name (jede
     * gilt als vorhanden, außer {@code unbekannt}).
     */
    public static final class TestResolver implements ShareResolver {

        @Override
        public Optional<String> userEmail(String usernameOrEmail) {
            return usernameOrEmail != null && usernameOrEmail.contains("@")
                    ? Optional.of(ShareViews.email(usernameOrEmail)) : Optional.empty();
        }

        @Override
        public Optional<String> role(String name) {
            return name == null || name.isBlank() || name.equals("unbekannt") ? Optional.empty()
                    : Optional.of(name.strip());
        }

        @Override
        public List<ShareViews.Candidate> candidates(String self) {
            return List.of();
        }
    }

    /** @param roles Rollen des Benutzers (Freigaben an Rollen) */
    public static ConfigurableApplicationContext start(Path home, String jdbcUrl, String email, boolean admin,
                                                       String legacyOwner, List<String> roles) {
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
                .initializers(ctx -> {
                    ctx.getBeanFactory().registerSingleton("skillOwner", new TestOwner(email, admin, roles));
                    ctx.getBeanFactory().registerSingleton("shareResolver", new TestResolver());
                })
                .run();
    }
}
