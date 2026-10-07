package systems.grebe.devtools.mcp.modules.mail;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import systems.grebe.devtools.mcp.core.ModuleConfig;

/**
 * Ein in der App hinterlegtes IMAP-Konto. Das LLM sieht Name, Benutzer@Host, Beschreibung und die freigegebenen
 * Ordner, nie das Passwort.
 *
 * @param folders freigegebene Ordner (Postfächer); leer = das ganze Konto. Ein Eintrag mit {@code *} am Ende gibt alle
 *                Ordner frei, die so beginnen ({@code Projekte/*} = alle Unterordner von Projekte).
 * @param watch   Ordner, deren neue Mails gemeldet werden; leer = keine Überwachung
 * @param auth    {@link #PASSWORD_AUTH} oder {@link #MICROSOFT} (Exchange Online: OAuth2 per Device Code, XOAUTH2)
 * @param tenant  nur Microsoft: Tenant-ID oder Domain
 * @param clientId nur Microsoft: Anwendungs-ID der App-Registrierung in Entra ID
 * @param authority nur Microsoft: Anmelde-Endpunkt (aus den Modul-Einstellungen)
 */
record MailAccount(String name, String host, int port, String security, String username, String password,
                   List<String> folders, List<String> watch, String description, String auth, String tenant,
                   String clientId, String authority) {

    static final String NAME = "name";
    static final String HOST = "host";
    static final String PORT = "port";
    static final String SECURITY = "security";
    static final String USERNAME = "username";
    static final String PASSWORD = "password";
    static final String FOLDERS = "folders";
    static final String WATCH = "watch";
    static final String DESCRIPTION = "description";
    static final String AUTH = "auth";
    static final String TENANT = "tenant";
    static final String CLIENT_ID = "clientId";

    static final String PASSWORD_AUTH = "password";
    static final String MICROSOFT = "microsoft";
    static final String DEFAULT_AUTHORITY = "https://login.microsoftonline.com";

    static final String SSL = "ssl";
    static final String STARTTLS = "starttls";
    static final String PLAIN = "none";

    static MailAccount of(Map<String, String> r) {
        return of(r, DEFAULT_AUTHORITY);
    }

    static MailAccount of(Map<String, String> r, String authority) {
        String security = trim(r.get(SECURITY)).toLowerCase(Locale.ROOT);
        if (!List.of(SSL, STARTTLS, PLAIN).contains(security)) {
            security = SSL;
        }
        int port;
        try {
            port = Integer.parseInt(trim(r.get(PORT)));
        } catch (NumberFormatException e) {
            port = security.equals(SSL) ? 993 : 143;
        }
        String password = r.get(PASSWORD);
        String auth = MICROSOFT.equalsIgnoreCase(trim(r.get(AUTH))) ? MICROSOFT : PASSWORD_AUTH;
        String host = trim(r.get(HOST));
        if (host.isEmpty() && auth.equals(MICROSOFT)) {
            host = "outlook.office365.com";
        }
        String tenant = trim(r.get(TENANT));
        return new MailAccount(trim(r.get(NAME)), host, port, security, trim(r.get(USERNAME)),
                password == null || password.isEmpty() ? null : password, lines(r.get(FOLDERS)), lines(r.get(WATCH)),
                trim(r.get(DESCRIPTION)), auth, tenant.isEmpty() ? "organizations" : tenant, trim(r.get(CLIENT_ID)),
                authority == null || authority.isBlank() ? DEFAULT_AUTHORITY : authority.strip().replaceAll("/+$", ""));
    }

    boolean microsoft() {
        return auth.equals(MICROSOFT);
    }

    private static List<String> lines(String v) {
        if (v == null || v.isBlank()) {
            return List.of();
        }
        // Zeilen (Listenfeld) oder Kommas (ältere/handgeschriebene Werte)
        return ModuleConfig.splitLines(v.replace(',', '\n')).stream().map(String::strip).filter(s -> !s.isEmpty())
                .toList();
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    String target() {
        return username + "@" + host + ":" + port;
    }

    boolean wholeAccount() {
        return folders.isEmpty();
    }

    /** Ob der Ordner freigegebenen ist. INBOX ist nach IMAP unabhängig von Groß-/Kleinschreibung. */
    boolean allows(String folder) {
        if (folders.isEmpty()) {
            return true;
        }
        for (String f : folders) {
            if (matches(f, folder)) {
                return true;
            }
        }
        return false;
    }

    static boolean matches(String pattern, String folder) {
        String p = canonical(pattern);
        String f = canonical(folder);
        return p.endsWith("*") ? f.startsWith(p.substring(0, p.length() - 1)) : p.equals(f);
    }

    /** {@code inbox}, {@code Inbox/Rechnungen} … → {@code INBOX…}; andere Namen unverändert. */
    static String canonical(String name) {
        if (name.length() >= 5 && name.regionMatches(true, 0, "INBOX", 0, 5)
                && (name.length() == 5 || !Character.isLetterOrDigit(name.charAt(5)))) {
            return "INBOX" + name.substring(5);
        }
        return name;
    }

    static boolean isInbox(String name) {
        return name.equalsIgnoreCase("INBOX");
    }
}
