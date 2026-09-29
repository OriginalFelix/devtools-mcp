package systems.grebe.devtools.mcp.modules.ticket.spi;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Kleiner JSON-über-HTTP-Client für Ticket-Provider: feste Kopfzeilen (Anmeldung), Timeout und HTTP-Fehler als
 * verständliche {@link IllegalStateException}. Öffentlich, damit auch Provider aus Plugins ihn verwenden können.
 */
public final class HttpJson {

    public static final JsonMapper JSON = JsonMapper.builder().build();

    /** HTTP-Fehler mit Statuscode; die Meldung ist für das LLM formuliert. */
    public static final class StatusException extends IllegalStateException {
        private final int status;

        public StatusException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    /** Antwort mit geparstem Body (leerer Body = {@code MissingNode}) und Kopfzeilen (Paginierung). */
    public record Response(int status, JsonNode body, HttpHeaders headers) {
        public String header(String name) {
            return headers.firstValue(name).orElse(null);
        }
    }

    private final String system;
    private final String baseUrl;
    private final Map<String, String> headers;
    private final Duration timeout;
    private final HttpClient http;

    /**
     * @param system Name für Fehlermeldungen, z.B. „Jira“
     * @param baseUrl Basis für relative Pfade (ohne abschließenden Schrägstrich)
     * @param headers feste Kopfzeilen, z.B. {@code Authorization}
     */
    public HttpJson(String system, String baseUrl, Map<String, String> headers, Duration timeout) {
        this.system = system;
        this.baseUrl = stripSlash(baseUrl);
        this.headers = Map.copyOf(headers);
        this.timeout = timeout;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public String baseUrl() {
        return baseUrl;
    }

    public static String stripSlash(String url) {
        String u = url == null ? "" : url.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    /**
     * Query-String aus Schlüssel/Wert-Paaren inkl. {@code ?}; {@code null}/leere Werte entfallen, eine
     * {@link Iterable} ergibt den Schlüssel mehrfach.
     */
    public static String query(Object... kv) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            Object v = kv[i + 1];
            if (v instanceof Iterable<?> it) {
                for (Object o : it) {
                    add(parts, kv[i], o);
                }
            } else {
                add(parts, kv[i], v);
            }
        }
        return parts.isEmpty() ? "" : "?" + String.join("&", parts);
    }

    private static void add(List<String> parts, Object key, Object value) {
        if (value != null && !value.toString().isBlank()) {
            parts.add(enc(key.toString()) + "=" + enc(value.toString().trim()));
        }
    }

    public static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public static ObjectNode object() {
        return JSON.createObjectNode();
    }

    public Response get(String pathOrUrl) {
        return send(HttpRequest.newBuilder(uri(pathOrUrl)).GET(), pathOrUrl);
    }

    public JsonNode getJson(String pathOrUrl) {
        return get(pathOrUrl).body();
    }

    public Response post(String pathOrUrl, JsonNode body) {
        return withBody("POST", pathOrUrl, body);
    }

    public Response put(String pathOrUrl, JsonNode body) {
        return withBody("PUT", pathOrUrl, body);
    }

    public Response patch(String pathOrUrl, JsonNode body) {
        return withBody("PATCH", pathOrUrl, body);
    }

