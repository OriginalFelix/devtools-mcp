package systems.grebe.devtools.mcp.modules.mail.spi;

/**
 * Ein Entwurf für {@link MailAccountProvider#draft}. Gesendet wird nie – den Entwurf prüft und sendet der Nutzer.
 *
 * @param to            Empfänger, durch Komma getrennt; bei Antworten {@code null} = Absender der Mail
 * @param cc            Kopie an; {@code null} = keine
 * @param subject       Betreff; bei Antworten {@code null} = „Re: …“
 * @param body          reiner Text
 * @param replyToFolder Antwort auf: Ordner der Mail; {@code null} = INBOX
 * @param replyToUid    Antwort auf: UID der Mail; {@code null} = keine Antwort
 */
public record MailDraft(String to, String cc, String subject, String body, String replyToFolder, Long replyToUid) {
}
