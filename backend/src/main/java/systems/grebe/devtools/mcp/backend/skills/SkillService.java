package systems.grebe.devtools.mcp.backend.skills;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import systems.grebe.devtools.mcp.modules.skills.SkillViews;
import systems.grebe.devtools.mcp.modules.skills.SkillBackend;

/**
 * Fachlogik des Skill-Speichers: Validierung, Suche, Anlegen, gezieltes Patchen, Zusatzdateien und Historie über die
 * Spring-Data-Repositories. Jede öffentliche Methode läuft in einer Transaktion und liefert kompakten Text für das
 * LLM; fachliche Fehler werden als {@link IllegalArgumentException} mit einem Hinweis auf den nächsten sinnvollen
 * Schritt gemeldet und rollen die Transaktion zurück.
 *
 * <p><b>User-Scoping:</b> Jede Abfrage ist auf den aktuellen Benutzer ({@link SkillOwner}) und die globalen Vorlagen
 * beschränkt. Ein eigener Skill verdeckt die Vorlage gleichen Namens. Globale Vorlagen sind schreibgeschützt: Ändert
 * das LLM eine, entsteht in derselben Transaktion zuerst eine persönliche Kopie ({@code adopt}), auf die die Änderung
 * wirkt. Veröffentlichen und Zurückziehen von Vorlagen gibt es nur in der App und nur mit Admin-Schalter.
 *
 * <p>Die Inhaltsgrenze kommt als Parameter, weil sie in der UI zur Laufzeit geändert werden kann, der Service aber
 * ein Singleton ist.
 */
@Service
@Transactional
public class SkillService implements SkillBackend {

