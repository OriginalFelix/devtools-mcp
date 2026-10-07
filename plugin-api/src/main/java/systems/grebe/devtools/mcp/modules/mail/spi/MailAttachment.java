package systems.grebe.devtools.mcp.modules.mail.spi;

/** Ein Anhang mit Inhalt (dekodiert). */
public record MailAttachment(String name, String type, byte[] data) {
}
