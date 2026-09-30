package systems.grebe.devtools.mcp.config;

import java.nio.file.Path;

/** Datenverzeichnis von Desktop-App und Server. */
public final class Home {

    private Home() {
    }

    /** {@code -Ddevtools.mcp.home}, {@code DEVTOOLS_MCP_HOME} oder {@code ~/.devtools-mcp}. */
    public static Path defaultHome() {
        String override = System.getProperty("devtools.mcp.home", System.getenv("DEVTOOLS_MCP_HOME"));
        return override != null && !override.isBlank()
                ? Path.of(override)
                : Path.of(System.getProperty("user.home"), ".devtools-mcp");
    }

    /**
     * Standard-Datenverzeichnis des Team-Servers ({@code ~/.devtools-server}), wenn {@code devtools.server.home} fehlt.
     * Bewusst getrennt von {@link #defaultHome()}: sonst öffneten Server und eingebettetes Backend einer Desktop-App auf
     * demselben Rechner dieselben H2-Dateien und der zweite Start scheiterte an der Dateisperre.
     */
    public static Path defaultServerHome() {
        return Path.of(System.getProperty("user.home"), ".devtools-server");
    }
}
