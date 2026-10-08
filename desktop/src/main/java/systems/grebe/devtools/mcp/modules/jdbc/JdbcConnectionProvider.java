package systems.grebe.devtools.mcp.modules.jdbc;

import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Supplier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolRegistry;
import systems.grebe.devtools.mcp.modules.jdbc.SqlStatements.Kind;
import systems.grebe.devtools.mcp.modules.jdbc.spi.DatabaseConnectionInfo;
import systems.grebe.devtools.mcp.modules.jdbc.spi.DatabaseConnectionProvider;

/**
 * Stellt die Verbindungen des JDBC-Moduls anderen Modulen und Plugins bereit ({@link DatabaseConnectionProvider} aus
 * der Plugin-API). Es gelten die wirksamen Einstellungen des Moduls, sein Verbindungs-Pool und seine Freigaben: Lesen
 * nur mit Schalter „Datensätze lesen“ und dem Recht auf {@code jdbc_query}.
 */
@Component
public class JdbcConnectionProvider implements DatabaseConnectionProvider {

    private final JdbcModule module;
    private final Supplier<ModuleConfig> config;
    private final BiPredicate<String, String> toolPermitted;

    @Autowired
    public JdbcConnectionProvider(JdbcModule module, ObjectProvider<ToolRegistry> registry) {
        // Registry erst beim Aufruf holen – sie wird mit allen Modulen aufgebaut
        this(module, () -> registry.getObject().config(JdbcModule.ID),
                (moduleId, tool) -> registry.getObject().toolPermitted(moduleId, tool));
    }

    /** Für Tests: Konfiguration und Rechte direkt. */
    JdbcConnectionProvider(JdbcModule module, Supplier<ModuleConfig> config, BiPredicate<String, String> toolPermitted) {
        this.module = module;
        this.config = config;
        this.toolPermitted = toolPermitted;
    }

    @Override
    public List<DatabaseConnectionInfo> connections() {
        JdbcEnvironment env = module.environment(config.get());
        boolean permitted = queryPermitted();
        return env.connections().stream()
                .map(c -> new DatabaseConnectionInfo(c.name(), c.safeUrl(), c.username(), c.description(),
                        env.product(c), permitted && env.permits(c, Kind.QUERY)))
                .toList();
    }

    @Override
    public <T> T read(String connection, ConnectionCallback<T> action) {
        JdbcEnvironment env = module.environment(config.get());
        JdbcConnection c = env.resolve(connection);
        if (!queryPermitted()) {
            throw new IllegalStateException("Nicht erlaubt: Die Rollen des Benutzers erlauben jdbc_query nicht – damit "
                    + "ist auch das Lesen über andere Module gesperrt.");
        }
        env.require(c, Set.of(Kind.QUERY));
        return env.withConnection(c, JdbcEnvironment.Mode.READ, action::run);
    }

    private boolean queryPermitted() {
        return toolPermitted.test(JdbcModule.ID, Kind.QUERY.tool);
    }
}
