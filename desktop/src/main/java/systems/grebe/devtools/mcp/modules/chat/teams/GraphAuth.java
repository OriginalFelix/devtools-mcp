package systems.grebe.devtools.mcp.modules.chat.teams;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import systems.grebe.devtools.mcp.modules.chat.spi.ChatVault;
import tools.jackson.databind.JsonNode;
import systems.grebe.devtools.mcp.core.EntraDeviceLogin;

/**
 * Anmeldung bei Entra ID als öffentlicher Client mit delegierten Berechtigungen: Device-Code-Flow (der Nutzer gibt im
 * Browser einen Code ein), danach Refresh-Token. Das Refresh-Token liegt verschlüsselt in der {@link ChatVault}, das
 * Access-Token nur im Speicher.
 */
final class GraphAuth {

    /** {@code offline_access} für das Refresh-Token, {@code openid} für das ID-Token (Tenant-ID). */
    static final String SCOPES = "openid profile offline_access User.Read Chat.ReadWrite ChatMessage.Send";

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
    /** Die laufende Anmeldung (Device-Code-Flow), solange {@link #pendingPrompt} gilt; sonst {@code null}. */
    private CompletableFuture<Void> running;

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
     * gehalten – andere Aufrufe laufen weiter. Läuft schon eine Anmeldung, schließt sich ein weiterer Aufruf ihr an
     * (gleiche Adresse und gleicher Code, kein zweiter Abfrage-Zyklus) und endet mit ihr.
     */
    void login(Consumer<String> prompt) {
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalStateException("Teams: 'Client-ID' fehlt – App-Registrierung in Entra ID anlegen und die "
                    + "Anwendungs-ID unter Module → Chat → Teams eintragen.");
        }
        CompletableFuture<Void> existing;
        String existingPrompt;
        synchronized (this) {
            existing = running != null && !running.isDone() && pendingPrompt != null ? running : null;
            existingPrompt = pendingPrompt;
        }
        if (existing != null) {
            prompt.accept(existingPrompt);
            awaitFlow(existing);
            return;
        }
        EntraDeviceLogin.Started flow = EntraDeviceLogin.start(http::form, endpoint("devicecode"), clientId, SCOPES,
                "Teams: ", "");
        CompletableFuture<Void> mine = new CompletableFuture<>();
        synchronized (this) {
            pendingPrompt = flow.text();
            lastError = null;
            running = mine;
        }
        RuntimeException failure = null;
        try {
            prompt.accept(flow.text());
            JsonNode token = flow.awaitToken(http::form, endpoint("token"), "Teams: ");
            synchronized (this) {
                accept(token);
            }
        } catch (RuntimeException e) {
            failure = e;
            synchronized (this) {
                lastError = e.getMessage();
            }
            throw e;
        } finally {
            synchronized (this) {
                if (running == mine) { // eine neuere Anmeldung hat den Zustand ggf. schon übernommen
                    running = null;
                    pendingPrompt = null;
                }
            }
            if (failure == null) {
                mine.complete(null);
            } else {
                mine.completeExceptionally(failure);
            }
        }
    }

    /** Wartet auf eine laufende Anmeldung eines anderen Aufrufs und übernimmt ihr Ergebnis. */
    private static void awaitFlow(CompletableFuture<Void> flow) {
        try {
            flow.get();
        } catch (ExecutionException e) {
            throw e.getCause() instanceof RuntimeException r ? r : new IllegalStateException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Abgebrochen", e);
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
