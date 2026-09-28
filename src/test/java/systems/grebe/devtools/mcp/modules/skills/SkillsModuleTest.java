package systems.grebe.devtools.mcp.modules.skills;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import systems.grebe.devtools.mcp.config.SettingsStore;
import systems.grebe.devtools.mcp.core.ConnectionTestResult;
import systems.grebe.devtools.mcp.core.ModuleConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Modulverhalten ohne Spring-Kontext: Tool-Zuschnitt, Fehlerfall beim Start, „Verbindung testen“. */
class SkillsModuleTest {

    @TempDir
    Path home;

    private SkillsModule module(SkillsPersistenceConfig.Status status) {
        SettingsStore store = new SettingsStore(home);
        return new SkillsModule(null, status, new SkillReview(), new SkillReviewTracker(null),
                new SkillUser(store, () -> java.util.Optional.of(SkillServiceTest.USER)), store);
    }

    private static SkillsPersistenceConfig.Connection h2(Path file) {
        return new SkillsPersistenceConfig.Connection("jdbc:h2:file:" + file, "sa", "", "update");
    }

    private ModuleConfig config(SkillsModule m, Map<String, String> values) {
        return ModuleConfig.of(m.configSchema(), values);
    }

    @Test
    void unavailableDatabaseBecomesModuleErrorInsteadOfTools() {
        SkillsPersistenceConfig.Connection broken =
                new SkillsPersistenceConfig.Connection("jdbc:gibtsnicht:x", "sa", "", "update");
        SkillsModule m = module(new SkillsPersistenceConfig.Status(broken, broken, "No suitable driver"));
        assertThatThrownBy(() -> m.createTools(config(m, Map.of())))
                .hasMessageContaining("jdbc:gibtsnicht:x war beim Start nicht erreichbar: No suitable driver")
                .hasMessageContaining("neu starten");
    }

    @Test
    void toolSelectionFollowsSwitches() {
        SkillsPersistenceConfig.Connection c = h2(home.resolve("skills"));
        SkillsModule m = module(new SkillsPersistenceConfig.Status(c, c, null));
        assertThat(m.createTools(config(m, Map.of()))).hasSize(9);
        assertThat(m.createTools(config(m, Map.of(SkillsModule.ALLOW_WRITE, "false")))).hasSize(3);
        assertThat(m.createTools(config(m, Map.of(SkillsModule.ALLOW_DELETE, "true")))).hasSize(10);
    }

    @Test
    void testingNewConnectionSaysRestartIsNeeded() {
        SkillsPersistenceConfig.Connection active = h2(home.resolve("skills"));
        SkillsModule m = module(new SkillsPersistenceConfig.Status(active, active, null));

        ConnectionTestResult ok = m.testConnection(config(m, Map.of(SkillsModule.JDBC_URL,
                "jdbc:h2:file:" + home.resolve("neu"))));
        assertThat(ok.success()).isTrue();
        assertThat(ok.message()).contains("Tabellen werden beim Start angelegt", "Neustart",
                "aktiv ist noch " + active.jdbcUrl());

        ConnectionTestResult failed = m.testConnection(config(m, Map.of(SkillsModule.JDBC_URL, "jdbc:gibtsnicht:x")));
        assertThat(failed.success()).isFalse();
        assertThat(failed.message()).startsWith("Verbindung fehlgeschlagen:");
    }

    @Test
    void defaultJdbcUrlPointsIntoSettingsFolder() {
        SkillsPersistenceConfig.Connection c = h2(home.resolve("skills"));
        SkillsModule m = module(new SkillsPersistenceConfig.Status(c, c, null));
        assertThat(config(m, Map.of()).get(SkillsModule.JDBC_URL))
                .contains("jdbc:h2:file:" + home.toAbsolutePath().resolve("skills"));
    }
}
