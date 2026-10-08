package systems.grebe.devtools.mcp.modules.chat;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.modules.chat.spi.ChatProvider;
import systems.grebe.devtools.mcp.modules.chat.spi.ChatSettings;
import systems.grebe.devtools.mcp.modules.chat.spi.ChatSystem;
import systems.grebe.devtools.mcp.api.Sha256;

/**
 * Ausgewertete Konfiguration des Chat-Moduls: aktive Systeme mit Standard-Unterhaltung, Auswahl von System und
 * Unterhaltung je Aufruf, gemeinsame Einstellungen.
 *
 * <p>Chat-System und Eingang je Provider liegen im {@link State} des Scopes und überleben Konfigurationsänderungen,
 * solange sich die Einstellungen dieses Providers nicht ändern – sonst gingen Anmeldung und ungelesene Nachrichten bei
 * jeder Änderung an einem anderen Provider verloren.
 */
final class ChatEnvironment {

    /** Ein aktives System. */
    record Entry(ChatProvider provider, Holder holder, String defaultConversation, ChatState chatState) {

        ChatSystem system() {
            return holder.system;
        }

        /** Eingang des Kontos; beim ersten Zugriff wird das Konto ermittelt (ggf. Anmeldung). */
        ChatInbox inbox() {
            return holder.inbox(chatState);
        }

        /** Eingang, falls schon angelegt – ohne Netzwerkzugriff. */
        ChatInbox inboxIfOpen() {
            return holder.inboxIfOpen();
        }

        String id() {
            return provider.id();
        }
    }

    /** Unterhaltung eines Systems für einen Aufruf. */
    record Target(Entry entry, String conversationId) {
    }

    /** System und Eingang eines Providers mit dem Stand der Einstellungen, für die sie erzeugt wurden. */
    static final class Holder {
        private final String fingerprint;
        private final ChatSystem system;
        private ChatInbox inbox;

        Holder(String fingerprint, ChatSystem system) {
            this.fingerprint = fingerprint;
            this.system = system;
        }

        synchronized ChatInbox inbox(ChatState state) {
            if (inbox == null) {
                ChatSystem.Account account = system.account();
                inbox = new ChatInbox(system, system.id() + "|" + account.key(), state);
            }
            return inbox;
        }

        synchronized ChatInbox inboxIfOpen() {
            return inbox;
        }
    }

    /** Zustand je {@code ToolScope}: je Provider das System zum aktuellen Stand seiner Einstellungen. */
    static final class State {
        private final Map<String, Holder> holders = new HashMap<>();

        synchronized Holder holder(String providerId, String fingerprint, Supplier<ChatSystem> factory) {
            Holder h = holders.get(providerId);
            if (h == null || !h.fingerprint.equals(fingerprint)) {
                h = new Holder(fingerprint, factory.get());
                holders.put(providerId, h);
            }
            return h;
        }
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final ModuleConfig config;
    private final String defaultProvider;

    ChatEnvironment(ChatProviders providers, ModuleConfig config, State state, ChatState chatState) {
        this.config = config;
        this.defaultProvider = config.getString(ChatModule.DEFAULT_PROVIDER, "auto");
        Duration timeout = Duration.ofSeconds(Math.max(5, config.getInt(ChatModule.TIMEOUT, 30)));
        for (ChatProvider p : providers.providers()) {
            if (!config.getBoolean(ChatModule.enabledKey(p.id()))) {
                continue;
            }
            ChatSettings settings = new ChatSettings(k -> config.get(ChatModule.key(p.id(), k)), timeout, chatState.vault());
            Holder holder = state.holder(p.id(), fingerprint(p, config, timeout), () -> p.create(settings));
            entries.put(p.id(), new Entry(p, holder,
                    config.get(ChatModule.key(p.id(), ChatModule.DEFAULT_CONVERSATION)).orElse(null), chatState));
        }
    }

    /** Hash über alle Einstellungen des Providers – ändert sich einer, wird das System neu erzeugt. */
    private static String fingerprint(ChatProvider p, ModuleConfig config, Duration timeout) {
        StringBuilder sb = new StringBuilder(timeout.toString());
        for (ConfigField f : p.configFields()) {
            sb.append('\n').append(f.key()).append('=').append(config.getString(ChatModule.key(p.id(), f.key()), ""));
        }
        return Sha256.hex(sb.toString());
    }

