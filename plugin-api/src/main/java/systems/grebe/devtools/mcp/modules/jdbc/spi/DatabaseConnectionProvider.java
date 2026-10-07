package systems.grebe.devtools.mcp.modules.jdbc.spi;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Die Datenbankverbindungen des Moduls „Datenbanken (JDBC)“ für andere Module und Plugins – damit Zugangsdaten nur an
 * einer Stelle gepflegt werden. Die App stellt genau eine Bean bereit; Plugins lassen sie sich injizieren:
 *
 * <pre>{@code
 * @Component
 * class MyModule implements ToolModule {
 *     MyModule(ObjectProvider<DatabaseConnectionProvider> databases) { … }   // fehlt sie, ist getIfAvailable() null
 * }
 *
 * List<Kunde> kunden = databases.read("crm-test", con -> {
 *     try (PreparedStatement ps = con.prepareStatement("SELECT id, name FROM kunden WHERE ort = ?")) { … }
 * });
 * }</pre>
 *
 * <p>Es gelten die Einstellungen des JDBC-Moduls im laufenden Scope ({@code ToolScope.current()}): Verbindungen, Treiber
 * (Klassenpfad, Maven, JAR-Dateien), Verbindungs-Timeout und die Freigaben. Lesen setzt den Schalter „Datensätze lesen“
 * voraus und dass der Benutzer {@code jdbc_query} nutzen darf; ob das JDBC-Modul selbst aktiv ist, spielt keine Rolle.
 * Passwörter verlassen die App nicht über diese Schnittstelle.
 */
public interface DatabaseConnectionProvider {

    /** Alle konfigurierten Verbindungen, ohne Zugangsdaten. */
    List<DatabaseConnectionInfo> connections();

    /** Verbindung nach Name (ohne Groß-/Kleinschreibung). */
    default Optional<DatabaseConnectionInfo> connection(String name) {
        return connections().stream().filter(c -> c.name().equalsIgnoreCase(name == null ? "" : name.strip()))
                .findFirst();
    }

    /**
     * Führt {@code action} mit einer Verbindung aus dem Pool des JDBC-Moduls aus – schreibgeschützt in einer
     * Transaktion, die danach immer zurückgerollt wird. Die Verbindung gehört nur während des Aufrufs dem Aufrufer:
     * nicht schließen, nicht festhalten, nichts festschreiben.
     *
     * @param connection Name der Verbindung; leer = die einzige konfigurierte
     * @return Ergebnis von {@code action}
     * @throws IllegalArgumentException wenn die Verbindung nicht konfiguriert ist
     * @throws IllegalStateException    wenn Lesen nicht freigegeben ist oder die Datenbank einen Fehler meldet (Meldung
     *                                  ohne Zugangsdaten, die {@link SQLException} als Ursache)
     */
    <T> T read(String connection, ConnectionCallback<T> action);

    /** Arbeit mit einer geliehenen Verbindung. */
    @FunctionalInterface
    interface ConnectionCallback<T> {
        T run(Connection connection) throws SQLException;
    }
}
