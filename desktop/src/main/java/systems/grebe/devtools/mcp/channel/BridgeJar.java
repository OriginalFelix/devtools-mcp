package systems.grebe.devtools.mcp.channel;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Optional;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.config.SettingsStore;

/**
 * Hält eine Kopie des laufenden Jars unter einem festen Pfad ({@code ~/.devtools-mcp/devtools-mcp.jar}), damit der
 * Eintrag in Claude Code ({@code java -jar ~/.devtools-mcp/devtools-mcp.jar stdio|channel}) nicht vom Ablageort und von
 * der Version des Jars abhängt. Beim Start wird kopiert, wenn Größe oder Änderungszeit abweichen.
 *
 * <p>Ersetzt wird über eine temporäre Datei und {@code move}: ein laufender Proxy liest nie ein halb geschriebenes Jar.
 * Hält ein laufender Proxy die Datei unter Windows gesperrt, versucht es die App alle {@link #RETRY} erneut, bis es
 * klappt. Aus der IDE ({@code bootRun}, Tests) gibt es kein Jar und damit nichts zu tun.
 */
@Component
public class BridgeJar {

    public static final String FILE_NAME = "devtools-mcp.jar";
    static final Duration RETRY = Duration.ofMinutes(1);
    private static final Logger LOG = LoggerFactory.getLogger(BridgeJar.class);

    private final Optional<Path> source;
    private final Path target;
    private volatile Thread retry;

    @Autowired
    public BridgeJar(SettingsStore store) {
        this(runningJar(System.getProperty("java.class.path", "")), path(store.dir()));
    }

    BridgeJar(Optional<Path> source, Path target) {
        this.source = source;
        this.target = target;
    }

    /** Fester Pfad der Kopie im Datenverzeichnis der App. */
    public static Path path(Path home) {
        return home.resolve(FILE_NAME).toAbsolutePath();
    }

    /** Jar dieser App, wenn sie mit {@code java -jar} läuft; aus der IDE leer. */
    public static Optional<Path> runningJar(String classPath) {
        return !classPath.contains(File.pathSeparator) && classPath.endsWith(".jar")
                ? Optional.of(Path.of(classPath).toAbsolutePath()) : Optional.empty();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (source.isEmpty() || sync()) {
            return;
        }
        retry = Thread.ofPlatform().daemon().name("bridge-jar").start(() -> {
            try {
                do {
                    Thread.sleep(RETRY);
                } while (!sync());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    @PreDestroy
    void stop() {
        Thread t = retry;
        if (t != null) {
            t.interrupt();
        }
    }

    /** Bringt die Kopie auf den Stand des laufenden Jars; {@code false}, wenn das (vorerst) nicht ging. */
    boolean sync() {
        if (source.isEmpty()) {
            return true;
        }
        Path src = source.get();
        Path tmp = null;
        try {
            if (upToDate(src)) {
                return true;
            }
            Files.createDirectories(target.getParent());
            tmp = Files.createTempFile(target.getParent(), FILE_NAME, ".tmp");
            Files.copy(src, tmp, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            LOG.info("Jar für den stdio-Proxy aktualisiert: {}", target);
            return true;
        } catch (IOException e) {
            LOG.warn("Jar für den stdio-Proxy nicht aktualisiert ({}): {} – neuer Versuch in {} s", target,
                    e.toString(), RETRY.toSeconds());
            return false;
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // bleibt liegen, stört nicht
                }
            }
        }
    }

    private boolean upToDate(Path src) throws IOException {
        if (!Files.isRegularFile(target)) {
            return false;
        }
        return Files.isSameFile(src, target) // App läuft aus der Kopie selbst
                || Files.size(src) == Files.size(target)
                && Files.getLastModifiedTime(src).equals(Files.getLastModifiedTime(target));
    }
}
