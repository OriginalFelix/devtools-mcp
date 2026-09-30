package systems.grebe.devtools.mcp.backend;

import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import systems.grebe.devtools.mcp.config.Home;
import systems.grebe.devtools.mcp.config.SecretCipher;

/**
 * Datenverzeichnis des Servers: Core-Datenbank ({@code core.mv.db}), Skill-Datenbank, {@code jwt.key} und
 * {@code secret.key} (verschlüsselt Geheimnisse in den Einstellungen). {@code devtools.server.home}, sonst wie die
 * Desktop-App {@code DEVTOOLS_MCP_HOME} bzw. {@code ~/.devtools-mcp}.
 */
@Configuration(proxyBeanMethods = false)
public class BackendHome {

    private final Path dir;

    public BackendHome(@Value("${devtools.server.home:}") String configured) {
        this.dir = (configured.isBlank() ? Home.defaultHome() : Path.of(configured)).toAbsolutePath();
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
