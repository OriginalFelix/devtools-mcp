package systems.grebe.devtools.mcp.modules.chat.teams;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * HTTP für Microsoft Graph (JSON mit Bearer-Token) und die Token-Endpunkte von Entra ID (Formular). Fehler werden als
 * {@link GraphException} mit einer für das LLM verständlichen Meldung geworfen; 429 wird kurz abgewartet und wiederholt.
 */
final class GraphHttp {

    static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_RETRIES = 3;
    private static final long MAX_RETRY_WAIT_MILLIS = 10_000;

    /** Fehler mit HTTP-Status und Fehlercode ({@code error} bzw. {@code error.code}). */
    static final class GraphException extends IllegalStateException {
        private final int status;
        private final String code;

        GraphException(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        int status() {
            return status;
        }

        String code() {
            return code;
        }
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final Duration timeout;

    GraphHttp(Duration timeout) {
        this.timeout = timeout;
    }

    /** POST mit Formular (Token-Endpunkte); Fehler kommen als JSON mit {@code error}/{@code error_description}. */
    JsonNode form(String url, Map<String, String> fields) {
        String body = fields.entrySet().stream()
                .map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
                .collect(Collectors.joining("&"));
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        HttpResponse<String> res = exchange(req, url);
        JsonNode json = parse(res.body());
        if (res.statusCode() >= 400) {
            String code = json.path("error").asString("");
            String desc = json.path("error_description").asString(abbreviate(res.body()));
            // Entra-Beschreibungen tragen Zeitstempel und Trace-IDs in weiteren Zeilen
            throw new GraphException(res.statusCode(), code, desc.lines().findFirst().orElse(desc));
        }
        return json;
    }

    /**
     * Graph-Anfrage mit Bearer-Token; {@code body == null} = ohne Body.
     *
     * @param token liefert das aktuelle Token (wird bei 401 einmal neu geholt)
     * @param onUnauthorized verwirft das zwischengespeicherte Token, damit {@code token} ein neues besorgt
     */
    JsonNode graph(String method, String url, JsonNode body, Supplier<String> token, Runnable onUnauthorized) {
        boolean retriedAuth = false;
        int retries = 0;
        while (true) {
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + token.get());
            if (body == null) {
                req.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                req.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body), StandardCharsets.UTF_8));
            }
            HttpResponse<String> res = exchange(req.build(), url);
            int code = res.statusCode();
            if (code < 400) {
                return parse(res.body());
            }
            JsonNode err = parse(res.body()).path("error");
            String errCode = err.path("code").asString("");
            String message = err.path("message").asString(abbreviate(res.body()));
            if ((code == 429 || code == 503) && retries++ < MAX_RETRIES) {
                long wait = res.headers().firstValueAsLong("Retry-After").orElse(2) * 1000;
                sleep(Math.min(MAX_RETRY_WAIT_MILLIS, Math.max(500, wait)));
                continue;
            }
            if (code == 401 && !retriedAuth) {
                onUnauthorized.run();
                retriedAuth = true;
                continue;
            }
            String path = shortPath(url);
            throw new GraphException(code, errCode, switch (code) {
                case 401 -> "Teams: Anmeldung abgelehnt (401) – neu anmelden (chat_login bzw. Aktion „Anmelden“). "
                        + message;
                case 403 -> "Teams: keine Berechtigung (403) für " + path + " – fehlen der App-Registrierung delegierte "
                        + "Berechtigungen (Chat.ReadWrite, ChatMessage.Send)? " + message;
                case 404 -> "Teams: nicht gefunden (404): " + path + " – Chat- bzw. Nachrichten-ID prüfen. " + message;
                case 429 -> "Teams: Anfragelimit von Microsoft Graph erreicht – später erneut versuchen.";
                default -> "Teams-Fehler " + code + (errCode.isEmpty() ? "" : " " + errCode) + " bei " + path + ": "
                        + message;
            });
        }
    }

    private HttpResponse<String> exchange(HttpRequest req, String url) {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Microsoft-Dienst nicht erreichbar (" + URI.create(url).getHost() + "): "
                    + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Abgebrochen", e);
        }
    }

    private static JsonNode parse(String body) {
        try {
            return body == null || body.isBlank() ? JSON.createObjectNode() : JSON.readTree(body);
        } catch (RuntimeException e) {
            return JSON.createObjectNode();
        }
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Abgebrochen", e);
        }
    }

    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String shortPath(String url) {
        String path = URI.create(url).getPath();
        return path == null ? url : path;
    }

    private static String abbreviate(String s) {
        return s == null ? "" : s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
