package systems.grebe.devtools.mcp.modules.skills;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fachlogik des Skill-Speichers: Validierung, Suche, Anlegen, gezieltes Patchen, Zusatzdateien und Historie über die
 * Spring-Data-Repositories. Jede öffentliche Methode läuft in einer Transaktion und liefert kompakten Text für das
 * LLM; fachliche Fehler werden als {@link IllegalArgumentException} mit einem Hinweis auf den nächsten sinnvollen
 * Schritt gemeldet und rollen die Transaktion zurück.
 *
 * <p>Die Inhaltsgrenze kommt als Parameter, weil sie in der UI zur Laufzeit geändert werden kann, der Service aber
 * ein Singleton ist.
 */
@Service
@Transactional
public class SkillService {

    static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    static final Pattern CATEGORY = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");
    static final List<String> FILE_DIRS = List.of("references/", "templates/", "scripts/", "assets/");
    static final int MAX_DESCRIPTION = 1024;
    static final int MAX_TAGS = 500;
    static final int MAX_NOTE = 500;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private final SkillRepository skills;
    private final SkillRevisionRepository revisions;

    public SkillService(SkillRepository skills, SkillRevisionRepository revisions) {
        this.skills = skills;
        this.revisions = revisions;
    }

    // ------------------------------------------------------------------ Lesen

    @Transactional(readOnly = true)
    public String list(String query, String category) {
        String q = blankToNull(query);
        String c = blankToNull(category);
        List<Skill> found = skills.search(q == null ? null : "%" + q.toLowerCase(Locale.ROOT) + "%",
                c == null ? null : c.toLowerCase(Locale.ROOT));
        if (found.isEmpty()) {
            return q == null && c == null
                    ? "Noch keine Skills gespeichert. Nach einer schwierigen oder mehrstufigen Aufgabe mit "
                    + "skills_create den Ablauf festhalten."
                    : "Keine Skills gefunden" + (q != null ? " für '" + q + "'" : "")
                    + (c != null ? " in Kategorie '" + c + "'" : "") + ". skills_list ohne Filter zeigt alle.";
        }
        StringBuilder sb = new StringBuilder(found.size() + " Skill(s)")
                .append(q != null ? " für '" + q + "'" : "").append(":\n");
        String lastCategory = "\u0000";
        for (Skill k : found) {
            String cat = k.getCategory() == null ? "(ohne Kategorie)" : k.getCategory();
            if (!cat.equals(lastCategory)) {
                sb.append(cat).append(":\n");
                lastCategory = cat;
            }
            sb.append("  - ").append(k.getName()).append(": ").append(k.getDescription());
            if (!k.tagList().isEmpty()) {
                sb.append("  [").append(String.join(", ", k.tagList())).append(']');
            }
            sb.append('\n');
        }
        return sb.append("Passende Skills vor Beginn der Aufgabe mit skills_view laden.").toString();
    }

    /** Schreibend, weil die Nutzung gezählt wird. */
    public String view(String name, String filePath) {
        String n = requireName(name);
        String path = blankToNull(filePath) == null ? null : normalizePath(filePath);
        Skill k = find(n);
        if (path != null) {
            SkillFile f = k.file(path).orElseThrow(() -> new IllegalArgumentException(
                    "Skill '" + n + "' hat keine Datei '" + path + "'. Vorhanden: " + filePaths(k)));
            return "# " + n + " / " + path + "\n\n" + f.getContent();
        }
        skills.markUsed(k.getId(), Instant.now());
        StringBuilder sb = new StringBuilder();
        sb.append("---\nname: ").append(k.getName())
                .append("\ndescription: ").append(k.getDescription());
        if (k.getCategory() != null) {
            sb.append("\ncategory: ").append(k.getCategory());
        }
        if (!k.tagList().isEmpty()) {
            sb.append("\ntags: [").append(String.join(", ", k.tagList())).append(']');
        }
        sb.append("\nrevision: ").append(k.getRevision())
                .append("\nupdated: ").append(DATE.format(k.getUpdatedAt()))
                .append("\n---\n\n").append(k.getContent());
        if (!k.getFiles().isEmpty()) {
            sb.append("\n\n---\nZusatzdateien (mit skills_view(name, file_path) laden): ").append(filePaths(k));
        }
        return sb.toString();
    }

    @Transactional(readOnly = true)
    public String history(String name, Integer revision) {
        String n = requireName(name);
        Skill k = find(n);
        if (revision != null) {
            SkillRevision r = revisions.findBySkillAndRevision(k, revision)
                    .orElseThrow(() -> new IllegalArgumentException("Skill '" + n + "' hat keine Revision "
                            + revision + " (aktuell " + k.getRevision() + ")."));
            return "# " + n + " – Revision " + r.getRevision() + " (" + r.getAction() + ", "
                    + DATE.format(r.getChangedAt()) + ")\ndescription: " + r.getDescription() + "\n\n"
                    + r.getContent();
        }
        StringBuilder sb = new StringBuilder("Historie von '" + n + "' (neueste zuerst):\n");
        for (SkillRevision r : revisions.findBySkillOrderByRevisionDesc(k)) {
            sb.append("  ").append(r.getRevision()).append("  ").append(DATE.format(r.getChangedAt()))
                    .append("  ").append(r.getAction());
            if (r.getNote() != null) {
                sb.append("  – ").append(r.getNote());
            }
            sb.append('\n');
        }
        return sb.append("Einzelne Stände mit skills_history(name, revision) ansehen.").toString();
    }

