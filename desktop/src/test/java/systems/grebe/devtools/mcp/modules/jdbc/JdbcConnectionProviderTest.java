package systems.grebe.devtools.mcp.modules.jdbc;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolScope;
import systems.grebe.devtools.mcp.modules.jdbc.spi.DatabaseConnectionInfo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcConnectionProviderTest {

    private static final String PASSWORD = "geheim-123";

    private JdbcModule module;
    private String url;
    private Connection keepAlive;
    private final AtomicReference<Map<String, String>> extra = new AtomicReference<>(Map.of());
    private final AtomicReference<Map<String, String>> connection = new AtomicReference<>(Map.of());
    private final AtomicBoolean permitted = new AtomicBoolean(true);
    private JdbcConnectionProvider provider;

    @BeforeEach
    void setUp() throws SQLException {
        module = new JdbcModule(new JdbcDrivers(coordinates -> List.of(h2Jar())));
        url = "jdbc:h2:mem:provider" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1";
        keepAlive = DriverManager.getConnection(url, "sa", PASSWORD);
        try (Statement st = keepAlive.createStatement()) {
            st.execute("CREATE TABLE kunden (id INT PRIMARY KEY, name VARCHAR(100))");
            st.execute("INSERT INTO kunden VALUES (1, 'Müller'), (2, 'Schmidt')");
        }
        provider = new JdbcConnectionProvider(module, this::config, (moduleId, tool) -> permitted.get());
    }

    @AfterEach
    void tearDown() throws SQLException {
        ToolScope.LOCAL.close();
        keepAlive.close();
    }

    private static Path h2Jar() {
        try {
            return Path.of(Class.forName("org.h2.Driver").getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ModuleConfig config() {
        Map<String, String> record = new LinkedHashMap<>(Map.of("name", "crm", "url", url + ";password=" + PASSWORD,
                "username", "sa", "password", PASSWORD, "description", "Testdatenbank"));
        record.putAll(connection.get());
        Map<String, String> values = new HashMap<>(extra.get());
        values.put(JdbcModule.CONNECTIONS, ModuleConfig.formatRecords(List.of(record)));
        return ModuleConfig.of(module.configSchema(), values);
    }

    private String firstName(Connection con) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT name FROM kunden WHERE id = ?")) {
            ps.setInt(1, 1);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    @Test
    void listsConnectionsWithoutSecrets() {
        List<DatabaseConnectionInfo> infos = provider.connections();

        assertThat(infos).singleElement().satisfies(i -> {
            assertThat(i.name()).isEqualTo("crm");
            assertThat(i.username()).isEqualTo("sa");
            assertThat(i.description()).isEqualTo("Testdatenbank");
            assertThat(i.readable()).isTrue();
            assertThat(i.url()).doesNotContain(PASSWORD);
        });
        assertThat(infos.toString()).doesNotContain(PASSWORD);
        assertThat(provider.connection("CRM")).isPresent();
        assertThat(provider.connection("gibtsnicht")).isEmpty();
    }

    @Test
    void readsReadOnlyAndRollsBack() {
        assertThat(provider.read("crm", this::firstName)).isEqualTo("Müller");
        assertThat(provider.read(null, this::firstName)).as("einzige Verbindung").isEqualTo("Müller");

        // Schreibschutz ist nur ein Hinweis an den Treiber (H2 ignoriert ihn) – entscheidend ist das Zurückrollen
        provider.read("crm", con -> {
            try (Statement st = con.createStatement()) {
                st.executeUpdate("UPDATE kunden SET name = 'X' WHERE id = 2");
            } catch (SQLException e) {
                // schreibgeschützt – auch recht
            }
            return null;
        });
        String second = provider.read("crm", con -> {
            try (Statement st = con.createStatement();
                 ResultSet rs = st.executeQuery("SELECT name FROM kunden WHERE id = 2")) {
                rs.next();
                return rs.getString(1);
            }
        });
        assertThat(second).as("nichts festgeschrieben").isEqualTo("Schmidt");
        assertThat(provider.connections().getFirst().product()).startsWith("H2");
    }

    @Test
    void respectsSwitchAndRoles() {
        extra.set(Map.of(JdbcModule.ALLOW_QUERY, "false"));
        assertThat(provider.connections().getFirst().readable()).isFalse();
        assertThatThrownBy(() -> provider.read("crm", this::firstName)).hasMessageContaining("jdbc_query");

        extra.set(Map.of());
        permitted.set(false);
        assertThat(provider.connections().getFirst().readable()).isFalse();
        assertThatThrownBy(() -> provider.read("crm", this::firstName)).hasMessageContaining("Rollen");
    }

    @Test
    void reportsUnknownConnectionsAndSqlErrorsWithoutSecrets() {
        assertThatThrownBy(() -> provider.read("gibtsnicht", this::firstName))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("crm");
        assertThatThrownBy(() -> provider.read("crm", con -> con.createStatement().executeQuery("SELECT * FROM fehlt")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(PASSWORD)
                .hasCauseInstanceOf(SQLException.class);
    }
}
