package systems.grebe.devtools.mcp.modules.memories;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import systems.grebe.devtools.mcp.modules.shares.ShareViews;

/**
 * Memory-Speicher aus Sicht von Tools und Oberfläche: im Backend (eingebettet oder auf dem Team-Server). Texte sind
 * für das LLM formuliert; fachliche Fehler kommen als {@link IllegalArgumentException} mit einem Hinweis auf den
 * nächsten sinnvollen Schritt.
 *
 * <p>{@code temporaryOnly}: Der Aufrufer hat keine Freigabe für dauerhafte Memories – geändert bzw. gelöscht
 * werden dürfen dann nur temporäre ({@link MemoryViews.Type#TEMPORARY}), und keine wird dauerhaft gemacht.
 */
public interface MemoryBackend {

    /** Nach jeder Änderung aufgerufen (beliebiger Thread). */
    void addChangeListener(Runnable listener);

    /** Neueste bzw. passendste Memories des Benutzers für die Oberfläche. */
    List<MemoryViews.Entry> overview(String query, String project, String skill, int limit);

    Optional<MemoryViews.Entry> details(long id);

    int count();

    /** Bezüge (Ticket-Keys, PRs …) aller Memories des Benutzers, klein – für Hinweise bei Tool-Aufrufen. */
    Set<String> references();

    /**
     * Neueste Memories mit einem der Bezüge (Groß-/Kleinschreibung egal) oder zu einem Skill; {@code content} bleibt
     * dabei leer (nur für kurze Hinweise).
     */
    List<MemoryViews.Entry> related(List<String> references, String skill, int limit);

    /** @param type nur Memories dieses Typs, {@code null} = alle */
    String search(String query, String project, String skill, String tag, MemoryViews.Type type, Integer days,
                  Integer limit);

    String view(long id);

    /** @param type {@code null} = {@link MemoryViews.Type#PERMANENT} */
    String save(String title, String content, MemoryViews.Type type, String project, String skill, String reference,
                List<String> tags, int maxContentChars);

    /** @param type neuer Typ, {@code null} = unverändert */
    String update(long id, String title, String content, String append, MemoryViews.Type type, String project,
                  String skill, String reference, List<String> tags, boolean temporaryOnly, int maxContentChars);

    String delete(long id, boolean temporaryOnly);

    /**
     * Hängt eine Datei mit beliebigem Inhalt (auch binär, ohne Größengrenze) an eine Memory an bzw. ersetzt die Datei
     * gleichen Pfads.
     *
     * @param filePath  Name in der Memory, z.B. {@code screenshot.png}; {@code null} = Dateiname von {@code source}
     * @param source    lokale Datei mit dem Inhalt
     * @param mediaType z.B. {@code image/png}; {@code null} = aus dem Namen raten
     */
    String attachFile(long id, String filePath, Path source, String mediaType, boolean temporaryOnly);

    String removeFile(long id, String filePath, boolean temporaryOnly);

    /** Metadaten einer angehängten Datei; leer, wenn es Memory oder Datei nicht gibt. */
    Optional<MemoryViews.File> file(long id, String filePath);

    /** Inhalt einer angehängten Textdatei für das LLM; bei Binärem nur die Metadaten. */
    String viewFile(long id, String filePath);

    /** Schreibt den Inhalt einer angehängten Datei nach {@code target}; vorhandene wird ersetzt. */
    String exportFile(long id, String filePath, Path target);

    /**
     * Gibt eine eigene Memory für Benutzer, Rollen oder alle frei bzw. nimmt Freigaben zurück ({@code revoke}); ohne
     * Ziele nur der aktuelle Stand. Geteilte Memories sehen die Empfänger in Suche und Übersicht, schreibgeschützt.
     */
    String share(long id, ShareViews.Request request, boolean revoke);

    /** Freigaben einer eigenen Memory. */
    List<ShareViews.Share> shares(long id);

    /** Mögliche Ziele einer Freigabe (Benutzer, Rollen, ggf. alle); ohne Team-Server leer. */
    default List<ShareViews.Candidate> shareTargets() {
        return List.of();
    }
}
