package systems.grebe.devtools.mcp.modules.matrix;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Minimaler Client für die Matrix Client-Server-API ({@code /_matrix/client/v3}): Anmelden, {@code /sync},
 * Nachrichten senden, Raumzustand und Verlauf lesen. Ohne Ende-zu-Ende-Verschlüsselung.
 */
public class MatrixClient {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String API = "/_matrix/client/v3";
    /** Feste Geräte-ID beim Anmelden mit Passwort – wiederholte Anmeldungen legen so kein neues Gerät an. */
    static final String DEVICE_ID = "DEVTOOLS_MCP";

    private static final int MAX_RATE_LIMIT_RETRIES = 3;
    private static final long MAX_RETRY_WAIT_MILLIS = 10_000;

    private final String baseUrl;
    private final HttpClient http;
    private final Duration timeout;
    private final String user;
    private final String password;
    private volatile String accessToken;

    /**
     * @param accessToken Zugangstoken oder leer, dann wird mit {@code user}/{@code password} angemeldet
     * @param password    leer = nur mit Token; sonst meldet sich der Client damit an, wenn kein Token gesetzt ist oder
     *                    der Server es nicht mehr annimmt
     */
    public MatrixClient(String baseUrl, String accessToken, String user, String password, Duration timeout) {
        this.baseUrl = trimSlash(baseUrl);
        this.accessToken = accessToken == null || accessToken.isBlank() ? null : accessToken.strip();
        this.user = user;
        this.password = password == null || password.isBlank() ? null : password;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public String baseUrl() {
        return baseUrl;
    }

    /** Ergebnis einer Anmeldung mit Passwort. */
    public record Login(String accessToken, String userId, String deviceId) {
    }

    /** Fehler des Homeservers mit Matrix-Fehlercode ({@code M_FORBIDDEN} …) und HTTP-Status. */
    public static final class MatrixException extends IllegalStateException {
        private final int status;
        private final String errcode;

        MatrixException(int status, String errcode, String message) {
            super(message);
            this.status = status;
            this.errcode = errcode;
        }

        public int status() {
            return status;
        }

        public String errcode() {
            return errcode;
        }

        boolean notFound() {
            return status == 404 || "M_NOT_FOUND".equals(errcode);
        }
    }

    // ------------------------------------------------------------------ Anmeldung

    /** Meldet sich mit Benutzername (oder vollständiger Matrix-ID) und Passwort an und verwendet das neue Token. */
    public synchronized Login login() {
        if (password == null || user == null || user.isBlank()) {
            throw new IllegalStateException("Matrix: weder Zugangstoken noch Benutzer und Passwort konfiguriert – in der "
                    + "DevTools-App unter Module → Matrix eintragen.");
        }
        ObjectNode body = JSON.createObjectNode();
        body.put("type", "m.login.password");
        ObjectNode id = body.putObject("identifier");
        id.put("type", "m.id.user");
        id.put("user", user);
        body.put("password", password);
        body.put("device_id", DEVICE_ID);
        body.put("initial_device_display_name", "DevTools MCP");
        JsonNode res = send("POST", API + "/login", Map.of(), body, timeout, false);
        Login login = new Login(res.path("access_token").asString(), res.path("user_id").asString(),
                res.path("device_id").asString(""));
        accessToken = login.accessToken();
        return login;
    }

    /** {@code user_id} und {@code device_id} des Tokens. */
    public JsonNode whoami() {
        return get(API + "/account/whoami", Map.of());
    }

    /** Unterstützte Spezifikationsversionen (ohne Anmeldung). */
    public JsonNode versions() {
        return send("GET", "/_matrix/client/versions", Map.of(), null, timeout, false);
    }

    // ------------------------------------------------------------------ Sync

    /**
     * Long-Polling auf neue Ereignisse.
     *
     * @param since     {@code next_batch} des letzten Aufrufs oder {@code null} für den ersten Abruf
     * @param timeoutMs so lange wartet der Server höchstens auf ein neues Ereignis
     */
    public JsonNode sync(String since, String filter, int timeoutMs) {
        Map<String, String> q = new LinkedHashMap<>();
        if (since != null) {
            q.put("since", since);
        }
        q.put("timeout", String.valueOf(Math.max(0, timeoutMs)));
        q.put("filter", filter);
        return send("GET", API + "/sync", q, null, timeout.plusMillis(Math.max(0, timeoutMs)), true);
    }

    // ------------------------------------------------------------------ Räume

    public List<String> joinedRooms() {
        List<String> out = new ArrayList<>();
        get(API + "/joined_rooms", Map.of()).path("joined_rooms").forEach(n -> out.add(n.asString()));
        return out;
    }

    /** Raum-ID zu einem Alias ({@code #raum:server}). */
    public String resolveAlias(String alias) {
        return get(API + "/directory/room/" + enc(alias), Map.of()).path("room_id").asString();
    }

    /** Tritt einem Raum bei (ID oder Alias) bzw. nimmt eine Einladung an; liefert die Raum-ID. */
    public String join(String roomIdOrAlias) {
        return send("POST", API + "/join/" + enc(roomIdOrAlias), Map.of(), JSON.createObjectNode(), timeout, true)
                .path("room_id").asString();
    }

    /** Inhalt eines Zustandsereignisses (z.B. {@code m.room.name}) oder {@code null}, wenn es keines gibt. */
    public JsonNode state(String roomId, String type) {
        try {
            // leerer state_key: der abschließende Schrägstrich ist laut Spezifikation optional
            return get(API + "/rooms/" + enc(roomId) + "/state/" + enc(type), Map.of());
        } catch (MatrixException e) {
            if (e.notFound()) {
                return null;
            }
            throw e;
        }
    }

    /** Mitglieder: {@code joined → {userId → {display_name}}}. */
    public JsonNode joinedMembers(String roomId) {
        return get(API + "/rooms/" + enc(roomId) + "/joined_members", Map.of());
    }

    /** Die letzten {@code limit} Ereignisse des Raums, neueste zuerst ({@code chunk}). */
    public JsonNode messages(String roomId, int limit, String filter) {
        return get(API + "/rooms/" + enc(roomId) + "/messages",
                Map.of("dir", "b", "limit", String.valueOf(limit), "filter", filter));
    }

    public JsonNode event(String roomId, String eventId) {
        return get(API + "/rooms/" + enc(roomId) + "/event/" + enc(eventId), Map.of());
    }

    // ------------------------------------------------------------------ Schreiben

    /** Sendet ein Ereignis; liefert die {@code event_id}. */
    public String sendEvent(String roomId, String type, JsonNode content) {
        String txn = "dtmcp-" + UUID.randomUUID();
        return send("PUT", API + "/rooms/" + enc(roomId) + "/send/" + enc(type) + "/" + txn, Map.of(), content,
                timeout, true).path("event_id").asString();
    }

    /** Lesebestätigung bis einschließlich {@code eventId}. */
    public void receipt(String roomId, String eventId) {
        send("POST", API + "/rooms/" + enc(roomId) + "/receipt/m.read/" + enc(eventId), Map.of(),
                JSON.createObjectNode(), timeout, true);
    }

    // ------------------------------------------------------------------ HTTP

    private JsonNode get(String path, Map<String, String> query) {
        return send("GET", path, query, null, timeout, true);
    }

    private JsonNode send(String method, String path, Map<String, String> query, JsonNode body, Duration requestTimeout,
                          boolean auth) {
        if (auth) {
            ensureToken();
        }
        boolean reloggedIn = false;
        int rateLimited = 0;
        while (true) {
            String usedToken = accessToken;
            HttpResponse<String> res = exchange(method, path, query, body, requestTimeout, auth);
            int code = res.statusCode();
            if (code < 400) {
                return parse(res.body(), path);
            }
            JsonNode err = parseQuietly(res.body());
            String errcode = err.path("errcode").asString("");
            if (code == 429 && rateLimited++ < MAX_RATE_LIMIT_RETRIES) {
                long wait = err.path("retry_after_ms").asLong(1000);
                sleep(Math.min(MAX_RETRY_WAIT_MILLIS, Math.max(100, wait)));
                continue;
            }
            if (auth && code == 401 && "M_UNKNOWN_TOKEN".equals(errcode) && password != null && !reloggedIn) {
                relogin(usedToken);
                reloggedIn = true;
                continue;
            }
            throw error(code, errcode, err.path("error").asString(abbreviate(res.body())), path);
        }
    }

    private synchronized void ensureToken() {
        if (accessToken == null) {
            login();
        }
    }

    /** Neu anmelden – außer ein anderer Aufruf hat das abgelehnte Token schon ersetzt. */
    private synchronized void relogin(String rejected) {
        if (Objects.equals(accessToken, rejected)) {
            login();
        }
    }

    private HttpResponse<String> exchange(String method, String path, Map<String, String> query, JsonNode body,
                                          Duration requestTimeout, boolean auth) {
        String q = query.entrySet().stream()
                .map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
                .collect(Collectors.joining("&"));
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(baseUrl + path + (q.isEmpty() ? "" : "?" + q)))
                .timeout(requestTimeout)
                .header("Accept", "application/json");
        if (auth && accessToken != null && !accessToken.isBlank()) {
            req.header("Authorization", "Bearer " + accessToken);
        }
        if (body == null) {
            req.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            req.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body), StandardCharsets.UTF_8));
        }
        try {
            return http.send(req.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Matrix-Homeserver nicht erreichbar (" + baseUrl + "): " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Abgebrochen", e);
        }
    }

    private static MatrixException error(int code, String errcode, String message, String path) {
        String where = path.startsWith(API) ? path.substring(API.length()) : path;
        String text = switch (errcode) {
            case "M_UNKNOWN_TOKEN", "M_MISSING_TOKEN" ->
                    "Matrix: Zugangstoken ungültig oder abgelaufen (" + errcode + ") – in der DevTools-App unter "
                            + "Module → Matrix ein neues Token eintragen oder Benutzer/Passwort hinterlegen.";
            case "M_FORBIDDEN" -> "Matrix: keine Berechtigung (" + message + ") bei " + where;
            case "M_LIMIT_EXCEEDED" -> "Matrix: Ratenbegrenzung des Homeservers – später erneut versuchen.";
            default -> "Matrix-Fehler " + code + (errcode.isEmpty() ? "" : " " + errcode) + " bei " + where + ": "
                    + message;
        };
        return new MatrixException(code, errcode, text);
    }

    private static JsonNode parse(String body, String path) {
        if (body == null || body.isBlank()) {
            return JSON.createObjectNode();
        }
        try {
            return JSON.readTree(body);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Unerwartete Antwort vom Matrix-Homeserver (" + path + "): "
                    + abbreviate(body), e);
        }
    }

    private static JsonNode parseQuietly(String body) {
        try {
            return body == null || body.isBlank() ? JSON.createObjectNode() : JSON.readTree(body);
        } catch (RuntimeException e) {
            return JSON.createObjectNode();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Abgebrochen", e);
        }
    }

    /** Pfadsegment bzw. Query-Wert kodieren – IDs enthalten {@code ! $ # : @}. */
    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String trimSlash(String url) {
        String u = url.strip();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    private static String abbreviate(String s) {
        return s == null ? "" : s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
