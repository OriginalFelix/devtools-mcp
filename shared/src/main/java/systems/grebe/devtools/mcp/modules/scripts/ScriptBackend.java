package systems.grebe.devtools.mcp.modules.scripts;

import java.util.List;
import java.util.Optional;

/**
 * Ablage der Groovy-Skripte im Backend (eingebettet oder Team-Server). Das Backend speichert nur Quelltext,
 * Beschreibung und Historie – übersetzt und ausgeführt werden die Skripte ausschließlich in der Desktop-App. Fachliche
 * Fehler kommen als {@link IllegalArgumentException} mit einem Hinweis auf den nächsten sinnvollen Schritt.
 */
public interface ScriptBackend {

    /** Nach jeder Änderung aufgerufen (beliebiger Thread). */
    void addChangeListener(Runnable listener);

    /** Sichtbare Skripte: eigene und globale Vorlagen, die kein eigenes Skript gleichen Namens verdeckt. */
    List<ScriptViews.Summary> overview();

    /** Sichtbares Skript samt Quelltext und Historie. */
    Optional<ScriptViews.Details> details(String name);

    /**
     * Legt ein eigenes Skript an oder ändert es (neue Revision). Gibt es nur eine globale Vorlage gleichen Namens,
     * entsteht ein eigenes Skript, das sie verdeckt.
     *
     * @param expectedRevision optional: Revision, auf der die Änderung beruht – weicht sie ab, wird abgelehnt
     */
    String save(String name, String description, String content, String note, Integer expectedRevision);

    /** Löscht ein eigenes Skript samt Historie; globale Vorlagen lassen sich nur zurückziehen. */
    String delete(String name);

    /** Veröffentlicht ein eigenes Skript als globale Vorlage bzw. aktualisiert sie (nur Administratoren). */
    String publish(String name);

    /** Zieht eine globale Vorlage zurück (nur Administratoren). */
    String unpublish(String name);
}
