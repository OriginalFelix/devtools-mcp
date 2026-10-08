package systems.grebe.devtools.mcp.modules.skills;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import systems.grebe.devtools.mcp.api.MediaTypes;

/** Lesemodell für die Oberfläche – unabhängig von JPA-Entities und Lazy Loading. */
public final class SkillViews {

    private SkillViews() {
    }

    /** Herkunft eines sichtbaren Skills aus Sicht des aktuellen Benutzers. */
    public enum Scope {
        /** Eigener Skill. */
        OWN,
        /** Persönliche Kopie einer globalen Vorlage (verdeckt die Vorlage). */
        COPY,
        /** Globale, schreibgeschützte Vorlage. */
        GLOBAL
    }

    /**
     * Zeile der Übersicht. {@code templateRevision}: bei {@link Scope#COPY} die Revision der Vorlage beim Kopieren;
     * {@code currentTemplateRevision}: die aktuelle Revision der Vorlage (oder {@code null}, wenn sie inzwischen
     * zurückgezogen wurde); {@code triggers}: Tool-Namen bzw. Präfixe ({@code pr_*}), für die der Skill registriert ist.
     */
    public record Summary(String name, String description, String category, List<String> tags, int revision,
                          long useCount, Instant lastUsedAt, Instant updatedAt, int fileCount, Scope scope,
                          Integer templateRevision, Integer currentTemplateRevision, List<String> triggers) {

        public Summary {
            triggers = triggers == null ? List.of() : List.copyOf(triggers);
        }

        /** Ist der Skill für dieses Tool registriert (exakter Name oder Präfix mit {@code *})? */
        public boolean triggeredBy(String toolName) {
            return triggers.stream().anyMatch(t -> t.endsWith("*")
                    ? toolName.startsWith(t.substring(0, t.length() - 1)) : t.equals(toolName));
        }

        public boolean global() {
            return scope == Scope.GLOBAL;
        }

        /** Die Vorlage wurde nach dem Kopieren weiterentwickelt. */
        public boolean templateUpdated() {
            return scope == Scope.COPY && templateRevision != null && currentTemplateRevision != null
                    && currentTemplateRevision > templateRevision;
        }
    }

    /**
     * Zusatzdatei: Text direkt im Skill ({@code content}, mit {@code skills_patch} änderbar) oder ein Anhang mit
     * beliebigem Inhalt in der Dateiablage des Backends ({@code blob} = SHA-256, {@code content} leer).
     *
     * @param size Größe in Bytes
     */
    public record File(String path, String content, Instant updatedAt, long size, String mediaType, String blob) {

        /** Textdatei im Skill. */
        public File(String path, String content, Instant updatedAt) {
            this(path, content, updatedAt, content == null ? 0 : content.getBytes(StandardCharsets.UTF_8).length,
                    MediaTypes.guess(path), null);
        }

        /** Text im Skill statt Anhang in der Dateiablage. */
        public boolean inline() {
            return blob == null;
        }
    }

    /** Eintrag der Änderungshistorie. */
    public record Revision(int revision, String action, String note, String changedBy, Instant changedAt,
                           String description, String content) {
    }

    /** Vollständiger Skill für die Detailansicht. */
    public record Details(Summary summary, String content, Instant createdAt, List<File> files,
                          List<Revision> revisions) {
    }

    // ------------------------------------------------------------------ Prüfer (vom Backend und der App gemeinsam genutzt)

    public static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    /** Verzeichnisse für Zusatzdateien eines Skills. */
    public static final List<String> FILE_DIRS = List.of("references/", "templates/", "scripts/", "assets/");

    /** Gültiger Skill-Name oder {@link IllegalArgumentException} - die App prüft vor einem Upload, nicht erst danach. */
    public static String requireName(String name) {
        String n = name == null ? "" : name.trim();
        if (!NAME.matcher(n).matches()) {
            throw new IllegalArgumentException("Ungültiger Skill-Name '" + n + "': Kleinbuchstaben, Ziffern, "
                    + "'.', '_' und '-', beginnend mit Buchstabe/Ziffer, max. 64 Zeichen (z.B. 'wildfly-heap-leak').");
        }
        return n;
    }

    /** Relativer Pfad einer Zusatzdatei unter {@link #FILE_DIRS} ohne {@code ..}, höchstens 200 Zeichen. */
    public static String normalizePath(String path) {
        String p = path == null ? "" : path.trim().replace('\\', '/');
        List<String> segments = new ArrayList<>(Arrays.asList(p.split("/")));
        boolean valid = FILE_DIRS.stream().anyMatch(p::startsWith)
                && segments.size() >= 2
                && segments.stream().noneMatch(x -> x.isEmpty() || x.equals(".") || x.equals(".."))
                && p.length() <= 200
                && p.matches("[A-Za-z0-9._/-]+");
        if (!valid) {
            throw new IllegalArgumentException("Ungültiger Dateipfad '" + path + "': relativ, beginnend mit "
                    + String.join(", ", FILE_DIRS) + " ohne '..' (z.B. 'references/api.md').");
        }
        return p;
    }
}
