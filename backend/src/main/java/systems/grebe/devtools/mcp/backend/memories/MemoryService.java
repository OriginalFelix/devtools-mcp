package systems.grebe.devtools.mcp.backend.memories;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import systems.grebe.devtools.mcp.backend.skills.SkillOwner;
import systems.grebe.devtools.mcp.backend.skills.SkillService;
import systems.grebe.devtools.mcp.modules.memories.MemoryBackend;
import systems.grebe.devtools.mcp.modules.memories.MemoryViews;

/**
 * Fachlogik des Memory-Speichers: Anlegen, Nachtragen, Suchen und Löschen von Memories des aktuellen Benutzers
 * ({@link SkillOwner}). Jede öffentliche Methode läuft in einer Transaktion und liefert kompakten Text für das LLM;
 * fachliche Fehler kommen als {@link IllegalArgumentException} mit einem Hinweis auf den nächsten Schritt.
 *
 * <p><b>Suche:</b> Der Suchtext wird in Begriffe zerlegt; ein Treffer braucht mindestens einen Begriff in Titel,
 * Inhalt, Tags, Bezug, Projekt oder Skill. Sortiert wird nach Anzahl getroffener Begriffe (Treffer in Titel und Bezug
 * zählen doppelt), dann nach Datum – neueste zuerst. Ohne Suchtext liefert die Suche die neuesten Memories.
 *
 * <p><b>Typ:</b> Memories sind dauerhaft, außer sie werden ausdrücklich als temporär angelegt. Mit
 * {@code temporaryOnly} (Aufrufer ohne Freigabe für dauerhafte Memories) lassen sich nur temporäre ändern und
 * löschen; dauerhaft machen lässt sich eine temporäre Memory so nicht.
 */
@Service
@Transactional
public class MemoryService implements MemoryBackend {

