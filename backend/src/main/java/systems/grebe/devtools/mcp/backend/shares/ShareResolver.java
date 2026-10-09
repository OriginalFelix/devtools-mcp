package systems.grebe.devtools.mcp.backend.shares;

import java.util.List;
import java.util.Optional;

import systems.grebe.devtools.mcp.modules.shares.ShareViews;

/**
 * Wer als Ziel einer Freigabe in Frage kommt: im Backend die Benutzer und Rollen der Core-Datenbank
 * ({@code AccountShareResolver}).
 */
public interface ShareResolver {

    /** E-Mail (klein) eines aktiven Benutzers per Anmeldename oder E-Mail; leer, wenn es ihn nicht gibt. */
    Optional<String> userEmail(String usernameOrEmail);

    /** Rollenname in der gespeicherten Schreibweise; leer, wenn es die Rolle nicht gibt. */
    Optional<String> role(String name);

    /** Benutzer (außer {@code self}) und Rollen zur Auswahl. */
    List<ShareViews.Candidate> candidates(String self);
}
