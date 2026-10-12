package systems.grebe.devtools.mcp.modules.decide;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;

import systems.grebe.devtools.mcp.core.ModuleAction;

/**
 * Modelldateien des Moduls: wo sie liegen, woher sie kommen, ob sie vollständig sind. Standard-Downloads sind auf
 * eine Revision gepinnt und per SHA-256 geprüft; eigene URLs (Mirror) werden ohne Prüfsumme übernommen, wenn die
 * Datei nicht zu einer bekannten passt.
 */
final class ModelFiles {

    /** Eine Datei eines Modells: Pfad relativ zum Modellordner, Quelle, erwartete Prüfsumme (oder {@code null}). */
    record File(String path, String url, String sha256, long size) {
    }

    /** Laya-Checkpoints auf Hugging Face (Apache-2.0), gepinnt. */
    enum Laya {
        MULTILINGUAL("laya-multilingual", "1720e3e3357cfe1e281542e223f8273b0890ca34",
                "9d628fd971b700382ac6f65920a86f149777b2e748e0c955fb3b19695aa8f204", 643_835_514L,
                "609d8f4c067cd3950f88594c5a802616cea245823836ef5848ee4fc40aab5b6f", 34_363_188L),
        ENGLISH("laya", "7b928d828b7b0e022f929d9bd2e44165aa270148",
                "891102d372688fc2a094dac56a384bc537b87c63f21f9f3dac0be2b7cbc8d86c", 842_609_210L, null, 3_583_228L),
        TYPED_DECISIONS("laya-typed-decisions", "e929ae5cf69bc34259cd2f95c9e91145b818b1f0",
                "4fa56de72383a9d3efa9cfa78955733c81b9fc8067a587ca4beb82c78107a24e", 842_609_220L, null, 3_583_228L);

        final String repo;
        final String revision;
        final String modelSha;
        final long modelSize;
        final String tokenizerSha;
        final long tokenizerSize;

        Laya(String repo, String revision, String modelSha, long modelSize, String tokenizerSha, long tokenizerSize) {
            this.repo = repo;
            this.revision = revision;
            this.modelSha = modelSha;
            this.modelSize = modelSize;
            this.tokenizerSha = tokenizerSha;
            this.tokenizerSize = tokenizerSize;
        }

        static Laya parse(String s) {
            return switch (s == null ? "" : s.toLowerCase(Locale.ROOT)) {
                case "english", "laya" -> ENGLISH;
                case "typed-decisions", "typed_decisions", "typed" -> TYPED_DECISIONS;
                default -> MULTILINGUAL;
            };
        }

        String id() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }

        /** Basis-URL, unter der die Dateien des Checkpoints liegen. */
        String defaultBase() {
            return "https://huggingface.co/convaiinnovations/" + repo + "/resolve/" + revision + "/";
        }

