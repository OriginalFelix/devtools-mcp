package systems.grebe.devtools.mcp.core;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

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

    /**
     * Nutzungshinweise für das LLM: wann die Tools dieses Moduls zu verwenden sind – insbesondere statt welcher
     * Shell-Befehle oder anderer Werkzeuge des Clients. Wird beim Verbindungsaufbau als Teil der
     * MCP-{@code instructions} an den Client gesendet (siehe {@code ServerInstructions}).
     *
     * <p>Die Instructions werden einmal beim Start des Servers gebaut und ändern sich danach nicht mehr – anders als
     * die Tool-Liste. Formulierungen deshalb bedingt halten („wenn {@code git_status} angeboten wird …“), damit sie
     * auch dann stimmen, wenn das Modul oder einzelne Tools in der UI abgeschaltet werden.
     *
     * @return Markdown-Text oder {@code null}/leer, wenn es nichts Besonderes zu sagen gibt
     */
    default String instructions() {
        return null;
    }

    /**
     * Modul, dessen Recht dieses Modul mitumfasst (Rollen: {@code module:<id>}), z.B. {@code scripts} für die Module
     * der Skripte; {@code null} = nur das eigene.
     */
    default String parentModule() {
        return null;
    }

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

    /**
     * Erzeugt die Tools für einen bestimmten Benutzer/Profil. Zustand, der zwischen Aufrufen erhalten bleibt (offene
     * Verbindungen …), gehört in {@link ToolScope#state}, nicht ins Modul. Standard: {@link #createTools(ModuleConfig)}
     * – Module ohne solchen Zustand (und bestehende Plugins) brauchen nichts zu ändern.
     */
    default List<ToolCallback> createTools(ModuleConfig config, ToolScope scope) {
        return createTools(config);
    }

    /** Prüft die (evtl. noch ungespeicherte) Konfiguration, z.B. Erreichbarkeit eines Servers. */
    default ConnectionTestResult testConnection(ModuleConfig config) {
        return ConnectionTestResult.ok("Für dieses Modul ist keine Verbindungsprüfung vorgesehen.");
    }

    /** Aktionen, die die UI für dieses Modul anbietet (z.B. „Projekt indizieren“); Standard keine. */
    default List<ModuleAction> actions() {
        return List.of();
    }

    /** Ob das Modul beim allerersten Start aktiv ist. */
    default boolean enabledByDefault() {
        return false;
    }

    /**
     * {@code false} für reine Einstellungs-Module (z.B. gemeinsame Grundeinstellungen mehrerer Module):
     * die UI blendet dann Schalter und Tool-Liste aus.
     */
    default boolean hasTools() {
        return true;
    }

    /**
     * Verzeichnislisten ({@link FieldType#DIRECTORY_LIST}), die zusätzlich die global freigegebenen Verzeichnisse aus
     * dem Modul „Freigaben“ ({@code AccessModule}) bekommen – für Module, die über {@code Workspaces} auf
     * Projektverzeichnisse zugreifen. Standard keine.
     */
    default Set<String> sharedDirectoryFields() {
        return Set.of();
    }

    /** Sortierung in der Modulliste (aufsteigend, danach Anzeigename). */
    default int order() {
        return 100;
    }

    /**
     * Anfangswerte, solange für dieses Modul noch keine Einstellungen gespeichert sind – z.B. um Werte aus
     * einem anderen Modul zu übernehmen, wenn Einstellungen umgezogen sind.
     *
     * @param savedValues liefert die gespeicherten Werte eines anderen Moduls (leer, falls keine)
     */
    default Map<String, String> initialValues(Function<String, Map<String, String>> savedValues) {
        return Map.of();
    }
}
