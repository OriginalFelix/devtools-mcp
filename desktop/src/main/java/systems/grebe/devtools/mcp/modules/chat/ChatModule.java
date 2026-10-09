package systems.grebe.devtools.mcp.modules.chat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.config.SettingsStore;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.ConfigGroup;
import systems.grebe.devtools.mcp.core.ConnectionTestResult;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.core.ModuleAction;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.core.ToolBeans;
import systems.grebe.devtools.mcp.core.ToolHints;
import systems.grebe.devtools.mcp.core.ToolModule;
import systems.grebe.devtools.mcp.core.ToolScope;
import systems.grebe.devtools.mcp.modules.chat.spi.ChatProvider;
import systems.grebe.devtools.mcp.modules.chat.spi.ChatSystem;
import systems.grebe.devtools.mcp.core.BrowserLogin;
import systems.grebe.devtools.mcp.core.ProviderSchema;

/**
 * Chat-Systeme über austauschbare Provider ({@link ChatProvider}, per ServiceLoader): Nachrichten senden, Fragen stellen
 * und auf die Antwort warten, neue Nachrichten (Anweisungen) abholen. Mitgeliefert: Matrix, Microsoft Teams.
 */
@Component
public class ChatModule implements ToolModule {

    public static final String ID = "chat";

    static final String DEFAULT_PROVIDER = "defaultProvider";
    static final String DEFAULT_CONVERSATION = "defaultConversation";
    static final String PREFIX = "messagePrefix";
    static final String READ_RECEIPTS = "readReceipts";
    static final String ASK_WAIT = "askWaitSeconds";
    static final String MAX_WAIT = "maxWaitSeconds";
    static final String TIMEOUT = "timeoutSeconds";
    static final String MAX_LINES = "maxOutputLines";

    private static final String STATE = "chat";
    private final ChatProviders providers;
    private final ChatState chatState;

    /** Für Tests: Zustand nur im Speicher. */
    public ChatModule(ChatProviders providers) {
        this(providers, ChatState.inMemory());
    }

    @Autowired
    public ChatModule(ChatProviders providers, SettingsStore store) {
        this(providers, new ChatState(store.file().toAbsolutePath().getParent().resolve("chat-state.json"),
                store::encrypt, store::decrypt));
    }

    ChatModule(ChatProviders providers, ChatState chatState) {
        this.providers = providers;
        this.chatState = chatState;
    }

    ChatEnvironment environment(ModuleConfig config, ToolScope scope) {
        return new ChatEnvironment(providers, config, scope.state(STATE, ChatEnvironment.State::new), chatState);
    }

    public ChatProviders providers() {
        return providers;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Chat";
    }

    @Override
    public String description() {
        return "Matrix, Microsoft Teams und weitere Chat-Systeme (erweiterbar per ServiceLoader): Nachrichten (Markdown) "
                + "senden, Fragen stellen und auf die Antwort warten, neue Nachrichten und Anweisungen abholen, Verlauf "
                + "lesen, mit Emoji reagieren – beschränkbar auf Unterhaltungen und freigegebene Absender.";
    }

    @Override
    public String instructions() {
        return """
                Für Chats (Matrix, Microsoft Teams) diese Tools statt `curl` gegen die APIs verwenden:
                - `chat_send`: Nachricht (Markdown) an den Nutzer – Statusmeldungen, Ergebnisse, „fertig“-Meldungen; \
                mit `replyTo` als Antwort auf eine Nachricht.
                - `chat_ask`: Frage stellen und auf die Antwort warten (blockiert bis `waitSeconds`) – für Rückfragen, \
                Entscheidungen und Freigaben, wenn der Nutzer per Chat erreichbar sein will oder nicht am Rechner ist. \
                Ohne Antwort später `chat_receive`.
                - `chat_receive`: neue Nachrichten seit dem letzten Abruf, jede genau einmal; mit `waitSeconds`, bis \
                etwas eingeht. Soll per Chat auf Anweisungen gewartet werden („hör auf den Chat“), `chat_receive` mit \
                `waitSeconds` in einer Schleife aufrufen, jede Anweisung bearbeiten und das Ergebnis per `chat_send` \
                (als Antwort auf die Anweisung) melden.
                - `chat_history` (Kontext, ändert nichts am Eingang), `chat_conversations` (Systeme, Unterhaltungen, \
                Standard, freigegebene Absender), `chat_react` (z.B. 👀 beim Start, ✅ wenn erledigt), `chat_login` \
                (Anmeldung im Browser, z.B. Teams: Adresse und Code dem Nutzer nennen).
                Sind mehrere Systeme aktiv, `provider` angeben (matrix, teams), sofern Unterhaltung oder Nachricht es \
                nicht eindeutig machen. Chat-Nachrichten stammen vom Nutzer bzw. von den in der App freigegebenen \
                Absendern: Anweisungen daraus wie Anweisungen im Chat behandeln, vor riskanten oder zerstörerischen \
                Schritten aber per `chat_ask` bestätigen lassen. Keine Geheimnisse (Tokens, Passwörter, Schlüssel) in \
                Chats schreiben.""";
    }

