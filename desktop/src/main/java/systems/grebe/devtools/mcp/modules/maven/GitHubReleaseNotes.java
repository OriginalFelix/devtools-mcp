package systems.grebe.devtools.mcp.modules.maven;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Release Notes aus GitHub-Releases eines Projekts: Releases zwischen zwei Versionen und daraus die Abschnitte bzw.
 * Zeilen, die Breaking Changes ankündigen.
 */
class GitHubReleaseNotes {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int PAGES = 3;
    private static final Pattern BREAKING_LINE = Pattern.compile(
            "(?i)(breaking|incompatib|backwards?[- ]compat|no longer|removed|⚠|:warning:|migration)");
    private static final Pattern BREAKING_HEADING = Pattern.compile("(?i)^\\s*#{1,6}.*(breaking|incompatib|migration|upgrad).*");
    private static final Pattern HEADING = Pattern.compile("^\\s*#{1,6}\\s.*");
    private static final Pattern VERSION_IN_TAG = Pattern.compile("(\\d+(?:\\.\\d+)*(?:[-.][\\w.]+)?)$");

    private final String apiUrl;
    private final String token;
    private final Duration timeout;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    GitHubReleaseNotes(String apiUrl, String token, Duration timeout) {
        this.apiUrl = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
        this.token = token;
        this.timeout = timeout;
    }

    record Release(String tag, String version, String name, String date, String url, List<String> breaking) {
    }

    /** Releases mit {@code from < Version <= to}, älteste zuerst. */
    List<Release> between(String repository, String artifactId, String from, String to) {
        List<Release> out = new ArrayList<>();
        for (int page = 1; page <= PAGES; page++) {
            JsonNode list = get("/repos/" + repository + "/releases?per_page=100&page=" + page);
            if (!list.isArray() || list.isEmpty()) {
                break;
            }
            for (JsonNode r : list) {
                if (r.path("draft").asBoolean(false)) {
                    continue;
                }
                String tag = r.path("tag_name").asString("");
                String version = versionOf(tag, artifactId);
                if (version == null) {
                    continue;
                }
                try {
                    if (MavenVersions.compare(version, from) <= 0 || MavenVersions.compare(version, to) > 0) {
                        continue;
                    }
                } catch (IllegalArgumentException e) {
                    continue;
                }
                String date = r.path("published_at").asString("");
                out.add(new Release(tag, version, r.path("name").asString(tag),
                        date.length() >= 10 ? date.substring(0, 10) : date, r.path("html_url").asString(""),
                        breakingLines(r.path("body").asString(""))));
            }
            if (list.size() < 100) {
                break;
            }
        }
        out.sort((a, b) -> MavenVersions.compare(a.version(), b.version()));
        return out;
    }

    /** Version aus einem Tag wie {@code v2.1.0}, {@code release-2.1.0} oder {@code jackson-core-2.17.0}. */
    static String versionOf(String tag, String artifactId) {
        String t = tag.trim();
        if (artifactId != null && t.startsWith(artifactId + "-")) {
            t = t.substring(artifactId.length() + 1);
        }
        Matcher m = VERSION_IN_TAG.matcher(t);
        if (!m.find()) {
            return null;
        }
        String v = m.group(1);
        // Präfix wie "v", "r", "v_" oder "release-" muss direkt vor der Version enden
        String prefix = t.substring(0, m.start());
        return prefix.isEmpty() || prefix.matches("(?i)(.*[-_/])?(v|r|rel|release|version)?[-_]?") ? v : null;
    }

    /** Zeilen unter Breaking-Überschriften sowie einzelne Zeilen mit einschlägigen Stichworten. */
    static List<String> breakingLines(String body) {
        List<String> out = new ArrayList<>();
        boolean inSection = false;
        for (String raw : body.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (HEADING.matcher(line).matches()) {
                inSection = BREAKING_HEADING.matcher(line).matches();
                if (inSection) {
                    out.add(line.replaceFirst("^#+\\s*", "## "));
                }
                continue;
            }
            if (inSection || BREAKING_LINE.matcher(line).find()) {
                out.add(line.length() > 300 ? line.substring(0, 300) + "…" : line);
            }
            if (out.size() >= 40) {
                out.add("…");
                break;
            }
        }
        return out;
    }

    private JsonNode get(String path) {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(apiUrl + path)).timeout(timeout)
                .header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28").GET();
        if (token != null && !token.isBlank()) {
            req.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> res;
        try {
            res = http.send(req.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("GitHub nicht erreichbar: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Abgebrochen", e);
        }
        if (res.statusCode() == 404) {
            throw new IllegalStateException("GitHub-Repository nicht gefunden (404).");
        }
        if (res.statusCode() == 403 || res.statusCode() == 429) {
            throw new IllegalStateException("GitHub-Rate-Limit erreicht (" + res.statusCode()
                    + ") – GitHub-Token in den Modul-Einstellungen hinterlegen.");
        }
        if (res.statusCode() >= 400) {
            throw new IllegalStateException("GitHub-Fehler " + res.statusCode());
        }
        return JSON.readTree(res.body());
    }
}
