package systems.grebe.devtools.mcp.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

/**
 * Lokale Dateien, die ein Tool lesen oder schreiben darf: innerhalb der freigegebenen Verzeichnisse (Verzeichnisliste
 * des Moduls, ergänzt um die globalen „Freigaben“) – oder überall, wenn der Benutzer die Beschränkung aufgehoben hat
 * ({@link ToolScope#unrestricted()}). Relativ nur, wenn genau ein Verzeichnis freigegeben ist; auch der echte Pfad
 * (Symlinks) muss in der Freigabe liegen.
 */
public final class LocalFiles {

    private final List<Path> roots;
    private final String where;

    /**
     * @param configured Einträge der Verzeichnisliste ({@code ~} = Benutzerverzeichnis, {@code name=pfad} erlaubt)
     * @param where      wo der Nutzer Verzeichnisse freigibt, für Fehlermeldungen
     */
    public LocalFiles(List<String> configured, String where) {
        List<Path> dirs = new ArrayList<>();
        for (String raw : configured) {
            String entry = raw.strip();
            int eq = entry.indexOf('=');
            if (eq > 0 && !entry.substring(0, eq).matches(".*[/\\\\:].*")) {
                entry = entry.substring(eq + 1).strip();
            }
            try {
                Path p = expand(entry).toAbsolutePath().normalize();
                if (Files.isDirectory(p) && !dirs.contains(p)) {
                    dirs.add(p);
                }
            } catch (InvalidPathException ignored) {
                // ungültiger Eintrag – übergehen
            }
        }
        this.roots = List.copyOf(dirs);
        this.where = where;
    }

    public List<Path> roots() {
        return roots;
    }

    /** Vorhandene, lesbare Datei. */
    public Path readable(String path) {
        Path p = resolve(path);
        if (!Files.isRegularFile(p)) {
            throw new IllegalArgumentException(Files.isDirectory(p) ? p + " ist ein Verzeichnis – eine Datei angeben."
                    : "Datei " + p + " gibt es nicht.");
        }
        if (!Files.isReadable(p)) {
            throw new IllegalArgumentException("Datei " + p + " ist nicht lesbar.");
        }
        return p;
    }

    /** Ziel zum Schreiben; ist {@code path} ein vorhandenes Verzeichnis, kommt {@code fileName} hinein. */
    public Path writable(String path, String fileName) {
        Path p = resolve(path);
        if (Files.isDirectory(p)) {
            p = resolve(p.resolve(fileName).toString());
        }
        return p;
    }

    private Path resolve(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Lokaler Pfad fehlt.");
        }
        boolean anywhere = ToolScope.current().unrestricted();
        if (roots.isEmpty() && !anywhere) {
            throw new IllegalStateException("Kein lokales Verzeichnis für Dateien freigegeben – der Nutzer kann es in "
                    + "der DevTools-App unter " + where + " eintragen (oder permissions_request mit path).");
        }
        Path p;
        try {
            p = expand(path.strip());
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("Ungültiger lokaler Pfad: " + path);
        }
        if (!p.isAbsolute()) {
            if (roots.size() != 1) {
                throw new IllegalArgumentException("Absoluten Pfad angeben. Freigegeben: " + roots);
            }
            p = roots.getFirst().resolve(p);
        }
        Path target = p.toAbsolutePath().normalize();
        if (anywhere) {
            return target;
        }
        for (Path root : roots) {
            if (target.startsWith(root) && realPathInside(target, root)) {
                return target;
            }
        }
        throw new IllegalArgumentException("Lokaler Pfad " + target + " ist nicht freigegeben. Freigegeben: " + roots
                + " – weitere unter " + where + " oder mit permissions_request(path).");
    }

    /** Der echte Pfad des nächsten vorhandenen Vorfahren muss in der Freigabe liegen (kein Ausweg über Symlinks). */
    private static boolean realPathInside(Path target, Path root) {
        try {
            Path existing = target;
            while (existing != null && !Files.exists(existing)) {
                existing = existing.getParent();
            }
            return existing != null && existing.toRealPath().startsWith(root.toRealPath());
        } catch (IOException e) {
            return false;
        }
    }

    private static Path expand(String path) {
        return Path.of(path.replaceFirst("^~(?=[/\\\\]|$)", Matcher.quoteReplacement(System.getProperty("user.home"))));
    }
}
