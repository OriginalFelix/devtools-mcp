package systems.grebe.devtools.mcp.backend.catalog;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import systems.grebe.devtools.mcp.api.Catalog;
import systems.grebe.devtools.mcp.api.ModuleDescriptor;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.FieldType;

import static org.assertj.core.api.Assertions.assertThat;

class ModuleCatalogTest {

    private JdbcClient jdbc;
    private ModuleCatalog catalog;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:catalog" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1");
        jdbc = JdbcClient.create(ds);
        jdbc.sql("CREATE TABLE module_catalog (module_id VARCHAR(32) NOT NULL PRIMARY KEY, descriptor TEXT NOT NULL, "
                + "updated_at TIMESTAMP WITH TIME ZONE NOT NULL)").update();
        catalog = new ModuleCatalog(jdbc, new TransactionTemplate(new DataSourceTransactionManager(ds)));
    }

    private void store(String id, String descriptor) {
        jdbc.sql("INSERT INTO module_catalog (module_id, descriptor, updated_at) VALUES (?, ?, ?)")
                .params(id, descriptor, Timestamp.from(Instant.now())).update();
    }

    @Test
    void descriptorsOfOtherAppVersionsDoNotBlockTheCatalog() {
        catalog.report(new Catalog(List.of(new ModuleDescriptor("git", "Git", "", true, true, 10,
                List.of(ConfigField.of("repositories", "Repositories", FieldType.DIRECTORY_LIST)), List.of()))));
        // von einem anderen Branch gemeldet: Feldtyp, den diese Version nicht kennt
        store("window", """
                {"id":"window","displayName":"Fenster","description":"","enabledByDefault":false,"hasTools":true,
                 "order":205,"schema":[{"key":"processFilter","label":"Prozessfilter","type":"PROCESS_PATTERN",
                 "required":false,"options":[],"columns":[]}],"tools":[{"name":"window_list","description":"x"}]}""");
        store("kaputt", "{nicht json");

        assertThat(catalog.modules()).extracting(ModuleDescriptor::id).containsExactly("git", "window");
        assertThat(catalog.module("window")).get().satisfies(m -> {
            assertThat(m.schema()).singleElement().extracting(ConfigField::type).isEqualTo(FieldType.STRING);
            assertThat(m.tools()).extracting(ModuleDescriptor.ToolDescriptor::name).containsExactly("window_list");
        });
        assertThat(catalog.module("kaputt")).isEmpty();
    }
}
