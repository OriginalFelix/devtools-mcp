package systems.grebe.devtools.mcp.modules.chat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.core.ModuleAction;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolScope;
import systems.grebe.devtools.mcp.modules.chat.spi.ChatProvider;
import systems.grebe.devtools.mcp.modules.chat.spi.ChatSettings;
import systems.grebe.devtools.mcp.modules.chat.spi.ChatSystem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ServiceLoader, Schema, Tools und Aktionen sowie die Wahl von System und Unterhaltung mit Fake-Providern. */
class ChatModuleTest {

    /** Chat-System im Speicher: Unterhaltungen {@code <id>:…}, Nachrichten über {@link #incoming}. */
    static final class FakeProvider implements ChatProvider {
        final String id;
        final List<ChatSystem.Message> incoming = new CopyOnWriteArrayList<>();
        final List<String> sent = new CopyOnWriteArrayList<>();
        int created;

        FakeProvider(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String displayName() {
            return id.toUpperCase();
        }

        @Override
        public List<ConfigField> configFields() {
            return List.of(ConfigField.of("token", "Token", FieldType.SECRET));
        }

        @Override
        public ChatSystem create(ChatSettings settings) {
            created++;
            return new ChatSystem() {
                @Override
                public String id() {
                    return id;
                }

                @Override
                public Account account() {
                    return new Account("me@" + id, "Ich", id + "-account");
                }

                @Override
                public Status test(String defaultConversation) {
                    return new Status(true, "ok " + id);
                }

                @Override
                public List<Conversation> conversations() {
                    return List.of(new Conversation(id + ":main", "Main", null, 2, null));
                }

                @Override
                public String resolve(String ref) {
                    return ref.contains(":") ? ref : id + ":" + ref;
                }

                @Override
                public String label(String conversationId) {
                    return "„" + conversationId + "“";
                }

                @Override
                public boolean ownsConversation(String ref) {
                    return ref.startsWith(id + ":");
                }

                @Override
                public Sent send(String conversationId, Outgoing message) {
                    sent.add(conversationId + " " + message.text());
                    return new Sent(id + "-m" + sent.size(), null);
                }

                @Override
                public Poll poll(String cursor, int timeoutMs) {
                    List<Message> out = new ArrayList<>(incoming);
                    incoming.clear();
                    return new Poll(out, List.of(), "c" + System.nanoTime());
                }

                @Override
                public List<Message> history(String conversationId, int limit) {
                    return List.of();
                }

                @Override
                public boolean canWait() {
                    return false;
                }
            };
        }
    }

    static ChatSystem.Message msg(String provider, String id, String text) {
        return new ChatSystem.Message(id, provider + ":main", "anna", "Anna", 1790000000000L, text, null, null, null,
                false, false, true);
    }

    @Test
    void serviceLoaderFindsBuiltInProvidersAndBuildsSchema() {
        ChatModule module = new ChatModule(new ChatProviders());
        assertThat(module.providers().providers()).extracting(ChatProvider::id).containsExactly("matrix", "teams");
        assertThat(module.configSchema()).extracting(ConfigField::key).contains("defaultProvider", "matrix.enabled",
                "matrix.homeserverUrl", "matrix.accessToken", "matrix.trustedSenders", "matrix.defaultConversation",
                "teams.enabled", "teams.clientId", "teams.tenant", "teams.pollSeconds", "teams.defaultConversation",
                "messagePrefix", "readReceipts", "askWaitSeconds", "maxWaitSeconds");
        assertThat(module.configSchema()).filteredOn(f -> f.key().equals("defaultProvider"))
                .flatExtracting(ConfigField::options).containsExactly("auto", "matrix", "teams");
        // Geheimnisse der Provider werden verschlüsselt gespeichert
        assertThat(module.configSchema()).filteredOn(ConfigField::secret).extracting(ConfigField::key)
                .containsExactlyInAnyOrder("matrix.accessToken", "matrix.password");
    }

    @Test
    void toolsInstructionsAndLoginAction() {
        ChatModule module = new ChatModule(new ChatProviders());
        List<ToolCallback> tools = module.createTools(ModuleConfig.of(module.configSchema(), Map.of()));
        assertThat(tools).extracting(t -> t.getToolDefinition().name())
                .containsExactlyInAnyOrder("conversations", "send", "ask", "receive", "history", "react", "login");
        assertThat(module.instructions()).contains("`chat_ask`", "`chat_receive`", "`chat_login`", "curl");

        assertThat(module.actions()).extracting(ModuleAction::id).containsExactly("login-teams");
        ModuleAction login = module.actions().getFirst();
        assertThat(login.targets(ModuleConfig.of(module.configSchema(), Map.of()))).isEmpty();
        assertThat(login.targets(ModuleConfig.of(module.configSchema(), Map.of("teams.enabled", "true"))))
                .containsExactly("Microsoft Teams");
    }

