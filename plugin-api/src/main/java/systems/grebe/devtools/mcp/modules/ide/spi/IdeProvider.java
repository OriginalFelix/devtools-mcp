package systems.grebe.devtools.mcp.modules.ide.spi;

import java.nio.file.Path;
import java.util.List;

import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.ServiceProvider;
import systems.grebe.devtools.mcp.modules.ticket.spi.ProviderSettings;

/**
 * Service-Provider-Schnittstelle für Entwicklungsumgebungen (IntelliJ IDEA, VS Code, Eclipse, NetBeans, Visual Studio,
 * Xcode, …): Projekt öffnen und schließen, dem Nutzer eine Code-Stelle zeigen, zwei Dateien vergleichen.
 *
 * <p>Implementierungen werden über {@link java.util.ServiceLoader} gefunden – in der App und in Plugin-Jars (siehe
 * {@link ServiceProvider}). Eine neue IDE braucht nur eine Klasse mit öffentlichem No-Arg-Konstruktor und einen Eintrag
 * in {@code META-INF/services/systems.grebe.devtools.mcp.modules.ide.spi.IdeProvider}. Das Modul IDE erzeugt daraus
 * automatisch Konfigurationsfelder (Präfix {@code <id>.}) und einen Aktivierungsschalter und bietet die IDE in allen
 * {@code ide_*}-Tools über den Parameter {@code ide} an. Was eine IDE kann, sagt {@link Ide#capabilities()} – nicht jede
 * lässt sich von außen zum Schließen eines Projekts bewegen.
 */
public interface IdeProvider extends ServiceProvider {

    /** Stabile technische ID (Kleinbuchstaben), z.B. {@code intellij}. Wird als Parameter {@code ide} verwendet. */
    String id();

    /** Anzeigename in der UI. */
    String displayName();

    /** IDE-spezifische Einstellungen (Schlüssel ohne Präfix, z.B. {@code executable}). */
    default List<ConfigField> configFields() {
        return List.of();
    }

    /** Reihenfolge in der UI und bei der automatischen Auswahl – kleinere Werte zuerst. */
    default int priority() {
        return 100;
    }

    /**
     * Ob das Projektverzeichnis Spuren dieser IDE enthält ({@code .idea}, {@code .vscode}, {@code *.sln} …). Sind
     * mehrere IDEs aktiv, öffnet die automatische Auswahl ein Projekt in der ersten, die es erkennt. Darf nicht
     * blockieren (nur das Verzeichnis selbst ansehen, nicht rekursiv suchen).
     */
    default boolean recognizes(Path projectDir) {
        return false;
    }

    /**
     * Erzeugt die Anbindung für die übergebenen Einstellungen. Darf weder blockieren (keine Suche nach der
     * Installation) noch bei unvollständiger Konfiguration werfen – das meldet erst der Aufruf bzw. {@link Ide#probe()}.
     */
    Ide create(ProviderSettings settings);
}
