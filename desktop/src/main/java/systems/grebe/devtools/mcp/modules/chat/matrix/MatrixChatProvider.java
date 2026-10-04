package systems.grebe.devtools.mcp.modules.chat.matrix;

import java.util.List;

import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.modules.chat.spi.ChatProvider;
import systems.grebe.devtools.mcp.modules.chat.spi.ChatSettings;
import systems.grebe.devtools.mcp.modules.chat.spi.ChatSystem;

/**
 * Matrix (Client-Server-API {@code /_matrix/client/v3}) mit Zugangstoken oder Benutzer/Passwort. Ohne
 * Ende-zu-Ende-Verschlüsselung; empfohlen ist ein eigenes Bot-Konto in einem unverschlüsselten Raum.
 */
public class MatrixChatProvider implements ChatProvider {

    static final String ID = "matrix";
    static final String HOMESERVER = "homeserverUrl";
    static final String TOKEN = "accessToken";
    static final String USER = "user";
    static final String PASSWORD = "password";
    static final String ROOMS = "rooms";
    static final String TRUSTED = "trustedSenders";
    static final String AUTO_JOIN = "autoJoin";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Matrix";
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public String conversationHelp() {
        return "Raum-ID (!…:server), Alias (#…:server) oder Name eines beigetretenen Raums";
    }

    @Override
    public List<ConfigField> configFields() {
        return List.of(
                ConfigField.of(HOMESERVER, "Homeserver-URL", FieldType.URL)
                        .withHelp("Client-API des Homeservers, z.B. https://matrix.org oder https://matrix.firma.de "
                                + "(bei Ende-zu-Ende-Verschlüsselung: Adresse eines Pantalaimon-Proxys)."),
                ConfigField.of(TOKEN, "Zugangstoken", FieldType.SECRET)
                        .withHelp("Access Token des (Bot-)Kontos. Leer = Anmeldung mit Benutzer und Passwort. Wird "
                                + "verschlüsselt gespeichert."),
                ConfigField.of(USER, "Benutzer", FieldType.STRING)
                        .withHelp("Benutzername oder Matrix-ID (@bot:server) für die Anmeldung mit Passwort."),
                ConfigField.of(PASSWORD, "Passwort", FieldType.SECRET)
                        .withHelp("Für die Anmeldung ohne Token (Gerät „DevTools MCP“) und zum erneuten Anmelden, wenn "
                                + "ein Token abläuft. Wird verschlüsselt gespeichert."),
                ConfigField.of(ROOMS, "Nur diese Räume", FieldType.STRING_LIST)
                        .withHelp("Ein Raum je Zeile (ID oder Alias). Leer = alle Räume, in denen das Konto Mitglied ist."),
                ConfigField.of(TRUSTED, "Freigegebene Absender", FieldType.STRING_LIST)
                        .withHelp("Matrix-IDs je Zeile (@felix:server), deren Nachrichten das LLM erhält – Anweisungen "
                                + "und Antworten. Leer = alle Raummitglieder (nicht empfohlen)."),
                ConfigField.of(AUTO_JOIN, "Einladungen freigegebener Absender annehmen", FieldType.BOOLEAN)
                        .withDefault("true")
                        .withHelp("Tritt Räumen bei, in die ein freigegebener Absender das Konto einlädt (und die "
                                + "'Nur diese Räume' erlaubt). Ohne Absenderliste nie."));
    }

    @Override
    public ChatSystem create(ChatSettings settings) {
        return new MatrixChat(settings);
    }
}