        List<File> files(String base) {
            String b = base == null || base.isBlank() ? defaultBase() : (base.endsWith("/") ? base : base + "/");
            boolean pinned = b.equals(defaultBase());
            return List.of(
                    new File("rl_agent_config.json", b + "rl_agent_config.json", null, 0),
                    new File("encoder/config.json", b + "encoder/config.json", null, 0),
                    new File("tokenizer/tokenizer_config.json", b + "tokenizer/tokenizer_config.json", null, 0),
                    new File("tokenizer/tokenizer.json", b + "tokenizer/tokenizer.json", pinned ? tokenizerSha : null, tokenizerSize),
                    new File("model.safetensors", b + "model.safetensors", pinned ? modelSha : null, modelSize));
        }
    }

    /** Standard-LLM: Qwen2.5-0.5B-Instruct, Q8_0 (Apache-2.0), gepinnt. */
    static final String DEFAULT_LLM_URL = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/"
            + "9217f5db79a29953eb74d5343926648285ec7e67/qwen2.5-0.5b-instruct-q8_0.gguf";
    static final String DEFAULT_LLM_SHA = "ca59ca7f13d0e15a8cfa77bd17e65d24f6844b554a7b6c12e07a5f89ff76844e";
    static final long DEFAULT_LLM_SIZE = 675_710_816L;

    private static final ReentrantLock LOCK = new ReentrantLock();

    private ModelFiles() {
    }

    static Path layaDir(DecideSettings s) {
        return s.modelsDir().resolve(s.laya().repo);
    }

    static List<File> layaFiles(DecideSettings s) {
        return s.laya().files(s.layaUrl());
    }

    static File llmFile(DecideSettings s) {
        String url = s.llmUrl() == null || s.llmUrl().isBlank() ? DEFAULT_LLM_URL : s.llmUrl().strip();
        boolean pinned = url.equals(DEFAULT_LLM_URL);
        String name = url.substring(url.lastIndexOf('/') + 1);
        int q = name.indexOf('?');
        if (q >= 0) {
            name = name.substring(0, q);
        }
        if (!name.toLowerCase(Locale.ROOT).endsWith(".gguf")) {
            name = name + ".gguf";
        }
        return new File(name, url, pinned ? DEFAULT_LLM_SHA : null, pinned ? DEFAULT_LLM_SIZE : 0);
    }

    static Path llmPath(DecideSettings s) {
        return s.modelsDir().resolve(llmFile(s).path());
    }

    static boolean layaPresent(DecideSettings s) {
        Path dir = layaDir(s);
        return layaFiles(s).stream().allMatch(f -> Files.isRegularFile(dir.resolve(f.path()))
                && (f.size() <= 0 || size(dir.resolve(f.path())) == f.size()));
    }

    static boolean llmPresent(DecideSettings s) {
        File f = llmFile(s);
        Path p = llmPath(s);
        return Files.isRegularFile(p) && (f.size() <= 0 || size(p) == f.size());
    }

    private static long size(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return -1;
        }
    }

    /** Lädt fehlende Laya-Dateien; liefert den Modellordner. */
    static Path ensureLaya(DecideSettings s, ModuleAction.Progress progress) throws IOException {
        Path dir = layaDir(s);
        List<File> files = layaFiles(s);
        for (int i = 0; i < files.size(); i++) {
            File f = files.get(i);
            int index = i;
            download(f, dir.resolve(f.path()), (msg, frac) -> progress.update("Laya (" + s.laya().id() + "): " + msg,
                    (index + Math.max(0, frac)) / files.size()));
        }
        return dir;
    }

    /** Lädt das LLM, falls es fehlt; liefert die Datei. */
    static Path ensureLlm(DecideSettings s, ModuleAction.Progress progress) throws IOException {
        Path p = llmPath(s);
        download(llmFile(s), p, (msg, frac) -> progress.update("LLM: " + msg, frac));
        return p;
    }

    private static void download(File f, Path target, ModuleAction.Progress progress) throws IOException {
        LOCK.lock();
        try {
            if (Files.isRegularFile(target) && (f.size() <= 0 || Files.size(target) == f.size())) {
                return;
            }
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".download");
            HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS)
                    .connectTimeout(Duration.ofSeconds(20)).build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(f.url())).timeout(Duration.ofMinutes(60)).build();
            HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (res.statusCode() != 200) {
                res.body().close();
                throw new IOException("Download fehlgeschlagen (HTTP " + res.statusCode() + "): " + f.url());
            }
            long total = res.headers().firstValueAsLong("content-length").orElse(f.size());
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            try (InputStream in = res.body(); OutputStream out = Files.newOutputStream(tmp)) {
                byte[] buf = new byte[1 << 16];
                long done = 0;
                long lastReport = 0;
                int r;
                while ((r = in.read(buf)) > 0) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new IOException("Download abgebrochen");
                    }
                    out.write(buf, 0, r);
                    sha.update(buf, 0, r);
                    done += r;
                    if (done - lastReport > (1 << 22)) {
                        lastReport = done;
                        progress.update(f.path() + " " + (done >> 20) + (total > 0 ? " / " + (total >> 20) : "") + " MB",
                                total > 0 ? (double) done / total : -1);
                    }
                }
            } catch (IOException e) {
                Files.deleteIfExists(tmp);
                throw e;
            }
            String actual = HexFormat.of().formatHex(sha.digest());
            if (f.sha256() != null && !f.sha256().equals(actual)) {
                Files.deleteIfExists(tmp);
                throw new IOException("Prüfsumme stimmt nicht für " + f.path() + " (erwartet " + f.sha256() + ", erhalten "
                        + actual + ") – Download verworfen.");
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download abgebrochen", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } finally {
            LOCK.unlock();
        }
    }
}
