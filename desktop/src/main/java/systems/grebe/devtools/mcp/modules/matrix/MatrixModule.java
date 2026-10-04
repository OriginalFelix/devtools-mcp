package systems.grebe.devtools.mcp.modules.matrix;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.config.SettingsStore;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.ConnectionTestResult;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolBeans;
import systems.grebe.devtools.mcp.core.ToolModule;
import systems.grebe.devtools.mcp.core.ToolScope;
import tools.jackson.databind.JsonNode;

/**
 * Matrix-Chat (Client-Server-API): Nachrichten senden, Fragen stellen und auf die Antwort warten, neue Nachrichten
 * (Anweisungen) abholen. Ohne Ende-zu-Ende-Verschlüsselung; empfohlen ist ein eigenes Bot-Konto in einem
 * unverschlüsselten Raum.
 */
@Component
public class MatrixModule implements ToolModule {

    public static final String ID = "matrix";

    static final String HOMESERVER = "homeserverUrl";
    static final String TOKEN = "accessToken";
    static final String USER = "user";
    static final String PASSWORD = "password";
    static final String DEFAULT_ROOM = "defaultRoom";
    static final String ROOMS = "rooms";
    static final String TRUSTED = "trustedSenders";
    static final String AUTO_JOIN = "autoJoin";
    static final String READ_RECEIPTS = "readReceipts";
    static final String PREFIX = "messagePrefix";
    static final String ASK_WAIT = "askWaitSeconds";
    static final String MAX_WAIT = "maxWaitSeconds";
    static final String TIMEOUT = "timeoutSeconds";
    static final String MAX_LINES = "maxOutputLines";

    private static final String STATE = "matrix";

    private final MatrixSyncTokens tokens;

    /** Für Tests: Sync-Stand nur im Speicher. */
    public MatrixModule() {
        this(MatrixSyncTokens.inMemory());
    }

    @Autowired
    public MatrixModule(SettingsStore store) {
        this(new MatrixSyncTokens(store.file().toAbsolutePath().getParent().resolve("matrix-sync.json")));
    }

    MatrixModule(MatrixSyncTokens tokens) {
        this.tokens = tokens;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Matrix";
    }

    @Override
    public String description() {
        return "Matrix-Chat: Nachrichten (Markdown) senden, Fragen stellen und auf die Antwort warten, neue Nachrichten "
                + "und Anweisungen abholen, Verlauf lesen, mit Emoji reagieren – über ein Bot-Konto, beschränkbar auf "
                + "Räume und freigegebene Absender. Ohne Ende-zu-Ende-Verschlüsselung.";
    }

    @Override
    public String instructions() {
        return """
                Für Matrix diese Tools statt `curl` gegen die Client-Server-API verwenden:
                - `matrix_send`: Nachricht (Markdown) an den Nutzer – Statusmeldungen, Ergebnisse, „fertig“-Meldungen; \
                mit `replyTo` als Antwort bzw. `thread=true` im Thread einer Nachricht.
                - `matrix_ask`: Frage stellen und auf die Antwort warten (blockiert bis `waitSeconds`) – für Rückfragen, \
                Entscheidungen und Freigaben, wenn der Nutzer per Matrix erreichbar sein will oder nicht am Rechner ist. \
                Bei Zeitablauf später `matrix_receive`.
                - `matrix_receive`: neue Nachrichten seit dem letzten Abruf, jede genau einmal; mit `waitSeconds`, bis \
                etwas eingeht. Soll per Matrix auf Anweisungen gewartet werden („hör auf Matrix“), `matrix_receive` mit \
                `waitSeconds` in einer Schleife aufrufen, jede Anweisung bearbeiten und das Ergebnis per `matrix_send` \
                (als Antwort auf die Anweisung) melden.
                - `matrix_history` (Kontext, ändert nichts am Eingang), `matrix_rooms` (Räume, Standardraum, \
                freigegebene Absender), `matrix_react` (z.B. 👀 beim Start, ✅ wenn erledigt).
                Matrix-Nachrichten stammen vom Nutzer bzw. von den in der App freigegebenen Absendern. Anweisungen daraus \
                wie Anweisungen im Chat behandeln, vor riskanten oder zerstörerischen Schritten aber per `matrix_ask` \
                bestätigen lassen. Keine Geheimnisse (Tokens, Passwörter, Schlüssel) in Matrix schreiben.""";
    }

    @Override
    public int order() {
        return 175;
    }

