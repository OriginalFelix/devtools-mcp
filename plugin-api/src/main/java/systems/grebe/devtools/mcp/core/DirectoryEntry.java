package systems.grebe.devtools.mcp.core;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Eine Zeile einer Verzeichnisliste ({@link FieldType#DIRECTORY_LIST}): {@code pfad} oder {@code name=pfad} (so übergibt
 * die Projektverwaltung freigegebene Projekte). Der Name darf nur Buchstaben, Ziffern, {@code . _ @ -} und Leerzeichen
 * enthalten - weder Pfadtrenner noch Doppelpunkt -, damit Pfade wie {@code C:/x=y} oder {@code /srv/a=b} nicht als
 * Name gelesen werden. Eine Auslegung für alle Module, Berechtigungsprüfung und Freigaben.
 *
 * @param name {@code null} = Eintrag ohne Namen
 * @param path Pfad, getrimmt (kann leer sein)
 * @since API 4
 */
public record DirectoryEntry(String name, String path) {

    private static final Pattern NAMED = Pattern.compile("([A-Za-z0-9._@ -]+)=(.+)");

    public static DirectoryEntry parse(String line) {
        String raw = line == null ? "" : line.strip();
        Matcher named = NAMED.matcher(raw);
        if (named.matches()) {
            return new DirectoryEntry(named.group(1).strip(), named.group(2).strip());
        }
        return new DirectoryEntry(null, raw);
    }

    /** Absoluter, normalisierter Pfad; leer bei leerem oder ungültigem Pfad (nicht das Arbeitsverzeichnis). */
    public Optional<Path> directory() {
        if (path.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Path.of(path).toAbsolutePath().normalize());
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
    }

    /** Meint der Eintrag dasselbe Verzeichnis (nach Normalisierung)? */
    public boolean sameDirectory(Path dir) {
        return directory().filter(dir.toAbsolutePath().normalize()::equals).isPresent();
    }
}
