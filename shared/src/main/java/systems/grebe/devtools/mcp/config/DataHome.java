package systems.grebe.devtools.mcp.config;

import java.nio.file.Path;

/** Datenverzeichnis der laufenden Anwendung; die Desktop-App stellt es bereit, das eingebettete Backend nutzt es. */
public interface DataHome {

    Path dir();
}
