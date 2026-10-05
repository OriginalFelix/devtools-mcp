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
 * {@code summaries/}. Wie lange eine Seite gilt, steht in der Seite selbst ({@link PageFetcher.Page#expiresAt()}, aus
 * den HTTP-Headern oder unbegrenzt); abgelaufene Seiten bleiben liegen, damit sie sich per bedingter Anfrage bestätigen
 * lassen. Eine Zusammenfassung gilt, solange der Text der Seite derselbe ist ({@link Summary#pageHash()}). Verwaiste
 * Zusammenfassungen werden beim Schreiben gelegentlich aufgeräumt.
 */
final class WebCache {

    private static final Logger LOG = LoggerFactory.getLogger(WebCache.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration PRUNE_INTERVAL = Duration.ofHours(1);

    /**
     * Zusammenfassung einer Seite für eine Fragestellung ({@code focus}, leer = allgemein).
     *
     * @param via          wo das Modell lief: „lokal“ oder „Claude API“
     * @param keyPoints    wichtigste Sätze der Seite, wörtlich
     * @param focusMatched ob Sätze zur Fragestellung gefunden wurden
     * @param truncated    ob der Download am Größenlimit abgeschnitten wurde
     * @param millis       Laufzeit des Modells
     * @param pageHash     Prüfsumme des Seitentextes, aus dem die Zusammenfassung entstand
     * @param inputTokens  Verbrauch (nur Claude API), {@code -1} = unbekannt
     * @param outputTokens Verbrauch (nur Claude API), {@code -1} = unbekannt
     * @param contextChars so viele Zeichen der Seite hat das Modell gesehen, {@code -1} = Auszug (lokal)
     */
    record Summary(String url, String finalUrl, String title, String model, String via, String focus, String text,
                   List<String> keyPoints, boolean focusMatched, boolean truncated, long millis, Instant fetchedAt,
                   Instant createdAt, String pageHash, long inputTokens, long outputTokens, int contextChars) {
        Summary {
            keyPoints = keyPoints == null ? List.of() : List.copyOf(keyPoints);
        }
    }

    private final Path dir;
    private final boolean enabled;
    private final Clock clock;
    private Instant lastPrune = Instant.EPOCH;

    WebCache(Path dir, boolean enabled, Clock clock) {
        this.dir = dir;
        this.enabled = enabled;
        this.clock = clock;
    }

    boolean enabled() {
        return enabled;
    }

    Path dir() {
        return dir;
    }

    /** Gespeicherte Seite, auch abgelaufen – ob sie noch gilt, sagt {@link PageFetcher.Page#fresh(Instant)}. */
    Optional<PageFetcher.Page> page(String url) {
        return read(pageFile(url), PageFetcher.Page.class).filter(p -> url.equals(p.url()));
    }

    /** Speichert die Seite, außer der Server hat es mit {@code no-store} untersagt. */
    void putPage(PageFetcher.Page page) {
        if (!page.noStore()) {
            write(pageFile(page.url()), page);
        }
    }

    /** Zusammenfassung zu genau diesem Seitentext ({@code pageHash}). */
    Optional<Summary> summary(String url, String model, String focus, String pageHash) {
        return read(summaryFile(url, model, focus), Summary.class)
                .filter(s -> url.equals(s.url()) && pageHash.equals(s.pageHash()));
    }

    /** Speichert die Zusammenfassung, außer die Seite selbst darf nicht gespeichert werden. */
    void putSummary(Summary summary, PageFetcher.Page page) {
        if (!page.noStore()) {
            write(summaryFile(summary.url(), summary.model(), summary.focus()), summary);
        }
    }

    /** Löscht alle Einträge; liefert ihre Anzahl. */
    int clear() {
        int n = 0;
        for (String sub : new String[] {"pages", "summaries"}) {
            for (Path f : files(sub)) {
                try {
                    Files.deleteIfExists(f);
                    n++;
                } catch (IOException e) {
                    throw new IllegalStateException("Cache nicht löschbar: " + e.getMessage(), e);
                }
            }
        }
        return n;
    }

    /** Anzahl Seiten und Zusammenfassungen im Cache. */
    int[] size() {
        return new int[] {files("pages").size(), files("summaries").size()};
    }

    private List<Path> files(String sub) {
        Path d = dir.resolve(sub);
        if (!Files.isDirectory(d)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(d)) {
            return files.filter(f -> f.toString().endsWith(".json")).toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private Path pageFile(String url) {
        return dir.resolve("pages").resolve(hash(url) + ".json");
    }

    private Path summaryFile(String url, String model, String focus) {
        String f = focus == null ? "" : focus.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        return dir.resolve("summaries").resolve(hash(model + "\n" + url + "\n" + f) + ".json");
    }

    private <T> Optional<T> read(Path file, Class<T> type) {
        if (!enabled || !Files.isRegularFile(file)) {
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
        if (!enabled) {
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
        prune();
    }

    /** Entfernt Zusammenfassungen, deren Seite fehlt oder inzwischen einen anderen Text hat. */
    private void prune() {
        for (Path f : files("summaries")) {
            Optional<Summary> s = read(f, Summary.class);
            boolean orphan = s.isEmpty() || s.get().pageHash() == null
                    || page(s.get().url()).map(p -> !p.contentHash().equals(s.get().pageHash())).orElse(true);
            if (orphan) {
                try {
                    Files.deleteIfExists(f);
                } catch (IOException e) {
                    LOG.debug("Cache-Eintrag {} nicht aufgeräumt", f, e);
                }
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
