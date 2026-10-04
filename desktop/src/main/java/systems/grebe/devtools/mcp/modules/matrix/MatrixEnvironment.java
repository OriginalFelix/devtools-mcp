package systems.grebe.devtools.mcp.modules.matrix;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import systems.grebe.devtools.mcp.core.ModuleConfig;
import tools.jackson.databind.JsonNode;

/**
 * Ausgewertete Konfiguration des Matrix-Moduls: Anmeldung, freigegebene Räume und Absender, Auswahl des Raums je
 * Aufruf. Die Sitzung (Client, eigener Benutzer, Eingang) liegt im {@link State} des Scopes und überlebt so
 * Konfigurationsänderungen, solange Homeserver und Zugangsdaten gleich bleiben.
 */
final class MatrixEnvironment {

    /** Sitzung eines Kontos. */
    record Session(MatrixClient client, String userId, MatrixInbox inbox, Map<String, String> aliases) {
    }

    /** Zustand je {@code ToolScope}: höchstens eine Sitzung, ersetzt bei geänderten Zugangsdaten. */
    static final class State {
        private String key;
        private Session session;

        synchronized Session session(String key, Supplier<Session> factory) {
            if (session == null || !key.equals(this.key)) {
                session = factory.get(); // wirft bei Fehlern – dann bleibt die bisherige Sitzung gemerkt
                this.key = key;
            }
            return session;
        }
    }

    private final ModuleConfig config;
    private final State state;
    private final MatrixSyncTokens tokens;
    private final List<String> rooms;
    private final List<String> trusted;
    private final boolean autoJoin;

    MatrixEnvironment(ModuleConfig config, State state, MatrixSyncTokens tokens) {
        this.config = config;
        this.state = state;
        this.tokens = tokens;
        this.rooms = config.getList(MatrixModule.ROOMS);
        this.trusted = config.getList(MatrixModule.TRUSTED).stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
        this.autoJoin = config.getBoolean(MatrixModule.AUTO_JOIN);
    }

    // ------------------------------------------------------------------ Sitzung

    Session session() {
        String homeserver = config.require(MatrixModule.HOMESERVER);
        String token = config.getString(MatrixModule.TOKEN, "");
        String user = config.getString(MatrixModule.USER, "");
        String password = config.getString(MatrixModule.PASSWORD, "");
        if (token.isEmpty() && (user.isEmpty() || password.isEmpty())) {
            throw new IllegalStateException("Matrix: weder Zugangstoken noch Benutzer und Passwort konfiguriert – in der "
                    + "DevTools-App unter Module → Matrix eintragen.");
        }
        String key = homeserver + "|" + hash(token) + "|" + user + "|" + hash(password);
        return state.session(key, () -> {
            MatrixClient client = client(config);
            String userId = client.whoami().path("user_id").asString("");
            if (userId.isEmpty()) {
                throw new IllegalStateException("Matrix: Homeserver nennt keinen Benutzer zum Token (whoami).");
            }
            String account = client.baseUrl() + "|" + userId;
            return new Session(client, userId, new MatrixInbox(client, userId, account, tokens), new ConcurrentHashMap<>());
        });
    }

    static MatrixClient client(ModuleConfig config) {
        return new MatrixClient(config.require(MatrixModule.HOMESERVER), config.getString(MatrixModule.TOKEN, ""),
                config.getString(MatrixModule.USER, ""), config.getString(MatrixModule.PASSWORD, ""),
                Duration.ofSeconds(Math.max(5, config.getInt(MatrixModule.TIMEOUT, 30))));
    }

    MatrixInbox inbox() {
        return session().inbox();
    }

    MatrixClient client() {
        return session().client();
    }

    // ------------------------------------------------------------------ Freigaben

    /**
     * Freigaben für den Eingang, Aliase der Raumliste bereits aufgelöst. Ungültige Einträge werden hier gemeldet (nicht
     * beim Erzeugen der Tools – das darf nicht werfen).
     */
    MatrixInbox.Policy policy() {
        for (String t : trusted) {
            if (!t.startsWith("@") || !t.contains(":")) {
                throw new IllegalStateException("Matrix: '" + t + "' in 'Freigegebene Absender' ist keine Matrix-ID "
                        + "(Form @benutzer:server).");
            }
        }
        Set<String> allowed = allowedRooms();
        Set<String> senders = Set.copyOf(trusted);
        return new MatrixInbox.Policy() {
            @Override
            public boolean roomAllowed(String roomId) {
                return allowed.isEmpty() || allowed.contains(roomId);
            }

            @Override
            public boolean trusted(String sender) {
                return senders.isEmpty() || senders.contains(sender.toLowerCase(Locale.ROOT));
            }

            @Override
            public boolean autoJoin(String inviter, String roomId) {
                // nie ohne Absenderliste: sonst könnte jeder den Bot in einen Raum holen und dort Anweisungen geben
                return autoJoin && !senders.isEmpty() && senders.contains(inviter.toLowerCase(Locale.ROOT))
                        && roomAllowed(roomId);
            }
        };
    }

