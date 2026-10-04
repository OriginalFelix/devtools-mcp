package systems.grebe.devtools.mcp.modules.chat.teams;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import systems.grebe.devtools.mcp.modules.chat.spi.ChatVault;
import tools.jackson.databind.JsonNode;

/**
 * Anmeldung bei Entra ID als öffentlicher Client mit delegierten Berechtigungen: Device-Code-Flow (der Nutzer gibt im
 * Browser einen Code ein), danach Refresh-Token. Das Refresh-Token liegt verschlüsselt in der {@link ChatVault}, das
 * Access-Token nur im Speicher.
 */
final class GraphAuth {

    /** {@code offline_access} für das Refresh-Token, {@code openid} für das ID-Token (Tenant-ID). */
    static final String SCOPES = "openid profile offline_access User.Read Chat.ReadWrite ChatMessage.Send";
    static final String DEVICE_CODE_GRANT = "urn:ietf:params:oauth:grant-type:device_code";
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    private final GraphHttp http;
    private final ChatVault vault;
    private final String authority;
    private final String tenant;
    private final String clientId;
    private final String vaultKey;

    // ------------------------------------------------------------------ geschützt durch this
    private String accessToken;
    private Instant expires = Instant.EPOCH;
    private String tenantId;
    private String pendingPrompt;
    private String lastError;

    GraphAuth(GraphHttp http, ChatVault vault, String authority, String tenant, String clientId) {
        this.http = http;
        this.vault = vault;
        this.authority = strip(authority);
        this.tenant = tenant;
        this.clientId = clientId;
        this.vaultKey = "teams|" + this.authority + "|" + tenant + "|" + clientId;
    }

    private String endpoint(String name) {
        return authority + "/" + GraphHttp.enc(tenant) + "/oauth2/v2.0/" + name;
    }

    /** Gültiges Access-Token; erneuert es bei Bedarf mit dem Refresh-Token. */
    synchronized String accessToken() {
        if (accessToken != null && Instant.now().isBefore(expires.minusSeconds(60))) {
            return accessToken;
        }
        String refresh = vault.get(vaultKey).orElseThrow(() -> new IllegalStateException(
                "Teams: nicht angemeldet – chat_login aufrufen (liefert Adresse und Code für den Nutzer) oder in der "
                        + "DevTools-App unter Module → Chat die Aktion „Anmelden“ ausführen."));
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", clientId);
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", refresh);
        form.put("scope", SCOPES);
        try {
            accept(http.form(endpoint("token"), form));
        } catch (GraphHttp.GraphException e) {
            if ("invalid_grant".equals(e.code()) || "interaction_required".equals(e.code())) {
                vault.put(vaultKey, null);
                throw new IllegalStateException("Teams: Anmeldung abgelaufen oder widerrufen – neu anmelden "
                        + "(chat_login bzw. Aktion „Anmelden“). " + e.getMessage(), e);
            }
            throw new IllegalStateException("Teams: Token nicht erneuerbar: " + e.getMessage(), e);
        }
        return accessToken;
    }

    /** Verwirft das Access-Token (nach 401), damit das nächste {@link #accessToken()} ein neues holt. */
    synchronized void invalidate() {
        accessToken = null;
    }

    /** Tenant-ID aus dem ID-Token oder {@code null}. */
    synchronized String tenantId() {
        return tenantId;
    }

    synchronized boolean loggedIn() {
        return vault.get(vaultKey).isPresent();
    }

    /** Anzeige: laufende Anmeldung, letzter Fehler oder angemeldet/nicht angemeldet. */
    synchronized String status() {
        if (pendingPrompt != null) {
            return "Anmeldung läuft – " + pendingPrompt;
        }
        if (lastError != null && !loggedIn()) {
            return "nicht angemeldet (" + lastError + ")";
        }
        return loggedIn() ? "angemeldet" : "nicht angemeldet";
    }

