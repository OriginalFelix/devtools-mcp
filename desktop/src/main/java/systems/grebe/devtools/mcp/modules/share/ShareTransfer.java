package systems.grebe.devtools.mcp.modules.share;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import systems.grebe.devtools.mcp.backend.memories.Memory;
import systems.grebe.devtools.mcp.backend.skills.Skill;
import systems.grebe.devtools.mcp.modules.memories.MemoryBackend;
import systems.grebe.devtools.mcp.modules.memories.MemoryViews;
import systems.grebe.devtools.mcp.modules.share.ShareMessages.FileItem;
import systems.grebe.devtools.mcp.modules.share.ShareMessages.MemoryFile;
import systems.grebe.devtools.mcp.modules.share.ShareMessages.MemoryItem;
import systems.grebe.devtools.mcp.modules.share.ShareMessages.Offer;
import systems.grebe.devtools.mcp.modules.share.ShareMessages.SkillFile;
import systems.grebe.devtools.mcp.modules.share.ShareMessages.SkillItem;
import systems.grebe.devtools.mcp.modules.skills.SkillBackend;
import systems.grebe.devtools.mcp.modules.skills.SkillViews;
import systems.grebe.devtools.mcp.core.Text;

/**
 * Packt Memories, Skills und Dateien dieser Instanz in ein Angebot und übernimmt ein angenommenes Angebot: Notiz und
 * Memories als <em>temporäre</em> Memories (der Empfänger macht sie bei Bedarf dauerhaft), Skills als eigene Skills
 * (bei gleichem Namen umbenannt, nie überschrieben), Dateien in einen eigenen Ordner je Angebot.
 */
final class ShareTransfer {

    static final String TAG = "geteilt";
    /** Nummer der gespeicherten Memory in der Antwort von {@code memories.save} („Memory #12 … gespeichert.“). */
    private static final Pattern SAVED_ID = Pattern.compile("Memory #(\\d+)");
    private static final int MEMORY_MAX = Memory.CONTENT_COLUMN;
    private static final int SKILL_MAX = Skill.CONTENT_COLUMN;

    private final MemoryBackend memories;
    private final SkillBackend skills;
    private final List<Path> sendRoots;
    private final Path receiveDir;

    ShareTransfer(MemoryBackend memories, SkillBackend skills, List<Path> sendRoots, Path receiveDir) {
        this.memories = memories;
        this.skills = skills;
        this.sendRoots = List.copyOf(sendRoots);
        this.receiveDir = receiveDir;
    }

    List<Path> sendRoots() {
        return sendRoots;
    }

    Path receiveDir() {
        return receiveDir;
    }

    // ------------------------------------------------------------------ Packen

