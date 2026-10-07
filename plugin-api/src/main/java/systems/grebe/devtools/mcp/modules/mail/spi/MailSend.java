package systems.grebe.devtools.mcp.modules.mail.spi;

/**
 * Eine Mail für {@link MailAccountProvider#send}. Vor dem Senden fragt die App den Nutzer, sofern er das nicht
 * abgeschaltet hat.
 *
 * @param to            Empfänger, durch Komma getrennt; bei Antworten {@code null} = Absender der Mail
 * @param cc            Kopie an; {@code null} = keine
 * @param bcc           Blindkopie an; {@code null} = keine
 * @param subject       Betreff; bei Antworten {@code null} = „Re: …“
 * @param body          reiner Text
 * @param replyToFolder Antwort auf: Ordner der Mail; {@code null} = INBOX
 * @param replyToUid    Antwort auf: UID der Mail; {@code null} = keine Antwort
 * @param quote         bei Antworten die ursprüngliche Mail zitieren
 */
public record MailSend(String to, String cc, String bcc, String subject, String body, String replyToFolder,
                       Long replyToUid, boolean quote) {
}
