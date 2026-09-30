package systems.grebe.devtools.mcp.remote;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import systems.grebe.devtools.mcp.api.Catalog;
import systems.grebe.devtools.mcp.api.Me;
import systems.grebe.devtools.mcp.api.ProjectInfo;
import systems.grebe.devtools.mcp.api.SettingsSnapshot;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** REST-Client für die API des Team-Servers ({@code /api/**}), angemeldet mit dem Desktop-Token. */
public class ServerClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final TypeReference<List<ProjectInfo>> PROJECTS = new TypeReference<>() {
    };

    /** Antwort mit {@code ETag}; {@code body == null}, wenn sich seit {@code etag} nichts geändert hat (304). */
    public record Fetched<T>(T body, String etag) {

        public boolean notModified() {
            return body == null;
        }
    }

    private final String base;
    private final String token;
    private final HttpClient http;
    private final JsonMapper json = JsonMapper.builder().build();

    public ServerClient(String url, String token) {
        this.base = url.replaceAll("/+$", "") + "/api";
        this.token = token;
        this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public Me me() {
        return json.readValue(send("GET", "/me", null, null).body(), Me.class);
    }

    public void putCatalog(Catalog catalog) {
        send("PUT", "/catalog", catalog, null);
    }

    public Fetched<SettingsSnapshot> settings(String etag) {
        return fetch("/settings", etag, s -> json.readValue(s, SettingsSnapshot.class));
    }

    public Fetched<List<ProjectInfo>> projects(String etag) {
        return fetch("/projects", etag, s -> json.readValue(s, PROJECTS));
    }

    public void activateProfile(long profileId) {
        send("PUT", "/profile/active", Map.of("profileId", profileId), null);
    }

    /** Beliebiger Aufruf mit JSON-Antwort (z.B. Skills). */
    public JsonNode call(String method, String path, Object body) {
        String response = send(method, path, body, null).body();
        return response == null || response.isBlank() ? json.nullNode() : json.readTree(response);
    }

    private <T> Fetched<T> fetch(String path, String etag, java.util.function.Function<String, T> parse) {
        HttpResponse<String> r = send("GET", path, null, etag);
        String newEtag = r.headers().firstValue("ETag").orElse(etag);
        return r.statusCode() == 304 ? new Fetched<>(null, etag) : new Fetched<>(parse.apply(r.body()), newEtag);
    }

    private HttpResponse<String> send(String method, String path, Object body, String etag) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(TIMEOUT)
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json");
        if (etag != null && !etag.isBlank()) {
            b.header("If-None-Match", etag);
        }
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        HttpResponse<String> r;
        try {
            r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new TeamServerException("Server nicht erreichbar (" + base + "): " + e.getMessage(), e, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TeamServerException("Abgebrochen", e, false);
        } catch (IllegalArgumentException e) {
            throw new TeamServerException("Ungültige Server-Adresse: " + base, e, true);
        }
        int status = r.statusCode();
        if (status == 401) {
            throw new TeamServerException("Desktop-Token ungültig, abgelaufen oder widerrufen – neues Token in der "
                    + "Web-UI unter „Mein Konto“ erzeugen.", null, true);
        }
        if (status == 400) {
            // fachlicher Fehler (z.B. Skill existiert schon) – wie lokal als IllegalArgumentException
            throw new IllegalArgumentException(errorText(r.body()).replaceFirst("^: ", ""));
        }
        if (status >= 300 && status != 304) {
            throw new TeamServerException("Server antwortet mit " + status + errorText(r.body())
                    + (status == 302 || status == 404 ? " – ist das die Adresse des Team-Servers?" : ""), null,
                    status < 500);
        }
        return r;
    }

    private String errorText(String body) {
        try {
            JsonNode n = json.readTree(body);
            return n.has("error") ? ": " + n.get("error").asString() : "";
        } catch (RuntimeException e) {
            return "";
        }
    }
}