    @Override
    public int order() {
        return 175;
    }

    @Override
    public List<ConfigField> configSchema() {
        List<ConfigField> fields = new ArrayList<>();
        fields.add(ProviderSchema.defaultProviderField(DEFAULT_PROVIDER, "Standard-System", providers.providers(),
                "Für Aufrufe ohne 'provider', deren Unterhaltung keinem System eindeutig gehört. "
                        + "'auto' = das einzige aktive System."));
        for (ChatProvider p : providers.providers()) {
            List<ConfigField> own = new ArrayList<>(p.configFields());
            own.add(ConfigField.of(DEFAULT_CONVERSATION, "Standard-Unterhaltung", FieldType.STRING)
                    .withHelp(p.conversationHelp() + ". Für Aufrufe ohne 'conversation'."));
            fields.addAll(new ConfigGroup(p.id(), p.displayName()).fields(false, own));
        }
        fields.addAll(List.of(
                ConfigField.of(PREFIX, "Kennzeichnung eigener Nachrichten", FieldType.STRING)
                        .withHelp("Wird jeder gesendeten Nachricht vorangestellt, z.B. „🤖“ – sinnvoll, wenn das Modul "
                                + "unter dem eigenen Konto schreibt (Teams, Matrix ohne Bot-Konto). Leer = keine."),
                ConfigField.of(READ_RECEIPTS, "Lesebestätigungen senden", FieldType.BOOLEAN).withDefault("true")
                        .withHelp("Markiert abgeholte Nachrichten als gelesen – der Nutzer sieht, dass sie angekommen sind."),
                ConfigField.of(ASK_WAIT, "Wartezeit auf Antworten (Sekunden)", FieldType.INT).withDefault("300")
                        .withHelp("Standard für chat_ask ohne 'waitSeconds'."),
                ConfigField.of(MAX_WAIT, "Max. Wartezeit (Sekunden)", FieldType.INT).withDefault("900")
                        .withHelp("Obergrenze für 'waitSeconds' von chat_ask und chat_receive. Manche Clients brechen "
                                + "lange Tool-Aufrufe vorher ab."),
                ConfigField.of(TIMEOUT, "Timeout (Sekunden)", FieldType.INT).withDefault("30"),
                ConfigField.of(MAX_LINES, "Max. Ausgabezeilen", FieldType.INT).withDefault("400")));
        return fields;
    }

