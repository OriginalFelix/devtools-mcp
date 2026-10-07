package systems.grebe.devtools.mcp.modules.mail;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.config.SettingsStore;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Anmeldung von Exchange-Online-Konten (Microsoft 365) für IMAP per OAuth2: Entra ID als öffentlicher Client mit der
 * delegierten Berechtigung {@code IMAP.AccessAsUser.All}, Device-Code-Flow (der Nutzer gibt im Browser einen Code ein),
 * danach Refresh-Token. Die Refresh-Tokens liegen verschlüsselt wie die Geheimnisse in {@code settings.json} in
 * {@code mail-tokens.json}, Access-Tokens nur im Speicher. IMAP meldet sich damit per SASL {@code XOAUTH2} an.
 *
 * <p>Microsoft erlaubt für Exchange Online keine IMAP-Anmeldung mit Passwort mehr.
 */
@Component
public class MailOAuth {

    private static final Logger LOG = LoggerFactory.getLogger(MailOAuth.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** {@code offline_access} für das Refresh-Token; die Zielgruppe ist Exchange Online, nicht Graph. */
    static final String SCOPES = "offline_access https://outlook.office.com/IMAP.AccessAsUser.All";
    static final String DEVICE_CODE_GRANT = "urn:ietf:params:oauth:grant-type:device_code";
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    /** Nicht angemeldet – die Überwachung wartet dann auf die Anmeldung statt wiederholt zu versuchen. */
    static final class NotLoggedInException extends IllegalStateException {
        NotLoggedInException(String message) {
            super(message);
        }
    }

    /** Fehlerantwort des Token-Endpunkts. */
    static final class OAuthException extends IllegalStateException {
        final String code;

        OAuthException(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    private static final class Session {
        String accessToken;
        Instant expires = Instant.EPOCH;
        String pendingPrompt;
        String lastError;
    }

    private final Path file;
    private final UnaryOperator<String> encrypt;
    private final UnaryOperator<String> decrypt;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    /** Refresh-Tokens, verschlüsselt wie gespeichert. */
    private final Map<String, String> refreshTokens = new LinkedHashMap<>();
    private final Map<String, Session> sessions = new HashMap<>();
    private final List<Consumer<String>> loginListeners = new CopyOnWriteArrayList<>();

    @Autowired
    public MailOAuth(SettingsStore store) {
        this(store.file().toAbsolutePath().getParent().resolve("mail-tokens.json"), store::encrypt, store::decrypt);
    }

    MailOAuth(Path file, UnaryOperator<String> encrypt, UnaryOperator<String> decrypt) {
        this.file = file;
        this.encrypt = encrypt;
        this.decrypt = decrypt;
        load();
    }

    /** Nur im Speicher (Tests). */
    static MailOAuth inMemory() {
        return new MailOAuth(null, UnaryOperator.identity(), UnaryOperator.identity());
    }

    /** Schlüssel einer Anmeldung: Endpunkt, Tenant, App und Postfach. */
    static String key(MailAccount a) {
        return a.authority() + "|" + a.tenant() + "|" + a.clientId() + "|" + a.username().toLowerCase(java.util.Locale.ROOT);
    }

    /** Wird nach jeder erfolgreichen Anmeldung mit dem Kontonamen aufgerufen. */
    void addLoginListener(Consumer<String> listener) {
        loginListeners.add(listener);
    }

    // ------------------------------------------------------------------ Token

    /** Gültiges Access-Token; erneuert es bei Bedarf mit dem Refresh-Token. */
    String accessToken(MailAccount a) {
        String key = key(a);
        String refresh;
        synchronized (this) {
            Session s = session(key);
            if (s.accessToken != null && Instant.now().isBefore(s.expires.minusSeconds(60))) {
                return s.accessToken;
            }
            String stored = refreshTokens.get(key);
            refresh = stored == null ? null : decrypt.apply(stored);
        }
        if (refresh == null || refresh.isEmpty()) {
            throw new NotLoggedInException("Konto '" + a.name() + "' (Exchange Online): nicht angemeldet – mail_login "
                    + "aufrufen (liefert Adresse und Code für den Nutzer) oder in der DevTools-App unter Module → Mail "
                    + "die Aktion „Anmelden“ ausführen.");
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", a.clientId());
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", refresh);
        form.put("scope", SCOPES);
        try {
            JsonNode token = post(endpoint(a, "token"), form);
            synchronized (this) {
                return accept(key, token);
            }
        } catch (OAuthException e) {
            if ("invalid_grant".equals(e.code) || "interaction_required".equals(e.code)) {
                store(key, null);
                throw new NotLoggedInException("Konto '" + a.name() + "': Anmeldung abgelaufen oder widerrufen – neu "
                        + "anmelden (mail_login bzw. Aktion „Anmelden“). " + e.getMessage());
            }
            throw new IllegalStateException("Konto '" + a.name() + "': Token nicht erneuerbar: " + e.getMessage(), e);
        }
    }

    /** Verwirft das Access-Token (nach abgelehnter Anmeldung), damit das nächste ein neues holt. */
    synchronized void invalidate(MailAccount a) {
        session(key(a)).accessToken = null;
    }

    synchronized boolean loggedIn(MailAccount a) {
        return refreshTokens.containsKey(key(a));
    }

    /** Anzeige: laufende Anmeldung, letzter Fehler oder angemeldet/nicht angemeldet. */
    synchronized String status(MailAccount a) {
        Session s = session(key(a));
        if (s.pendingPrompt != null) {
            return "Anmeldung läuft – " + s.pendingPrompt;
        }
        if (s.lastError != null && !loggedIn(a)) {
            return "nicht angemeldet (" + s.lastError + ")";
        }
        return loggedIn(a) ? "angemeldet" : "nicht angemeldet";
    }

    /** Meldet das Konto ab (Refresh-Token verwerfen). */
    synchronized void logout(MailAccount a) {
        String key = key(a);
        sessions.remove(key);
        store(key, null);
    }

    // ------------------------------------------------------------------ Device Code

    /**
     * Device-Code-Flow: Code anfordern, Anweisung über {@code prompt} melden, dann im vorgegebenen Abstand abfragen,
     * bis der Nutzer im Browser bestätigt hat. Blockiert; ein Interrupt bricht ab.
     *
     * @return Ergebnis als Text
     */
    String login(MailAccount a, Consumer<String> prompt) {
        if (!a.microsoft()) {
            throw new IllegalArgumentException("Konto '" + a.name() + "' meldet sich mit Passwort an – keine Anmeldung "
                    + "im Browser nötig.");
        }
        if (a.clientId().isBlank()) {
            throw new IllegalStateException("Konto '" + a.name() + "': 'Client-ID' fehlt – App-Registrierung in Entra ID "
                    + "anlegen (siehe README) und die Anwendungs-ID beim Konto eintragen.");
        }
        String key = key(a);
        JsonNode code;
        try {
            code = post(endpoint(a, "devicecode"), Map.of("client_id", a.clientId(), "scope", SCOPES));
        } catch (OAuthException e) {
            // häufig: AADSTS7000218 = „Öffentliche Clientflows zulassen“ ist in der App-Registrierung aus
            throw new IllegalStateException("Anmeldung konnte nicht starten (" + e.code + "): " + e.getMessage()
                    + " – Client-ID, Tenant und „Öffentliche Clientflows zulassen“ in der App-Registrierung prüfen.", e);
        }
        String uri = code.path("verification_uri").asString("https://microsoft.com/devicelogin");
        String userCode = code.path("user_code").asString("");
        long expiresIn = code.path("expires_in").asLong(900);
        long interval = Math.max(1, code.path("interval").asLong(5));
        Instant deadline = Instant.now().plusSeconds(expiresIn);
        String text = "Im Browser " + uri + " öffnen und den Code " + userCode + " eingeben (gültig bis "
                + LocalTime.ofInstant(deadline, ZoneId.systemDefault()).format(HHMM) + "), mit dem Konto "
                + a.username() + " anmelden.";
        synchronized (this) {
            Session s = session(key);
            s.pendingPrompt = text;
            s.lastError = null;
        }
        prompt.accept(text);
        Map<String, String> form = Map.of("client_id", a.clientId(), "grant_type", DEVICE_CODE_GRANT,
                "device_code", code.path("device_code").asString(""));
        try {
            while (true) {
                try {
                    Thread.sleep(interval * 1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Anmeldung abgebrochen.", e);
                }
                if (Instant.now().isAfter(deadline)) {
                    throw new IllegalStateException("Code abgelaufen – Anmeldung neu starten.");
                }
                try {
                    JsonNode token = post(endpoint(a, "token"), form);
                    synchronized (this) {
                        accept(key, token);
                    }
                    break;
                } catch (OAuthException e) {
                    switch (e.code) {
                        case "authorization_pending" -> { }
                        case "slow_down" -> interval += 5; // RFC 8628
                        case "authorization_declined" -> throw new IllegalStateException("Anmeldung im Browser "
                                + "abgelehnt.", e);
                        case "expired_token", "bad_verification_code" -> throw new IllegalStateException("Code "
                                + "abgelaufen oder ungültig – Anmeldung neu starten.", e);
                        default -> throw new IllegalStateException("Anmeldung fehlgeschlagen: " + e.getMessage(), e);
                    }
                }
            }
        } catch (RuntimeException e) {
            synchronized (this) {
                session(key).lastError = e.getMessage();
            }
            throw e;
        } finally {
            synchronized (this) {
                session(key).pendingPrompt = null;
            }
        }
        LOG.info("Mail-Konto {}: bei Microsoft angemeldet", a.name());
        for (Consumer<String> l : loginListeners) {
            try {
                l.accept(a.name());
            } catch (RuntimeException e) {
                LOG.debug("Anmelde-Listener: {}", e.toString());
            }
        }
        return "Konto " + a.name() + " (" + a.username() + ") ist angemeldet.";
    }

    // ------------------------------------------------------------------ intern

    private Session session(String key) {
        return sessions.computeIfAbsent(key, k -> new Session());
    }

    private static String endpoint(MailAccount a, String name) {
        return a.authority() + "/" + URLEncoder.encode(a.tenant(), StandardCharsets.UTF_8) + "/oauth2/v2.0/" + name;
    }

    /** Übernimmt eine Token-Antwort; nur unter der Sperre. */
    private String accept(String key, JsonNode token) {
        String access = token.path("access_token").asString(null);
        if (access == null) {
            throw new IllegalStateException("Token-Antwort ohne access_token.");
        }
        Session s = session(key);
        s.accessToken = access;
        s.expires = Instant.now().plusSeconds(token.path("expires_in").asLong(3600));
        s.lastError = null;
        String refresh = token.path("refresh_token").asString(null);
        if (refresh != null) {
            store(key, refresh); // Refresh-Tokens rotieren: immer das neueste merken
        }
        return access;
    }

    private JsonNode post(String url, Map<String, String> fields) {
        String body = fields.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json").header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        HttpResponse<String> res;
        try {
            res = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("Anmeldedienst nicht erreichbar (" + url + "): " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Abgebrochen.", e);
        }
        JsonNode json;
        try {
            json = JSON.readTree(res.body());
        } catch (RuntimeException e) {
            throw new IllegalStateException("Antwort des Anmeldedienstes nicht lesbar (HTTP " + res.statusCode() + ").");
        }
        if (res.statusCode() >= 400) {
            String desc = json.path("error_description").asString("HTTP " + res.statusCode());
            // Entra-Beschreibungen tragen Zeitstempel und Trace-IDs in weiteren Zeilen
            throw new OAuthException(json.path("error").asString(""), desc.lines().findFirst().orElse(desc));
        }
        return json;
    }

    private synchronized void store(String key, String refreshToken) {
        if (refreshToken == null || refreshToken.isEmpty()) {
            if (refreshTokens.remove(key) == null) {
                return;
            }
        } else {
            refreshTokens.put(key, encrypt.apply(refreshToken));
        }
        save();
    }

    private void load() {
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            JSON.readTree(Files.readString(file)).path("refreshTokens").properties()
                    .forEach(e -> refreshTokens.put(e.getKey(), e.getValue().asString()));
        } catch (IOException | RuntimeException e) {
            LOG.warn("Mail-Anmeldungen nicht lesbar ({}): {}", file, e.getMessage());
        }
    }

    private void save() {
        if (file == null) {
            return;
        }
        ObjectNode root = JSON.createObjectNode();
        ObjectNode tokens = root.putObject("refreshTokens");
        refreshTokens.forEach(tokens::put);
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, JSON.writeValueAsString(root));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            LOG.warn("Mail-Anmeldungen nicht gespeichert ({}): {}", file, e.getMessage());
        }
    }
}