    /** IDs der freigegebenen Räume; leer = alle Räume, in denen das Konto Mitglied ist. */
    Set<String> allowedRooms() {
        Set<String> out = new LinkedHashSet<>();
        for (String r : rooms) {
            if (!r.startsWith("!") && !r.startsWith("#")) {
                throw new IllegalStateException("Matrix: '" + r + "' in 'Nur diese Räume' ist weder Raum-ID (!…:server) "
                        + "noch Alias (#…:server).");
            }
            out.add(r.startsWith("!") ? r : alias(r));
        }
        return out;
    }

    boolean hasTrustedSenders() {
        return !trusted.isEmpty();
    }

    List<String> trustedSenders() {
        return trusted;
    }

    // ------------------------------------------------------------------ Raumauswahl

    /**
     * Raum-ID für einen Aufruf: angegeben (ID, Alias oder Name) → der; sonst der Raum des Ereignisses
     * {@code eventHint}; sonst der Standardraum; sonst der einzige freigegebene bzw. beigetretene Raum.
     */
    String room(String given, String eventHint) {
        Set<String> allowed = allowedRooms();
        String roomId;
        if (given != null && !given.isBlank()) {
            roomId = resolve(given.strip());
        } else if (eventHint != null && !eventHint.isBlank() && inbox().roomOf(eventHint.strip()) != null) {
            roomId = inbox().roomOf(eventHint.strip());
        } else if (config.get(MatrixModule.DEFAULT_ROOM).isPresent()) {
            roomId = resolve(config.get(MatrixModule.DEFAULT_ROOM).get());
        } else if (allowed.size() == 1) {
            roomId = allowed.iterator().next();
        } else {
            List<String> joined = client().joinedRooms().stream()
                    .filter(r -> allowed.isEmpty() || allowed.contains(r)).toList();
            if (joined.size() != 1) {
                throw new IllegalArgumentException("Kein Raum angegeben und kein Standardraum konfiguriert – 'room' "
                        + "angeben (ID, #alias oder Name; siehe matrix_rooms).");
            }
            roomId = joined.getFirst();
        }
        if (!allowed.isEmpty() && !allowed.contains(roomId)) {
            throw new IllegalStateException("Raum " + roomId + " ist nicht freigegeben. Der Nutzer kann ihn in der "
                    + "DevTools-App unter Module → Matrix → 'Nur diese Räume' ergänzen.");
        }
        return roomId;
    }

    /** ID, Alias oder Name eines beigetretenen Raums → Raum-ID. */
    String resolve(String ref) {
        if (ref.startsWith("!")) {
            return ref;
        }
        if (ref.startsWith("#")) {
            return alias(ref);
        }
        MatrixClient client = client();
        MatrixInbox inbox = inbox();
        List<String> matches = new ArrayList<>();
        for (String roomId : client.joinedRooms()) {
            String name = inbox.knownNames().get(roomId);
            if (name == null) {
                name = roomName(roomId);
            }
            if (name != null && name.equalsIgnoreCase(ref)) {
                matches.add(roomId);
            }
        }
        if (matches.size() == 1) {
            return matches.getFirst();
        }
        throw new IllegalArgumentException(matches.isEmpty()
                ? "Kein beigetretener Raum heißt '" + ref + "' – Raum-ID oder #alias angeben (siehe matrix_rooms)."
                : "Mehrere Räume heißen '" + ref + "': " + matches + " – Raum-ID angeben.");
    }

    private String alias(String alias) {
        Session s = session();
        return s.aliases().computeIfAbsent(alias.toLowerCase(Locale.ROOT), a -> {
            try {
                return s.client().resolveAlias(alias);
            } catch (MatrixClient.MatrixException e) {
                if (e.notFound()) {
                    throw new IllegalArgumentException("Matrix: Raum-Alias " + alias + " existiert nicht.", e);
                }
                throw e;
            }
        });
    }

    /** Name eines Raums aus dem Raumzustand (merkt ihn sich im Eingang); {@code null}, wenn keiner gesetzt ist. */
    String roomName(String roomId) {
        MatrixClient client = client();
        JsonNode name = client.state(roomId, "m.room.name");
        JsonNode alias = client.state(roomId, "m.room.canonical_alias");
        boolean encrypted = client.state(roomId, "m.room.encryption") != null;
        String n = name == null ? null : name.path("name").asString(null);
        inbox().remember(roomId, n, alias == null ? null : alias.path("alias").asString(null), encrypted);
        return n;
    }

    // ------------------------------------------------------------------ Einstellungen

    String defaultRoom() {
        return config.getString(MatrixModule.DEFAULT_ROOM, null);
    }

    String prefix() {
        return config.getString(MatrixModule.PREFIX, "");
    }

    boolean readReceipts() {
        return config.getBoolean(MatrixModule.READ_RECEIPTS);
    }

    /** Wartezeit in Sekunden: angegeben oder Standard, höchstens das konfigurierte Maximum. */
    int waitSeconds(Integer given, int fallback) {
        int max = Math.max(1, config.getInt(MatrixModule.MAX_WAIT, 900));
        int value = given == null ? fallback : given;
        return Math.max(0, Math.min(max, value));
    }

    int askWaitSeconds() {
        return config.getInt(MatrixModule.ASK_WAIT, 300);
    }

    int maxLines() {
        return Math.max(50, config.getInt(MatrixModule.MAX_LINES, 400));
    }

    private static String hash(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
