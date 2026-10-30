package systems.grebe.devtools.mcp.modules.ide;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Verbindung zum DevTools-Plugin in laufenden IDEs (IntelliJ-Plattform, siehe {@code ide-plugins/intellij}). Jedes
 * Plugin lauscht nur auf 127.0.0.1 und legt Port und Zugriffstoken in {@code ~/.devtools-mcp/ide/<pid>.json} ab (nur
 * für den Nutzer lesbar). Darüber liest die App den Editor-Zustand ({@code GET /editor}) und setzt den Cursor der KI
 * ({@code POST /cursor}, Formular-Parameter) – anders als die Befehlszeilen-Starter der {@code IdeProvider} braucht das
 * eine laufende IDE mit Plugin.
 */
final class IdeLink {

    /** Ein laufendes Plugin laut Lock-Datei. */
    record Instance(long pid, int port, String token, String ide) {
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final Path dir;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    IdeLink(Path dir) {
        this.dir = dir;
    }

    /** Plugins, deren IDE-Prozess noch läuft (Lock-Dateien abgestürzter IDEs bleiben liegen und zählen nicht). */
    List<Instance> instances() {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(f -> f.getFileName().toString().endsWith(".json")).sorted().map(IdeLink::read)
                    .filter(Objects::nonNull)
                    .filter(i -> ProcessHandle.of(i.pid()).map(ProcessHandle::isAlive).orElse(false)).toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static Instance read(Path lock) {
        try {
            JsonNode n = JSON.readTree(Files.readString(lock));
            return new Instance(n.path("pid").asLong(), n.path("port").asInt(), n.path("token").asString(),
                    n.path("ide").asString("IDE"));
        } catch (IOException | RuntimeException e) {
            return null; // halb geschrieben oder fremd
        }
    }

    /** Zustand aller geöffneten Projekte: {@code {ide, projects: [{name, path, active, file, line, column, selection, openFiles}]}}. */
    JsonNode editor(Instance i) {
        return JSON.readTree(send(i, HttpRequest.newBuilder(uri(i, "/editor")).GET()));
    }

    /** Setzt den Cursor der KI ({@code file}, {@code line}, …) bzw. entfernt ihn ({@code clear=true}); liefert die Meldung des Plugins. */
    String cursor(Instance i, Map<String, String> params) {
        String form = params.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return send(i, HttpRequest.newBuilder(uri(i, "/cursor"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)));
    }

    private String send(Instance i, HttpRequest.Builder request) {
        try {
            HttpResponse<String> r = http.send(request.header("Authorization", "Bearer " + i.token()).timeout(TIMEOUT)
                    .build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                throw new IllegalStateException(i.ide() + ": " + r.body().strip() + " (HTTP " + r.statusCode() + ")");
            }
            return r.body();
        } catch (IOException e) {
            throw new IllegalStateException(i.ide() + " antwortet nicht: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Abgebrochen", e);
        }
    }

    private static URI uri(Instance i, String path) {
        return URI.create("http://127.0.0.1:" + i.port() + path);
    }
}
