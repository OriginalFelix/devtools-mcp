package systems.grebe.devtools.mcp.core;

import java.util.List;

import org.springframework.ai.tool.ToolCallback;

/**
 * Erweiterungspunkt: Ein Modul bündelt thematisch zusammengehörige MCP-Tools (z.B. Git, SonarQube).
 *
 * <p>Ein neues Modul besteht aus einer Spring-{@code @Component}, die dieses Interface implementiert,
 * und einer Klasse mit {@code @Tool}-annotierten Methoden. Registrierung am MCP-Server, UI-Formular,
 * Persistenz, Aktivierung einzelner Tools und das Aufrufprotokoll übernimmt die Anwendung.
 *
 * <p>Tool-Namen sollten mit {@code id() + "_"} beginnen; fehlt das Präfix, wird es automatisch ergänzt.
 */
public interface ToolModule {

    /** Technische, stabile ID (Kleinbuchstaben, z.B. {@code git}). Dient als Tool-Präfix und Settings-Schlüssel. */
    String id();

    /** Anzeigename in der UI. */
    String displayName();

    /** Kurze Beschreibung für die UI. */
    String description();

    /** Deklaratives Konfigurationsschema – daraus erzeugt die UI das Formular. */
    default List<ConfigField> configSchema() {
        return List.of();
    }

    /**
     * Erzeugt die Tools für die übergebene (gespeicherte) Konfiguration. Wird bei jeder Konfigurationsänderung
     * erneut aufgerufen. Darf bei unvollständiger Konfiguration nicht werfen – Tools prüfen ihre Konfiguration
     * erst beim Aufruf.
     */
    List<ToolCallback> createTools(ModuleConfig config);

    /** Prüft die (evtl. noch ungespeicherte) Konfiguration, z.B. Erreichbarkeit eines Servers. */
    default ConnectionTestResult testConnection(ModuleConfig config) {
        return ConnectionTestResult.ok("Für dieses Modul ist keine Verbindungsprüfung vorgesehen.");
    }

    /** Ob das Modul beim allerersten Start aktiv ist. */
    default boolean enabledByDefault() {
        return false;
    }
}
