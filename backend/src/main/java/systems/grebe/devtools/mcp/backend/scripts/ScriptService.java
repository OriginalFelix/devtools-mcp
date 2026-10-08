package systems.grebe.devtools.mcp.backend.scripts;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import systems.grebe.devtools.mcp.backend.skills.SkillOwner;
import systems.grebe.devtools.mcp.modules.scripts.ScriptBackend;
import systems.grebe.devtools.mcp.modules.scripts.ScriptViews;

/**
 * Ablage der Groovy-Skripte: Validierung, Historie und Eigentümer über die Spring-Data-Repositories. Ausgeführt wird
 * hier nichts – das Backend läuft auch auf dem Team-Server, Skripte laufen nur in der Desktop-App, die sie vor dem
 * Speichern auswertet und die Beschreibung mitliefert. Das Backend prüft zusätzlich die Syntax ({@link ScriptSyntax},
 * ohne Ausführung) – so landet auch aus der Web-UI kein unübersetzbares Skript in der Ablage.
 *
 * <p><b>Eigentümer</b> wie bei den Skills ({@link SkillOwner}): Jeder sieht seine eigenen Skripte und die globalen
 * Vorlagen; ein eigenes Skript verdeckt die Vorlage gleichen Namens. Vorlagen veröffentlichen und zurückziehen nur
 * Administratoren – sie laufen danach in den Desktop-Apps aller Benutzer.
 */
@Service
@Transactional
public class ScriptService implements ScriptBackend {

    /** Wie eine Modul-ID: der Name wird Tool-Präfix ({@code jira_open_issues}). */
    static final Pattern NAME = Pattern.compile("[a-z][a-z0-9]{1,31}");
    static final int MAX_DESCRIPTION = 1024;
    static final int MAX_NOTE = 500;

    private static final Logger LOG = LoggerFactory.getLogger(ScriptService.class);

    private final ScriptRepository scripts;
    private final ScriptRevisionRepository revisions;
    private final SkillOwner users;
    private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();

    public ScriptService(ScriptRepository scripts, ScriptRevisionRepository revisions, SkillOwner users) {
        this.scripts = scripts;
        this.revisions = revisions;
        this.users = users;
    }

    /** Nach jeder erfolgreich committeten Änderung (nicht bei Rollback); läuft im Thread des Aufrufers. */
    @Override
    public void addChangeListener(Runnable listener) {
        changeListeners.add(listener);
    }

    // ------------------------------------------------------------------ Lesen

