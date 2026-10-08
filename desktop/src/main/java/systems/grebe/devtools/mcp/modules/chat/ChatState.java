package systems.grebe.devtools.mcp.modules.chat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import systems.grebe.devtools.mcp.modules.chat.spi.ChatVault;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import systems.grebe.devtools.mcp.config.AtomicFiles;

/**
 * Dauerhafter Zustand des Chat-Moduls in {@code chat-state.json} im DevTools-Ordner:
 *
 * <ul>
 *   <li>je Konto der letzte Abrufstand und die IDs der zuletzt selbst gesendeten Nachrichten – so liefert
 *   {@code chat_receive} nach einem Neustart genau das, was seitdem einging, und hält eigene Nachrichten nicht für
 *   Anweisungen des Nutzers (bei Teams schreibt das Modul unter dem Konto des Nutzers);</li>
 *   <li>die {@link ChatVault Ablage} der Provider (Refresh-Token u.ä.), verschlüsselt wie die Geheimnisse in
 *   {@code settings.json}.</li>
 * </ul>
 *
 * Ohne Datei (Tests) nur im Speicher.
 */
final class ChatState {

    private static final Logger LOG = LoggerFactory.getLogger(ChatState.class);
    private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
    static final int MAX_SENT = 300;

    private final Path file;
    private final UnaryOperator<String> encrypt;
    private final UnaryOperator<String> decrypt;
    private final Map<String, String> cursors = new LinkedHashMap<>();
    private final Map<String, Deque<String>> sent = new LinkedHashMap<>();
    /** Werte verschlüsselt wie gespeichert. */
    private final Map<String, String> vault = new LinkedHashMap<>();

    ChatState(Path file, UnaryOperator<String> encrypt, UnaryOperator<String> decrypt) {
        this.file = file;
        this.encrypt = encrypt;
        this.decrypt = decrypt;
        load();
    }

    static ChatState inMemory() {
        return new ChatState(null, UnaryOperator.identity(), UnaryOperator.identity());
    }

    // ------------------------------------------------------------------ Abrufstand

    synchronized String cursor(String account) {
        return cursors.get(account);
    }

    synchronized void cursor(String account, String cursor) {
        if (cursor == null || cursor.isBlank() || cursor.equals(cursors.get(account))) {
            return;
        }
        cursors.put(account, cursor);
        save();
    }

    synchronized boolean sent(String account, String messageId) {
        Deque<String> ids = sent.get(account);
        return ids != null && ids.contains(messageId);
    }

    synchronized void addSent(String account, String messageId) {
        Deque<String> ids = sent.computeIfAbsent(account, a -> new ArrayDeque<>());
        if (messageId == null || ids.contains(messageId)) {
            return;
        }
        ids.addLast(messageId);
        while (ids.size() > MAX_SENT) {
            ids.removeFirst();
        }
        save();
    }

    // ------------------------------------------------------------------ Ablage der Provider

    ChatVault vault() {
        return new ChatVault() {
            @Override
            public Optional<String> get(String key) {
                synchronized (ChatState.this) {
                    String stored = vault.get(key);
                    String plain = stored == null ? null : decrypt.apply(stored);
                    return Optional.ofNullable(plain).filter(s -> !s.isEmpty());
                }
            }

            @Override
            public void put(String key, String value) {
                synchronized (ChatState.this) {
                    if (value == null || value.isEmpty()) {
                        if (vault.remove(key) != null) {
                            save();
                        }
                    } else {
                        vault.put(key, encrypt.apply(value));
                        save();
                    }
                }
            }
        };
    }

    // ------------------------------------------------------------------ Datei

    private void load() {
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            JsonNode root = JSON.readTree(Files.readString(file));
            root.path("accounts").properties().forEach(e -> {
                JsonNode a = e.getValue();
                if (a.path("cursor").isString()) {
                    cursors.put(e.getKey(), a.path("cursor").asString());
                }
                Deque<String> ids = new ArrayDeque<>();
                a.path("sent").forEach(n -> ids.add(n.asString()));
                if (!ids.isEmpty()) {
                    sent.put(e.getKey(), ids);
                }
            });
            root.path("vault").properties().forEach(e -> vault.put(e.getKey(), e.getValue().asString()));
        } catch (IOException | RuntimeException e) {
            // ohne Stand beginnt der Eingang neu – was fehlt, steht im Verlauf
            LOG.warn("Chat-Zustand nicht lesbar ({}): {}", file, e.getMessage());
        }
    }

    private void save() {
        if (file == null) {
            return;
        }
        ObjectNode root = JSON.createObjectNode();
        ObjectNode accounts = root.putObject("accounts");
        Set<String> keys = new LinkedHashSet<>(cursors.keySet());
        keys.addAll(sent.keySet());
        for (String key : keys) {
            ObjectNode a = accounts.putObject(key);
            if (cursors.containsKey(key)) {
                a.put("cursor", cursors.get(key));
            }
            ArrayNode ids = a.putArray("sent");
            sent.getOrDefault(key, new ArrayDeque<>()).forEach(ids::add);
        }
        ObjectNode v = root.putObject("vault");
        vault.forEach(v::put);
        try {
            AtomicFiles.writeString(file, JSON.writeValueAsString(root));
        } catch (IOException | RuntimeException e) {
            LOG.warn("Chat-Zustand nicht gespeichert ({}): {}", file, e.getMessage());
        }
    }
}