    private Response withBody(String method, String pathOrUrl, JsonNode body) {
        return send(HttpRequest.newBuilder(uri(pathOrUrl))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body), StandardCharsets.UTF_8)),
                pathOrUrl);
    }

    private URI uri(String pathOrUrl) {
        String s = pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://") ? pathOrUrl : baseUrl + pathOrUrl;
        try {
            return URI.create(s);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(system + ": ungültige URL " + s + " – Server-URL in der DevTools-App prüfen.");
        }
    }

    private Response send(HttpRequest.Builder req, String path) {
        req.timeout(timeout).header("Accept", "application/json");
        headers.forEach(req::header);
        HttpResponse<String> res;
        try {
            res = http.send(req.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(system + " nicht erreichbar (" + baseUrl + "): " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Abgebrochen", e);
        }
        int code = res.statusCode();
        String body = res.body() == null ? "" : res.body();
        if (code == 401) {
            throw new StatusException(code, system + ": nicht angemeldet (401) – Token in der DevTools-App prüfen "
                    + "(Modul Tickets)." + suffix(errorMessage(body)));
        }
        if (code == 403) {
            String limit = res.headers().firstValue("X-RateLimit-Remaining").orElse(null);
            throw new StatusException(code, system + ("0".equals(limit)
                    ? ": Anfragelimit erschöpft (403) – später erneut versuchen oder ein Token hinterlegen."
                    : ": keine Berechtigung (403) für " + shortPath(path) + ".") + suffix(errorMessage(body)));
        }
        if (code == 404) {
            throw new StatusException(code, system + ": nicht gefunden (404): " + shortPath(path)
                    + " – Schlüssel bzw. Projekt prüfen, oder das Token sieht es nicht." + suffix(errorMessage(body)));
        }
        if (code >= 400) {
            throw new StatusException(code, system + "-Fehler " + code + " bei " + shortPath(path) + ": " + errorMessage(body));
        }
        JsonNode node;
        try {
            node = body.isBlank() ? JSON.missingNode() : JSON.readTree(body);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Unerwartete Antwort von " + system + " (" + shortPath(path) + "): "
                    + abbreviate(body), e);
        }
        return new Response(code, node, res.headers());
    }

    private static String suffix(String msg) {
        return msg.isBlank() ? "" : " Server: " + msg;
    }

    private static String shortPath(String path) {
        int q = path.indexOf('?');
        return q < 0 ? path : path.substring(0, q);
    }

    /** Fehlermeldung aus den üblichen Formaten von Jira, GitHub und GitLab. */
    static String errorMessage(String body) {
        try {
            JsonNode n = JSON.readTree(body);
            List<String> msgs = new ArrayList<>();
            n.path("errorMessages").forEach(m -> msgs.add(m.asString()));
            JsonNode errors = n.path("errors");
            if (errors.isObject()) {
                Map<String, String> m = new LinkedHashMap<>();
                errors.properties().forEach(e -> m.put(e.getKey(), e.getValue().isString() ? e.getValue().asString()
                        : e.getValue().toString()));
                m.forEach((k, v) -> msgs.add(k + ": " + v));
            } else if (errors.isArray()) {
                errors.forEach(e -> msgs.add(e.isString() ? e.asString() : e.path("message").asString(e.toString())));
            }
            for (String f : List.of("message", "error", "error_description")) {
                JsonNode v = n.path(f);
                if (v.isString() && !v.asString().isBlank()) {
                    msgs.add(v.asString());
                } else if (v.isObject() || v.isArray()) {
                    msgs.add(v.toString());
                }
            }
            if (!msgs.isEmpty()) {
                return abbreviate(String.join("; ", msgs));
            }
        } catch (RuntimeException ignored) {
            // kein JSON
        }
        return abbreviate(body);
    }

    private static String abbreviate(String s) {
        String t = s == null ? "" : s.strip();
        return t.length() > 300 ? t.substring(0, 300) + "…" : t;
    }

    // ------------------------------------------------------------------ JSON-Hilfen für Provider

    /** Textwert oder {@code null}, wenn fehlend/leer. */
    public static String text(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) {
            return null;
        }
        String s = n.isString() ? n.asString() : n.isValueNode() ? n.asString() : null;
        return s == null || s.isBlank() ? null : s;
    }

    /** Textwerte eines Arrays, optional über ein Feld der Elemente. */
    public static List<String> texts(JsonNode array, String field) {
        List<String> out = new ArrayList<>();
        if (array != null) {
            array.forEach(e -> {
                String s = text(field == null ? e : e.path(field));
                if (s != null) {
                    out.add(s);
                }
            });
        }
        return out;
    }

    /** Erster gesetzter Wert. */
    public static String first(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
