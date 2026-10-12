package systems.grebe.devtools.mcp.remote;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Dateien von und zur Dateiablage des Backends ({@code /blobs} der API-Version, eingebettet oder Team-Server) – für
 * Anhänge von Skills und Memories. Hochgeladen wird in Teilen von {@value #CHUNK} Bytes, damit keine Grenze je Anfrage
 * (WildFly, Proxy) die Dateigröße beschränkt; ein fehlgeschlagener Teil wird einmal wiederholt. Heruntergeladen wird
 * gestreamt in eine Datei.
 */
@Component
public class BackendFiles {

    /** Größe eines Teils beim Hochladen. */
    static final int CHUNK = 4 * 1024 * 1024;
    private static final Duration TIMEOUT = Duration.ofMinutes(5);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final BackendConnection backend;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10)).build();

    public BackendFiles(BackendConnection backend) {
        this.backend = backend;
    }

    /** Lädt die Datei in die Dateiablage des angemeldeten Benutzers; liefert ihren SHA-256. */
    public String upload(Path file) {
        String id = (String) send(request("/blobs/uploads").POST(HttpRequest.BodyPublishers.noBody())).get("upload");
        byte[] buffer = new byte[CHUNK];
        long offset = 0;
        try (InputStream in = Files.newInputStream(file)) {
            int n;
            while ((n = in.readNBytes(buffer, 0, CHUNK)) > 0) {
                HttpRequest.Builder part = request("/blobs/uploads/" + id + "?offset=" + offset)
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(buffer, 0, n));
                try {
                    send(part);
                } catch (BackendConnection.UnreachableException e) {
                    send(part); // einmal wiederholen – der Server überschreibt ab offset
                }
                offset += n;
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Datei " + file + " nicht lesbar: " + e.getMessage(), e);
        }
        return (String) send(request("/blobs/uploads/" + id + "/complete")
                .POST(HttpRequest.BodyPublishers.noBody())).get("blob");
    }

    /** Lädt einen Inhalt der Dateiablage nach {@code target} (vorhandene Datei wird ersetzt). */
    public void download(String blob, Path target) {
        Path tmp = null;
        try {
            Path dir = target.toAbsolutePath().getParent();
            Files.createDirectories(dir);
            tmp = Files.createTempFile(dir, ".download-", ".tmp");
            HttpResponse<Path> r = exchange(request("/blobs/" + blob).GET(), HttpResponse.BodyHandlers.ofFile(tmp));
            if (r.statusCode() / 100 != 2) {
                fail(r.statusCode(), Files.readString(tmp));
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("Datei " + target + " nicht schreibbar: " + e.getMessage(), e);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // temporäre Datei bleibt liegen
                }
            }
        }
    }

    private HttpRequest.Builder request(String path) {
        String token = backend.token().orElseThrow(() -> new IllegalStateException("Nicht angemeldet."));
        return HttpRequest.newBuilder(URI.create(backend.apiUrl() + path)).timeout(TIMEOUT)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private Map<?, ?> send(HttpRequest.Builder request) {
        HttpResponse<String> r = exchange(request, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2) {
            fail(r.statusCode(), r.body());
        }
        return JSON.readValue(r.body(), Map.class);
    }

    private <T> HttpResponse<T> exchange(HttpRequest.Builder request, HttpResponse.BodyHandler<T> handler) {
        try {
            return http.send(request.build(), handler);
        } catch (IOException e) {
            throw new BackendConnection.UnreachableException("Backend nicht erreichbar (" + backend.url() + "): "
                    + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Dateiübertragung abgebrochen.", e);
        }
    }

    /** Fehler wie bei GraphQL: abgelehnt = {@link IllegalArgumentException}, sonst {@link IllegalStateException}. */
    private static void fail(int status, String body) {
        String message = body;
        try {
            Object error = JSON.readValue(body, Map.class).get("error");
            if (error != null) {
                message = error.toString();
            }
        } catch (RuntimeException ignored) {
            // kein JSON – Text übernehmen
        }
        if (status == 401) {
            throw new IllegalStateException("Anmeldung am Backend fehlt oder ist abgelaufen.");
        }
        if (status == 400 || status == 403 || status == 404) {
            throw new IllegalArgumentException(message);
        }
        throw new IllegalStateException("Backend-Fehler " + status + " bei der Dateiübertragung: " + message);
    }
}
