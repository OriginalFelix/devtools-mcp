package systems.grebe.devtools.mcp.core;

import java.util.List;

import systems.grebe.devtools.mcp.api.CredentialInfo;

/**
 * Ablage der Zugangsdaten des angemeldeten Benutzers (Modul „Zugangsdaten“) – im Backend, je Benutzer verschlüsselt.
 */
public interface CredentialStore {

    /** Aktueller Stand mit IDs; leer ohne Anmeldung. */
    List<CredentialInfo> credentials();

    /** Ersetzt alle Einträge; Einträge mit ID behalten, was sie nicht neu angeben (siehe {@link CredentialInfo}). */
    void save(List<CredentialInfo> entries);

    /** Erneuert das Access-Token eines OAuth-Eintrags, falls es bald abläuft. */
    void refresh(long id);
}
