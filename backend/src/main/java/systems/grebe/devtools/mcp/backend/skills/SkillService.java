package systems.grebe.devtools.mcp.backend.skills;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
import systems.grebe.devtools.mcp.api.MediaTypes;
import systems.grebe.devtools.mcp.backend.blobs.BlobReferences;
import systems.grebe.devtools.mcp.backend.blobs.BlobStore;
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
 * <p><b>Zusatzdateien:</b> Text liegt im Skill und lässt sich patchen; beliebige Dateien (auch binär, ohne
 * Größengrenze) liegen als Anhang in der {@link BlobStore Dateiablage} im Verzeichnis des Eigentümers – beim Übernehmen
 * einer Vorlage bzw. beim Veröffentlichen wird der Inhalt mitkopiert, nicht mehr verwendete Inhalte gibt
 * {@link BlobReferences} frei.
 *
 * <p>Die Inhaltsgrenze kommt als Parameter, weil sie in der UI zur Laufzeit geändert werden kann, der Service aber
 * ein Singleton ist.
 */
@Service
@Transactional
public class SkillService implements SkillBackend {

    static final Pattern CATEGORY = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");
    static final int MAX_DESCRIPTION = 1024;
    static final int MAX_TAGS = 500;
    static final int MAX_NOTE = 500;
    static final Pattern TRIGGER = Pattern.compile("[a-z][a-z0-9_]{0,62}\\*?");
    static final int MAX_TRIGGERS = 500;
    /** Beschreibungen in skills_list werden auf so viele Zeichen gekürzt (Tokens sparen). */
    static final int LIST_DESCRIPTION = 160;
    /** Bis zu dieser Länge liefert skills_list bei genau einem Treffer den Inhalt gleich mit. */
    static final int INLINE_MAX = 6_000;
    /** Anhänge mit Text bis zu dieser Größe (Bytes) zeigt skills_view direkt. */
    public static final int VIEW_MAX = 200_000;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());
    private static final Logger LOG = LoggerFactory.getLogger(SkillService.class);

    private final SkillRepository skills;
    private final SkillRevisionRepository revisions;
    private final SkillOwner users;
    private final BlobStore blobs;
    private final BlobReferences references;
    private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();

    public SkillService(SkillRepository skills, SkillRevisionRepository revisions, SkillOwner users, BlobStore blobs,
                        BlobReferences references) {
        this.skills = skills;
        this.revisions = revisions;
        this.users = users;
        this.blobs = blobs;
        this.references = references;
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
                k.getFiles().stream().map(SkillService::view).toList(),
                revisions.findBySkillOrderByRevisionDesc(k).stream().map(r -> new SkillViews.Revision(r.getRevision(),
                        r.getAction(), r.getNote(), r.getChangedBy(), r.getChangedAt(), r.getDescription(),
                        r.getContent())).toList()));
    }

    private static SkillViews.File view(SkillFile f) {
        return new SkillViews.File(f.getPath(), f.inline() ? f.getContent() : null, f.getUpdatedAt(), f.getSize(),
                f.getMediaType(), f.getBlob());
    }

    private static SkillViews.Summary summary(Skill k, Map<String, Integer> templates) {
        SkillViews.Scope scope = scope(k);
        return new SkillViews.Summary(k.getName(), k.getDescription(), k.getCategory(), k.tagList(), k.getRevision(),
                k.getUseCount(), k.getLastUsedAt(), k.getUpdatedAt(), k.getFiles().size(), scope,
                k.getTemplateRevision(), scope == SkillViews.Scope.COPY ? templates.get(k.getName()) : null,
                k.triggerList());
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
        copyBlobs(own, user, SkillOwner.GLOBAL);
        Skill template;
        if (existing.isPresent()) {
            template = existing.get();
            List<String> before = blobsOf(template);
            template.replaceWith(own, now);
            skills.flush();
            references.release(SkillOwner.GLOBAL, before);
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
        List<String> files = blobsOf(template);
        skills.delete(template);
        skills.flush();
        references.release(SkillOwner.GLOBAL, files);
        changed();
        return "Globale Vorlage '" + n + "' zurückgezogen. Persönliche Kopien bleiben erhalten.";
    }

    private String requireAdmin() {
        if (!users.admin()) {
            throw new IllegalStateException("Globale Vorlagen verwalten: dafür fehlt das Recht „Vorlagen "
                    + "veröffentlichen“.");
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

    /**
     * Kompakte Liste für das LLM (Beschreibungen gekürzt, ohne Tags). Bei Suchtext und genau einem Treffer kommt der
     * Inhalt gleich mit – spart den zweiten Aufruf; dann wird auch die Nutzung gezählt (daher schreibend).
     */
    public String list(String query, String category) {
        String q = blankToNull(query);
        String c = blankToNull(category);
        List<Skill> found = visible(users.email(), q == null ? null : "%" + q.toLowerCase(Locale.ROOT) + "%",
                c == null ? null : c.toLowerCase(Locale.ROOT));
        if (found.isEmpty()) {
            return q == null && c == null
                    ? "Noch keine Skills. Nach einer mehrstufigen Aufgabe den Ablauf mit skills_create registrieren."
                    : "Keine Skills" + (q != null ? " für '" + q + "'" : "")
                    + (c != null ? " in Kategorie '" + c + "'" : "") + ". Andere Stichworte oder skills_list ohne "
                    + "Filter.";
        }
        if (q != null && found.size() == 1 && found.getFirst().getContent().length() <= INLINE_MAX) {
            Skill k = found.getFirst();
            skills.markUsed(k.getId(), Instant.now());
            return "1 Skill für '" + q + "' – direkt geladen:\n\n" + render(k);
        }
        StringBuilder sb = new StringBuilder(found.size() + " Skill(s)")
                .append(q != null ? " für '" + q + "'" : "").append(" – laden mit skills_view(name):\n");
        String lastCategory = "\u0000";
        for (Skill k : found) {
            String cat = k.getCategory() == null ? "(ohne Kategorie)" : k.getCategory();
            if (!cat.equals(lastCategory)) {
                sb.append(cat).append(":\n");
                lastCategory = cat;
            }
            sb.append("  ").append(k.getName()).append(k.isGlobal() ? "*" : "").append(" – ")
                    .append(shorten(k.getDescription(), LIST_DESCRIPTION)).append('\n');
        }
        if (found.stream().anyMatch(Skill::isGlobal)) {
            sb.append("* globale Vorlage (Änderung legt automatisch eine persönliche Kopie an)\n");
        }
        return sb.toString().stripTrailing();
    }

    /** Schreibend, weil die Nutzung gezählt wird. */
    public String view(String name, String filePath) {
        String n = requireName(name);
        String path = blankToNull(filePath) == null ? null : normalizePath(filePath);
        Skill k = find(users.email(), n);
        if (path != null) {
            SkillFile f = k.file(path).orElseThrow(() -> new IllegalArgumentException(
                    "Skill '" + n + "' hat keine Datei '" + path + "'. Vorhanden: " + filePaths(k)));
            if (f.inline()) {
                return "# " + n + " / " + path + "\n\n" + f.getContent();
            }
            String head = "# " + n + " / " + path + " · Anhang (" + f.getMediaType() + ", "
                    + MediaTypes.size(f.getSize()) + ")";
            if (MediaTypes.textual(f.getMediaType()) && f.getSize() <= VIEW_MAX) {
                return head + "\n\n" + readText(k.getOwner(), f.getBlob());
            }
            return head + "\n\nNicht als Text anzeigbar – skills_view mit file_path speichert ihn als lokale Datei.";
        }
        skills.markUsed(k.getId(), Instant.now());
        return render(k);
    }

    /** Kompakter Kopf (Name, Revision, Herkunft, Registrierung) plus Inhalt – die Beschreibung kennt das LLM schon. */
    private static String render(Skill k) {
        StringBuilder sb = new StringBuilder("# ").append(k.getName()).append(" · Revision ").append(k.getRevision());
        if (k.isGlobal()) {
            sb.append(" · globale Vorlage (Änderung legt persönliche Kopie an)");
        } else if (k.getTemplateRevision() != null) {
            sb.append(" · Kopie der Vorlage Rev. ").append(k.getTemplateRevision());
        }
        if (!k.triggerList().isEmpty()) {
            sb.append("\nRegistriert für: ").append(String.join(", ", k.triggerList()));
        }
        sb.append("\n\n").append(k.getContent().strip());
        if (!k.getFiles().isEmpty()) {
            sb.append("\n\nZusatzdateien (skills_view mit file_path): ").append(fileList(k));
        }
        return sb.toString();
    }

    static String shorten(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1).stripTrailing() + "…";
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

    // Kurzformen ohne Trigger hier statt als Default-Methode: nur so laufen sie über den Transaktions-Proxy.
    @Override
    public String create(String name, String description, String content, String category, List<String> tags,
                         int maxContentChars) {
        return create(name, description, content, category, tags, null, maxContentChars);
    }

    @Override
    public String update(String name, String description, String content, String category, List<String> tags,
                         String note, Integer expectedRevision, int maxContentChars) {
        return update(name, description, content, category, tags, null, note, expectedRevision, maxContentChars);
    }

    @Override
    public String create(String name, String description, String content, String category, List<String> tags,
                         List<String> triggers, int maxContentChars) {
        String n = requireName(name);
        String d = requireDescription(description);
        String body = requireContent(content, "content", maxContentChars);
        String cat = normalizeCategory(category);
        String t = normalizeTags(tags);
        String tr = normalizeTriggers(triggers);
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
        k.setTriggers(tr);
        k.recordRevision("create", null, user, k.getCreatedAt());
        skills.save(k);
        changed();
        return "Skill '" + n + "' angelegt (Revision 1)" + (tr == null ? "" : ", registriert für " + tr) + ".";
    }

    @Override
    public String update(String name, String description, String content, String category, List<String> tags,
                         List<String> triggers, String note, Integer expectedRevision, int maxContentChars) {
        String n = requireName(name);
        String user = users.email();
        Skill current = find(user, n);
        checkRevision(current, expectedRevision);
        String d = description == null ? current.getDescription() : requireDescription(description);
        String body = content == null ? current.getContent() : requireContent(content, "content", maxContentChars);
        String cat = category == null ? current.getCategory() : normalizeCategory(category);
        String t = tags == null ? current.getTags() : normalizeTags(tags);
        String tr = triggers == null ? current.getTriggers() : normalizeTriggers(triggers);
        if (d.equals(current.getDescription()) && body.equals(current.getContent())
                && Objects.equals(cat, current.getCategory()) && Objects.equals(t, current.getTags())
                && Objects.equals(tr, current.getTriggers())) {
            // Ohne Änderung auch keine Kopie einer Vorlage anlegen
            return "Keine Änderung an '" + n + "' (Revision " + current.getRevision() + ").";
        }
        Writable w = writable(user, current);
        Skill k = w.skill();
        k.setDescription(d);
        k.setContent(body);
        k.setCategory(cat);
        k.setTags(t);
        k.setTriggers(tr);
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
        if (file != null && !file.inline()) {
            throw new IllegalArgumentException("'" + path + "' ist ein Anhang (" + file.getMediaType() + ") und lässt "
                    + "sich nicht patchen – mit skills_write_file ersetzen.");
        }
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
        Optional<SkillFile> existing = k.file(p);
        String replaced = existing.map(SkillFile::getBlob).orElse(null);
        existing.ifPresentOrElse(f -> f.update(body, now), () -> k.addFile(new SkillFile(k, p, body, now)));
        k.recordRevision("write_file", noteOr(note, p), user, now);
        releaseReplaced(user, replaced);
        changed();
        return w.prefix() + "Datei '" + p + "' in Skill '" + n + "' " + (existing.isPresent() ? "überschrieben"
                : "angelegt") + " (Revision " + k.getRevision() + ").";
    }

    /** Legt die lokale Datei in der Dateiablage ab und hängt sie an (siehe {@link #attachBlob}). */
    @Override
    public String attachFile(String name, String filePath, Path source, String mediaType, String note) {
        requireName(name);
        normalizePath(filePath);
        BlobStore.Blob blob;
        try (InputStream in = Files.newInputStream(source)) {
            blob = blobs.put(users.email(), in);
        } catch (IOException e) {
            throw new UncheckedIOException("Datei " + source + " nicht lesbar: " + e.getMessage(), e);
        }
        return attachBlob(name, filePath, blob.sha(), mediaType, note);
    }

    /**
     * Hängt einen bereits in der Dateiablage des Benutzers liegenden Inhalt (Upload über {@code /blobs}) als
     * Zusatzdatei an bzw. ersetzt die Datei gleichen Pfads – auch eine Textdatei.
     */
    public String attachBlob(String name, String filePath, String blob, String mediaType, String note) {
        String n = requireName(name);
        String p = normalizePath(filePath);
        String type = MediaTypes.orGuess(mediaType, p);
        String user = users.email();
        BlobStore.Blob b = blobs.describe(user, BlobStore.requireSha(blob));
        Writable w = writable(user, find(user, n));
        Skill k = w.skill();
        Instant now = Instant.now();
        Optional<SkillFile> existing = k.file(p);
        String replaced = existing.map(SkillFile::getBlob).orElse(null);
        existing.ifPresentOrElse(f -> f.attach(b, type, now), () -> k.addFile(new SkillFile(k, p, b, type, now)));
        k.recordRevision("attach_file", noteOr(note, p), user, now);
        if (!b.sha().equals(replaced)) {
            releaseReplaced(user, replaced);
        }
        changed();
        return w.prefix() + "Anhang '" + p + "' (" + type + ", " + MediaTypes.size(b.size()) + ") in Skill '" + n
                + "' " + (existing.isPresent() ? "ersetzt" : "angelegt") + " (Revision " + k.getRevision() + ").";
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SkillViews.File> file(String name, String filePath) {
        String p = normalizePath(filePath);
        return resolve(users.email(), requireName(name)).flatMap(k -> k.file(p)).map(SkillService::view);
    }

    @Override
    @Transactional(readOnly = true)
    public String exportFile(String name, String filePath, Path target) {
        String n = requireName(name);
        String p = normalizePath(filePath);
        Skill k = find(users.email(), n);
        SkillFile f = k.file(p).orElseThrow(() -> new IllegalArgumentException(
                "Skill '" + n + "' hat keine Datei '" + p + "'. Vorhanden: " + filePaths(k)));
        try {
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            if (f.inline()) {
                Files.writeString(target, f.getContent(), StandardCharsets.UTF_8);
            } else {
                Files.copy(blobs.require(k.getOwner(), f.getBlob()), target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Datei " + target + " nicht schreibbar: " + e.getMessage(), e);
        }
        return "'" + p + "' aus Skill '" + n + "' gespeichert: " + target + " (" + f.getMediaType() + ", "
                + MediaTypes.size(f.getSize()) + ").";
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
        SkillFile removed = k.file(p).orElseThrow();
        k.removeFile(removed);
        k.recordRevision("remove_file", noteOr(note, p), user, Instant.now());
        releaseReplaced(user, removed.getBlob());
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
        List<String> attached = blobsOf(own.get());
        skills.delete(own.get());
        skills.flush();
        references.release(user, attached);
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
        copyBlobs(visible, SkillOwner.GLOBAL, user);
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

    /** Pfade, Anhänge mit Medientyp und Größe. */
    private static String fileList(Skill k) {
        return k.getFiles().stream().map(f -> f.inline() ? f.getPath()
                : f.getPath() + " (Anhang, " + f.getMediaType() + ", " + MediaTypes.size(f.getSize()) + ")")
                .collect(Collectors.joining(", "));
    }

    private static List<String> blobsOf(Skill k) {
        return k.getFiles().stream().map(SkillFile::getBlob).filter(Objects::nonNull).toList();
    }

    /** Anhänge eines Skills in die Dateiablage eines anderen Eigentümers kopieren (Vorlage übernehmen/veröffentlichen). */
    private void copyBlobs(Skill k, String from, String to) {
        blobsOf(k).stream().distinct().forEach(sha -> blobs.copy(from, to, sha));
    }

    /** Gibt den Inhalt einer ersetzten oder entfernten Datei frei, falls nichts anderes ihn verwendet. */
    private void releaseReplaced(String owner, String blob) {
        if (blob != null) {
            skills.flush();
            references.release(owner, List.of(blob));
        }
    }

    private String readText(String owner, String blob) {
        try {
            return new String(Files.readAllBytes(blobs.require(owner, blob)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Anhang nicht lesbar: " + e.getMessage(), e);
        }
    }

    static int occurrences(String text, String part) {
        int count = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + part.length())) {
            count++;
        }
        return count;
    }

    static String requireName(String name) {
        return SkillViews.requireName(name);
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

    /** Tool-Namen bzw. Präfixe mit {@code *}, klein, ohne Duplikate; {@code null} bei leerer Liste. */
    static String normalizeTriggers(List<String> triggers) {
        if (triggers == null) {
            return null;
        }
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String t : triggers) {
            if (t == null) {
                continue;
            }
            for (String x : t.split(",")) {
                String v = x.trim().toLowerCase(Locale.ROOT);
                if (v.isEmpty()) {
                    continue;
                }
                if (!TRIGGER.matcher(v).matches()) {
                    throw new IllegalArgumentException("Ungültiger Trigger '" + x.trim() + "': Tool-Name wie "
                            + "'ticket_get' oder Präfix mit * wie 'pr_*'.");
                }
                set.add(v);
            }
        }
        String joined = String.join(",", set);
        if (joined.length() > MAX_TRIGGERS) {
            throw new IllegalArgumentException("Zu viele Trigger (max. " + MAX_TRIGGERS + " Zeichen).");
        }
        return joined.isEmpty() ? null : joined;
    }

    static String normalizePath(String path) {
        return SkillViews.normalizePath(path);
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