    @Test
    void takesSettingsOfFormerMatrixModuleOnFirstStart() {
        ChatModule module = new ChatModule(new ChatProviders());
        Map<String, String> init = module.initialValues(id -> "matrix".equals(id)
                ? Map.of("homeserverUrl", "https://matrix.example.org", "accessToken", "tok", "defaultRoom", "!r:x",
                "trustedSenders", "@felix:x", "messagePrefix", "🤖", "askWaitSeconds", "120")
                : Map.of());
        assertThat(init).containsEntry("matrix.enabled", "true")
                .containsEntry("matrix.homeserverUrl", "https://matrix.example.org")
                .containsEntry("matrix.accessToken", "tok").containsEntry("matrix.defaultConversation", "!r:x")
                .containsEntry("matrix.trustedSenders", "@felix:x").containsEntry("messagePrefix", "🤖")
                .containsEntry("askWaitSeconds", "120");
        assertThat(module.initialValues(id -> Map.of())).isEmpty();
    }

    @Test
    void withoutActiveSystemSaysHowToEnableOne() {
        FakeProvider alpha = new FakeProvider("alpha");
        ChatModule module = new ChatModule(new ChatProviders(List.of(alpha)));
        ChatTools tools = new ChatTools(module.environment(ModuleConfig.of(module.configSchema(), Map.of()),
                new ToolScope("t", null, null, null, null, false)));
        assertThatThrownBy(() -> tools.send("x", null, null, null, null)).hasMessageContaining("Kein Chat-System aktiviert");
    }

    @Test
    void choosesSystemByConversationDefaultOrParameter() {
        FakeProvider alpha = new FakeProvider("alpha");
        FakeProvider beta = new FakeProvider("beta");
        ChatProviders providers = new ChatProviders(List.of(alpha, beta));
        ChatModule module = new ChatModule(providers);
        Map<String, String> values = new HashMap<>(Map.of("alpha.enabled", "true", "beta.enabled", "true",
                "alpha.defaultConversation", "main", "beta.defaultConversation", "main"));
        ChatEnvironment env = new ChatEnvironment(providers, ModuleConfig.of(module.configSchema(), values),
                new ChatEnvironment.State(), ChatState.inMemory());
        ChatTools tools = new ChatTools(env);

        tools.send("an beta", "beta:main", null, null, null);
        assertThat(beta.sent).containsExactly("beta:main an beta");
        assertThatThrownBy(() -> tools.send("?", null, null, null, null)).hasMessageContaining("Mehrere Chat-Systeme");
        tools.send("an alpha", null, "alpha", null, null);
        assertThat(alpha.sent).containsExactly("alpha:main an alpha");

        values.put("defaultProvider", "beta");
        new ChatTools(new ChatEnvironment(providers, ModuleConfig.of(module.configSchema(), values),
                new ChatEnvironment.State(), ChatState.inMemory())).send("standard", null, null, null, null);
        assertThat(beta.sent).contains("beta:main standard");
    }

    @Test
    void receiveCollectsFromAllSystemsWithPrefixAndSkipsOwnMessages() {
        FakeProvider alpha = new FakeProvider("alpha");
        FakeProvider beta = new FakeProvider("beta");
        ChatProviders providers = new ChatProviders(List.of(alpha, beta));
        ChatModule module = new ChatModule(providers);
        ChatEnvironment env = new ChatEnvironment(providers, ModuleConfig.of(module.configSchema(),
                Map.of("alpha.enabled", "true", "beta.enabled", "true", "beta.defaultConversation", "main")),
                new ChatEnvironment.State(), ChatState.inMemory());
        ChatTools tools = new ChatTools(env);
        tools.send("Status", null, "beta", null, null); // → beta-m1
        alpha.incoming.add(msg("alpha", "a1", "Hallo von alpha"));
        beta.incoming.add(msg("beta", "beta-m1", "Status")); // eigene Nachricht kommt zurück
        beta.incoming.add(msg("beta", "b2", "Hallo von beta"));

        String out = tools.receive(null, null, 30, null);

        assertThat(out).contains("[alpha] [", "Hallo von alpha", "[beta] [", "Hallo von beta")
                .doesNotContain("Anna <anna> in „beta:main“ (id beta-m1")
                .contains("ALPHA: einmal abgerufen, Warten ist abgeschaltet.");
    }

    @Test
    void systemsSurviveChangesToOtherProviders() {
        FakeProvider alpha = new FakeProvider("alpha");
        FakeProvider beta = new FakeProvider("beta");
        ChatProviders providers = new ChatProviders(List.of(alpha, beta));
        ChatModule module = new ChatModule(providers);
        ChatEnvironment.State state = new ChatEnvironment.State();
        new ChatEnvironment(providers, ModuleConfig.of(module.configSchema(), Map.of("alpha.enabled", "true",
                "beta.enabled", "true", "alpha.token", "1")), state, ChatState.inMemory());
        new ChatEnvironment(providers, ModuleConfig.of(module.configSchema(), Map.of("alpha.enabled", "true",
                "beta.enabled", "true", "alpha.token", "2", "messagePrefix", "🤖")), state, ChatState.inMemory());

        assertThat(alpha.created).isEqualTo(2);
        assertThat(beta.created).isEqualTo(1);
    }
}
