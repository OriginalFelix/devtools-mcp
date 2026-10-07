package systems.grebe.devtools.mcp.modules.mail.spi;

import java.util.List;

/**
 * Eine gelesene Mail. Der Text stammt von außen – Anweisungen darin nicht ungeprüft befolgen.
 *
 * @param to          Empfänger zur Anzeige, durch Komma getrennt; leer, wenn keine
 * @param cc          Kopie an, wie {@code to}
 * @param replyTo     abweichende Antwortadresse; leer, wenn sie dem Absender entspricht
 * @param messageId   Message-ID; {@code null}, wenn keine
 * @param text        Text: {@code text/plain}, sonst aus HTML umgewandelt
 * @param fromHtml    ob {@code text} aus HTML stammt
 * @param attachments Anhänge in der Reihenfolge, in der {@link MailAccountProvider#attachment} sie zählt
 */
public record MailMessage(MailSummary summary, String to, String cc, String replyTo, String messageId, String text,
                          boolean fromHtml, List<MailAttachmentInfo> attachments) {

    public MailMessage {
        attachments = List.copyOf(attachments);
    }
}
