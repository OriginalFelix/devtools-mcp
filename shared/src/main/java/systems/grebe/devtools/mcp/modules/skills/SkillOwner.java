package systems.grebe.devtools.mcp.modules.skills;

import java.util.Optional;

/**
 * Wem die Skills gehören, mit denen {@link SkillService} gerade arbeitet: in der Desktop-App der Benutzer am Rechner
 * (Git-E-Mail oder Modul-Einstellung), auf dem Team-Server der per Desktop-Token angemeldete Benutzer.
 */
public interface SkillOwner {

    /** Eigentümer globaler Vorlagen – bewusst keine gültige E-Mail, damit kein Benutzer so heißen kann. */
    String GLOBAL = "@global";

    /** Maximale Länge einer E-Mail (Spaltenbreite). */
    int MAX_EMAIL = 320;

    /** Aktueller Benutzer (E-Mail, klein geschrieben); wirft mit Hinweis, wenn keiner bekannt ist. */
    String email();

    /** Wie {@link #email()}, aber leer statt Fehler. */
    Optional<String> emailIfKnown();

    /** Darf globale Vorlagen veröffentlichen und zurückziehen. */
    boolean admin();
}