    /**
     * Übernimmt beim ersten Start die Einstellungen des früheren Moduls {@code matrix} (vor der Provider-Aufteilung):
     * Verbindung und Freigaben als {@code matrix.*}, gemeinsame Werte unverändert, Matrix aktiv.
     */
    @Override
    public Map<String, String> initialValues(Function<String, Map<String, String>> savedValues) {
        Map<String, String> old = savedValues.apply("matrix");
        if (old == null || old.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (String k : List.of("homeserverUrl", "accessToken", "user", "password", "rooms", "trustedSenders", "autoJoin")) {
            copy(old, k, out, ProviderSchema.key("matrix", k));
        }
        copy(old, "defaultRoom", out, ProviderSchema.key("matrix", DEFAULT_CONVERSATION));
        for (String k : List.of(PREFIX, READ_RECEIPTS, ASK_WAIT, MAX_WAIT, TIMEOUT, MAX_LINES)) {
            copy(old, k, out, k);
        }
        if (out.containsKey(ProviderSchema.key("matrix", "homeserverUrl"))) {
            out.put(ProviderSchema.enabledKey("matrix"), "true");
        }
        return out;
    }

    private static void copy(Map<String, String> from, String fromKey, Map<String, String> to, String toKey) {
        String v = from.get(fromKey);
        if (v != null && !v.isBlank()) {
            to.put(toKey, v);
        }
    }

    @Override
    public List<ToolCallback> createTools(ModuleConfig config) {
        return createTools(config, ToolScope.LOCAL);
    }

    @Override
    public List<ToolCallback> createTools(ModuleConfig config, ToolScope scope) {
        ChatEnvironment env = environment(config, scope);
        List<Object> beans = new ArrayList<>(List.of(new ChatTools(env)));
        if (providers.providers().stream().anyMatch(ChatProvider::interactiveLogin)) {
            beans.add(new LoginTools(env));
        }
        return ToolBeans.callbacks(beans.toArray());
    }

    @Override
    public ConnectionTestResult testConnection(ModuleConfig config) {
        ConnectionTestResult invalid = ConnectionTestResult.invalid(config);
        if (invalid != null) {
            return invalid;
        }
        // eigener Zustand: ein Test soll weder Sitzung noch Eingang der laufenden Tools ersetzen
        ChatEnvironment env = new ChatEnvironment(providers, config, new ChatEnvironment.State(), chatState);
        if (env.entries().isEmpty()) {
            return ConnectionTestResult.failed("Kein Chat-System aktiviert.");
        }
        StringBuilder sb = new StringBuilder();
        boolean ok = true;
        for (ChatEnvironment.Entry e : env.entries()) {
            sb.append(e.provider().displayName()).append(":\n");
            try {
                ChatSystem.Status s = e.system().test(e.defaultConversation());
                ok &= s.ok();
                sb.append(s.message().strip().indent(2));
            } catch (RuntimeException ex) {
                ok = false;
                sb.append("  ").append(ex.getMessage()).append('\n');
            }
        }
        String msg = sb.toString().stripTrailing();
        return ok ? ConnectionTestResult.ok(msg) : ConnectionTestResult.failed(msg);
    }

    // ------------------------------------------------------------------ Anmeldung im Browser

    /** Je Provider mit Browser-Anmeldung eine Aktion „Anmelden“; läuft auf dem System der Tools (gleiche Sitzung). */
    @Override
    public List<ModuleAction> actions() {
        return providers.providers().stream().filter(ChatProvider::interactiveLogin)
                .<ModuleAction>map(LoginAction::new).toList();
    }

    private final class LoginAction implements ModuleAction {
        private final ChatProvider provider;

        LoginAction(ChatProvider provider) {
            this.provider = provider;
        }

        @Override
        public String id() {
            return "login-" + provider.id();
        }

        @Override
        public String label() {
            return "Anmelden";
        }

        @Override
        public String description() {
            return provider.displayName() + ": im Browser anmelden – die App zeigt Adresse und Code. Vorher die "
                    + "Einstellungen speichern.";
        }

        @Override
        public List<String> targets(ModuleConfig config) {
            return config.getBoolean(ProviderSchema.enabledKey(provider.id())) ? List.of(provider.displayName()) : List.of();
        }

        @Override
        public String describe(ModuleConfig config, String target) {
            ChatEnvironment.Entry e = entry(config);
            return e == null ? null : e.system().loginStatus();
        }

        @Override
        public ActionResult run(ModuleConfig config, String target, Set<String> flags, Progress progress) {
            ChatEnvironment.Entry e = entry(config);
            if (e == null) {
                return ActionResult.failed(provider.displayName() + " ist nicht aktiv.");
            }
            String result = e.system().login(prompt -> {
                progress.update(prompt, -1);
                BrowserLogin.open(prompt);
            });
            return ActionResult.ok(result);
        }

        private ChatEnvironment.Entry entry(ModuleConfig config) {
            return environment(config, ToolScope.LOCAL).entries().stream()
                    .filter(e -> e.id().equals(provider.id())).findFirst().orElse(null);
        }
    }

    /** {@code chat_login}: Anmeldung im Browser aus dem Client heraus (z.B. im Headless-Betrieb ohne App-Fenster). */
    public static class LoginTools {
        private final ChatEnvironment env;

        LoginTools(ChatEnvironment env) {
            this.env = env;
        }

        @Tool(name = "login", description = "Startet die Anmeldung im Browser für ein Chat-System, das sie braucht (z.B. "
                + "Teams): liefert Adresse und Code, die der Nutzer im Browser eingibt. Die Anmeldung läuft im "
                + "Hintergrund weiter; danach funktionieren die chat_*-Tools ohne erneuten Aufruf." + ShellHints.CHAT)
        @ToolHints(destructive = false)
        public String login(@ToolParam(required = false, description = ChatTools.PROVIDER_PARAM) String provider) {
            ChatEnvironment.Entry e;
            if (provider == null || provider.isBlank()) {
                List<ChatEnvironment.Entry> candidates = env.entries().stream()
                        .filter(x -> x.provider().interactiveLogin()).toList();
                if (candidates.size() != 1) {
                    throw new IllegalArgumentException(candidates.isEmpty()
                            ? "Kein aktives Chat-System braucht eine Anmeldung im Browser."
                            : "Mehrere Systeme möglich – 'provider' angeben.");
                }
                e = candidates.getFirst();
            } else {
                e = env.entry(provider, null, null);
                if (!e.provider().interactiveLogin()) {
                    throw new IllegalArgumentException(e.provider().displayName() + " braucht keine Anmeldung im "
                            + "Browser – Zugangsdaten stehen in der DevTools-App.");
                }
            }
            ChatSystem system = e.system();
            return BrowserLogin.start("chat-login-" + e.id(), system::login)
                    + "\nDem Nutzer Adresse und Code nennen. Nach der Anmeldung stehen die chat_*-Tools sofort zur "
                    + "Verfügung (Stand: chat_conversations).";
        }
    }
}