    List<Entry> entries() {
        return List.copyOf(entries.values());
    }

    boolean multiple() {
        return entries.size() > 1;
    }

    // ------------------------------------------------------------------ Auswahl

    /**
     * Wählt das System: ausdrücklich angegeben → das; sonst das, dem die Nachricht {@code messageHint} bzw. die
     * Unterhaltung gehört; sonst das Standard-System; sonst das einzige aktive.
     */
    Entry entry(String provider, String conversation, String messageHint) {
        if (entries.isEmpty()) {
            throw new IllegalStateException("Kein Chat-System aktiviert – in der DevTools-App unter Module → Chat z.B. "
                    + "'Matrix: aktiv' einschalten und Zugangsdaten eintragen.");
        }
        if (provider != null && !provider.isBlank()) {
            Entry e = entries.get(provider.strip().toLowerCase(Locale.ROOT));
            if (e == null) {
                throw new IllegalArgumentException("Chat-System '" + provider + "' ist nicht aktiviert. Aktiv: "
                        + entries.keySet() + ".");
            }
            return e;
        }
        if (messageHint != null && !messageHint.isBlank()) {
            String hint = messageHint.strip();
            List<Entry> owners = entries.values().stream().filter(e -> knows(e, hint)).toList();
            if (owners.size() == 1) {
                return owners.getFirst();
            }
        }
        if (conversation != null && !conversation.isBlank()) {
            String ref = conversation.strip();
            List<Entry> owners = entries.values().stream().filter(e -> owns(e, ref)).toList();
            if (owners.size() == 1) {
                return owners.getFirst();
            }
        }
        if (!"auto".equals(defaultProvider) && entries.containsKey(defaultProvider)) {
            return entries.get(defaultProvider);
        }
        if (entries.size() == 1) {
            return entries.values().iterator().next();
        }
        throw new IllegalArgumentException("Mehrere Chat-Systeme aktiv " + entries.keySet()
                + " – 'provider' angeben (oder in der App ein Standard-System wählen).");
    }

    private static boolean knows(Entry e, String messageId) {
        ChatInbox inbox = e.inboxIfOpen();
        if (inbox != null && inbox.conversationOf(messageId) != null) {
            return true;
        }
        try {
            return e.system().ownsMessage(messageId);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static boolean owns(Entry e, String ref) {
        try {
            return e.system().ownsConversation(ref);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /**
     * System und Unterhaltung: angegeben → die; sonst die Unterhaltung der Nachricht {@code messageHint}; sonst die
     * Standard-Unterhaltung; sonst die einzige freigegebene.
     */
    Target target(String provider, String conversation, String messageHint) {
        Entry e = entry(provider, conversation, messageHint);
        ChatSystem system = e.system();
        if (conversation != null && !conversation.isBlank()) {
            return new Target(e, system.resolve(conversation.strip()));
        }
        if (messageHint != null && !messageHint.isBlank()) {
            String known = e.inbox().conversationOf(messageHint.strip());
            if (known != null) {
                return new Target(e, known);
            }
        }
        if (e.defaultConversation() != null) {
            return new Target(e, system.resolve(e.defaultConversation()));
        }
        List<ChatSystem.Conversation> all = system.conversations();
        if (all.size() == 1) {
            return new Target(e, all.getFirst().id());
        }
        throw new IllegalArgumentException("Keine Unterhaltung angegeben und keine Standard-Unterhaltung für "
                + e.provider().displayName() + " konfiguriert – 'conversation' angeben (" + e.provider().conversationHelp()
                + "; siehe chat_conversations).");
    }

    // ------------------------------------------------------------------ Einstellungen

    String prefix() {
        return config.getString(ChatModule.PREFIX, "");
    }

    boolean readReceipts() {
        return config.getBoolean(ChatModule.READ_RECEIPTS);
    }

    /** Wartezeit in Sekunden: angegeben oder Standard, höchstens das konfigurierte Maximum. */
    int waitSeconds(Integer given, int fallback) {
        int max = Math.max(1, config.getInt(ChatModule.MAX_WAIT, 900));
        int value = given == null ? fallback : given;
        return Math.max(0, Math.min(max, value));
    }

    int askWaitSeconds() {
        return config.getInt(ChatModule.ASK_WAIT, 300);
    }

    int maxLines() {
        return Math.max(50, config.getInt(ChatModule.MAX_LINES, 400));
    }
}
