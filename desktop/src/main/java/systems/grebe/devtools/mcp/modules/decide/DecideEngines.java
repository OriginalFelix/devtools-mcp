package systems.grebe.devtools.mcp.modules.decide;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.core.ModuleAction;
import systems.grebe.devtools.mcp.modules.decide.engine.Kernels;
import systems.grebe.devtools.mcp.modules.decide.engine.LayaModel;
import systems.grebe.devtools.mcp.modules.decide.engine.LlamaModel;

/**
 * Geladene Modelle, geteilt von allen Benutzern der App: einmal geladen (mmap), bleiben sie bis zum Wechsel der
 * Modelldatei oder der Thread-Zahl. Gerechnet wird nacheinander – die Kernels nutzen ohnehin alle Kerne.
 */
@Component
public class DecideEngines {

    private static final Logger LOG = LoggerFactory.getLogger(DecideEngines.class);

    private final ReentrantLock lock = new ReentrantLock(true);
    private Kernels kernels;
    private int threads = -1;
    private LayaModel laya;
    private Path layaDir;
    private LlamaModel llm;
    private Path llmPath;
    private int llmContext;
    private LlamaModel.Session session;

    /** Rechnet {@code body} mit dem Laya-Modell; lädt es (und bei Bedarf die Dateien) zuvor. */
    <T> T withLaya(DecideSettings s, Function<LayaModel, T> body) {
        lock.lock();
        try {
            Path dir = ModelFiles.layaDir(s);
            if (!ModelFiles.layaPresent(s)) {
                if (!s.autoDownload()) {
                    throw new IllegalStateException(missing("Laya-Modell (" + s.laya().id() + ")", dir));
                }
                ModelFiles.ensureLaya(s, ModuleAction.Progress.NONE);
            }
            Kernels k = kernels(s);
            if (laya == null || !dir.equals(layaDir)) {
                closeLaya();
                long start = System.nanoTime();
                laya = LayaModel.load(dir, k);
                layaDir = dir;
                LOG.info("Laya-Modell {} geladen ({} ms)", dir, (System.nanoTime() - start) / 1_000_000);
            }
            return body.apply(laya);
        } catch (IOException e) {
            throw new IllegalStateException("Laya-Modell nicht ladbar: " + e.getMessage(), e);
        } finally {
            lock.unlock();
        }
    }

    /** Rechnet {@code body} mit der Sitzung des LLM (KV-Cache bleibt zwischen Aufrufen erhalten). */
    <T> T withLlm(DecideSettings s, Function<LlmContext, T> body) {
        lock.lock();
        try {
            Path file = ModelFiles.llmPath(s);
            if (!ModelFiles.llmPresent(s)) {
                if (!s.autoDownload()) {
                    throw new IllegalStateException(missing("LLM", file));
                }
                ModelFiles.ensureLlm(s, ModuleAction.Progress.NONE);
            }
            Kernels k = kernels(s);
            if (llm == null || !file.equals(llmPath) || llmContext != s.maxLlmTokens()) {
                closeLlm();
                long start = System.nanoTime();
                llm = LlamaModel.load(file, k, s.maxLlmTokens());
                llmPath = file;
                llmContext = s.maxLlmTokens();
                session = llm.session();
                LOG.info("LLM {} geladen ({} ms)", file, (System.nanoTime() - start) / 1_000_000);
            }
            return body.apply(new LlmContext(llm, session));
        } catch (IOException e) {
            throw new IllegalStateException("LLM nicht ladbar: " + e.getMessage(), e);
        } finally {
            lock.unlock();
        }
    }

    /** Modell und KV-Cache-Sitzung für einen Aufruf. */
    record LlmContext(LlamaModel model, LlamaModel.Session session) {
    }

    private static String missing(String what, Path where) {
        return what + " fehlt unter " + where + ". In der App unter Module → Entscheidungen die Aktion „Modelle "
                + "herunterladen“ ausführen oder „Modelle automatisch herunterladen“ einschalten.";
    }

    private Kernels kernels(DecideSettings s) {
        if (kernels == null || threads != s.threads()) {
            // Modelle halten keine Referenz auf den Pool über den Aufruf hinaus – aber sie wurden mit ihm gebaut
            closeLaya();
            closeLlm();
            if (kernels != null) {
                kernels.shutdown();
            }
            kernels = new Kernels(s.threads());
            threads = s.threads();
            LOG.info("Entscheidungen: {} Rechen-Threads, {}", kernels.threads(), Kernels.simd());
        }
        return kernels;
    }

    /** Für {@code decide_status}: was geladen ist. */
    String describe() {
        if (!lock.tryLock()) {
            return "Modelle: gerade in Benutzung";
        }
        try {
            return "Geladen: " + (laya == null ? "kein Laya-Modell" : "Laya " + layaDir.getFileName())
                    + ", " + (llm == null ? "kein LLM" : "LLM " + llm.name() + " (" + llm.architecture() + ")")
                    + (kernels == null ? "" : ", " + kernels.threads() + " Rechen-Threads");
        } finally {
            lock.unlock();
        }
    }

    /** Gibt die Modelle frei (z.B. nach Änderung der Einstellungen). */
    void unload() {
        lock.lock();
        try {
            closeLaya();
            closeLlm();
        } finally {
            lock.unlock();
        }
    }

    private void closeLaya() {
        if (laya != null) {
            laya.close();
            laya = null;
            layaDir = null;
        }
    }

    private void closeLlm() {
        if (llm != null) {
            llm.close();
            llm = null;
            llmPath = null;
            session = null;
        }
    }

    @PreDestroy
    void shutdown() {
        unload();
        if (kernels != null) {
            kernels.shutdown();
        }
    }

    @Override
    public String toString() {
        return Objects.toString(layaDir) + " / " + llmPath;
    }
}
