package systems.grebe.devtools.mcp.modules.maven;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;


import org.w3c.dom.Element;
import systems.grebe.devtools.mcp.core.Xml;
import systems.grebe.devtools.mcp.modules.ticket.spi.HttpJson;

/**
 * Lesender HTTP-Zugriff auf ein Maven-Repository im Standard-Layout ({@code g/r/o/u/p/artifact/version/…}):
 * {@code maven-metadata.xml}, POMs und JARs. Zugangsdaten per Basic Auth.
 */
class MavenRepositoryClient {

    private final String baseUrl;
    private final String username;
    private final String password;
    private final Duration timeout;
    private final long maxBytes;
    private final HttpClient http;

    MavenRepositoryClient(String baseUrl, String username, String password, Duration timeout, long maxBytes) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.username = username;
        this.password = password;
        this.timeout = timeout;
        this.maxBytes = maxBytes;
        this.http = HttpJson.sharedClient();
    }

    String baseUrl() {
        return baseUrl;
    }

    /** Inhalt von {@code maven-metadata.xml} eines Artefakts. */
    record Metadata(List<String> versions, String latest, String release, Instant lastUpdated) {
    }

    Metadata metadata(Coordinates c) {
        byte[] body = get(dir(c) + "/maven-metadata.xml")
                .orElseThrow(() -> new IllegalArgumentException("Artefakt " + c.ga() + " nicht gefunden in " + baseUrl
                        + " (maven-metadata.xml fehlt – groupId/artifactId prüfen)."));
        Element versioning = Xml.child(Xml.parse(body).getDocumentElement(), "versioning");
        List<String> versions = new ArrayList<>();
        Element list = Xml.child(versioning, "versions");
        if (list != null) {
            for (Element v : Xml.children(list, "version")) {
                versions.add(v.getTextContent().trim());
            }
        }
        return new Metadata(versions, Xml.text(versioning, "latest"), Xml.text(versioning, "release"),
                parseTimestamp(Xml.text(versioning, "lastUpdated")));
    }

    /** POM einer Version oder leer, wenn sie nicht existiert. */
    Optional<byte[]> pom(Coordinates c, String version) {
        return get(file(c, version, "pom"));
    }

    /** Haupt-JAR einer Version oder leer (z.B. bei {@code packaging=pom}). */
    Optional<byte[]> jar(Coordinates c, String version) {
        return get(file(c, version, "jar"));
    }

    /** Veröffentlichungszeitpunkt einer Version (Last-Modified des POMs), falls der Server ihn liefert. */
    Optional<Instant> published(Coordinates c, String version) {
        HttpRequest req = request(file(c, version, "pom")).method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
        try {
            HttpResponse<Void> res = http.send(req, HttpResponse.BodyHandlers.discarding());
            if (res.statusCode() >= 400) {
                return Optional.empty();
            }
            return res.headers().firstValue("Last-Modified")
                    .map(v -> ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------ intern

    private String dir(Coordinates c) {
        return "/" + c.groupId().replace('.', '/') + "/" + c.artifactId();
    }

    private String file(Coordinates c, String version, String ext) {
        return dir(c) + "/" + version + "/" + c.artifactId() + "-" + version + "." + ext;
    }

    private HttpRequest.Builder request(String path) {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(timeout);
        if (username != null && !username.isBlank()) {
            req.header("Authorization", HttpJson.basicAuth(username, password));
        }
        return req;
    }

    private Optional<byte[]> get(String path) {
        HttpResponse<InputStream> res;
        try {
            res = http.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new IllegalStateException("Maven-Repository nicht erreichbar (" + baseUrl + "): " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Abgebrochen", e);
        }
        int code = res.statusCode();
        try (InputStream in = res.body()) {
            if (code == 404) {
                return Optional.empty();
            }
            if (code == 401 || code == 403) {
                throw new IllegalStateException("Maven-Repository: keine Berechtigung (" + code + ") – Zugangsdaten prüfen.");
            }
            if (code >= 400) {
                throw new IllegalStateException("Maven-Repository: Fehler " + code + " bei " + path);
            }
            byte[] data = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, maxBytes + 1));
            if (data.length > maxBytes) {
                throw new IllegalStateException("Datei zu groß (> " + (maxBytes / (1024 * 1024)) + " MB): " + path
                        + " – Grenze in den Modul-Einstellungen anpassen.");
            }
            return Optional.of(data);
        } catch (IOException e) {
            throw new IllegalStateException("Lesefehler bei " + path + ": " + e.getMessage(), e);
        }
    }

    private static Instant parseTimestamp(String ts) {
        if (ts == null || !ts.matches("\\d{14}")) {
            return null;
        }
        return Instant.parse(ts.substring(0, 4) + "-" + ts.substring(4, 6) + "-" + ts.substring(6, 8) + "T"
                + ts.substring(8, 10) + ":" + ts.substring(10, 12) + ":" + ts.substring(12, 14) + "Z");
    }
}
