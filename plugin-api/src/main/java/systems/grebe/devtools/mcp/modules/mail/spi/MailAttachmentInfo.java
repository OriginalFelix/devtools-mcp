package systems.grebe.devtools.mcp.modules.mail.spi;

/**
 * Ein Anhang ohne Inhalt.
 *
 * @param index Position, über die {@link MailAccountProvider#attachment} ihn liefert (ab 0)
 * @param type  MIME-Typ ohne Parameter, z.B. {@code application/pdf}
 * @param size  Größe laut Server (kodiert, also etwas größer als der Inhalt); -1, wenn unbekannt
 */
public record MailAttachmentInfo(int index, String name, String type, int size) {
}
