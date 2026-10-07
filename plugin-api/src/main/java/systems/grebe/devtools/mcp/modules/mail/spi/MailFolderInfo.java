package systems.grebe.devtools.mcp.modules.mail.spi;

/**
 * Ein freigegebener Ordner (Postfach).
 *
 * @param name    voller Name, z.B. {@code INBOX} oder {@code Projekte/Kunde-A}
 * @param total   Anzahl Mails; -1, wenn der Ordner keine Mails enthalten kann oder die Zahl nicht lesbar war
 * @param unread  davon ungelesen; -1 wie oben
 * @param watched ob neue Mails darin gemeldet werden
 */
public record MailFolderInfo(String name, int total, int unread, boolean watched) {
}
