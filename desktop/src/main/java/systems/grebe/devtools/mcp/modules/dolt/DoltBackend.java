package systems.grebe.devtools.mcp.modules.dolt;

import java.util.List;

/**
 * Zugriff auf die Branches einer Datenbank – je Abgleich geöffnet und danach geschlossen. Fehler sind
 * {@link IllegalStateException}s mit einer Meldung für Nutzer und LLM (ohne Zugangsdaten).
 */
interface DoltBackend extends AutoCloseable {

    /** Produkt und Version, z.B. „Dolt 2.4.0“. */
    String version();

    /**
     * Branch, auf dem neue Verbindungen ohne Branch-Angabe landen; {@code null}, wenn er sich nicht auflösen lässt (etwa
     * weil der eingestellte Branch gelöscht wurde).
     */
    String defaultBranch();

    List<String> branches();

    /** Legt {@code branch} am Stand von {@code from} an. */
    void create(String branch, String from);

    /**
     * Stellt ein, dass neue Verbindungen auf {@code branch} landen (bleibt über Neustarts erhalten).
     *
     * @return {@code false}, wenn die Datenbank das nicht kann (Doltgres bis mindestens 1.4) – dann bleibt der
     * Standard-Branch, wie er ist
     */
    boolean setDefault(String branch);

    /** Wie sich eine Anwendung ausdrücklich mit {@code branch} verbindet, z.B. {@code localhost:5432/app/feature}. */
    String connectHint(String branch);

    @Override
    void close();
}
