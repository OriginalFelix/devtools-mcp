package systems.grebe.devtools.mcp.modules.sonar;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import systems.grebe.devtools.mcp.modules.ticket.spi.HttpJson;

/** Minimaler Client für die SonarQube/SonarCloud Web-API. */
public class SonarClient {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String baseUrl;
    private final String token;
    private final String organization;
    private final HttpClient http;
    private final Duration timeout;

    public SonarClient(String baseUrl, String token, String organization, Duration timeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.token = token;
        this.organization = organization;
        this.timeout = timeout;
        this.http = HttpJson.sharedClient();
    }

    public String baseUrl() {
        return baseUrl;
    }

    /** Baut Parameter; {@code null}/leere Werte werden weggelassen. */
    public static Map<String, String> params(Object... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            Object v = kv[i + 1];
            if (v != null && !v.toString().isBlank()) {
                m.put(kv[i].toString(), v.toString().trim());
            }
        }
        return m;
    }

    public JsonNode get(String path, Map<String, String> params) {
        String body = getRaw(path, params);
        try {
            return JSON.readTree(body);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Unerwartete Antwort von SonarQube (" + path + "): " + abbreviate(body), e);
        }
    }

    public String getRaw(String path, Map<String, String> params) {
        Map<String, String> all = new LinkedHashMap<>(params);
        if (organization != null && !organization.isBlank() && !all.containsKey("organization")) {
            all.put("organization", organization);
        }
        String query = all.entrySet().stream()
                .map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
                .collect(Collectors.joining("&"));
        URI uri = URI.create(baseUrl + path + (query.isEmpty() ? "" : "?" + query));
        HttpRequest.Builder req = HttpRequest.newBuilder(uri).timeout(timeout).header("Accept", "application/json").GET();
        if (token != null && !token.isBlank()) {
            // Basic mit Token als Benutzername funktioniert mit SonarQube (alle Versionen) und SonarCloud
            req.header("Authorization", HttpJson.basicAuth(token, ""));
        }
        HttpResponse<String> res;
        try {
            res = http.send(req.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("SonarQube nicht erreichbar (" + baseUrl + "): " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Abgebrochen", e);
        }
        int code = res.statusCode();
        if (code == 401) {
            throw new IllegalStateException("SonarQube: nicht authentifiziert (401) – Token prüfen.");
        }
        if (code == 403) {
            throw new IllegalStateException("SonarQube: keine Berechtigung (403) für " + path);
        }
        if (code >= 400) {
            throw new IllegalStateException("SonarQube-Fehler " + code + " bei " + path + ": " + errorMessage(res.body()));
        }
        return res.body();
    }

    private static String errorMessage(String body) {
        try {
            JsonNode n = JSON.readTree(body);
            JsonNode errors = n.path("errors");
            if (errors.isArray() && !errors.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                errors.forEach(e -> sb.append(e.path("msg").asString()).append(' '));
                return sb.toString().trim();
            }
        } catch (RuntimeException ignored) {
            // kein JSON
        }
        return abbreviate(body);
    }

    private static String abbreviate(String s) {
        return s == null ? "" : s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