    static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    static final Pattern CATEGORY = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");
    static final List<String> FILE_DIRS = List.of("references/", "templates/", "scripts/", "assets/");
    static final int MAX_DESCRIPTION = 1024;
    static final int MAX_TAGS = 500;
    static final int MAX_NOTE = 500;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());
    private static final Logger LOG = LoggerFactory.getLogger(SkillService.class);

    private final SkillRepository skills;
    private final SkillRevisionRepository revisions;
    private final SkillOwner users;
    private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();

    public SkillService(SkillRepository skills, SkillRevisionRepository revisions, SkillOwner users) {
        this.skills = skills;
        this.revisions = revisions;
        this.users = users;
    }

    // ------------------------------------------------------------------ Oberfläche

    /**
     * Wird nach jeder erfolgreich committeten Änderung aufgerufen (auch durch das LLM) – nicht bei Rollback und
     * nicht, wenn nur die Nutzung gezählt wurde. Läuft im Thread des Aufrufers, nicht im FX-Thread.
     */
    public void addChangeListener(Runnable listener) {
        changeListeners.add(listener);
    }

    /** Für den aktuellen Benutzer sichtbare Skills (eigene und nicht verdeckte Vorlagen), nach Kategorie und Name. */
    @Transactional(readOnly = true)
    public List<SkillViews.Summary> overview() {
        String user = users.email();
        Map<String, Integer> templates = templateRevisions();
        return visible(user, null, null).stream().map(k -> summary(k, templates)).toList();
    }

    /** Vollständiger sichtbarer Skill samt Dateien und Historie (neueste Revision zuerst). */
    @Transactional(readOnly = true)
    public Optional<SkillViews.Details> details(String name) {
        return resolve(users.email(), name).map(k -> new SkillViews.Details(summary(k, templateRevisions()),
                k.getContent(), k.getCreatedAt(),
                k.getFiles().stream().map(f -> new SkillViews.File(f.getPath(), f.getContent(), f.getUpdatedAt()))
                        .toList(),
                revisions.findBySkillOrderByRevisionDesc(k).stream().map(r -> new SkillViews.Revision(r.getRevision(),
                        r.getAction(), r.getNote(), r.getChangedBy(), r.getChangedAt(), r.getDescription(),
                        r.getContent())).toList()));
    }

    private static SkillViews.Summary summary(Skill k, Map<String, Integer> templates) {
        SkillViews.Scope scope = scope(k);
        return new SkillViews.Summary(k.getName(), k.getDescription(), k.getCategory(), k.tagList(), k.getRevision(),
                k.getUseCount(), k.getLastUsedAt(), k.getUpdatedAt(), k.getFiles().size(), scope,
                k.getTemplateRevision(), scope == SkillViews.Scope.COPY ? templates.get(k.getName()) : null);
    }

    private static SkillViews.Scope scope(Skill k) {
        return k.isGlobal() ? SkillViews.Scope.GLOBAL
                : k.getTemplateRevision() != null ? SkillViews.Scope.COPY : SkillViews.Scope.OWN;
    }

    private Map<String, Integer> templateRevisions() {
        return skills.search(List.of(SkillOwner.GLOBAL), null, null).stream()
                .collect(Collectors.toMap(Skill::getName, Skill::getRevision));
    }

    // ------------------------------------------------------------------ Vorlagen verwalten (nur App, nur Admin)

    /**
     * Veröffentlicht einen eigenen Skill als globale Vorlage bzw. aktualisiert die bestehende Vorlage gleichen Namens.
     * Der eigene Skill bleibt als persönliche Kopie mit Bezug auf die neue Vorlagen-Revision erhalten.
     */
    public String publish(String name) {
        String user = requireAdmin();
        String n = requireName(name);
        Skill own = skills.findByOwnerAndName(user, n).orElseThrow(() -> new IllegalArgumentException(
                "Nur eigene Skills können veröffentlicht werden – '" + n + "' gehört nicht " + user + "."));
        Instant now = Instant.now();
        Optional<Skill> existing = skills.findByOwnerAndName(SkillOwner.GLOBAL, n);
        Skill template;
        if (existing.isPresent()) {
            template = existing.get();
            template.replaceWith(own, now);
            template.recordRevision("publish", "aktualisiert aus dem Skill von " + user, user, now);
        } else {
            template = own.copyFor(SkillOwner.GLOBAL, now);
            template.makeGlobal();
            template.recordRevision("publish", "veröffentlicht aus dem Skill von " + user, user, now);
            skills.save(template);
        }
        own.linkTemplate(template.getRevision());
        changed();
        return "Skill '" + n + "' als globale Vorlage " + (existing.isPresent() ? "aktualisiert" : "veröffentlicht")
                + " (Vorlage Revision " + template.getRevision() + ").";
    }

    /** Zieht eine globale Vorlage zurück; persönliche Kopien der Benutzer bleiben erhalten. */
    public String unpublish(String name) {
        requireAdmin();
        String n = requireName(name);
        Skill template = skills.findByOwnerAndName(SkillOwner.GLOBAL, n).orElseThrow(() ->
                new IllegalArgumentException("Es gibt keine globale Vorlage '" + n + "'."));
        skills.delete(template);
        changed();
        return "Globale Vorlage '" + n + "' zurückgezogen. Persönliche Kopien bleiben erhalten.";
    }

    private String requireAdmin() {
        if (!users.admin()) {
            throw new IllegalStateException("Globale Vorlagen verwalten ist nicht freigegeben (Schalter im Modul "
                    + "„Skills“).");
        }
        return users.email();
    }

    private void changed() {
        if (changeListeners.isEmpty()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    fireChanged();
                }
            });
        } else {
            fireChanged();
        }
    }

    private void fireChanged() {
        for (Runnable l : changeListeners) {
            try {
                l.run();
            } catch (RuntimeException e) {
                LOG.warn("Skill-ChangeListener fehlgeschlagen", e);
            }
        }
    }

    // ------------------------------------------------------------------ Lesen

    @Transactional(readOnly = true)
    public String list(String query, String category) {
        String q = blankToNull(query);
        String c = blankToNull(category);
        List<Skill> found = visible(users.email(), q == null ? null : "%" + q.toLowerCase(Locale.ROOT) + "%",
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
            if (k.isGlobal()) {
                sb.append("  (global)");
            }
            sb.append('\n');
        }
        sb.append("Passende Skills vor Beginn der Aufgabe mit skills_view laden.");
        if (found.stream().anyMatch(Skill::isGlobal)) {
            sb.append(" (global) = schreibgeschützte Vorlage; eine Änderung legt automatisch eine persönliche Kopie an.");
        }
        return sb.toString();
    }

    /** Schreibend, weil die Nutzung gezählt wird. */
    public String view(String name, String filePath) {
        String n = requireName(name);
        String path = blankToNull(filePath) == null ? null : normalizePath(filePath);
        Skill k = find(users.email(), n);
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
        if (k.isGlobal()) {
            sb.append("\nscope: global (schreibgeschützt – eine Änderung legt automatisch eine persönliche Kopie an)");
        } else if (k.getTemplateRevision() != null) {
            sb.append("\nscope: persönliche Kopie der globalen Vorlage (Revision ").append(k.getTemplateRevision())
                    .append(')');
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
        Skill k = find(users.email(), n);
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
            if (r.getChangedBy() != null) {
                sb.append("  (").append(r.getChangedBy()).append(')');
            }
            if (r.getNote() != null) {
                sb.append("  – ").append(r.getNote());
            }
            sb.append('\n');
        }
        return sb.append("Einzelne Stände mit skills_history(name, revision) ansehen.").toString();
    }

    /** Anzahl der für den aktuellen Benutzer sichtbaren Skills (eigene und nicht verdeckte Vorlagen). */
    @Transactional(readOnly = true)
    public int visibleCount() {
        return visible(users.email(), null, null).size();
    }

    /** Eigene Skills und globale Vorlagen – für „Verbindung testen“. */
    @Transactional(readOnly = true)
    public String countText() {
        String user = users.email();
        return skills.countByOwner(user) + " eigene Skill(s) von " + user + ", "
                + skills.countByOwner(SkillOwner.GLOBAL) + " globale Vorlage(n)";
    }

    // ------------------------------------------------------------------ Schreiben

    public String create(String name, String description, String content, String category, List<String> tags,
                         int maxContentChars) {
        String n = requireName(name);
        String d = requireDescription(description);
        String body = requireContent(content, "content", maxContentChars);
        String cat = normalizeCategory(category);
        String t = normalizeTags(tags);
        String user = users.email();
        if (skills.existsByOwnerAndName(user, n)) {
            throw new IllegalArgumentException("Skill '" + n + "' existiert bereits. Mit skills_view ansehen und "
                    + "mit skills_patch ergänzen, statt ihn neu anzulegen.");
        }
        if (skills.existsByOwnerAndName(SkillOwner.GLOBAL, n)) {
            throw new IllegalArgumentException("Es gibt bereits die globale Vorlage '" + n + "'. Mit skills_view "
                    + "laden und mit skills_patch anpassen – das legt automatisch eine persönliche Kopie an – oder "
                    + "einen anderen Namen wählen.");
        }
        Skill k = new Skill(user, n, d, body, cat, t, Instant.now());
        k.recordRevision("create", null, user, k.getCreatedAt());
        skills.save(k);
        changed();
        return "Skill '" + n + "' angelegt (Revision 1).";
    }

    public String update(String name, String description, String content, String category, List<String> tags,
                         String note, Integer expectedRevision, int maxContentChars) {
        String n = requireName(name);
        String user = users.email();
        Skill current = find(user, n);
        checkRevision(current, expectedRevision);
        String d = description == null ? current.getDescription() : requireDescription(description);
        String body = content == null ? current.getContent() : requireContent(content, "content", maxContentChars);
        String cat = category == null ? current.getCategory() : normalizeCategory(category);
        String t = tags == null ? current.getTags() : normalizeTags(tags);
        if (d.equals(current.getDescription()) && body.equals(current.getContent())
                && Objects.equals(cat, current.getCategory()) && Objects.equals(t, current.getTags())) {
            // Ohne Änderung auch keine Kopie einer Vorlage anlegen
            return "Keine Änderung an '" + n + "' (Revision " + current.getRevision() + ").";
        }
        Writable w = writable(user, current);
        Skill k = w.skill();
        k.setDescription(d);
        k.setContent(body);
        k.setCategory(cat);
        k.setTags(t);
        k.recordRevision("update", normalizeNote(note), user, Instant.now());
        changed();
        return w.prefix() + "Skill '" + n + "' aktualisiert (Revision " + k.getRevision() + ").";
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
        String user = users.email();
        Skill current = find(user, n);
        checkRevision(current, expectedRevision);
        // Schlägt die Ersetzung fehl, rollt die Transaktion eine eben angelegte Kopie wieder zurück.
        Writable w = writable(user, current);
        Skill k = w.skill();
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
        k.recordRevision("patch", normalizeNote(note), user, now);
        changed();
        return w.prefix() + "Skill '" + n + "' gepatcht" + (path == null ? "" : " (" + path + ")") + ": "
                + (all ? count : 1) + " Stelle(n) ersetzt, Revision " + k.getRevision() + ".";
    }

    public String writeFile(String name, String filePath, String content, String note, int maxContentChars) {
        String n = requireName(name);
        String p = normalizePath(filePath);
        String body = requireContent(content, "file_content", maxContentChars);
        String user = users.email();
        Writable w = writable(user, find(user, n));
        Skill k = w.skill();
        Instant now = Instant.now();
        boolean exists = k.file(p).isPresent();
        k.file(p).ifPresentOrElse(f -> f.update(body, now), () -> k.addFile(new SkillFile(k, p, body, now)));
        k.recordRevision("write_file", noteOr(note, p), user, now);
        changed();
        return w.prefix() + "Datei '" + p + "' in Skill '" + n + "' " + (exists ? "überschrieben" : "angelegt")
                + " (Revision " + k.getRevision() + ").";
    }

    public String removeFile(String name, String filePath, String note) {
        String n = requireName(name);
        String p = normalizePath(filePath);
        String user = users.email();
        Skill current = find(user, n);
        if (current.file(p).isEmpty()) {
            throw new IllegalArgumentException("Skill '" + n + "' hat keine Datei '" + p + "'. Vorhanden: "
                    + filePaths(current));
        }
        Writable w = writable(user, current);
        Skill k = w.skill();
        k.removeFile(k.file(p).orElseThrow());
        k.recordRevision("remove_file", noteOr(note, p), user, Instant.now());
        changed();
        return w.prefix() + "Datei '" + p + "' aus Skill '" + n + "' entfernt (Revision " + k.getRevision() + ").";
    }

    /** Löscht einen eigenen Skill (auch eine persönliche Kopie); globale Vorlagen sind nicht löschbar. */
    public String delete(String name) {
        String n = requireName(name);
        String user = users.email();
        Optional<Skill> own = skills.findByOwnerAndName(user, n);
        boolean template = skills.existsByOwnerAndName(SkillOwner.GLOBAL, n);
        if (own.isEmpty()) {
            if (template) {
                throw new IllegalArgumentException("'" + n + "' ist eine globale Vorlage und schreibgeschützt – sie "
                        + "kann nicht gelöscht werden.");
            }
            throw notFound(n);
        }
        int files = own.get().getFiles().size();
        skills.delete(own.get());
        changed();
        return "Skill '" + n + "' samt " + files + " Datei(en) und Historie gelöscht."
                + (template ? " Die globale Vorlage '" + n + "' ist wieder sichtbar." : "");
    }

    // ------------------------------------------------------------------ intern

    /** Sichtbarer Skill: der eigene vor der globalen Vorlage gleichen Namens. */
    private Optional<Skill> resolve(String user, String name) {
        return skills.findByOwnerAndName(user, name).or(() -> skills.findByOwnerAndName(SkillOwner.GLOBAL, name));
    }

    private Skill find(String user, String name) {
        return resolve(user, name).orElseThrow(() -> notFound(name));
    }

    private static IllegalArgumentException notFound(String name) {
        return new IllegalArgumentException("Skill '" + name
                + "' gibt es nicht. Vorhandene mit skills_list anzeigen oder mit skills_create anlegen.");
    }

    /** Eigene Skills und globale Vorlagen, ohne die Vorlagen, die ein eigener Skill gleichen Namens verdeckt. */
    private List<Skill> visible(String user, String pattern, String category) {
        List<Skill> found = skills.search(List.of(user, SkillOwner.GLOBAL), pattern, category);
        Set<String> own = new HashSet<>(skills.namesOf(user));
        return found.stream().filter(k -> !k.isGlobal() || !own.contains(k.getName())).toList();
    }

    /** Ziel einer Änderung und ggf. Hinweis, dass dafür eine persönliche Kopie entstanden ist. */
    private record Writable(Skill skill, String prefix) {
    }

    /**
     * Globale Vorlagen sind schreibgeschützt: Statt sie zu ändern, entsteht eine persönliche Kopie des Benutzers
     * (Aktion {@code adopt}), auf die die Änderung wirkt. Eigene Skills werden direkt geändert.
     */
    private Writable writable(String user, Skill visible) {
        if (!visible.isGlobal()) {
            return new Writable(visible, "");
        }
        Instant now = Instant.now();
        Skill copy = visible.copyFor(user, now);
        copy.recordRevision("adopt", "Kopie der globalen Vorlage (Revision " + visible.getRevision() + ")", user, now);
        skills.save(copy);
        return new Writable(copy, "Globale Vorlage '" + visible.getName() + "' ist schreibgeschützt – persönliche "
                + "Kopie angelegt (Vorlage Revision " + visible.getRevision() + "). ");
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

    /** Tags klein, ohne Leerzeichen und Duplikate, kommagetrennt; auch für Memories. */
    public static String normalizeTags(List<String> tags) {
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
