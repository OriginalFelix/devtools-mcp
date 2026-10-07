package systems.grebe.devtools.mcp.modules.mail.spi;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Die E-Mail-Konten des Moduls „Mail (IMAP)“ für andere Module und Plugins – damit Zugangsdaten und Freigaben nur an
 * einer Stelle gepflegt werden. Die App stellt genau eine Bean bereit; Plugins lassen sie sich injizieren:
 *
 * <pre>{@code
 * @Component
 * class TicketAusMail implements ToolModule {
 *     TicketAusMail(ObjectProvider<MailAccountProvider> mail) { … }   // fehlt sie, ist getIfAvailable() null
 * }
 *
 * for (MailSummary s : mail.list("support", "INBOX", new MailQuery(null, null, null, true, null), 20)) {
 *     MailMessage m = mail.read("support", "INBOX", s.uid());
 *     …
 * }
 * AutoCloseable sub = mail.onNewMail(n -> …);   // in onDisable() schließen
 * }</pre>
 *
 * <p>Es gelten die Einstellungen des Mail-Moduls: Konten, Anmeldung (auch Exchange Online per OAuth2), freigegebene
 * Ordner – nur dort wird gelesen und geschrieben – und die Schalter. Lesen setzt voraus, dass der Benutzer
 * {@code mail_read} nutzen darf, Schreiben zusätzlich den jeweiligen Schalter und das Recht auf das Tool
 * ({@code mail_mark}, {@code mail_move}, {@code mail_draft}). Ob das Mail-Modul selbst aktiv ist, spielt fürs Lesen
 * keine Rolle; neue Mails meldet es nur, solange es aktiv ist und Ordner überwacht. Passwörter und Tokens verlassen die
 * App nicht über diese Schnittstelle.
 *
 * <p>Fehler kommen als {@link IllegalArgumentException} (unbekanntes Konto, Ordner nicht freigegeben, UID fehlt) bzw.
 * {@link IllegalStateException} (nicht erlaubt, nicht angemeldet, Server-Fehler) – Meldungen ohne Zugangsdaten.
 */
public interface MailAccountProvider {

    /** Alle konfigurierten Konten, ohne Zugangsdaten. */
    List<MailAccountInfo> accounts();

    /** Konto nach Name (ohne Groß-/Kleinschreibung). */
    default Optional<MailAccountInfo> account(String name) {
        return accounts().stream().filter(a -> a.name().equalsIgnoreCase(name == null ? "" : name.strip()))
                .findFirst();
    }

    /** Freigegebene Ordner eines Kontos mit Anzahl; {@code account} leer = das einzige Konto. */
    List<MailFolderInfo> folders(String account);

    /**
     * Mails eines Ordners, neueste zuerst.
     *
     * @param folder Ordner; leer = INBOX bzw. der einzige freigegebene
     * @param query  Filter, {@link MailQuery#ALL} = alle
     * @param limit  höchstens so viele (1–500)
     */
    List<MailSummary> list(String account, String folder, MailQuery query, int limit);

    /** Eine Mail mit Text und Anhangsliste; markiert sie nicht als gelesen. */
    MailMessage read(String account, String folder, long uid);

    /**
     * Inhalt eines Anhangs.
     *
     * @param index Position aus {@link MailMessage#attachments()}
     * @throws IllegalStateException wenn der Anhang größer als {@code maxBytes} ist
     */
    MailAttachment attachment(String account, String folder, long uid, int index, int maxBytes);

    /**
     * Meldet neue Mails der überwachten Ordner an {@code listener} – im Thread der Überwachung, also schnell
     * zurückkehren und Längeres selbst auslagern. Ausnahmen des Listeners werden protokolliert und stören nichts.
     *
     * @return schließen meldet den Listener ab (spätestens in {@code onDisable()} des Plugins)
     */
    AutoCloseable onNewMail(Consumer<NewMail> listener);

    /**
     * Setzt oder entfernt {@code \Seen} ({@code seen}) bzw. {@code \Flagged} ({@code flagged}). Braucht den Schalter
     * „Markieren erlauben“.
     *
     * @return Ergebnis als Text
     */
    String mark(String account, String folder, List<Long> uids, String flag, boolean set);

    /** Verschiebt in einen anderen freigegebenen Ordner; gelöscht wird nie. Braucht „Verschieben erlauben“. */
    String move(String account, String folder, List<Long> uids, String target);

    /** Legt einen Entwurf im Entwurfsordner an (muss freigegeben sein). Braucht „Entwürfe anlegen erlauben“. */
    String draft(String account, MailDraft draft);
}