    /** Anzahl gespeicherter Skills – für „Verbindung testen“. */
    @Transactional(readOnly = true)
    public long count() {
        return skills.count();
    }

    // ------------------------------------------------------------------ Schreiben

    public String create(String name, String description, String content, String category, List<String> tags,
                         int maxContentChars) {
        String n = requireName(name);
        String d = requireDescription(description);
        String body = requireContent(content, "content", maxContentChars);
        String cat = normalizeCategory(category);
        String t = normalizeTags(tags);
        if (skills.existsByName(n)) {
            throw new IllegalArgumentException("Skill '" + n + "' existiert bereits. Mit skills_view ansehen und "
                    + "mit skills_patch ergänzen, statt ihn neu anzulegen.");
        }
        Skill k = new Skill(n, d, body, cat, t, Instant.now());
        k.recordRevision("create", null, k.getCreatedAt());
        skills.save(k);
        return "Skill '" + n + "' angelegt (Revision 1).";
    }

    public String update(String name, String description, String content, String category, List<String> tags,
                         String note, Integer expectedRevision, int maxContentChars) {
        String n = requireName(name);
        Skill k = find(n);
        checkRevision(k, expectedRevision);
        boolean changed = false;
        if (description != null) {
            String d = requireDescription(description);
            changed |= !d.equals(k.getDescription());
            k.setDescription(d);
        }
        if (content != null) {
            String body = requireContent(content, "content", maxContentChars);
            changed |= !body.equals(k.getContent());
            k.setContent(body);
        }
        if (category != null) {
            String cat = normalizeCategory(category);
            changed |= !Objects.equals(cat, k.getCategory());
            k.setCategory(cat);
        }
        if (tags != null) {
            String t = normalizeTags(tags);
            changed |= !Objects.equals(t, k.getTags());
            k.setTags(t);
        }
        if (!changed) {
            return "Keine Änderung an '" + n + "' (Revision " + k.getRevision() + ").";
        }
        k.recordRevision("update", normalizeNote(note), Instant.now());
        return "Skill '" + n + "' aktualisiert (Revision " + k.getRevision() + ").";
    }

    public String patch(String name, String oldString, String newString, Boolean replaceAll, String filePath,
                        String note, Integer expectedRevision, int maxContentChars) {
        String n = requireName(name);
        if (oldString == null || oldString.isEmpty()) {
            throw new IllegalArgumentException("'old_string' darf nicht leer sein.");
        }
        String replacement = newString == null ? "" : newString;
        if (replacement.equals(oldString)) {
            throw new IllegalArgumentException("'new_string' muss sich von 'old_string' unterscheiden.");
        }
        boolean all = Boolean.TRUE.equals(replaceAll);
        String path = blankToNull(filePath) == null ? null : normalizePath(filePath);
        Skill k = find(n);
        checkRevision(k, expectedRevision);
        SkillFile file = path == null ? null : k.file(path).orElseThrow(() -> new IllegalArgumentException(
                "Skill '" + n + "' hat keine Datei '" + path + "'. Vorhanden: " + filePaths(k)));
        String before = file == null ? k.getContent() : file.getContent();
        int count = occurrences(before, oldString);
        if (count == 0) {
            throw new IllegalArgumentException("'old_string' kommt in " + target(n, path) + " nicht vor. "
                    + "Aktuellen Stand mit skills_view laden und den Text exakt übernehmen.");
        }
        if (count > 1 && !all) {
            throw new IllegalArgumentException("'old_string' kommt " + count + "-mal in " + target(n, path)
                    + " vor. Mehr Kontext angeben, damit die Stelle eindeutig ist, oder replace_all=true setzen.");
        }
        int at = before.indexOf(oldString);
        String after = all ? before.replace(oldString, replacement)
                : before.substring(0, at) + replacement + before.substring(at + oldString.length());
        Instant now = Instant.now();
        if (file == null) {
            k.setContent(requireContent(after, "content", maxContentChars));
        } else {
            file.update(requireContent(after, "Dateiinhalt", maxContentChars), now);
        }
        k.recordRevision("patch", normalizeNote(note), now);
        return "Skill '" + n + "' gepatcht" + (path == null ? "" : " (" + path + ")") + ": "
                + (all ? count : 1) + " Stelle(n) ersetzt, Revision " + k.getRevision() + ".";
    }

