package systems.grebe.devtools.mcp.modules.java;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import systems.grebe.devtools.mcp.config.SettingsStore;
import systems.grebe.devtools.mcp.api.Sha256;

/** Lädt externe Werkzeuge (async-profiler, VisualVM) einmalig herunter und prüft ihre SHA-256-Prüfsumme. */
public final class ToolDownloads {

    /** Fest hinterlegte Release-Artefakte (Prüfsummen aus den GitHub-Releases). */
    public enum Artifact {
        ASYNC_PROFILER_LINUX_X64("async-profiler-4.5-linux-x64.tar.gz",
                "https://github.com/async-profiler/async-profiler/releases/download/v4.5/async-profiler-4.5-linux-x64.tar.gz",
                "89546fbb9ee0fc5496c7edd4099b0709489bc78b0d8057ccbb4b801f6b032b62"),
        ASYNC_PROFILER_LINUX_ARM64("async-profiler-4.5-linux-arm64.tar.gz",
                "https://github.com/async-profiler/async-profiler/releases/download/v4.5/async-profiler-4.5-linux-arm64.tar.gz",
                "64c41d1465d60097439c50d7e924b4946f1f62b1cbd21ce5b034fad09c0d6979"),
        ASYNC_PROFILER_MACOS("async-profiler-4.5-macos.zip",
                "https://github.com/async-profiler/async-profiler/releases/download/v4.5/async-profiler-4.5-macos.zip",
                "46d04ef81f532a065a0b3877e488aa706afa14aa2ea14433b323db9e6fda76dc"),
        VISUALVM("visualvm_222.zip",
                "https://github.com/oracle/visualvm/releases/download/2.2.2/visualvm_222.zip",
                "5c234f3b241b9d6c33ed4223f86fbf53604da19253884d6eaec28f8b50d1e0b1");

        final String fileName;
        final String url;
        final String sha256;

        Artifact(String fileName, String url, String sha256) {
            this.fileName = fileName;
            this.url = url;
            this.sha256 = sha256;
        }

        public String fileName() {
            return fileName;
        }
    }

    private static final ConcurrentHashMap<Artifact, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

    private ToolDownloads() {
    }

    public static Path toolsDir() {
        return SettingsStore.defaultHome().resolve("tools");
    }

    /** Lokale Datei des Artefakts; lädt es bei Bedarf herunter. */
    public static Path get(Artifact a) {
        Path target = toolsDir().resolve(a.fileName);
        ReentrantLock lock = LOCKS.computeIfAbsent(a, k -> new ReentrantLock());
        lock.lock();
        try {
            if (Files.isRegularFile(target) && a.sha256.equals(sha256(target))) {
                return target;
            }
            Files.createDirectories(toolsDir());
            Path tmp = target.resolveSibling(a.fileName + ".download");
            HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS)
                    .connectTimeout(Duration.ofSeconds(20)).build();
            HttpResponse<Path> res = http.send(HttpRequest.newBuilder(URI.create(a.url)).timeout(Duration.ofMinutes(10)).build(),
                    HttpResponse.BodyHandlers.ofFile(tmp));
            if (res.statusCode() != 200) {
                throw new IllegalStateException("Download fehlgeschlagen (HTTP " + res.statusCode() + "): " + a.url);
            }
            String actual = sha256(tmp);
            if (!a.sha256.equals(actual)) {
                Files.deleteIfExists(tmp);
                throw new IllegalStateException("Prüfsumme stimmt nicht für " + a.fileName + " (erwartet " + a.sha256
                        + ", erhalten " + actual + ") – Download verworfen.");
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException e) {
            throw new IllegalStateException("Download von " + a.url + " fehlgeschlagen: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Download abgebrochen", e);
        } finally {
            lock.unlock();
        }
    }

    /** Entpackt ein Zip (einmalig) in {@code tools/<name>} und liefert das Verzeichnis. */
    public static Path unzipOnce(Path zip, String dirName) {
        Path dest = toolsDir().resolve(dirName);
        Path marker = dest.resolve(".complete");
        if (Files.exists(marker)) {
            return dest;
        }
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                Path out = dest.resolve(e.getName()).normalize();
                if (!out.startsWith(dest)) {
                    throw new IOException("Ungültiger Eintrag im Archiv: " + e.getName());
                }
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                    if (e.getName().contains("/bin/")) {
                        out.toFile().setExecutable(true, false);
                    }
                }
            }
            Files.writeString(marker, "ok");
            return dest;
        } catch (IOException ex) {
            throw new IllegalStateException("Entpacken fehlgeschlagen: " + ex.getMessage(), ex);
        }
    }

    public static String sha256(Path file) {
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), Sha256.newDigest())) {
            MessageDigest md = ((DigestInputStream) in).getMessageDigest();
            in.transferTo(java.io.OutputStream.nullOutputStream());
            return Sha256.hex(md);
        } catch (IOException e) {
            return "";
        }
    }
}
