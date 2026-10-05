package systems.grebe.devtools.mcp.modules.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * Dateiablage für abgerufene Seiten und ihre Zusammenfassungen, je Eintrag eine JSON-Datei unter {@code pages/} bzw.
 * {@code summaries/}. Einträge gelten {@code ttl} lang; eine Dauer von 0 schaltet den Cache ab. Abgelaufenes wird beim
 * Schreiben gelegentlich aufgeräumt.
 */
final class WebCache {

    private static final Logger LOG = LoggerFactory.getLogger(WebCache.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration PRUNE_INTERVAL = Duration.ofHours(1);

    /**
     * Zusammenfassung einer Seite für eine Fragestellung ({@code focus}, leer = allgemein).
     *
     * @param keyPoints    wichtigste Sätze der Seite, wörtlich
     * @param focusMatched ob Sätze zur Fragestellung gefunden wurden
     * @param truncated    ob der Download am Größenlimit abgeschnitten wurde
     * @param millis       Laufzeit des Modells
     */
    record Summary(String url, String finalUrl, String title, String model, String focus, String text,
                   List<String> keyPoints, boolean focusMatched, boolean truncated, long millis, Instant fetchedAt,
                   Instant createdAt) {
        Summary {
            keyPoints = keyPoints == null ? List.of() : List.copyOf(keyPoints);
        }
    }

    private final Path dir;
    private final Duration ttl;
    private final Clock clock;
    private Instant lastPrune = Instant.EPOCH;

    WebCache(Path dir, Duration ttl, Clock clock) {
        this.dir = dir;
        this.ttl = ttl;
        this.clock = clock;
    }

    boolean enabled() {
        return ttl.isPositive();
    }

    Path dir() {
        return dir;
    }

    Optional<PageFetcher.Page> page(String url) {
        return read(pageFile(url), PageFetcher.Page.class)
                .filter(p -> url.equals(p.url()) && fresh(p.fetchedAt()));
    }

    void putPage(PageFetcher.Page page) {
        write(pageFile(page.url()), page);
    }

    Optional<Summary> summary(String url, String model, String focus) {
        return read(summaryFile(url, model, focus), Summary.class)
                .filter(s -> url.equals(s.url()) && fresh(s.createdAt()));
    }

    void putSummary(Summary summary) {
        write(summaryFile(summary.url(), summary.model(), summary.focus()), summary);
    }

    /** Löscht alle Einträge; liefert ihre Anzahl. */
    int clear() {
        int n = 0;
        for (String sub : new String[] {"pages", "summaries"}) {
            Path d = dir.resolve(sub);
            if (!Files.isDirectory(d)) {
                continue;
            }
            try (Stream<Path> files = Files.list(d)) {
                for (Path f : files.filter(f -> f.toString().endsWith(".json")).toList()) {
                    Files.deleteIfExists(f);
                    n++;
                }
            } catch (IOException e) {
                throw new IllegalStateException("Cache nicht löschbar: " + e.getMessage(), e);
            }
        }
        return n;
    }

    /** Anzahl Seiten und Zusammenfassungen im Cache (auch abgelaufene, die noch nicht aufgeräumt sind). */
    int[] size() {
        return new int[] {count("pages"), count("summaries")};
    }

    private int count(String sub) {
        Path d = dir.resolve(sub);
        if (!Files.isDirectory(d)) {
            return 0;
        }
        try (Stream<Path> files = Files.list(d)) {
            return (int) files.filter(f -> f.toString().endsWith(".json")).count();
        } catch (IOException e) {
            return 0;
        }
    }

    private boolean fresh(Instant created) {
        return enabled() && created != null && created.plus(ttl).isAfter(clock.instant());
    }

    private Path pageFile(String url) {
        return dir.resolve("pages").resolve(hash(url) + ".json");
    }

    private Path summaryFile(String url, String model, String focus) {
        String f = focus == null ? "" : focus.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        return dir.resolve("summaries").resolve(hash(model + "\n" + url + "\n" + f) + ".json");
    }

    private <T> Optional<T> read(Path file, Class<T> type) {
        if (!enabled() || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(JSON.readValue(Files.readString(file, StandardCharsets.UTF_8), type));
        } catch (IOException | RuntimeException e) {
            LOG.debug("Cache-Eintrag {} unlesbar, wird ignoriert", file, e);
            return Optional.empty();
        }
    }

    private void write(Path file, Object value) {
        if (!enabled()) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, JSON.writeValueAsString(value), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            // Ein Cache, der nicht schreiben kann, darf den Aufruf nicht scheitern lassen
            LOG.warn("Cache-Eintrag {} nicht geschrieben: {}", file, e.getMessage());
        }
        pruneOccasionally();
    }

    private synchronized void pruneOccasionally() {
        Instant now = clock.instant();
        if (lastPrune.plus(PRUNE_INTERVAL).isAfter(now)) {
            return;
        }
        lastPrune = now;
        prune(now.minus(ttl));
    }

    private void prune(Instant cutoff) {
        for (String sub : new String[] {"pages", "summaries"}) {
            Path d = dir.resolve(sub);
            if (!Files.isDirectory(d)) {
                continue;
            }
            try (Stream<Path> files = Files.list(d)) {
                for (Path f : files.toList()) {
                    if (Files.getLastModifiedTime(f).toInstant().isBefore(cutoff)) {
                        Files.deleteIfExists(f);
                    }
                }
            } catch (IOException e) {
                LOG.debug("Cache {} nicht aufgeräumt", d, e);
            }
        }
    }

    static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