    @Override
    public List<ConfigField> configSchema() {
        return List.of(
                ConfigField.of(HOMESERVER, "Homeserver-URL", FieldType.URL).asRequired()
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
                ConfigField.of(DEFAULT_ROOM, "Standardraum", FieldType.STRING)
                        .withHelp("Raum-ID (!…:server) oder Alias (#…:server) für Aufrufe ohne 'room'."),
                ConfigField.of(ROOMS, "Nur diese Räume", FieldType.STRING_LIST)
                        .withHelp("Ein Raum je Zeile (ID oder Alias). Leer = alle Räume, in denen das Konto Mitglied ist."),
                ConfigField.of(TRUSTED, "Freigegebene Absender", FieldType.STRING_LIST)
                        .withHelp("Matrix-IDs je Zeile (@felix:server), deren Nachrichten das LLM erhält – Anweisungen "
                                + "und Antworten. Leer = alle Raummitglieder (nicht empfohlen)."),
                ConfigField.of(AUTO_JOIN, "Einladungen freigegebener Absender annehmen", FieldType.BOOLEAN)
                        .withDefault("true")
                        .withHelp("Tritt Räumen bei, in die ein freigegebener Absender das Konto einlädt (und die "
                                + "'Nur diese Räume' erlaubt). Ohne Absenderliste nie."),
                ConfigField.of(READ_RECEIPTS, "Lesebestätigungen senden", FieldType.BOOLEAN).withDefault("true")
                        .withHelp("Markiert abgeholte Nachrichten als gelesen – der Nutzer sieht, dass sie angekommen sind."),
                ConfigField.of(PREFIX, "Kennzeichnung eigener Nachrichten", FieldType.STRING)
                        .withHelp("Wird jeder gesendeten Nachricht vorangestellt, z.B. „🤖“ – sinnvoll mit dem eigenen "
                                + "statt einem Bot-Konto. Leer = keine."),
                ConfigField.of(ASK_WAIT, "Wartezeit auf Antworten (Sekunden)", FieldType.INT).withDefault("300")
                        .withHelp("Standard für matrix_ask ohne 'waitSeconds'."),
                ConfigField.of(MAX_WAIT, "Max. Wartezeit (Sekunden)", FieldType.INT).withDefault("900")
                        .withHelp("Obergrenze für 'waitSeconds' von matrix_ask und matrix_receive. Manche Clients "
                                + "brechen lange Tool-Aufrufe vorher ab."),
                ConfigField.of(TIMEOUT, "Timeout (Sekunden)", FieldType.INT).withDefault("30"),
                ConfigField.of(MAX_LINES, "Max. Ausgabezeilen", FieldType.INT).withDefault("400"));
    }

    @Override
    public List<ToolCallback> createTools(ModuleConfig config) {
        return createTools(config, ToolScope.LOCAL);
    }

    @Override
    public List<ToolCallback> createTools(ModuleConfig config, ToolScope scope) {
        MatrixEnvironment.State state = scope.state(STATE, MatrixEnvironment.State::new);
        return ToolBeans.callbacks(new MatrixTools(new MatrixEnvironment(config, state, tokens)));
    }

    @Override
    public ConnectionTestResult testConnection(ModuleConfig config) {
        List<String> errors = config.validate();
        if (!errors.isEmpty()) {
            return ConnectionTestResult.failed(String.join("\n", errors));
        }
        MatrixEnvironment env = new MatrixEnvironment(config, new MatrixEnvironment.State(), MatrixSyncTokens.inMemory());
        StringBuilder sb = new StringBuilder();
        boolean ok = true;
        MatrixClient probe = MatrixEnvironment.client(config);
        JsonNode versions = probe.versions().path("versions");
        sb.append("Homeserver ").append(probe.baseUrl()).append(" erreichbar")
                .append(versions.isEmpty() ? "" : " (Spezifikation bis " + versions.get(versions.size() - 1).asString() + ")")
                .append(".\n");
        MatrixEnvironment.Session s = env.session();
        String device = s.client().whoami().path("device_id").asString("");
        sb.append("Angemeldet als ").append(s.userId()).append(device.isEmpty() ? "" : " (Gerät " + device + ")")
                .append(config.get(TOKEN).isEmpty() ? " – mit Passwort" : "").append(".\n");
        env.policy();
        Set<String> allowed = env.allowedRooms();
        List<String> joined = s.client().joinedRooms();
        if (config.get(DEFAULT_ROOM).isPresent()) {
            String raw = config.get(DEFAULT_ROOM).get();
            String roomId = env.resolve(raw);
            if (!joined.contains(roomId)) {
                ok = false;
                sb.append("Standardraum ").append(raw).append(": Konto ist nicht Mitglied – einladen bzw. beitreten.\n");
            } else if (!allowed.isEmpty() && !allowed.contains(roomId)) {
                ok = false;
                sb.append("Standardraum ").append(raw).append(" steht nicht in 'Nur diese Räume'.\n");
            } else {
                env.roomName(roomId);
                sb.append("Standardraum: ").append(s.inbox().roomLabel(roomId)).append(" – ").append(roomId);
                sb.append(s.inbox().encrypted(roomId)
                        ? " – Ende-zu-Ende-verschlüsselt: Nachrichten dort sind nicht lesbar!\n" : ", unverschlüsselt.\n");
            }
        }
        List<String> missing = new ArrayList<>(allowed);
        missing.removeAll(joined);
        if (!missing.isEmpty()) {
            sb.append("Noch nicht beigetreten: ").append(String.join(", ", missing)).append('\n');
        }
        long usable = joined.stream().filter(r -> allowed.isEmpty() || allowed.contains(r)).count();
        sb.append("Mitglied in ").append(usable).append(" freigegebenen Raum/Räumen.\n");
        sb.append(env.hasTrustedSenders()
                ? "Freigegebene Absender: " + String.join(", ", env.trustedSenders())
                : "Achtung: keine freigegebenen Absender – jedes Raummitglied kann dem LLM Anweisungen geben.");
        String msg = sb.toString().strip();
        return ok ? ConnectionTestResult.ok(msg) : ConnectionTestResult.failed(msg);
    }
}