    static final Pattern SKILL = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    static final int MAX_TITLE = 200;
    static final int MAX_PROJECT = 100;
    static final int MAX_REFERENCE = 200;
    static final int DEFAULT_LIMIT = 5;
    static final int MAX_LIMIT = 50;
    /** So viele Kandidaten (neueste zuerst) holt die Suche aus der Datenbank, bevor sie gewichtet. */
    static final int CANDIDATES = 500;
    private static final int MAX_TERMS = 10;
    private static final int SNIPPET = 120;
    /** Bis zu dieser Länge liefert memories_search bei genau einem Treffer den Inhalt gleich mit. */
    static final int INLINE_MAX = 4_000;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneId.systemDefault());
    private static final Logger LOG = LoggerFactory.getLogger(MemoryService.class);

    private final MemoryRepository memories;
    private final SkillOwner users;
    private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();

    public MemoryService(MemoryRepository memories, SkillOwner users) {
        this.memories = memories;
        this.users = users;
    }

    /** Wird nach jeder erfolgreich committeten Änderung aufgerufen – nicht bei Rollback. */
    @Override
    public void addChangeListener(Runnable listener) {
        changeListeners.add(listener);
    }

    // ------------------------------------------------------------------ Oberfläche

    @Override
    @Transactional(readOnly = true)
    public List<MemoryViews.Entry> overview(String query, String project, String skill, int limit) {
        return find(users.email(), query, project, skill, null, null, null, Math.max(1, limit)).stream()
                .map(h -> entry(h.memory())).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MemoryViews.Entry> details(long id) {
        return memories.findByIdAndOwner(id, users.email()).map(MemoryService::entry);
    }

    @Override
    @Transactional(readOnly = true)
    public int count() {
        return (int) memories.countByOwner(users.email());
    }

    /**
     * Ob die Memory ohne Freigabe für dauerhafte Memories geändert werden darf (temporär oder Rückruf); unbekannte oder
     * fremde Memories wie bei {@link #view}.
     */
    @Transactional(readOnly = true)
    public boolean temporary(long id) {
        return find(users.email(), id).isEphemeral();
    }

    @Override
    @Transactional(readOnly = true)
    public Set<String> references() {
        return Set.copyOf(memories.referencesOf(users.email()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<MemoryViews.Entry> related(List<String> references, String skill, int limit) {
        String user = users.email();
        List<String> refs = references == null ? List.of() : references.stream().filter(r -> r != null && !r.isBlank())
                .map(r -> r.strip().toLowerCase(Locale.ROOT)).distinct().toList();
        String sk = blankToNull(skill) == null ? null : skill.strip().toLowerCase(Locale.ROOT);
        if (refs.isEmpty() && sk == null) {
            return List.of();
        }
        Specification<Memory> spec = (root, q, cb) -> {
            List<Predicate> or = new ArrayList<>();
            if (!refs.isEmpty()) {
                or.add(cb.lower(root.<String>get("reference")).in(refs));
            }
            if (sk != null) {
                or.add(cb.equal(root.get("skill"), sk));
            }
            return cb.and(cb.equal(root.get("owner"), user), cb.or(or.toArray(Predicate[]::new)));
        };
        return memories.findAll(spec, PageRequest.of(0, Math.max(1, Math.min(MAX_LIMIT, limit)),
                        Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")))).stream()
                .map(m -> new MemoryViews.Entry(m.getId(), m.getTitle(), null, m.getProject(), m.getSkill(),
                        m.getReference(), m.tagList(), m.getType(), m.getCreatedAt(), m.getUpdatedAt()))
                .toList();
    }

    private static MemoryViews.Entry entry(Memory m) {
        return new MemoryViews.Entry(m.getId(), m.getTitle(), m.getContent(), m.getProject(), m.getSkill(),
                m.getReference(), m.tagList(), m.getType(), m.getCreatedAt(), m.getUpdatedAt());
    }

    // ------------------------------------------------------------------ Lesen

    @Override
    @Transactional(readOnly = true)
    public String search(String query, String project, String skill, String tag, MemoryViews.Type type,
                         Integer days, Integer limit) {
        if (days != null && days < 1) {
            throw new IllegalArgumentException("'days' muss mindestens 1 sein (leer = beliebig alt).");
        }
        int max = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(MAX_LIMIT, limit));
        List<Hit> found = find(users.email(), query, project, skill, tag, type, days, max);
        String filters = describeFilters(query, project, skill, tag, type, days);
        if (found.isEmpty()) {
            return filters.isEmpty()
                    ? "Noch keine Memories gespeichert. Nach einer abgeschlossenen Aktion (Ticket reviewt, Fehler "
                    + "behoben, Entscheidung getroffen …) mit memories_save festhalten, was passiert ist."
                    : "Keine Memories gefunden" + filters + ". Mit weniger Begriffen oder ohne Filter erneut suchen.";
        }
        List<String> terms = terms(query);
        if (found.size() == 1 && (!terms.isEmpty() || !filters.isEmpty())
                && found.getFirst().memory().getContent().length() <= INLINE_MAX) {
            return "1 Memory" + filters + " – direkt geladen:\n\n" + render(found.getFirst().memory());
        }
        StringBuilder sb = new StringBuilder(found.size() + (found.size() == 1 ? " Memory" : " Memories"))
                .append(filters).append(" (").append(terms.isEmpty() ? "neueste" : "beste Treffer")
                .append(" zuerst; laden mit memories_view(id)):\n");
        for (Hit h : found) {
            Memory m = h.memory();
            sb.append(line(m)).append("\n  ").append(snippet(m.getContent(), terms)).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /** Eine Zeile: Nummer, Datum, Titel und – kompakt – temporär, Projekt, Skill, Bezug. */
    static String line(Memory m) {
        String meta = meta(m);
        return "#" + m.getId() + " " + DAY.format(m.getCreatedAt()) + " " + m.getTitle()
                + (meta.isEmpty() ? "" : " [" + meta + "]");
    }

    @Override
    @Transactional(readOnly = true)
    public String view(long id) {
        return render(find(users.email(), id));
    }

    /** Kompakter Kopf (eine Zeile Metadaten) plus Inhalt. */
    private static String render(Memory m) {
        StringBuilder sb = new StringBuilder("# #").append(m.getId()).append(' ').append(m.getTitle()).append('\n');
        List<String> parts = new ArrayList<>();
        if (m.isEphemeral()) {
            parts.add(m.getType().label());
        }
        if (m.getProject() != null) {
            parts.add("Projekt " + m.getProject());
        }
        if (m.getSkill() != null) {
            parts.add("Skill " + m.getSkill());
        }
        if (m.getReference() != null) {
            parts.add("Bezug " + m.getReference());
        }
        if (!m.tagList().isEmpty()) {
            parts.add("Tags " + String.join(", ", m.tagList()));
        }
        parts.add(DATE.format(m.getCreatedAt())
                + (m.getUpdatedAt().equals(m.getCreatedAt()) ? "" : ", geändert " + DATE.format(m.getUpdatedAt())));
        return sb.append(String.join(" · ", parts)).append("\n\n").append(m.getContent().strip()).toString();
    }

    // ------------------------------------------------------------------ Schreiben

    @Override
    public String save(String title, String content, MemoryViews.Type type, String project, String skill,
                       String reference, List<String> tags, int maxContentChars) {
        String user = users.email();
        String t = requireTitle(title);
        String body = requireContent(content, "content", maxContentChars);
        String p = normalizeProject(project);
        String s = normalizeSkill(skill);
        String r = normalizeReference(reference);
        String tg = SkillService.normalizeTags(tags);
        // vor dem Speichern suchen, damit die neue Memory nicht selbst als Vorgänger erscheint
        List<Long> earlier = r == null ? List.of() : sameReference(user, r);
        Memory m = memories.save(new Memory(user, t, body, p, s, r, tg, type, Instant.now()));
        changed();
        String msg = "Memory #" + m.getId() + (m.isEphemeral() ? " (" + m.getType().label() + ")" : "")
                + " gespeichert.";
        if (!earlier.isEmpty()) {
            msg += " Zu '" + r + "' gibt es außerdem " + earlier.stream().map(id -> "#" + id)
                    .collect(Collectors.joining(", ")) + " – Ergänzungen zu einer bestehenden Aktion besser mit "
                    + "memories_update(id, append=…) nachtragen.";
        }
        return msg;
    }

    @Override
    public String update(long id, String title, String content, String append, MemoryViews.Type type,
                         String project, String skill, String reference, List<String> tags, boolean temporaryOnly,
                         int maxContentChars) {
        if (content != null && append != null) {
            throw new IllegalArgumentException("Entweder 'content' (ersetzt den Inhalt) oder 'append' (hängt einen "
                    + "Nachtrag an) angeben, nicht beides.");
        }
        Memory m = find(users.email(), id);
        if (temporaryOnly) {
            requireTemporary(m, "geändert");
            if (type == MemoryViews.Type.PERMANENT) {
                throw new IllegalArgumentException("Memory #" + id + " dauerhaft zu machen braucht die Freigabe für "
                        + "dauerhafte Memories (permissions_request) – ohne sie bleibt sie " + m.getType().label()
                        + ".");
            }
        }
        Instant now = Instant.now();
        List<String> changes = new ArrayList<>();
        if (title != null) {
            m.setTitle(requireTitle(title));
            changes.add("Titel");
        }
        if (content != null) {
            m.setContent(requireContent(content, "content", maxContentChars));
            changes.add("Inhalt");
        }
        if (append != null) {
            String addition = requireContent(append, "append", maxContentChars);
            m.setContent(requireContent(m.getContent() + "\n\n**Nachtrag " + DATE.format(now) + ":**\n" + addition,
                    "content", maxContentChars));
            changes.add("Nachtrag");
        }
        if (project != null) {
            m.setProject(normalizeProject(project));
            changes.add("Projekt");
        }
        if (skill != null) {
            m.setSkill(normalizeSkill(skill));
            changes.add("Skill");
        }
        if (reference != null) {
            m.setReference(normalizeReference(reference));
            changes.add("Bezug");
        }
        if (tags != null) {
            m.setTags(SkillService.normalizeTags(tags));
            changes.add("Tags");
        }
        if (type != null && type != m.getType()) {
            m.setType(type);
            changes.add("jetzt " + type.label());
        }
        if (changes.isEmpty()) {
            return "Keine Änderung an Memory #" + id + " – mindestens ein Feld angeben (z.B. append).";
        }
        m.touch(now);
        changed();
        return "Memory #" + id + " aktualisiert (" + String.join(", ", changes) + ").";
    }

    @Override
    public String delete(long id, boolean temporaryOnly) {
        Memory m = find(users.email(), id);
        if (temporaryOnly) {
            requireTemporary(m, "gelöscht");
        }
        memories.delete(m);
        changed();
        return "Memory #" + id + " („" + m.getTitle() + "“) gelöscht.";
    }

    // ------------------------------------------------------------------ Suche

    /** Treffer mit Gewicht (Anzahl getroffener Begriffe, Titel/Bezug doppelt). */
    record Hit(Memory memory, int score) {
    }

    private List<Hit> find(String user, String query, String project, String skill, String tag,
                           MemoryViews.Type type, Integer days, int limit) {
        List<String> terms = terms(query);
        String p = blankToNull(project);
        String s = blankToNull(skill);
        String t = blankToNull(tag);
        Instant since = days == null ? null : Instant.now().minus(Duration.ofDays(days));
        Specification<Memory> spec = (root, q, cb) -> {
            List<Predicate> and = new ArrayList<>();
            and.add(cb.equal(root.get("owner"), user));
            if (p != null) {
                and.add(cb.equal(cb.lower(root.<String>get("project")), p.toLowerCase(Locale.ROOT)));
            }
            if (s != null) {
                and.add(cb.equal(root.get("skill"), s.toLowerCase(Locale.ROOT)));
            }
            if (t != null) {
                // exakter Tag in der kommagetrennten Liste
                and.add(cb.like(cb.concat(cb.concat(",", root.<String>get("tags")), ","),
                        "%," + t.toLowerCase(Locale.ROOT).replaceAll("\\s+", "-") + ",%"));
            }
            if (type == MemoryViews.Type.PERMANENT) {
                // leer = Memory von vor dem Typ, also dauerhaft
                and.add(cb.or(cb.isNull(root.get("type")), cb.equal(root.get("type"), type.name())));
            } else if (type != null) {
                and.add(cb.equal(root.get("type"), type.name()));
            }
            if (since != null) {
                and.add(cb.greaterThanOrEqualTo(root.get("createdAt"), since));
            }
            if (!terms.isEmpty()) {
                and.add(cb.or(terms.stream().map(term -> anyField(root, cb, term)).toArray(Predicate[]::new)));
            }
            return cb.and(and.toArray(Predicate[]::new));
        };
        Sort newest = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        if (terms.isEmpty()) {
            return memories.findAll(spec, PageRequest.of(0, limit, newest)).stream().map(m -> new Hit(m, 0)).toList();
        }
        return memories.findAll(spec, PageRequest.of(0, CANDIDATES, newest)).stream()
                .map(m -> new Hit(m, score(m, terms)))
                .sorted(Comparator.comparingInt(Hit::score).reversed()
                        .thenComparing(h -> h.memory().getCreatedAt(), Comparator.reverseOrder()))
                .limit(limit).toList();
    }

    private static Predicate anyField(Root<Memory> root, CriteriaBuilder cb, String term) {
        String pattern = "%" + term + "%";
        return cb.or(List.of("title", "content", "tags", "reference", "project", "skill").stream()
                .map(f -> cb.like(cb.lower(root.<String>get(f)), pattern)).toArray(Predicate[]::new));
    }

    static int score(Memory m, List<String> terms) {
        int score = 0;
        for (String term : terms) {
            if (contains(m.getTitle(), term) || contains(m.getReference(), term)) {
                score += 2;
            } else if (contains(m.getContent(), term) || contains(m.getTags(), term)
                    || contains(m.getProject(), term) || contains(m.getSkill(), term)) {
                score += 1;
            }
        }
        return score;
    }

    private static boolean contains(String text, String term) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(term);
    }

    /** Suchbegriffe: klein, ohne Duplikate, höchstens {@value #MAX_TERMS}. */
    static List<String> terms(String query) {
        String q = blankToNull(query);
        if (q == null) {
            return List.of();
        }
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String part : q.toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!part.isEmpty() && set.size() < MAX_TERMS) {
                set.add(part);
            }
        }
        return List.copyOf(set);
    }

    /** Ausschnitt des Inhalts: um den ersten Treffer, sonst der Anfang; Zeilenumbrüche zu Leerzeichen. */
    static String snippet(String content, List<String> terms) {
        String flat = content.strip().replaceAll("\\s+", " ");
        if (flat.length() <= SNIPPET) {
            return flat;
        }
        String lower = flat.toLowerCase(Locale.ROOT);
        int at = terms.stream().mapToInt(lower::indexOf).filter(i -> i >= 0).min().orElse(0);
        int start = Math.max(0, Math.min(at - SNIPPET / 4, flat.length() - SNIPPET));
        int end = Math.min(flat.length(), start + SNIPPET);
        return (start > 0 ? "…" : "") + flat.substring(start, end) + (end < flat.length() ? "…" : "");
    }

    private List<Long> sameReference(String user, String reference) {
        Specification<Memory> spec = (root, q, cb) -> cb.and(cb.equal(root.get("owner"), user),
                cb.equal(cb.lower(root.<String>get("reference")), reference.toLowerCase(Locale.ROOT)));
        return memories.findAll(spec, PageRequest.of(0, 5, Sort.by("id"))).stream().map(Memory::getId).toList();
    }

    private static String meta(Memory m) {
        List<String> parts = new ArrayList<>();
        if (m.isEphemeral()) {
            parts.add(m.getType().label());
        }
        if (m.getProject() != null) {
            parts.add(m.getProject());
        }
        if (m.getSkill() != null) {
            parts.add(m.getSkill());
        }
        if (m.getReference() != null) {
            parts.add(m.getReference());
        }
        return String.join(" · ", parts);
    }

    private static String describeFilters(String query, String project, String skill, String tag,
                                          MemoryViews.Type type, Integer days) {
        StringBuilder sb = new StringBuilder();
        if (blankToNull(query) != null) {
            sb.append(" für '").append(query.strip()).append('\'');
        }
        if (blankToNull(project) != null) {
            sb.append(" in Projekt '").append(project.strip()).append('\'');
        }
        if (blankToNull(skill) != null) {
            sb.append(" zu Skill '").append(skill.strip()).append('\'');
        }
        if (blankToNull(tag) != null) {
            sb.append(" mit Tag '").append(tag.strip()).append('\'');
        }
        if (type != null) {
            sb.append(switch (type) {
                case PERMANENT -> " (nur dauerhafte)";
                case TEMPORARY -> " (nur temporäre)";
                case INVOCATION -> " (nur Rückrufe)";
            });
        }
        if (days != null) {
            sb.append(" der letzten ").append(days).append(days == 1 ? " Tag" : " Tage");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ intern

    private Memory find(String user, long id) {
        return memories.findByIdAndOwner(id, user).orElseThrow(() -> new IllegalArgumentException("Memory #" + id
                + " gibt es nicht (oder sie gehört einem anderen Benutzer). Mit memories_search suchen."));
    }

    /** Ohne Freigabe für dauerhafte Memories: nur temporäre und Rückrufe dürfen geändert bzw. gelöscht werden. */
    private static void requireTemporary(Memory m, String action) {
        if (!m.isEphemeral()) {
            throw new IllegalArgumentException("Memory #" + m.getId() + " ist dauerhaft und darf hier nicht "
                    + action + " werden – ohne Freigabe nur temporäre Memories und Rückrufe. Freigabe für dauerhafte "
                    + "Memories mit permissions_request anfragen oder den Nutzer fragen.");
        }
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
                LOG.warn("Memory-ChangeListener fehlgeschlagen", e);
            }
        }
    }

    private static String requireTitle(String title) {
        String t = title == null ? "" : title.strip().replaceAll("\\s+", " ");
        if (t.isEmpty()) {
            throw new IllegalArgumentException("'title' fehlt: eine Zeile, was getan wurde, z.B. 'Ticket ABC-123 "
                    + "reviewt: Akzeptanzkriterien fehlen'.");
        }
        if (t.length() > MAX_TITLE) {
            throw new IllegalArgumentException("'title' ist " + t.length() + " Zeichen lang (max. " + MAX_TITLE
                    + "). Details gehören in 'content'.");
        }
        return t;
    }

    private static String requireContent(String content, String field, int maxContentChars) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("'" + field + "' darf nicht leer sein.");
        }
        if (content.length() > maxContentChars) {
            throw new IllegalArgumentException("'" + field + "' ist " + content.length() + " Zeichen lang (max. "
                    + maxContentChars + "). Auf Ergebnis, Entscheidungen und Begründung kürzen.");
        }
        return content;
    }

    private static String normalizeProject(String project) {
        String p = blankToNull(project);
        if (p != null && p.length() > MAX_PROJECT) {
            throw new IllegalArgumentException("'project' ist zu lang (max. " + MAX_PROJECT + " Zeichen) – den "
                    + "Namen aus projects_list verwenden.");
        }
        return p;
    }

    private static String normalizeSkill(String skill) {
        String s = blankToNull(skill);
        if (s == null) {
            return null;
        }
        s = s.toLowerCase(Locale.ROOT);
        if (!SKILL.matcher(s).matches()) {
            throw new IllegalArgumentException("Ungültiger Skill-Name '" + skill + "': den Namen aus skills_list "
                    + "verwenden (z.B. 'ticket-review').");
        }
        return s;
    }

    private static String normalizeReference(String reference) {
        String r = blankToNull(reference);
        if (r != null && r.length() > MAX_REFERENCE) {
            throw new IllegalArgumentException("'reference' ist zu lang (max. " + MAX_REFERENCE + " Zeichen).");
        }
        return r;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