    Offer build(ShareBroker.Settings s, String instance, String to, String title, String note, List<Long> memoryIds,
                List<String> skillNames, List<String> filePaths) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("'title' fehlt – eine Zeile, worum es geht.");
        }
        List<MemoryItem> ms = new ArrayList<>();
        for (Long id : distinct(memoryIds)) {
            MemoryViews.Entry e = memories.details(id).orElseThrow(() -> new IllegalArgumentException(
                    "Memory #" + id + " nicht gefunden – memories_search zeigt die Nummern."));
            List<MemoryFile> files = new ArrayList<>();
            for (MemoryViews.File f : e.files()) {
                files.add(new MemoryFile(f.path(), f.mediaType(), f.size(), base64("Datei '" + f.path()
                        + "' von Memory #" + id, f.size(), s, tmp -> memories.exportFile(id, f.path(), tmp))));
            }
            ms.add(new MemoryItem(e.title(), e.content(), e.project(), e.skill(), e.reference(), e.tags(), files));
        }
        List<SkillItem> ks = new ArrayList<>();
        for (String name : distinct(skillNames)) {
            SkillViews.Details d = skills.details(name.strip()).orElseThrow(() -> new IllegalArgumentException(
                    "Skill '" + name + "' nicht gefunden – skills_list zeigt die Namen."));
            SkillViews.Summary k = d.summary();
            List<SkillFile> files = new ArrayList<>();
            for (SkillViews.File f : d.files()) {
                files.add(f.inline() ? new SkillFile(f.path(), f.content())
                        : new SkillFile(f.path(), null, base64("Anhang '" + f.path() + "' von Skill '" + k.name()
                        + "'", f.size(), s, tmp -> skills.exportFile(k.name(), f.path(), tmp)), f.mediaType()));
            }
            ks.add(new SkillItem(k.name(), k.description(), d.content(), k.category(), k.tags(), k.triggers(),
                    files));
        }
        List<FileItem> fs = new ArrayList<>();
        for (String raw : distinct(filePaths)) {
            Path p = localPath(raw);
            if (!Files.isRegularFile(p)) {
                throw new IllegalArgumentException("Keine Datei: " + p + " (Verzeichnisse einzeln senden geht nicht).");
            }
            try {
                long size = Files.size(p);
                if (size > s.maxBytes()) {
                    throw new IllegalArgumentException("Datei zu groß: " + p + " (" + size / 1024 + " KB, höchstens "
                            + s.maxBytes() / 1024 + " KB je Angebot).");
                }
                fs.add(new FileItem(p.getFileName().toString(), size,
                        Base64.getEncoder().encodeToString(Files.readAllBytes(p))));
            } catch (IOException e) {
                throw new IllegalStateException("Datei nicht lesbar: " + p + " – " + e.getMessage(), e);
            }
        }
        String n = note == null || note.isBlank() ? null : note.strip();
        if (n == null && ms.isEmpty() && ks.isEmpty() && fs.isEmpty()) {
            throw new IllegalArgumentException("Nichts zu senden – 'note', 'memories', 'skills' oder 'files' angeben.");
        }
        return new Offer(ShareMessages.VERSION, UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                s.address(), s.name(), instance, ShareMessages.address(to), Instant.now().toString(), Text.oneLine(title, 200),
                n, ms, ks, fs);
    }

    /**
     * Anhang aus der Dateiablage Base64-kodiert (die Größe des ganzen Angebots prüft der Broker beim Senden).
     *
     * @param export schreibt den Inhalt in die übergebene temporäre Datei
     */
    private static String base64(String what, long size, ShareBroker.Settings s, Consumer<Path> export) {
        if (size > s.maxBytes()) {
            throw new IllegalArgumentException(what + " ist zu groß (" + size / 1024 + " KB, höchstens "
                    + s.maxBytes() / 1024 + " KB je Angebot).");
        }
        Path tmp = null;
        try {
            tmp = Files.createTempFile("devtools-share-", ".tmp");
            export.accept(tmp);
            return Base64.getEncoder().encodeToString(Files.readAllBytes(tmp));
        } catch (IOException e) {
            throw new IllegalStateException(what + " nicht lesbar: " + e.getMessage(), e);
        } finally {
            deleteQuietly(tmp);
        }
    }

    private static void deleteQuietly(Path p) {
        if (p != null) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException ignored) {
                // temporäre Datei bleibt liegen
            }
        }
    }

    /**
     * Lokale Datei innerhalb der Verzeichnisse, aus denen gesendet werden darf. Relativ nur, wenn genau eines
     * freigegeben ist; auch der echte Pfad (Symlinks) muss darin liegen.
     */
    Path localPath(String path) {
        if (sendRoots.isEmpty()) {
            throw new IllegalStateException("Kein Verzeichnis zum Senden von Dateien freigegeben – der Nutzer kann es "
                    + "in der DevTools-App unter Module → Kooperation → „Dateien senden aus“ eintragen.");
        }
        Path p;
        try {
            p = Path.of(path.strip().replaceFirst("^~(?=[/\\\\]|$)",
                    java.util.regex.Matcher.quoteReplacement(System.getProperty("user.home"))));
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("Ungültiger Pfad: " + path);
        }
        if (!p.isAbsolute()) {
            if (sendRoots.size() != 1) {
                throw new IllegalArgumentException("Mehrere Verzeichnisse freigegeben – absoluten Pfad angeben: "
                        + sendRoots);
            }
            p = sendRoots.getFirst().resolve(p);
        }
        Path target = p.toAbsolutePath().normalize();
        for (Path root : sendRoots) {
            if (target.startsWith(root) && realPathInside(target, root)) {
                return target;
            }
        }
        throw new IllegalArgumentException("Datei " + target + " liegt nicht in einem freigegebenen Verzeichnis: "
                + sendRoots);
    }

    private static boolean realPathInside(Path target, Path root) {
        try {
            return !Files.exists(target) || target.toRealPath().startsWith(root.toRealPath());
        } catch (IOException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ Rückfragen

    /** Text der Rückfrage an Nutzer 1 vor dem Senden. */
    static String sendQuestion(Offer o, String broker) {
        StringBuilder sb = new StringBuilder("An: ").append(o.to()).append('\n')
                .append("Titel: ").append(o.title()).append('\n');
        describeContent(sb, o);
        sb.append("\nÜber den Broker ").append(broker).append(". Der Empfänger muss das Angebot selbst annehmen.");
        return sb.toString();
    }

    /** Text der Rückfrage an Nutzer 2 vor dem Übernehmen. */
    String acceptQuestion(Offer o) {
        StringBuilder sb = new StringBuilder("Von: ").append(o.sender()).append('\n')
                .append("Titel: ").append(o.title()).append('\n');
        describeContent(sb, o);
        sb.append("\nÜbernommen werden Notiz und Memories als temporäre Memories, Skills als eigene Skills (bei "
                + "gleichem Namen umbenannt)");
        if (!o.files().isEmpty()) {
            sb.append(", Dateien nach ").append(receiveDir);
        }
        return sb.append('.').toString();
    }

    private static void describeContent(StringBuilder sb, Offer o) {
        if (o.note() != null) {
            sb.append("Notiz: ").append(shorten(o.note(), 600)).append('\n');
        }
        for (MemoryItem m : o.memories()) {
            sb.append("Memory: ").append(m.title()).append(m.files().isEmpty() ? "" : " (+" + m.files().size()
                    + " Dateien)").append('\n');
        }
        for (SkillItem k : o.skills()) {
            sb.append("Skill: ").append(k.name()).append(k.files().isEmpty() ? "" : " (+" + k.files().size()
                    + " Dateien)").append(" – ").append(shorten(k.description(), 200)).append('\n');
        }
        for (FileItem f : o.files()) {
            sb.append("Datei: ").append(f.name()).append(" (").append(Text.fileSize(f.size())).append(")\n");
        }
    }

    // ------------------------------------------------------------------ Übernehmen

    /** Übernimmt den Inhalt; Fehler einzelner Teile stehen im Ergebnis, der Rest wird trotzdem übernommen. */
    String importOffer(Offer o) {
        List<String> out = new ArrayList<>();
        String footer = "\n\n---\nÜbernommen aus dem Angebot „" + o.title() + "“ von " + o.sender() + " (" + o.id()
                + ", gesendet " + o.sent() + ").";
        if (o.note() != null) {
            out.add(attempt("Notiz", () -> memories.save(o.title(), fit(o.note(), footer, MEMORY_MAX),
                    MemoryViews.Type.TEMPORARY, null, null, null, List.of(TAG), MEMORY_MAX)));
        }
        for (MemoryItem m : o.memories()) {
            List<String> tags = new ArrayList<>(m.tags() == null ? List.of() : m.tags());
            if (!tags.contains(TAG)) {
                tags.add(TAG);
            }
            out.add(attempt("Memory „" + m.title() + "“", () -> importMemory(m, footer, tags)));
        }
        for (SkillItem k : o.skills()) {
            out.add(attempt("Skill „" + k.name() + "“", () -> importSkill(o, k)));
        }
        if (!o.files().isEmpty()) {
            out.add(attempt("Dateien", () -> importFiles(o)));
        }
        return String.join("\n", out);
    }

    private String importMemory(MemoryItem m, String footer, List<String> tags) throws IOException {
        String result = memories.save(m.title(), fit(m.content() == null ? "" : m.content(), footer, MEMORY_MAX),
                MemoryViews.Type.TEMPORARY, m.project(), m.skill(), m.reference(), tags, MEMORY_MAX);
        if (m.files().isEmpty()) {
            return result;
        }
        Matcher id = SAVED_ID.matcher(result);
        if (!id.find()) {
            return result + " Dateien nicht angehängt – Nummer der Memory unbekannt.";
        }
        long memory = Long.parseLong(id.group(1));
        StringBuilder sb = new StringBuilder(result);
        int attached = 0;
        for (MemoryFile f : m.files()) {
            Path tmp = Files.createTempFile("devtools-share-", ".tmp");
            try {
                Files.write(tmp, f.data() == null ? new byte[0] : Base64.getDecoder().decode(f.data()));
                // die Memory ist temporär – dafür braucht es keine Freigabe für dauerhafte Memories
                memories.attachFile(memory, f.path(), tmp, f.mediaType(), true);
                attached++;
            } catch (RuntimeException e) {
                sb.append(" Datei '").append(f.path()).append("' FEHLER – ").append(e.getMessage());
            } finally {
                deleteQuietly(tmp);
            }
        }
        return sb.append(attached > 0 ? " + " + attached + " Datei(en) angehängt." : "").toString();
    }

    private String importSkill(Offer o, SkillItem k) {
        String name = freeSkillName(k.name(), o.from());
        String note = "Übernommen von " + o.sender() + " (Angebot " + o.id() + ")";
        String result = skills.create(name, k.description(), k.content(), k.category(), k.tags(), k.triggers(),
                SKILL_MAX);
        StringBuilder sb = new StringBuilder(result);
        if (!name.equals(k.name())) {
            sb.append(" Als '").append(name).append("' angelegt, weil es '").append(k.name()).append("' schon gibt.");
        }
        for (SkillFile f : k.files()) {
            try {
                if (f.data() != null) {
                    importAttachment(name, f, note);
                } else {
                    skills.writeFile(name, f.path(), f.content(), note, SKILL_MAX);
                }
            } catch (RuntimeException | IOException e) {
                sb.append(" Datei '").append(f.path()).append("' FEHLER – ").append(e.getMessage());
            }
        }
        return sb.toString();
    }

    private void importAttachment(String skill, SkillFile f, String note) throws IOException {
        Path tmp = Files.createTempFile("devtools-share-", ".tmp");
        try {
            Files.write(tmp, Base64.getDecoder().decode(f.data()));
            skills.attachFile(skill, f.path(), tmp, f.mediaType(), note);
        } finally {
            deleteQuietly(tmp);
        }
    }

    /** Freier Skill-Name: der ursprüngliche, sonst {@code <name>-<absender>}, sonst mit Zähler. */
    String freeSkillName(String name, String from) {
        String base = name.strip().toLowerCase(Locale.ROOT);
        if (skills.details(base).isEmpty()) {
            return base;
        }
        String who = from.replaceFirst("@.*", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-");
        String candidate = cut(base + "-" + who);
        for (int i = 2; skills.details(candidate).isPresent(); i++) {
            candidate = cut(base + "-" + who) + "-" + i;
        }
        return candidate;
    }

    private static String cut(String s) {
        return s.length() > 60 ? s.substring(0, 60) : s;
    }

    private String importFiles(Offer o) throws IOException {
        String who = o.from().replaceFirst("@.*", "").replaceAll("[^A-Za-z0-9._-]+", "-");
        Path dir = receiveDir.resolve(LocalDate.now(ZoneId.systemDefault()) + "_" + who + "_" + o.id());
        Files.createDirectories(dir);
        List<String> written = new ArrayList<>();
        for (FileItem f : o.files()) {
            Path target = unique(dir, safeName(f.name()));
            Files.write(target, f.data() == null ? new byte[0] : Base64.getDecoder().decode(f.data()),
                    StandardOpenOption.CREATE_NEW);
            written.add(target.getFileName() + " (" + Text.fileSize(Files.size(target)) + ")");
        }
        return written.size() + " Datei(en) in " + dir + ": " + String.join(", ", written);
    }

    /** Nur der Dateiname, ohne Pfadanteile und Zeichen, die das Dateisystem nicht mag. */
    static String safeName(String raw) {
        String n = raw == null ? "" : raw.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}:*?\"<>|]+", "_").strip();
        if (n.isEmpty() || n.equals(".") || n.equals("..")) {
            n = "datei";
        }
        return n.startsWith(".") ? "_" + n : n;
    }

    private static Path unique(Path dir, String name) {
        Path p = dir.resolve(name);
        int dot = name.lastIndexOf('.');
        for (int i = 2; Files.exists(p); i++) {
            p = dir.resolve(dot > 0 ? name.substring(0, dot) + "-" + i + name.substring(dot) : name + "-" + i);
        }
        return p;
    }

    // ------------------------------------------------------------------ Hilfen

    @FunctionalInterface
    private interface Step {
        String run() throws Exception;
    }

    private static String attempt(String what, Step step) {
        try {
            return what + ": " + step.run();
        } catch (Exception e) {
            return what + ": FEHLER – " + e.getMessage();
        }
    }

    /** Inhalt mit Herkunftsvermerk, gekürzt, damit beides in eine Memory passt. */
    private static String fit(String content, String footer, int max) {
        int room = max - footer.length();
        String c = content.length() > room ? content.substring(0, Math.max(0, room - 20)) + "\n… (gekürzt)" : content;
        return c + footer;
    }

    private static <T> List<T> distinct(List<T> values) {
        return values == null ? List.of() : new ArrayList<>(new LinkedHashSet<>(values.stream()
                .filter(v -> v != null && !(v instanceof String s && s.isBlank())).toList()));
    }

    static String shorten(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }

}
