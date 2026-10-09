package systems.grebe.devtools.mcp.backend.skills;

import java.util.List;
import java.util.Optional;

/**
 * Wem die Skills gehören, mit denen {@link SkillService} gerade arbeitet: in der Desktop-App der Benutzer am Rechner
 * (Git-E-Mail oder Modul-Einstellung), im Backend der an der GraphQL-API angemeldete Benutzer.
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

    /** Darf globale Vorlagen veröffentlichen und zurückziehen (Recht „Vorlagen veröffentlichen“). */
    boolean admin();

    /** Namen der Rollen des Benutzers – für Freigaben an Rollen. */
    default List<String> roles() {
        return List.of();
    }

    /** Darf eigene Skills und Memories für alle freigeben (Recht „Mit allen teilen“). */
    default boolean shareWithAll() {
        return admin();
    }
}
