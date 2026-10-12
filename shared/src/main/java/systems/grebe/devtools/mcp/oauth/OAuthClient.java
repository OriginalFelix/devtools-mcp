package systems.grebe.devtools.mcp.oauth;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * OAuth 2.0 / OpenID Connect nach Standard, ohne Bibliothek eines Anbieters:
 *
 * <ul>
 *   <li>Discovery: Authorization Server Metadata (RFC 8414) bzw. OpenID Connect Discovery; GitHub ohne Discovery über
 *       {@link OAuthServer#github feste Endpunkte}.</li>
 *   <li>Anmeldung im Browser: Device Authorization Grant (RFC 8628), sonst Authorization Code mit PKCE (RFC 7636) und
 *       Rückleitung an eine Loopback-Adresse (RFC 8252, „native apps“).</li>
 *   <li>Erneuern mit dem Refresh-Token (RFC 6749 §6).</li>
 * </ul>
 *
 * <p>Öffentliche Clients (nur Client-ID) und vertrauliche (mit Client-Geheimnis, {@code client_secret_post} bzw.
 * {@code client_secret_basic} je nach Metadaten). Fehlerantworten kommen als {@link OAuthException}, auch wenn der
 * Server sie mit HTTP 200 schickt (GitHub).
 */
public class OAuthClient {

    static final String DEVICE_CODE_GRANT = "urn:ietf:params:oauth:grant-type:device_code";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final HttpClient http;
    private final Duration timeout;
    private final Clock clock;

    public OAuthClient(Duration timeout) {
        this(timeout, Clock.systemUTC());
    }

    /** @param clock für Ablaufzeiten ({@code expires_in}) */
    public OAuthClient(Duration timeout, Clock clock) {
        this(HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NORMAL).build(),
                timeout, clock);
    }

    OAuthClient(HttpClient http, Duration timeout, Clock clock) {
        this.http = http;
        this.timeout = timeout;
        this.clock = clock;
    }

    /** Laufende Anmeldung per Device Authorization Grant – was der Nutzer im Browser eingeben muss. */
    public record DeviceAuthorization(String deviceCode, String userCode, String verificationUri,
                                      String verificationUriComplete, Instant expiresAt, int interval) {

        /** Adresse zum Öffnen: mit Code, falls der Server sie anbietet. */
        public String browserUri() {
            return verificationUriComplete != null ? verificationUriComplete : verificationUri;
        }
    }

    // ---------------------------------------------------------------- Discovery

    /**
     * Der Server zu Zugangsdaten: Endpunkte von Hand ({@link OAuthOptions#manualEndpoints()}), Atlassian Cloud fest
     * vorgegeben, sonst über {@link #discover} beim Aussteller (leer = Herkunft der URL).
     */
    public OAuthServer resolve(String issuer, String url, OAuthOptions options) {
        String iss = OAuthServer.issuerFor(issuer, url);
        if (options.manualEndpoints()) {
            return options.server(iss);
        }
        if (OAuthServer.isAtlassian(issuer, url)) {
            return OAuthServer.atlassian();
        }
        return discover(iss);
    }

    /**
     * Endpunkte des Autorisierungsservers {@code issuer}: erst RFC 8414 ({@code /.well-known/oauth-authorization-server}),
     * dann OpenID Connect ({@code /.well-known/openid-configuration}); beides fehlgeschlagen und die Adresse gehört zu
     * GitHub, dessen feste Endpunkte.
     *
     * @throws IllegalStateException wenn nichts davon passt
     */
    public OAuthServer discover(String issuer) {
        String base = stripSlash(issuer.strip());
        URI uri = URI.create(base);
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        String origin = uri.getScheme() + "://" + uri.getRawAuthority();
        List<String> tried = new ArrayList<>();
        for (String candidate : List.of(origin + "/.well-known/oauth-authorization-server" + path,
                base + "/.well-known/openid-configuration")) {
            tried.add(candidate);
            JsonNode meta = getJson(candidate);
            if (meta != null && meta.path("token_endpoint").isString()) {
                String declared = stripSlash(meta.path("issuer").asString(""));
                if (!declared.isEmpty() && !declared.equals(base)) {
                    throw new IllegalStateException("Discovery unter " + candidate + " nennt den Aussteller "
                            + declared + " statt " + base + " (RFC 8414 §3.3) – Aussteller in den Zugangsdaten anpassen.");
                }
                List<String> methods = new ArrayList<>();
                meta.path("token_endpoint_auth_methods_supported").forEach(n -> methods.add(n.asString()));
                return new OAuthServer(base, text(meta, "authorization_endpoint"), text(meta, "token_endpoint"),
                        text(meta, "device_authorization_endpoint"), methods);
            }
        }
        String github = OAuthServer.githubWebBase(base);
        if (github != null) {
            return OAuthServer.github(github);
        }
        throw new IllegalStateException("Keine OAuth-/OIDC-Metadaten für " + base + " gefunden (versucht: "
                + String.join(", ", tried) + "). Aussteller (Issuer) in den Zugangsdaten prüfen.");
    }

    // ---------------------------------------------------------------- Anmeldung

    /**
     * Anmeldung im Browser: Device Authorization Grant, wenn der Server ihn anbietet, sonst Authorization Code mit
     * PKCE über eine Loopback-Adresse. Blockiert bis zur Bestätigung; ein Interrupt bricht ab.
     *
     * @param prompt bekommt die Anweisung für den Nutzer (Adresse, ggf. Code)
     * @param browse öffnet eine Adresse im Browser (darf nichts tun, z.B. ohne Desktop)
     */
    public OAuthTokens login(OAuthServer server, String clientId, String clientSecret, String scope,
                             Consumer<String> prompt, Consumer<URI> browse) {
        return login(server, clientId, clientSecret, scope, OAuthOptions.NONE, prompt, browse);
    }

    /** Wie {@link #login(OAuthServer, String, String, String, Consumer, Consumer)} mit Angaben von Hand. */
    public OAuthTokens login(OAuthServer server, String clientId, String clientSecret, String scope,
                             OAuthOptions options, Consumer<String> prompt, Consumer<URI> browse) {
        if (server.supportsDeviceFlow() && options.redirectUri() == null) {
            DeviceAuthorization d = startDevice(server, clientId, clientSecret, scope, options);
            prompt.accept("Im Browser " + d.verificationUri() + " öffnen und den Code " + d.userCode()
                    + " eingeben.");
            browse.accept(URI.create(d.browserUri()));
            return pollDevice(server, clientId, clientSecret, d);
        }
        if (server.supportsAuthorizationCode()) {
            return authorizationCode(server, clientId, clientSecret, scope, options, Duration.ofMinutes(10), prompt,
                    browse);
        }
        throw new IllegalStateException("Der Server " + server.issuer() + " bietet weder Device Authorization Grant "
                + "noch Authorization Code an.");
    }

    /** Fordert einen Gerätecode an (RFC 8628 §3.1). */
    public DeviceAuthorization startDevice(OAuthServer server, String clientId, String clientSecret, String scope) {
        return startDevice(server, clientId, clientSecret, scope, OAuthOptions.NONE);
    }

    private DeviceAuthorization startDevice(OAuthServer server, String clientId, String clientSecret, String scope,
                                            OAuthOptions options) {
        Map<String, String> form = new LinkedHashMap<>(parameters(server, options));
        if (scope != null && !scope.isBlank()) {
            form.put("scope", scope.strip());
        }
        JsonNode r = post(server, server.deviceAuthorizationEndpoint(), clientId, clientSecret, form);
        String deviceCode = text(r, "device_code");
        String userCode = text(r, "user_code");
        String verification = text(r, "verification_uri");
        if (verification == null) {
            verification = text(r, "verification_url"); // ältere Implementierungen (Google)
        }
        if (deviceCode == null || userCode == null || verification == null) {
            throw new IllegalStateException("Antwort ohne device_code, user_code oder verification_uri.");
        }
        return new DeviceAuthorization(deviceCode, userCode, verification, text(r, "verification_uri_complete"),
                clock.instant().plusSeconds(r.path("expires_in").asLong(900)),
                Math.max(1, r.path("interval").asInt(5)));
    }

    /**
     * Fragt den Token-Endpunkt ab, bis der Nutzer bestätigt hat (RFC 8628 §3.4/3.5): {@code authorization_pending}
     * wartet weiter, {@code slow_down} verlängert den Abstand um 5 Sekunden.
     */
    public OAuthTokens pollDevice(OAuthServer server, String clientId, String clientSecret, DeviceAuthorization d) {
        long interval = d.interval();
        Map<String, String> form = Map.of("grant_type", DEVICE_CODE_GRANT, "device_code", d.deviceCode());
        while (true) {
            sleep(Duration.ofSeconds(interval));
            if (clock.instant().isAfter(d.expiresAt())) {
                throw new IllegalStateException("Code abgelaufen – Anmeldung neu starten.");
            }
            try {
                return tokens(post(server, server.tokenEndpoint(), clientId, clientSecret, form));
            } catch (OAuthException e) {
                switch (e.code()) {
                    case "authorization_pending" -> { }
                    case "slow_down" -> interval += 5;
                    case "access_denied" -> throw new IllegalStateException("Anmeldung im Browser abgelehnt.", e);
                    case "expired_token" -> throw new IllegalStateException("Code abgelaufen – Anmeldung neu "
                            + "starten.", e);
                    default -> throw e;
                }
            }
        }
    }

    /**
     * Authorization Code mit PKCE (S256) und Rückleitung an {@code http://127.0.0.1:<freier Port>/callback} (RFC 8252
     * §7.3); {@code state} schützt vor untergeschobenen Antworten. Der Client muss diese Redirect-URI zulassen (bei
     * Loopback ist der Port nach RFC 8252 §7.3 beliebig).
     */
    public OAuthTokens authorizationCode(OAuthServer server, String clientId, String clientSecret, String scope,
                                         Duration wait, Consumer<String> prompt, Consumer<URI> browse) {
        return authorizationCode(server, clientId, clientSecret, scope, OAuthOptions.NONE, wait, prompt, browse);
    }

    /** Mit {@link OAuthOptions#redirectUri() fester Rückleitung} und weiteren Parametern der Anmeldung. */
    public OAuthTokens authorizationCode(OAuthServer server, String clientId, String clientSecret, String scope,
                                         OAuthOptions options, Duration wait, Consumer<String> prompt,
                                         Consumer<URI> browse) {
        String verifier = random(32);
        String state = random(16);
        CompletableFuture<Map<String, String>> callback = new CompletableFuture<>();
        URI fixed = options.redirectUri() == null ? null : URI.create(options.redirectUri());
        String path = fixed == null || fixed.getPath() == null || fixed.getPath().isEmpty() ? "/callback"
                : fixed.getPath();
        HttpServer loopback;
        try {
            loopback = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),
                    fixed == null ? 0 : fixed.getPort()), 0);
        } catch (IOException e) {
            throw new IllegalStateException("Lokale Rückleitungsadresse nicht verfügbar"
                    + (fixed == null ? "" : " (Port " + fixed.getPort() + " belegt?)") + ": " + e.getMessage(), e);
        }
        loopback.createContext(path, exchange -> {
            Map<String, String> params = query(exchange.getRequestURI().getRawQuery());
            boolean ok = state.equals(params.get("state")) && params.containsKey("code");
            byte[] page = ("<!doctype html><meta charset=utf-8><title>DevTools MCP</title><p>"
                    + (ok ? "Anmeldung abgeschlossen – dieses Fenster kann geschlossen werden."
                    : "Anmeldung fehlgeschlagen – zurück zur App.") + "</p>").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(ok ? 200 : 400, page.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(page);
            }
            if (state.equals(params.get("state"))) {
                callback.complete(params); // fremde Aufrufe ohne passendes state ändern nichts
            }
        });
        loopback.start();
        try {
            String redirect = fixed != null ? options.redirectUri()
                    : "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":"
                    + loopback.getAddress().getPort() + "/callback";
            Map<String, String> q = new LinkedHashMap<>(parameters(server, options));
            q.put("response_type", "code");
            q.put("client_id", clientId);
            q.put("redirect_uri", redirect);
            if (scope != null && !scope.isBlank()) {
                q.put("scope", scope.strip());
            }
            q.put("state", state);
            q.put("code_challenge", challenge(verifier));
            q.put("code_challenge_method", "S256");
            String sep = server.authorizationEndpoint().contains("?") ? "&" : "?";
            URI authorize = URI.create(server.authorizationEndpoint() + sep + form(q));
            prompt.accept("Im Browser anmelden: " + authorize);
            browse.accept(authorize);
            Map<String, String> result = await(callback, wait);
            if (result.containsKey("error")) {
                throw new OAuthException(result.get("error"), result.get("error_description"));
            }
            Map<String, String> form = new LinkedHashMap<>();
            form.put("grant_type", "authorization_code");
            form.put("code", result.get("code"));
            form.put("redirect_uri", redirect);
            form.put("code_verifier", verifier);
            return tokens(post(server, server.tokenEndpoint(), clientId, clientSecret, form));
        } finally {
            loopback.stop(0);
        }
    }

    // ---------------------------------------------------------------- Erneuern

    /** Neues Access-Token mit dem Refresh-Token (RFC 6749 §6); ohne neues Refresh-Token bleibt das alte gültig. */
    public OAuthTokens refresh(OAuthServer server, String clientId, String clientSecret, String refreshToken) {
        OAuthTokens t = tokens(post(server, server.tokenEndpoint(), clientId, clientSecret,
                Map.of("grant_type", "refresh_token", "refresh_token", refreshToken)));
        return t.refreshToken() != null ? t : new OAuthTokens(t.accessToken(), refreshToken, t.expiresAt(), t.scope());
    }

    /** Zusätzliche Parameter der Anmeldung: was der Server verlangt, darüber die Angaben von Hand. */
    private static Map<String, String> parameters(OAuthServer server, OAuthOptions options) {
        Map<String, String> p = new LinkedHashMap<>(server.authorizationParameters());
        p.putAll(options.parameters());
        return p;
    }

    // ---------------------------------------------------------------- HTTP

    private OAuthTokens tokens(JsonNode r) {
        String access = text(r, "access_token");
        if (access == null) {
            throw new IllegalStateException("Token-Antwort ohne access_token.");
        }
        Instant expires = r.path("expires_in").canConvertToLong() && r.path("expires_in").asLong() > 0
                ? clock.instant().plusSeconds(r.path("expires_in").asLong()) : null;
        return new OAuthTokens(access, text(r, "refresh_token"), expires, text(r, "scope"));
    }

    /** POST als Formular; Client-Authentifizierung nach den Metadaten. Fehlerantworten werden zur Exception. */
    private JsonNode post(OAuthServer server, String endpoint, String clientId, String clientSecret,
                          Map<String, String> params) {
        Map<String, String> body = new LinkedHashMap<>(params);
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(endpoint)).timeout(timeout)
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded");
        boolean secret = clientSecret != null && !clientSecret.isBlank();
        if (secret && !server.secretInBody()) {
            // RFC 6749 §2.3.1: Client-ID und Geheimnis form-kodiert, dann Basic
            String pair = form1(clientId) + ":" + form1(clientSecret);
            req.header("Authorization", "Basic "
                    + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8)));
        } else {
            body.put("client_id", clientId);
            if (secret) {
                body.put("client_secret", clientSecret);
            }
        }
        HttpResponse<String> res = send(req.POST(HttpRequest.BodyPublishers.ofString(form(body))).build(), endpoint);
        JsonNode json = parse(res.body());
        if (json != null && json.path("error").isString()) {
            throw new OAuthException(json.path("error").asString(), text(json, "error_description"));
        }
        if (res.statusCode() / 100 != 2 || json == null) {
            throw new IllegalStateException("HTTP " + res.statusCode() + " von " + endpoint);
        }
        return json;
    }

    private JsonNode getJson(String url) {
        try {
            HttpResponse<String> res = send(HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
                    .header("Accept", "application/json").GET().build(), url);
            return res.statusCode() == 200 ? parse(res.body()) : null;
        } catch (IllegalStateException e) {
            return null;
        }
    }

    private HttpResponse<String> send(HttpRequest req, String url) {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("Keine Verbindung zu " + url + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Abgebrochen.", e);
        }
    }

    private static JsonNode parse(String body) {
        try {
            JsonNode n = JSON.readTree(body);
            return n != null && n.isObject() ? n : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- Hilfen

    private static Map<String, String> await(CompletableFuture<Map<String, String>> f, Duration wait) {
        try {
            return f.get(wait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Anmeldung abgebrochen.", e);
        } catch (TimeoutException e) {
            throw new IllegalStateException("Keine Rückmeldung aus dem Browser – Anmeldung neu starten.", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Anmeldung fehlgeschlagen: " + e.getCause().getMessage(), e);
        }
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Anmeldung abgebrochen.", e);
        }
    }

    static String challenge(String verifier) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String random(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String form(Map<String, String> params) {
        return params.entrySet().stream().map(e -> form1(e.getKey()) + "=" + form1(e.getValue()))
                .collect(Collectors.joining("&"));
    }

    private static String form1(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    static Map<String, String> query(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String part : raw.split("&")) {
            int eq = part.indexOf('=');
            String k = URLDecoder.decode(eq < 0 ? part : part.substring(0, eq), StandardCharsets.UTF_8);
            String v = eq < 0 ? "" : URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
            out.putIfAbsent(k, v);
        }
        return out;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isString() && !v.asString().isBlank() ? v.asString() : null;
    }

    private static String stripSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
