package systems.grebe.devtools.mcp.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Verknüpfte Worktrees ({@code git worktree add}) freigegebener Repositories – für Module, die ein Repository über
 * {@link Workspaces} auflösen und einen Worktree nicht dem Haupt-Repository zuordnen sollen (das gälte sonst für jeden
 * Pfad darunter, z.B. {@code .claude/worktrees/…}).
 */
public final class GitWorktrees {

    /**
     * Verknüpfter Worktree eines freigegebenen Repositories.
     *
     * @param name {@code <repository>/<ordner>}, so in den Tools anzugeben
     * @param main Arbeitsverzeichnis des Haupt-Repositories – maßgeblich für Schreibfreigabe und Projekt-Identität
     */
    public record Worktree(String name, Path dir, Path main) { }

    private GitWorktrees() {
    }

    /** Worktrees der Repositories (Name → Arbeitsverzeichnis mit {@code .git}-Ordner), aus {@code .git/worktrees/*}/gitdir. */
    public static List<Worktree> of(Map<String, Path> repositories) {
        List<Worktree> out = new ArrayList<>();
        for (Map.Entry<String, Path> e : repositories.entrySet()) {
            Path meta = e.getValue().resolve(".git").resolve("worktrees");
            if (!Files.isDirectory(meta)) {
                continue;
            }
            try (Stream<Path> dirs = Files.list(meta)) {
                for (Path d : dirs.sorted().toList()) {
                    Path gitdir = d.resolve("gitdir");
                    if (!Files.isRegularFile(gitdir)) {
                        continue;
                    }
                    Path dotGit = Path.of(Files.readString(gitdir).strip());
                    Path dir = (dotGit.isAbsolute() ? dotGit : d.resolve(dotGit)).normalize().getParent();
                    if (dir != null && Files.isDirectory(dir)) {
                        out.add(new Worktree(e.getKey() + "/" + dir.getFileName(), dir.toAbsolutePath().normalize(),
                                e.getValue().toAbsolutePath().normalize()));
                    }
                }
            } catch (IOException | InvalidPathException ex) {
                // nicht lesbar -> ohne Worktrees
            }
        }
        return out;
    }

    /**
     * Worktree zu {@code <repository>/<ordner>} oder einem Pfad darin; {@code null}, wenn keiner passt. Bei Pfaden gewinnt
     * der speziellste Worktree (Worktrees liegen oft im Haupt-Repository, z.B. {@code .claude/worktrees}).
     */
    public static Worktree find(List<Worktree> all, String wanted) {
        if (wanted == null || wanted.isBlank()) {
            return null;
        }
        String w = wanted.trim();
        for (Worktree t : all) {
            if (t.name().equalsIgnoreCase(w)) {
                return t;
            }
        }
        Path p;
        try {
            p = Path.of(w);
        } catch (InvalidPathException e) {
            return null;
        }
        Path real = realPath(p);
        return all.stream().filter(t -> real.startsWith(realPath(t.dir())))
                .max(Comparator.comparingInt(t -> t.dir().getNameCount())).orElse(null);
    }

    /** Haupt-Repository eines Verzeichnisses: für einen Worktree das Repository, zu dem er gehört, sonst es selbst. */
    public static Path main(List<Worktree> all, Path root) {
        return all.stream().filter(w -> w.dir().equals(root)).map(Worktree::main).findFirst().orElse(root);
    }

    /**
     * Pfad mit aufgelösten Symlinks (z.B. macOS {@code /var} → {@code /private/var}; git schreibt den echten Pfad
     * nach {@code gitdir}); existiert der Pfad nicht, wird der nächste vorhandene Elternordner aufgelöst.
     */
    static Path realPath(Path p) {
        Path abs = p.toAbsolutePath().normalize();
        for (Path base = abs; base != null; base = base.getParent()) {
            try {
                return base.toRealPath().resolve(base.relativize(abs));
            } catch (IOException e) {
                // weiter mit dem Elternordner
            }
        }
        return abs;
    }
}