    public String writeFile(String name, String filePath, String content, String note, int maxContentChars) {
        String n = requireName(name);
        String p = normalizePath(filePath);
        String body = requireContent(content, "file_content", maxContentChars);
        Skill k = find(n);
        Instant now = Instant.now();
        boolean exists = k.file(p).isPresent();
        k.file(p).ifPresentOrElse(f -> f.update(body, now), () -> k.addFile(new SkillFile(k, p, body, now)));
        k.recordRevision("write_file", noteOr(note, p), now);
        return "Datei '" + p + "' in Skill '" + n + "' " + (exists ? "überschrieben" : "angelegt")
                + " (Revision " + k.getRevision() + ").";
    }

    public String removeFile(String name, String filePath, String note) {
        String n = requireName(name);
        String p = normalizePath(filePath);
        Skill k = find(n);
        SkillFile f = k.file(p).orElseThrow(() -> new IllegalArgumentException(
                "Skill '" + n + "' hat keine Datei '" + p + "'. Vorhanden: " + filePaths(k)));
        k.removeFile(f);
        k.recordRevision("remove_file", noteOr(note, p), Instant.now());
        return "Datei '" + p + "' aus Skill '" + n + "' entfernt (Revision " + k.getRevision() + ").";
    }

    public String delete(String name) {
        String n = requireName(name);
        Skill k = find(n);
        int files = k.getFiles().size();
        skills.delete(k);
        return "Skill '" + n + "' samt " + files + " Datei(en) und Historie gelöscht.";
    }

    // ------------------------------------------------------------------ intern

    private Skill find(String name) {
        return skills.findByName(name).orElseThrow(() -> new IllegalArgumentException("Skill '" + name
                + "' gibt es nicht. Vorhandene mit skills_list anzeigen oder mit skills_create anlegen."));
    }

    private static void checkRevision(Skill k, Integer expected) {
        if (expected != null && expected != k.getRevision()) {
            throw new IllegalArgumentException("Skill '" + k.getName() + "' wurde inzwischen geändert (Revision "
                    + k.getRevision() + " statt " + expected + "). Mit skills_view neu laden und erneut ändern.");
        }
    }

    private static String target(String name, String path) {
        return path == null ? "Skill '" + name + "'" : "'" + path + "' von Skill '" + name + "'";
    }

    private static String filePaths(Skill k) {
        return k.getFiles().isEmpty() ? "keine"
                : k.getFiles().stream().map(SkillFile::getPath).collect(Collectors.joining(", "));
    }

    static int occurrences(String text, String part) {
        int count = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + part.length())) {
            count++;
        }
        return count;
    }

    static String requireName(String name) {
        String n = name == null ? "" : name.trim();
        if (!NAME.matcher(n).matches()) {
            throw new IllegalArgumentException("Ungültiger Skill-Name '" + n + "': Kleinbuchstaben, Ziffern, "
                    + "'.', '_' und '-', beginnend mit Buchstabe/Ziffer, max. 64 Zeichen (z.B. 'wildfly-heap-leak').");
        }
        return n;
    }

    private static String requireDescription(String description) {
        String d = description == null ? "" : description.strip().replaceAll("\\s+", " ");
        if (d.isEmpty()) {
            throw new IllegalArgumentException("'description' fehlt: ein Satz, wann der Skill greift, z.B. "
                    + "'Use when … / Verwenden, wenn …'.");
        }
        if (d.length() > MAX_DESCRIPTION) {
            throw new IllegalArgumentException("'description' ist " + d.length() + " Zeichen lang (max. "
                    + MAX_DESCRIPTION + "). Details gehören in 'content'.");
        }
        return d;
    }

    private static String requireContent(String content, String field, int maxContentChars) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("'" + field + "' darf nicht leer sein.");
        }
        if (content.length() > maxContentChars) {
            throw new IllegalArgumentException("'" + field + "' ist " + content.length() + " Zeichen lang (max. "
                    + maxContentChars + "). Längere Details mit skills_write_file in references/ auslagern.");
        }
        return content;
    }

    private static String normalizeCategory(String category) {
        String c = blankToNull(category);
        if (c == null) {
            return null;
        }
        c = c.toLowerCase(Locale.ROOT);
        if (!CATEGORY.matcher(c).matches()) {
            throw new IllegalArgumentException("Ungültige Kategorie '" + category + "': Kleinbuchstaben, Ziffern "
                    + "und '-' (z.B. 'software-development').");
        }
        return c;
    }

    static String normalizeTags(List<String> tags) {
        if (tags == null) {
            return null;
        }
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String t : tags) {
            if (t == null) {
                continue;
            }
            Arrays.stream(t.split(",")).map(x -> x.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "-"))
                    .filter(x -> !x.isEmpty()).forEach(set::add);
        }
        String joined = String.join(",", set);
        if (joined.length() > MAX_TAGS) {
            throw new IllegalArgumentException("Zu viele Tags (" + joined.length() + " Zeichen, max. " + MAX_TAGS + ").");
        }
        return joined.isEmpty() ? null : joined;
    }

    static String normalizePath(String path) {
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

    private static String normalizeNote(String note) {
        String n = blankToNull(note);
        return n == null ? null : n.length() > MAX_NOTE ? n.substring(0, MAX_NOTE) : n;
    }

    private static String noteOr(String note, String fallback) {
        String n = normalizeNote(note);
        return n == null ? fallback : n;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