    @Override
    @Transactional(readOnly = true)
    public List<ScriptViews.Summary> overview() {
        return visible(users.email()).stream().map(ScriptService::summary).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ScriptViews.Details> details(String name) {
        return resolve(users.email(), requireName(name)).map(s -> new ScriptViews.Details(summary(s), s.getContent(),
                s.getCreatedAt(), revisions.findByScriptOrderByRevisionDesc(s).stream()
                .map(r -> new ScriptViews.Revision(r.getRevision(), r.getAction(), r.getNote(), r.getChangedBy(),
                        r.getChangedAt(), r.getContent())).toList()));
    }

    private static ScriptViews.Summary summary(Script s) {
        return new ScriptViews.Summary(s.getName(), s.getDescription(),
                s.isGlobal() ? ScriptViews.Scope.GLOBAL : ScriptViews.Scope.OWN, s.getLanguage(), s.getRevision(),
                s.getUpdatedAt(),
                s.getUpdatedBy());
    }

    // ------------------------------------------------------------------ Schreiben

    /** Hier deklariert, damit auch dieser Weg in einer Transaktion läuft (die Default-Methode hätte keine). */
    @Override
    public String save(String name, String description, String content, String note, Integer expectedRevision) {
        return save(name, null, description, content, note, expectedRevision);
    }

    @Override
    public String save(String name, ScriptViews.Language language, String description, String content, String note,
                       Integer expectedRevision) {
        String n = requireName(name);
        String body = requireContent(content);
        String user = users.email();
        Optional<Script> own = scripts.findByOwnerAndName(user, n);
        Optional<Script> template = own.isPresent() ? Optional.empty() : scripts.findByOwnerAndName(SkillOwner.GLOBAL, n);
        Optional<Script> current = own.or(() -> template);
        current.ifPresent(s -> checkRevision(s, expectedRevision));
        ScriptViews.Language lang = language != null ? language
                : current.map(Script::getLanguage).orElse(ScriptViews.Language.GROOVY);
        Optional<String> declared = ScriptSyntax.check(lang, n, body);
        // Beschreibung: von der Desktop-App ausgewertet, sonst fester Text im Skript, sonst die bisherige
        String d = requireDescription(description != null && !description.isBlank() ? description
                : declared.orElse(current.map(Script::getDescription).orElse(null)));
        Instant now = Instant.now();
        if (own.isPresent()) {
            Script s = own.get();
            if (lang == s.getLanguage() && d.equals(s.getDescription()) && body.equals(s.getContent())) {
                return "Keine Änderung an Skript '" + n + "' (Revision " + s.getRevision() + ").";
            }
            s.change(lang, d, body);
            s.recordRevision("update", normalizeNote(note), user, now);
            changed();
            return "Skript '" + n + "' gespeichert (Revision " + s.getRevision() + ").";
        }
        Script s = new Script(user, n, lang, d, body, now);
        s.recordRevision("create", template.isPresent()
                ? noteOr(note, "verdeckt die globale Vorlage (Revision " + template.get().getRevision() + ")")
                : normalizeNote(note), user, now);
        scripts.save(s);
        changed();
        return "Skript '" + n + "' angelegt (Revision 1)."
                + (template.isPresent() ? " Es verdeckt die globale Vorlage gleichen Namens." : "");
    }

    @Override
    public String delete(String name) {
        String n = requireName(name);
        String user = users.email();
        Optional<Script> own = scripts.findByOwnerAndName(user, n);
        boolean template = scripts.findByOwnerAndName(SkillOwner.GLOBAL, n).isPresent();
        if (own.isEmpty()) {
            throw template
                    ? new IllegalArgumentException("'" + n + "' ist eine globale Vorlage – sie kann nicht gelöscht, "
                    + "nur von einem Administrator zurückgezogen werden.")
                    : notFound(n);
        }
        scripts.delete(own.get());
        changed();
        return "Skript '" + n + "' samt Historie gelöscht."
                + (template ? " Die globale Vorlage '" + n + "' gilt wieder." : "");
    }

    @Override
    public String publish(String name) {
        String user = requireAdmin();
        String n = requireName(name);
        Script own = scripts.findByOwnerAndName(user, n).orElseThrow(() -> new IllegalArgumentException(
                "Nur eigene Skripte können veröffentlicht werden – '" + n + "' gehört nicht " + user + "."));
        Instant now = Instant.now();
        Optional<Script> existing = scripts.findByOwnerAndName(SkillOwner.GLOBAL, n);
        Script template = existing.orElseGet(() -> new Script(SkillOwner.GLOBAL, n, own.getLanguage(),
                own.getDescription(), own.getContent(), now));
        if (existing.isPresent()) {
            if (Objects.equals(template.getContent(), own.getContent()) && template.getLanguage() == own.getLanguage()
                    && Objects.equals(template.getDescription(), own.getDescription())) {
                return "Die globale Vorlage '" + n + "' entspricht schon deinem Stand (Revision "
                        + template.getRevision() + ").";
            }
            template.change(own.getLanguage(), own.getDescription(), own.getContent());
        }
        template.recordRevision("publish", (existing.isPresent() ? "aktualisiert" : "veröffentlicht")
                + " aus dem Skript von " + user + " (Revision " + own.getRevision() + ")", user, now);
        scripts.save(template);
        changed();
        return "Skript '" + n + "' als globale Vorlage " + (existing.isPresent() ? "aktualisiert" : "veröffentlicht")
                + " (Vorlage Revision " + template.getRevision() + "). Dein eigenes Skript verdeckt sie, solange es "
                + "existiert.";
    }

    @Override
    public String unpublish(String name) {
        requireAdmin();
        String n = requireName(name);
        Script template = scripts.findByOwnerAndName(SkillOwner.GLOBAL, n).orElseThrow(() ->
                new IllegalArgumentException("Es gibt keine globale Vorlage '" + n + "'."));
        scripts.delete(template);
        changed();
        return "Globale Vorlage '" + n + "' zurückgezogen. Eigene Skripte gleichen Namens bleiben erhalten.";
    }

    // ------------------------------------------------------------------ intern

    private Optional<Script> resolve(String user, String name) {
        return scripts.findByOwnerAndName(user, name).or(() -> scripts.findByOwnerAndName(SkillOwner.GLOBAL, name));
    }

    private List<Script> visible(String user) {
        List<Script> all = scripts.findByOwners(List.of(user, SkillOwner.GLOBAL));
        Set<String> own = new HashSet<>();
        all.stream().filter(s -> !s.isGlobal()).forEach(s -> own.add(s.getName()));
        return all.stream().filter(s -> !s.isGlobal() || !own.contains(s.getName())).toList();
    }

    private String requireAdmin() {
        if (!users.admin()) {
            throw new IllegalStateException("Globale Skript-Vorlagen verwalten: dafür fehlt das Recht „Vorlagen "
                    + "veröffentlichen“.");
        }
        return users.email();
    }

    private static void checkRevision(Script s, Integer expected) {
        if (expected != null && expected != s.getRevision()) {
            throw new IllegalArgumentException("Skript '" + s.getName() + "' wurde inzwischen geändert (Revision "
                    + s.getRevision() + " statt " + expected + "). Neu laden und die Änderung erneut anwenden.");
        }
    }

    private static IllegalArgumentException notFound(String name) {
        return new IllegalArgumentException("Skript '" + name + "' gibt es nicht. Vorhandene mit scripts_list "
                + "anzeigen.");
    }

    static String requireName(String name) {
        String n = name == null ? "" : name.trim();
        if (!NAME.matcher(n).matches()) {
            throw new IllegalArgumentException("Ungültiger Skriptname '" + n + "': 2–32 Kleinbuchstaben/Ziffern, "
                    + "beginnend mit einem Buchstaben (er wird Modul-ID und Tool-Präfix, z.B. 'jira' → jira_…).");
        }
        return n;
    }

    private static String requireDescription(String description) {
        String d = description == null ? "" : description.strip().replaceAll("\\s+", " ");
        if (d.isEmpty()) {
            throw new IllegalArgumentException("Beschreibung fehlt: im Skript module { description '…' } (Groovy) bzw. "
                    + "description() mit return \"…\" (Java) als festen Text angeben.");
        }
        return d.length() > MAX_DESCRIPTION ? d.substring(0, MAX_DESCRIPTION) : d;
    }

    private static String requireContent(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Der Quelltext darf nicht leer sein.");
        }
        if (content.length() > Script.CONTENT_COLUMN) {
            throw new IllegalArgumentException("Der Quelltext ist " + content.length() + " Zeichen lang (max. "
                    + Script.CONTENT_COLUMN + ").");
        }
        return content;
    }

    private static String normalizeNote(String note) {
        String n = note == null || note.isBlank() ? null : note.strip();
        return n == null ? null : n.length() > MAX_NOTE ? n.substring(0, MAX_NOTE) : n;
    }

    private static String noteOr(String note, String fallback) {
        String n = normalizeNote(note);
        return n == null ? fallback : n;
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
                LOG.warn("Skript-ChangeListener fehlgeschlagen", e);
            }
        }
    }
}
