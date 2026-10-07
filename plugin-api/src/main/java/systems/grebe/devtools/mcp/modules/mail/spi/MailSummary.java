package systems.grebe.devtools.mcp.modules.mail.spi;

import java.time.Instant;

/**
 * Eine Mail in einer Liste.
 *
 * @param uid            UID im Ordner – zusammen mit Konto und Ordner der Schlüssel für {@link MailAccountProvider#read}
 * @param date           Eingangs- bzw. Sendedatum; {@code null}, wenn unbekannt
 * @param from           Absender zur Anzeige, z.B. {@code Kunde <kunde@example.com>}
 * @param fromAddress    erste Absenderadresse, klein geschrieben; leer, wenn keine
 * @param hasAttachments ob die Mail Anhänge hat (multipart/mixed)
 */
public record MailSummary(String account, String folder, long uid, Instant date, String from, String fromAddress,
                          String subject, boolean seen, boolean flagged, boolean hasAttachments) {
}
