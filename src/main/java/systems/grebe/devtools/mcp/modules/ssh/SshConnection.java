package systems.grebe.devtools.mcp.modules.ssh;

import java.util.Map;

/**
 * Eine konfigurierte SSH-Verbindung. Passwort und Passphrase verlassen das Modul nie – weder in Tool-Ausgaben noch in
 * {@link #toString()}.
 *
 * @param privateKey Pfad zu einer privaten Schlüsseldatei (OpenSSH/PEM/PuTTY) oder leer
 */
public record SshConnection(String name, String host, int port, String username, String password,
                            String privateKey, String passphrase, String description) {

    static final String NAME = "name";
    static final String HOST = "host";
    static final String PORT = "port";
    static final String USERNAME = "username";
    static final String PASSWORD = "password";
    static final String PRIVATE_KEY = "privateKey";
    static final String PASSPHRASE = "passphrase";
    static final String DESCRIPTION = "description";

    static SshConnection of(Map<String, String> r) {
        int port;
        try {
            port = Integer.parseInt(r.getOrDefault(PORT, "22").trim());
        } catch (NumberFormatException e) {
            port = 22;
        }
        return new SshConnection(r.getOrDefault(NAME, "").trim(), r.getOrDefault(HOST, "").trim(), port,
                r.getOrDefault(USERNAME, "").trim(), blankToNull(r.get(PASSWORD)), blankToNull(r.get(PRIVATE_KEY)),
                blankToNull(r.get(PASSPHRASE)), r.getOrDefault(DESCRIPTION, "").trim());
    }

    /** {@code user@host:port} für Ausgaben. */
    public String target() {
        return username + "@" + host + ":" + port;
    }

    /** Anmeldeverfahren für Ausgaben, ohne Geheimnisse. */
    public String auth() {
        if (privateKey != null) {
            return password != null ? "Schlüssel + Passwort" : "Schlüssel";
        }
        return password != null ? "Passwort" : "keine Zugangsdaten";
    }

    @Override
    public String toString() {
        return name + " (" + target() + ", " + auth() + ")";
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
