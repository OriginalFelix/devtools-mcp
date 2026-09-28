package systems.grebe.devtools.mcp.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Startet externe Programme ohne Shell (Argumente werden nicht interpretiert) mit Timeout. */
public final class CommandRunner {

    public static final boolean WINDOWS = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    public static final Charset NATIVE = Charset.forName(
            System.getProperty("native.encoding", Charset.defaultCharset().name()));

    private static final int MAX_BYTES = 8 * 1024 * 1024;

    private CommandRunner() {
    }

    public record Result(List<String> command, int exitCode, boolean timedOut, String output) {

        public boolean ok() {
            return !timedOut && exitCode == 0;
        }

        /** Wirft mit verständlicher Meldung, falls der Aufruf fehlgeschlagen ist. */
        public Result orThrow(String what) {
            if (timedOut) {
                throw new IllegalStateException(what + ": Zeitüberschreitung");
            }
            if (exitCode != 0) {
                throw new IllegalStateException(what + " fehlgeschlagen (Exit-Code " + exitCode + "): "
                        + Text.limitLines(output.strip(), 30));
            }
            return this;
        }
    }

    public static Result run(List<String> command, Duration timeout) {
        return run(command, timeout, NATIVE);
    }

    public static Result run(List<String> command, Duration timeout, Charset charset) {
        return run(command, timeout, charset, null);
    }

    /** Wie {@link #run(List, Duration, Charset)}, mit Arbeitsverzeichnis ({@code null} = aktuelles). */
    public static Result run(List<String> command, Duration timeout, Charset charset, java.nio.file.Path workDir) {
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        if (workDir != null) {
            pb.directory(workDir.toFile());
        }
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new IllegalStateException("Programm nicht startbar: " + command.getFirst() + " (" + e.getMessage() + ")", e);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Thread reader = Thread.ofVirtual().start(() -> copy(p.getInputStream(), out));
        try {
            boolean finished = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
                p.waitFor(5, TimeUnit.SECONDS);
            }
            reader.join(Duration.ofSeconds(5));
            String text;
            synchronized (out) {
                text = out.toString(charset);
            }
            return new Result(command, finished ? p.exitValue() : -1, !finished, text);
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Abgebrochen", e);
        }
    }

    public static Result runUtf8(List<String> command, Duration timeout) {
        return run(command, timeout, StandardCharsets.UTF_8);
    }

    private static void copy(InputStream in, ByteArrayOutputStream out) {
        byte[] buf = new byte[8192];
        try (in) {
            int n;
            while ((n = in.read(buf)) >= 0) {
                synchronized (out) {
                    if (out.size() < MAX_BYTES) {
                        out.write(buf, 0, n);
                    }
                }
            }
        } catch (IOException ignored) {
            // Prozessende
        }
    }
}
