package systems.grebe.devtools.mcp.backend;

import java.nio.file.Path;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import systems.grebe.devtools.mcp.config.DataHome;
import systems.grebe.devtools.mcp.config.Home;
import systems.grebe.devtools.mcp.config.SecretCipher;

/**
 * Datenverzeichnis des Backends: Core-Datenbank ({@code core.mv.db}), Skill-Datenbank, {@code jwt.key} und
 * {@code secret.key} (verschlüsselt Geheimnisse in den Einstellungen). Eingebettet in der Desktop-App deren Ordner
 * ({@link DataHome}), im Server {@code devtools.server.home}, sonst {@code DEVTOOLS_MCP_HOME} bzw.
 * {@code ~/.devtools-mcp}.
 */
@Configuration(proxyBeanMethods = false)
public class BackendHome {

    private final Path dir;

    public BackendHome(ObjectProvider<DataHome> app, @Value("${devtools.server.home:}") String configured) {
        DataHome home = app.getIfAvailable();
        this.dir = (home != null ? home.dir()
                : configured.isBlank() ? Home.defaultHome() : Path.of(configured)).toAbsolutePath();
    }

    public Path dir() {
        return dir;
    }

    public Path resolve(String name) {
        return dir.resolve(name);
    }

    @Bean
    SecretCipher secretCipher() {
        return new SecretCipher(dir.resolve("secret.key"));
    }
}
