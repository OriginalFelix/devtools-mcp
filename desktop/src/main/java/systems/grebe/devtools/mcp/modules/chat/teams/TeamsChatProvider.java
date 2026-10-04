package systems.grebe.devtools.mcp.modules.chat.teams;

import java.util.List;

import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.modules.chat.spi.ChatProvider;
import systems.grebe.devtools.mcp.modules.chat.spi.ChatSettings;
import systems.grebe.devtools.mcp.modules.chat.spi.ChatSystem;

/**
 * Microsoft Teams über Microsoft Graph mit delegierten Berechtigungen (Anmeldung im Browser per Device Code). Braucht
 * eine App-Registrierung in Entra ID als öffentlicher Client.
 */
public class TeamsChatProvider implements ChatProvider {

    static final String ID = "teams";
    static final String TENANT = "tenant";
    static final String CLIENT_ID = "clientId";
    static final String CHATS = "chats";
    static final String TRUSTED = "trustedSenders";
    static final String POLL_SECONDS = "pollSeconds";
    static final String AUTHORITY = "authorityUrl";
    static final String GRAPH = "graphUrl";
    static final String DEFAULT_AUTHORITY = "https://login.microsoftonline.com";
    static final String DEFAULT_GRAPH = "https://graph.microsoft.com/v1.0";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Microsoft Teams";
    }

    @Override
    public int priority() {
        return 20;
    }

    @Override
    public boolean interactiveLogin() {
        return true;
    }

    @Override
    public String conversationHelp() {
        return "Chat-ID (19:…), Thema eines Chats oder Name/E-Mail des Gegenübers im 1:1-Chat";
    }

    @Override
    public List<ConfigField> configFields() {
        return List.of(
                ConfigField.of(TENANT, "Tenant", FieldType.STRING).withDefault("organizations")
                        .withHelp("Tenant-ID oder Domain (firma.onmicrosoft.com); 'organizations' = beliebiges "
                                + "Geschäftskonto."),
                ConfigField.of(CLIENT_ID, "Client-ID", FieldType.STRING)
                        .withHelp("Anwendungs-ID einer App-Registrierung in Entra ID: Authentifizierung → „Öffentliche "
                                + "Clientflows zulassen“ = Ja; delegierte Berechtigungen User.Read, Chat.ReadWrite, "
                                + "ChatMessage.Send (ohne Admin-Zustimmung, sofern der Tenant Benutzerzustimmung erlaubt)."),
                ConfigField.of(CHATS, "Nur diese Chats", FieldType.STRING_LIST)
                        .withHelp("Chat-IDs je Zeile (siehe chat_conversations). Leer = alle Chats des Kontos."),
                ConfigField.of(TRUSTED, "Freigegebene Absender", FieldType.STRING_LIST)
                        .withHelp("E-Mail-Adressen oder Benutzer-IDs je Zeile, deren Nachrichten das LLM erhält. Das "
                                + "eigene Konto ist immer freigegeben. Leer = alle Chat-Mitglieder."),
                ConfigField.of(POLL_SECONDS, "Warten durch Abfragen alle … Sekunden", FieldType.INT).withDefault("0")
                        .withHelp("0 = aus: Nachrichten nur je Tool-Aufruf, chat_ask wartet nicht. Sonst fragt das Modul "
                                + "während chat_ask/chat_receive wiederholt ab (mind. 10 s). Achtung: Microsoft erlaubt in "
                                + "den Nutzungsbedingungen der Teams-APIs kein regelmäßiges Abfragen auf Änderungen – "
                                + "nur einschalten, wenn das für eure App-Registrierung in Ordnung ist."),
                ConfigField.of(AUTHORITY, "Anmelde-Endpunkt", FieldType.URL).withDefault(DEFAULT_AUTHORITY)
                        .withHelp("Nur für nationale Clouds ändern (z.B. https://login.microsoftonline.us)."),
                ConfigField.of(GRAPH, "Graph-Endpunkt", FieldType.URL).withDefault(DEFAULT_GRAPH)
                        .withHelp("Nur für nationale Clouds ändern (z.B. https://graph.microsoft.us/v1.0)."));
    }

    @Override
    public ChatSystem create(ChatSettings settings) {
        return new TeamsChat(settings);
    }
}
