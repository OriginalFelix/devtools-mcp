package systems.grebe.devtools.mcp.modules.mail.spi;

import java.util.List;

/**
 * Ein Konto des Mail-Moduls, wie es {@link MailAccountProvider#accounts()} liefert – ohne Zugangsdaten.
 *
 * @param name        eindeutiger Name, z.B. {@code support}
 * @param address     Benutzer@Host:Port
 * @param auth        {@code password} oder {@code microsoft} (Exchange Online, OAuth2)
 * @param description Beschreibung aus den Einstellungen; leer, wenn keine
 * @param folders     freigegebene Ordner (auch Muster wie {@code Projekte/*}); leer = das ganze Konto
 * @param watched     überwachte Ordner, deren neue Mails {@link MailAccountProvider#onNewMail} meldet
 * @param loggedIn    bei {@code microsoft}: ob eine Anmeldung vorliegt; sonst immer {@code true}
 */
public record MailAccountInfo(String name, String address, String auth, String description, List<String> folders,
                              List<String> watched, boolean loggedIn) {

    public MailAccountInfo {
        folders = List.copyOf(folders);
        watched = List.copyOf(watched);
    }

    /** Ob der Ordner freigegeben ist (INBOX ohne Groß-/Kleinschreibung, Muster mit {@code *} am Ende). */
    public boolean allows(String folder) {
        if (folders.isEmpty()) {
            return true;
        }
        String f = canonical(folder);
        for (String pattern : folders) {
            String p = canonical(pattern);
            if (p.endsWith("*") ? f.startsWith(p.substring(0, p.length() - 1)) : p.equals(f)) {
                return true;
            }
        }
        return false;
    }

    private static String canonical(String name) {
        if (name.length() >= 5 && name.regionMatches(true, 0, "INBOX", 0, 5)
                && (name.length() == 5 || !Character.isLetterOrDigit(name.charAt(5)))) {
            return "INBOX" + name.substring(5);
        }
        return name;
    }
}