    /**
     * Device-Code-Flow: Code anfordern, Anweisung über {@code prompt} melden, dann im vorgegebenen Abstand abfragen,
     * bis der Nutzer im Browser bestätigt hat. Blockiert; ein Interrupt bricht ab. Die Sperre wird dabei nicht
     * gehalten – andere Aufrufe laufen weiter.
     */
    void login(Consumer<String> prompt) {
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalStateException("Teams: 'Client-ID' fehlt – App-Registrierung in Entra ID anlegen und die "
                    + "Anwendungs-ID unter Module → Chat → Teams eintragen.");
        }
        JsonNode code;
        try {
            code = http.form(endpoint("devicecode"), Map.of("client_id", clientId, "scope", SCOPES));
        } catch (GraphHttp.GraphException e) {
            // häufig: AADSTS7000218 = „Öffentliche Clientflows zulassen“ ist in der App-Registrierung aus
            throw new IllegalStateException("Teams: Anmeldung konnte nicht starten (" + e.code() + "): " + e.getMessage()
                    + " – Client-ID, Tenant und „Öffentliche Clientflows zulassen“ in der App-Registrierung prüfen.", e);
        }
        String uri = code.path("verification_uri").asString("https://microsoft.com/devicelogin");
        String userCode = code.path("user_code").asString("");
        long expiresIn = code.path("expires_in").asLong(900);
        long interval = Math.max(1, code.path("interval").asLong(5));
        Instant deadline = Instant.now().plusSeconds(expiresIn);
        String text = "Im Browser " + uri + " öffnen und den Code " + userCode + " eingeben (gültig bis "
                + LocalTime.ofInstant(deadline, ZoneId.systemDefault()).format(HHMM) + ").";
        synchronized (this) {
            pendingPrompt = text;
            lastError = null;
        }
        prompt.accept(text);
        Map<String, String> form = Map.of("client_id", clientId, "grant_type", DEVICE_CODE_GRANT,
                "device_code", code.path("device_code").asString(""));
        try {
            while (true) {
                GraphHttp.sleep(interval * 1000);
                if (Instant.now().isAfter(deadline)) {
                    throw new IllegalStateException("Teams: Code abgelaufen – Anmeldung neu starten.");
                }
                try {
                    JsonNode token = http.form(endpoint("token"), form);
                    synchronized (this) {
                        accept(token);
                    }
                    return;
                } catch (GraphHttp.GraphException e) {
                    switch (e.code()) {
                        case "authorization_pending" -> { }
                        case "slow_down" -> interval += 5; // RFC 8628
                        case "authorization_declined" -> throw new IllegalStateException("Teams: Anmeldung im Browser "
                                + "abgelehnt.", e);
                        case "expired_token", "bad_verification_code" -> throw new IllegalStateException("Teams: Code "
                                + "abgelaufen oder ungültig – Anmeldung neu starten.", e);
                        default -> throw new IllegalStateException("Teams: Anmeldung fehlgeschlagen: " + e.getMessage(), e);
                    }
                }
            }
        } catch (RuntimeException e) {
            synchronized (this) {
                lastError = e.getMessage();
            }
            throw e;
        } finally {
            synchronized (this) {
                pendingPrompt = null;
            }
        }
    }

    /** Übernimmt eine Token-Antwort; nur unter der Sperre. */
    private void accept(JsonNode token) {
        accessToken = token.path("access_token").asString(null);
        if (accessToken == null) {
            throw new IllegalStateException("Teams: Token-Antwort ohne access_token.");
        }
        expires = Instant.now().plusSeconds(token.path("expires_in").asLong(3600));
        String refresh = token.path("refresh_token").asString(null);
        if (refresh != null) {
            vault.put(vaultKey, refresh); // Refresh-Tokens rotieren: immer das neueste merken
        }
        String tid = claim(token.path("id_token").asString(null), "tid");
        if (tid != null) {
            tenantId = tid;
        }
        lastError = null;
    }

    /** Claim aus dem Payload eines JWT (ohne Signaturprüfung – nur für die Tenant-ID aus dem eigenen ID-Token). */
    static String claim(String jwt, String name) {
        if (jwt == null) {
            return null;
        }
        String[] parts = jwt.split("\\.");
        if (parts.length < 2) {
            return null;
        }
        try {
            JsonNode payload = GraphHttp.JSON.readTree(new String(Base64.getUrlDecoder().decode(parts[1]),
                    StandardCharsets.UTF_8));
            return payload.path(name).asString(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String strip(String url) {
        String u = url.strip();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }
}
