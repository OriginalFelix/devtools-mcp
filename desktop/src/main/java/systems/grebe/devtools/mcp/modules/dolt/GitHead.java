package systems.grebe.devtools.mcp.modules.dolt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * Liest den aktuellen Branch eines Git-Arbeitsverzeichnisses direkt aus {@code HEAD} – ohne JGit, weil das bei jedem
 * Dateiereignis im Git-Verzeichnis geschieht und nur Mikrosekunden kosten soll. Worktrees und Submodule (Datei
 * {@code .git} mit {@code gitdir: …}) werden aufgelöst.
 */
final class GitHead {

    private static final String REF_PREFIX = "ref: refs/heads/";

    private GitHead() {
    }

    /**
     * Git-Verzeichnis des Arbeitsverzeichnisses: {@code .git} oder – bei Worktrees und Submodulen – das Verzeichnis,
     * auf das die Datei {@code .git} zeigt. Darin liegt das {@code HEAD}, das sich beim Branch-Wechsel ändert.
     */
    static Path gitDir(Path workTree) throws IOException {
        Path dotGit = workTree.resolve(".git");
        if (Files.isDirectory(dotGit)) {
            return dotGit;
        }
        if (Files.isRegularFile(dotGit)) {
            String content = Files.readString(dotGit, StandardCharsets.UTF_8).strip();
            if (content.startsWith("gitdir:")) {
                try {
                    Path target = Path.of(content.substring("gitdir:".length()).strip());
                    return (target.isAbsolute() ? target : workTree.resolve(target)).normalize();
                } catch (InvalidPathException e) {
                    throw new IOException("Ungültiger Verweis in " + dotGit + ": " + content, e);
                }
            }
            throw new IOException(dotGit + " enthält keinen gitdir-Verweis.");
        }
        throw new IOException("Kein Git-Arbeitsverzeichnis: " + workTree + " (kein .git gefunden)");
    }

    /** Aktueller Branch (ohne {@code refs/heads/}) oder {@code null} bei losgelöstem HEAD (Rebase, Bisect, Tag …). */
    static String branch(Path gitDir) throws IOException {
        String head = Files.readString(gitDir.resolve("HEAD"), StandardCharsets.UTF_8).strip();
        return head.startsWith(REF_PREFIX) ? head.substring(REF_PREFIX.length()) : null;
    }
}
