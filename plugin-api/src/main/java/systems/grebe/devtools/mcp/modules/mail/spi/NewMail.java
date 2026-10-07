package systems.grebe.devtools.mcp.modules.mail.spi;

/**
 * Eine neue Mail in einem überwachten Ordner (siehe {@link MailAccountProvider#onNewMail}). Gemeldet wird jede neue
 * Mail genau einmal, unabhängig von den Absender-Filtern des Moduls für Channel und Befehl.
 *
 * @param seen ob die Mail beim Eintreffen schon gelesen war
 */
public record NewMail(MailSummary summary, boolean seen) {
}
