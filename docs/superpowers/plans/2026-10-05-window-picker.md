# Fensterauswahl-Dialog und globale Freigaben – Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Neben „Nur diese Prozesse“ / „Prozesse ausschließen“ ein Button, der einen grafischen Fensterauswahl-Dialog öffnet; Programme aus global freigegebenen Ordnern sind für die Fenstersteuerung freigegeben (auch gegen „Prozesse ausschließen“), mit Warnung beim Speichern.

**Architecture:** Neuer Feldtyp `PROCESS_PATTERN` (shared); `ConfigForm` rendert dafür Textfeld + Button, der `WindowPickerDialog` (JavaFX) öffnet. Die Daten liefert `WindowCandidates` (Modul `window`, ohne JavaFX testbar). `ProcessFilter` bekommt die freigegebenen Ordner über `sharedDirectoryFields()`; `ToolModule.saveWarnings` + `ToolRegistry.saveWarnings` liefern Warnungen, die `ModuleDetailPane` vor dem Speichern anzeigt.

**Tech Stack:** Java 25, JavaFX 25, Spring Boot 4, JUnit 5 + AssertJ, Gradle (Kotlin DSL). Spec: `docs/superpowers/specs/2026-10-05-window-picker-design.md`.

## Global Constraints

- Texte, Kommentare und Javadoc auf Deutsch, Stil wie im umgebenden Code.
- Hart gesperrt bleiben immer: diese App samt Kindprozessen (außer per `window_launch` gestartet) und `ProcessFilter.ALWAYS_EXCLUDED`.
- Freigegebener Ordner überschreibt „Nur diese Prozesse“ **und** „Prozesse ausschließen“.
- „Beschränkung aufheben“ (`AccessModule.UNRESTRICTED`) wirkt nicht auf die Fenstersteuerung.
- Scan: übersprungene Ordner `.git`, `node_modules`, `build`, `target`, `.gradle`, `.idea`, `out`, `dist`; Obergrenze 50 000 Einträge; höchstens 20 Treffer im Dialog, dann „… und N weitere“.
- Firmen-QS-Git-Hook-Fehler in Git-/PR-Tests sind lokal – nicht im Projekt umgehen.
- Commits nur lokal; Push nur auf Anweisung des Nutzers.
- Tests: `./gradlew :desktop:test --tests '<Klasse>'` (Windows: Git Bash im Projektverzeichnis).

---

## Dateien

| Datei | Aktion | Verantwortung |
|---|---|---|
| `desktop/src/main/java/systems/grebe/devtools/mcp/modules/window/ProcessPatterns.java` | neu | Prozessnamen escaped an Ausdruck hängen |
| `desktop/src/main/java/systems/grebe/devtools/mcp/modules/window/ProcessFilter.java` | ändern | `Info.command`, freigegebene Ordner |
| `desktop/src/main/java/systems/grebe/devtools/mcp/modules/window/SharedProgramScan.java` | neu | rekursive Suche nach Programmen |
| `desktop/src/main/java/systems/grebe/devtools/mcp/modules/window/WindowCandidates.java` | neu | wählbare Fenster + Vorschau |
| `desktop/src/main/java/systems/grebe/devtools/mcp/modules/window/WindowModule.java` | ändern | Feldtyp, `sharedDirectoryFields`, Filter, `saveWarnings` |
| `desktop/src/main/java/systems/grebe/devtools/mcp/modules/window/WindowLaunchTools.java` | ändern | Programmpfad an `Info` |
| `desktop/src/main/java/systems/grebe/devtools/mcp/core/ToolModule.java` | ändern | `saveWarnings` (Default leer) |
| `desktop/src/main/java/systems/grebe/devtools/mcp/core/ToolRegistry.java` | ändern | `saveWarnings(moduleId, values)` |
| `desktop/src/main/java/systems/grebe/devtools/mcp/ui/ModuleDetailPane.java` | ändern | Warnung vor dem Speichern |
| `shared/src/main/java/systems/grebe/devtools/mcp/core/FieldType.java` | ändern | `PROCESS_PATTERN` |
| `desktop/src/main/java/systems/grebe/devtools/mcp/ui/ConfigForm.java` | ändern | Textfeld + „Fenster wählen…“ |
| `desktop/src/main/java/systems/grebe/devtools/mcp/ui/WindowPickerDialog.java` | neu | Übersicht, Vorschau, Hinzufügen |
| Tests unter `desktop/src/test/java/systems/grebe/devtools/mcp/modules/window/` | neu/ändern | siehe Tasks |
| `README.md`, Spec | ändern | Doku |

---

### Task 1: ProcessPatterns.append

**Files:**
- Create: `desktop/src/main/java/systems/grebe/devtools/mcp/modules/window/ProcessPatterns.java`
- Test: `desktop/src/test/java/systems/grebe/devtools/mcp/modules/window/ProcessPatternsTest.java`

**Interfaces:**
- Produces: `public static String ProcessPatterns.append(String pattern, String processName)`

- [ ] **Step 1: Failing test**

```java
package systems.grebe.devtools.mcp.modules.window;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessPatternsTest {

    @Test
    void startsAnEmptyPattern() {
        assertThat(ProcessPatterns.append("", "winword")).isEqualTo("winword");
        assertThat(ProcessPatterns.append(null, "winword")).isEqualTo("winword");
    }

    @Test
    void addsAnAlternativeAndKeepsOwnExpressions() {
        assertThat(ProcessPatterns.append("charmap", "winword")).isEqualTo("charmap|winword");
        assertThat(ProcessPatterns.append("^calc.*", "winword")).isEqualTo("^calc.*|winword");
    }

    @Test
    void escapesSpecialCharacters() {
        assertThat(ProcessPatterns.append("", "notepad++")).isEqualTo("notepad\\+\\+");
    }

    @Test
    void leavesThePatternWhenItAlreadyMatches() {
        assertThat(ProcessPatterns.append("charmap|WINWORD", "winword")).isEqualTo("charmap|WINWORD");
        assertThat(ProcessPatterns.append("word", "winword")).isEqualTo("word");
    }

    @Test
    void appendsToInvalidPatterns() {
        assertThat(ProcessPatterns.append("(", "winword")).isEqualTo("(|winword");
    }
}
```

- [ ] **Step 2:** `./gradlew :desktop:test --tests '*ProcessPatternsTest'` → FAIL (Klasse fehlt).

- [ ] **Step 3: Implementierung**

```java
package systems.grebe.devtools.mcp.modules.window;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Hilfen für die regulären Ausdrücke „Nur diese Prozesse“ und „Prozesse ausschließen“. */
public final class ProcessPatterns {

    private ProcessPatterns() {
    }

    /**
     * Hängt einen Prozessnamen als Alternative an den Ausdruck an ({@code charmap} → {@code charmap|winword}).
     * Sonderzeichen werden escaped; passt der Ausdruck schon auf den Namen, bleibt er unverändert.
     */
    public static String append(String pattern, String processName) {
        String p = pattern == null ? "" : pattern.strip();
        String quoted = processName.replaceAll("[\\\\^$.|?*+()\\[\\]{}]", "\\\\$0");
        if (p.isEmpty()) {
            return quoted;
        }
        try {
            if (Pattern.compile(p, Pattern.CASE_INSENSITIVE).matcher(processName).find()) {
                return p;
            }
        } catch (PatternSyntaxException e) {
            // ungültiger Ausdruck: trotzdem anhängen, die Prüfung beim Speichern meldet ihn
        }
        return p + "|" + quoted;
    }
}
```

- [ ] **Step 4:** Test erneut → PASS.
- [ ] **Step 5:** `git add` beider Dateien, `git commit -m "feat(window): Prozessnamen an Prozess-Ausdrücke anhängen"`.

---

### Task 2: Freigegebene Ordner im ProcessFilter

**Files:**
- Modify: `ProcessFilter.java` (Info, Konstruktor, `rejection(Info)`)
- Modify: `WindowModule.java` (`SHARED`, `sharedDirectoryFields`, `sharedDirectories`, Filter-Aufbau in `support` und `testConnection`)
- Modify: `WindowLaunchTools.java:53`
- Test: `ProcessFilterTest.java`

**Interfaces:**
- Produces: `record ProcessFilter.Info(long pid, String name, String commandLine, String command)` mit Zusatz-Konstruktor `Info(long, String, String)` (`command = null`); `static Info Info.ofExecutable(Path)`; Konstruktor `ProcessFilter(Pattern include, Pattern exclude, long self, Set<Long> launched, List<Path> shared)`; `boolean ProcessFilter.shared(String command)`; `static final String WindowModule.SHARED = "sharedDirectories"`; `static List<Path> WindowModule.sharedDirectories(ModuleConfig)`.

- [ ] **Step 1: Failing tests** (in `ProcessFilterTest` ergänzen; Imports `java.nio.file.Path`, `java.util.List`, `java.util.Set`)

```java
    private static ProcessFilter sharing(Pattern include, Pattern exclude, Path dir) {
        return new ProcessFilter(include, exclude, -1, Set.of(), List.of(dir));
    }

    @Test
    void programsInSharedDirectoriesOverrideIncludeAndExclude() {
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "tools").toAbsolutePath();
        String exe = dir.resolve("sub").resolve("deep").resolve("foo.exe").toString();
        ProcessFilter f = sharing(Pattern.compile("(?i)notepad"), Pattern.compile("(?i)foo"), dir);

        assertThat(f.rejection(new ProcessFilter.Info(1, "foo", exe, exe))).isEmpty();
        String outside = Path.of(System.getProperty("java.io.tmpdir"), "other", "foo.exe").toAbsolutePath().toString();
        assertThat(f.rejection(new ProcessFilter.Info(1, "foo", outside, outside))).isPresent();
    }

    @Test
    void sharedDirectoriesNeverUnlockPasswordManagers() {
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "tools").toAbsolutePath();
        String exe = dir.resolve("KeePass.exe").toString();
        assertThat(sharing(null, null, dir).rejection(new ProcessFilter.Info(1, "KeePass", exe, exe))).isPresent();
    }

    @Test
    void infoOfAnExecutableUsesNameWithoutExtension() {
        ProcessFilter.Info i = ProcessFilter.Info.ofExecutable(Path.of("C:", "Tools", "Foo.exe"));
        assertThat(i.name()).isEqualTo("Foo");
        assertThat(i.command()).endsWith("Foo.exe");
    }
```

- [ ] **Step 2:** `./gradlew :desktop:test --tests '*ProcessFilterTest'` → FAIL (Kompilierfehler).

- [ ] **Step 3: ProcessFilter**

`Info` ersetzen:

```java
    /**
     * Ein Prozess mit Name, Kommandozeile und – sofern bekannt – Pfad der ausführbaren Datei.
     *
     * @param command Pfad der ausführbaren Datei oder {@code null}
     */
    record Info(long pid, String name, String commandLine, String command) {

        Info(long pid, String name, String commandLine) {
            this(pid, name, commandLine, null);
        }

        /** Eine Programmdatei, als wäre sie gestartet (für die Prüfung beim Speichern). */
        static Info ofExecutable(Path file) {
            String path = file.toString();
            return new Info(0, name(path), path, path);
        }
    }
```

Feld + Konstruktoren (bestehende delegieren mit `List.of()`):

```java
    private final List<Path> shared;

    /** @param launched von der KI gestartete Programme – geteilt, damit alle Filter des Moduls sie kennen */
    ProcessFilter(Pattern include, Pattern exclude, long self, Set<Long> launched) {
        this(include, exclude, self, launched, List.of());
    }

    /** @param shared global freigegebene Ordner (Modul „Freigaben“): Programme darin sind immer freigegeben */
    ProcessFilter(Pattern include, Pattern exclude, long self, Set<Long> launched, List<Path> shared) {
        this.include = include;
        this.exclude = exclude;
        this.self = self;
        this.launched = launched;
        this.shared = shared.stream().map(p -> p.toAbsolutePath().normalize()).toList();
    }
```

`info(ProcessHandle)`: `new Info(p.pid(), name(cmd), i.commandLine().orElse(cmd), cmd)`.

In `rejection(Info p)` direkt nach der ALWAYS_EXCLUDED-Schleife:

```java
        if (shared(p.command())) {
            return Optional.empty(); // global freigegebener Ordner überschreibt „Nur diese Prozesse“ und Ausschlüsse
        }
```

Neue Methode:

```java
    /** Ob die ausführbare Datei in einem global freigegebenen Ordner (oder einem Unterordner davon) liegt. */
    boolean shared(String command) {
        if (command == null || shared.isEmpty()) {
            return false;
        }
        try {
            Path exe = Path.of(command).toAbsolutePath().normalize();
            return shared.stream().anyMatch(exe::startsWith); // unter Windows ohne Groß-/Kleinschreibung
        } catch (java.nio.file.InvalidPathException e) {
            return false;
        }
    }
```

Klassen-Javadoc ergänzen: „Programme in global freigegebenen Ordnern (Modul „Freigaben“) sind freigegeben, auch gegen Include/Exclude – nicht aber gegen die immer ausgeschlossenen Prozesse und diese App.“

- [ ] **Step 4: WindowModule**

```java
    /** Global freigegebene Ordner (Modul „Freigaben“), von der ToolRegistry eingefügt – nicht im Formular. */
    static final String SHARED = "sharedDirectories";
    private static final Pattern NAMED = Pattern.compile("([A-Za-z0-9._@ -]+)=(.+)");

    @Override
    public Set<String> sharedDirectoryFields() {
        return Set.of(SHARED);
    }

    /** Freigegebene Ordner; Einträge {@code name=pfad} werden auf den Pfad reduziert, ungültige übersprungen. */
    static List<Path> sharedDirectories(ModuleConfig config) {
        List<Path> out = new ArrayList<>();
        for (String line : config.getList(SHARED)) {
            java.util.regex.Matcher m = NAMED.matcher(line);
            String path = m.matches() && !Path.of(line).isAbsolute() ? m.group(2) : line;
            try {
                out.add(Path.of(path.strip()));
            } catch (java.nio.file.InvalidPathException e) {
                // ungültiger Eintrag: ignorieren
            }
        }
        return out;
    }
```

(Hinweis: `Path.of(line)` für `name=C:\x` kann unter Windows werfen – deshalb so: `String path = line; if (m.matches()) { try { if (!Path.of(line).isAbsolute()) path = m.group(2); } catch (InvalidPathException e) { path = m.group(2); } }`.)

Beide `new ProcessFilter(pattern(config, INCLUDE), pattern(config, EXCLUDE), ProcessHandle.current().pid(), launched)` → `…, launched, sharedDirectories(config))`. Imports `java.nio.file.Path`, `java.util.Set`.

- [ ] **Step 5: WindowLaunchTools:53** – Pfad mitgeben:

```java
        support.filter().rejection(new ProcessFilter.Info(0, name, commandLine, program.strip()))
```

- [ ] **Step 6:** `./gradlew :desktop:test --tests 'systems.grebe.devtools.mcp.modules.window.*'` → PASS.
- [ ] **Step 7:** Commit `feat(window): Programme aus global freigegebenen Ordnern freigeben`.

---

### Task 3: SharedProgramScan

**Files:**
- Create: `desktop/src/main/java/systems/grebe/devtools/mcp/modules/window/SharedProgramScan.java`
- Test: `desktop/src/test/java/systems/grebe/devtools/mcp/modules/window/SharedProgramScanTest.java`

**Interfaces:**
- Produces: `record SharedProgramScan.Result(List<Path> programs, boolean complete)`; `static Result scan(List<Path> roots)`; `static Result scan(List<Path> roots, int limit)`; `static final int LIMIT = 50_000`.

- [ ] **Step 1: Failing test**

```java
package systems.grebe.devtools.mcp.modules.window;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class SharedProgramScanTest {

    private static Path program(Path dir, String name) throws Exception {
        Files.createDirectories(dir);
        Path f = Files.writeString(dir.resolve(name), "x");
        f.toFile().setExecutable(true);
        return f;
    }

    private static String exe(String base) {
        return System.getProperty("os.name", "").toLowerCase().contains("win") ? base + ".exe" : base;
    }

    @Test
    void findsProgramsInSubdirectoriesAndSkipsBuildFolders(@TempDir Path root) throws Exception {
        Path deep = program(root.resolve("a").resolve("b"), exe("tool"));
        program(root.resolve("node_modules").resolve("x"), exe("hidden"));
        program(root.resolve(".git"), exe("hook"));
        Files.writeString(root.resolve("readme.txt"), "x");

        SharedProgramScan.Result r = SharedProgramScan.scan(List.of(root));

        assertThat(r.programs()).containsExactly(deep);
        assertThat(r.complete()).isTrue();
    }

    @Test
    void stopsAtTheLimit(@TempDir Path root) throws Exception {
        for (int i = 0; i < 10; i++) {
            program(root, exe("p" + i));
        }

        SharedProgramScan.Result r = SharedProgramScan.scan(List.of(root), 5);

        assertThat(r.complete()).isFalse();
        assertThat(r.programs().size()).isLessThanOrEqualTo(5);
    }

    @Test
    void ignoresMissingDirectories(@TempDir Path root) {
        assertThat(SharedProgramScan.scan(List.of(root.resolve("fehlt"))).programs()).isEmpty();
    }
}
```

(`readme.txt` ist unter Windows kein Programm; unter Unix ohne Ausführungsrecht – `Files.writeString` setzt keins.)

- [ ] **Step 2:** Test → FAIL.

- [ ] **Step 3: Implementierung**

```java
package systems.grebe.devtools.mcp.modules.window;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Sucht in den global freigegebenen Ordnern rekursiv nach Programmen ({@code .exe} unter Windows, {@code .app}-Bundles
 * unter macOS, sonst Dateien mit Ausführungsrecht) – für die Warnung beim Speichern. Build- und Werkzeugordner werden
 * übersprungen, nach {@link #LIMIT} Einträgen bricht die Suche ab.
 */
final class SharedProgramScan {

    static final int LIMIT = 50_000;
    static final Set<String> SKIPPED = Set.of(".git", "node_modules", "build", "target", ".gradle", ".idea", "out", "dist");
    private static final boolean WINDOWS = File.separatorChar == '\\';

    /** @param complete {@code false}, wenn die Obergrenze erreicht wurde */
    record Result(List<Path> programs, boolean complete) {
    }

    private SharedProgramScan() {
    }

    static Result scan(List<Path> roots) {
        return scan(roots, LIMIT);
    }

    static Result scan(List<Path> roots, int limit) {
        List<Path> found = new ArrayList<>();
        int[] seen = {0};
        boolean[] cut = {false};
        for (Path root : roots) {
            if (cut[0] || !Files.isDirectory(root)) {
                continue;
            }
            try {
                Files.walkFileTree(root, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (++seen[0] > limit) {
                            cut[0] = true;
                            return FileVisitResult.TERMINATE;
                        }
                        String name = dir.getFileName() == null ? "" : dir.getFileName().toString().toLowerCase(Locale.ROOT);
                        if (!dir.equals(root) && SKIPPED.contains(name)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        if (name.endsWith(".app")) {
                            found.add(dir); // macOS-Bundle: zählt als ein Programm
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (++seen[0] > limit) {
                            cut[0] = true;
                            return FileVisitResult.TERMINATE;
                        }
                        if (attrs.isRegularFile() && program(file)) {
                            found.add(file);
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException e) {
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                // nicht lesbarer Ordner: überspringen
            }
        }
        return new Result(List.copyOf(found), !cut[0]);
    }

    private static boolean program(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return WINDOWS ? name.endsWith(".exe") : Files.isExecutable(file);
    }
}
```

- [ ] **Step 4:** Test → PASS.
- [ ] **Step 5:** Commit `feat(window): Programme in freigegebenen Ordnern finden`.

---

### Task 4: Warnung beim Speichern

**Files:**
- Modify: `desktop/src/main/java/systems/grebe/devtools/mcp/core/ToolModule.java` (nach `sharedDirectoryFields`)
- Modify: `desktop/src/main/java/systems/grebe/devtools/mcp/core/ToolRegistry.java` (`saveWarnings`, `withSharedDirectories`-Überladung)
- Modify: `WindowModule.java` (`saveWarnings`)
- Modify: `desktop/src/main/java/systems/grebe/devtools/mcp/ui/ModuleDetailPane.java` (`save()`)
- Test: `desktop/src/test/java/systems/grebe/devtools/mcp/modules/window/WindowModuleSaveWarningsTest.java`, `McpServerIntegrationTest.java`

**Interfaces:**
- Consumes: `SharedProgramScan.scan`, `ProcessFilter.Info.ofExecutable`, `WindowModule.sharedDirectories`, `WindowModule.SHARED`
- Produces: `default List<String> ToolModule.saveWarnings(ModuleConfig config)`; `public List<String> ToolRegistry.saveWarnings(String moduleId, Map<String, String> values)`

- [ ] **Step 1: Failing test (Modul)**

```java
package systems.grebe.devtools.mcp.modules.window;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import systems.grebe.devtools.mcp.core.ModuleConfig;

import static org.assertj.core.api.Assertions.assertThat;

class WindowModuleSaveWarningsTest {

    private final WindowModule module = new WindowModule();

    private static String exe(String base) {
        return System.getProperty("os.name", "").toLowerCase().contains("win") ? base + ".exe" : base;
    }

    private List<String> warnings(Path shared, String exclude) {
        return module.saveWarnings(ModuleConfig.of(module.configSchema(),
                Map.of(WindowModule.EXCLUDE, exclude, WindowModule.SHARED, shared.toString())));
    }

    @Test
    void warnsWhenSharedDirectoriesOverrideExclusions(@TempDir Path root) throws Exception {
        Path foo = Files.writeString(root.resolve(exe("foo")), "x");
        foo.toFile().setExecutable(true);

        List<String> w = warnings(root, "foo");

        assertThat(String.join("\n", w)).contains("Prozesse ausschließen", foo.toString());
    }

    @Test
    void noWarningWithoutConflict(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve(exe("KeePass")), "x").toFile().setExecutable(true);

        assertThat(warnings(root, "")).isEmpty();
        assertThat(warnings(root, "bar")).isEmpty();
    }
}
```

Prüfen, wie `WindowModule` erzeugt wird (`grep -n "WindowModule(" WindowModule.java`); hat es Konstruktor-Abhängigkeiten, im Test passende Doubles übergeben bzw. den vorhandenen Test-Konstruktor nutzen.

- [ ] **Step 2:** Test → FAIL.

- [ ] **Step 3: ToolModule**

```java
    /**
     * Warnungen, die vor dem Speichern dieser Einstellungen bestätigt werden müssen (z.B. weil globale Freigaben einen
     * Ausschluss aufheben). {@code config} enthält die global freigegebenen Verzeichnisse wie beim Bau der Tools.
     * Darf länger dauern (läuft im Hintergrund). Standard keine.
     */
    default List<String> saveWarnings(ModuleConfig config) {
        return List.of();
    }
```

- [ ] **Step 4: WindowModule.saveWarnings**

```java
    private static final int MAX_LISTED = 20;

    @Override
    public List<String> saveWarnings(ModuleConfig config) {
        List<Path> shared = sharedDirectories(config);
        Pattern exclude;
        try {
            exclude = pattern(config, EXCLUDE);
        } catch (IllegalStateException e) {
            return List.of(); // ungültiger Ausdruck: meldet die Prüfung beim Aufruf
        }
        if (shared.isEmpty() || exclude == null) {
            return List.of();
        }
        SharedProgramScan.Result scan = SharedProgramScan.scan(shared);
        ProcessFilter plain = new ProcessFilter(null, null, -1);
        List<Path> overridden = new ArrayList<>();
        int locked = 0;
        for (Path program : scan.programs()) {
            ProcessFilter.Info info = ProcessFilter.Info.ofExecutable(program);
            if (plain.rejection(info).isPresent()) {
                locked++;
            } else if (exclude.matcher(info.name() + " " + info.commandLine()).find()) {
                overridden.add(program);
            }
        }
        if (overridden.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        out.add("Diese Programme fallen unter „Prozesse ausschließen“, liegen aber in global freigegebenen Ordnern "
                + "(Freigaben) und dürfen deshalb trotzdem gesteuert werden:");
        overridden.stream().limit(MAX_LISTED).forEach(p -> out.add("  " + p));
        if (overridden.size() > MAX_LISTED) {
            out.add("  … und " + (overridden.size() - MAX_LISTED) + " weitere");
        }
        if (locked > 0) {
            out.add(locked + " Programm(e) in den freigegebenen Ordnern bleiben gesperrt (Anmeldung, "
                    + "Berechtigungsdialog oder Passwortmanager).");
        }
        if (!scan.complete()) {
            out.add("Die Suche wurde nach " + SharedProgramScan.LIMIT + " Einträgen abgebrochen – weitere Programme "
                    + "können betroffen sein.");
        }
        return out;
    }
```

`EXCLUDE` und `SHARED` sind schon paketsichtbar (`static final`).

- [ ] **Step 5:** Modul-Test → PASS.

- [ ] **Step 6: ToolRegistry** – bestehende Methode delegieren lassen und `saveWarnings` ergänzen:

```java
    private Map<String, String> withSharedDirectories(ToolModule module, Map<String, String> values) {
        return withSharedDirectories(module, values, accessConfig().getList(AccessModule.DIRECTORIES));
    }

    /** Hängt {@code shared} an die Projektlisten des Moduls an (ohne Dubletten). */
    private static Map<String, String> withSharedDirectories(ToolModule module, Map<String, String> values,
                                                             List<String> shared) {
        if (module.sharedDirectoryFields().isEmpty() || AccessModule.ID.equals(module.id()) || shared.isEmpty()) {
            return values;
        }
        // … restlicher bisheriger Rumpf ab "Map<String, String> out = new LinkedHashMap<>(values);" unverändert
    }

    /**
     * Warnungen vor dem Speichern von {@code values} für das Modul. Beim Modul „Freigaben“ die Warnungen aller Module
     * mit Projektlisten – mit ihren gespeicherten Werten und den neuen Verzeichnissen.
     */
    public List<String> saveWarnings(String moduleId, Map<String, String> values) {
        ToolModule m = state(moduleId).module;
        if (!AccessModule.ID.equals(moduleId)) {
            return m.saveWarnings(ModuleConfig.of(m.configSchema(), withSharedDirectories(m, values)));
        }
        List<String> dirs = ModuleConfig.of(m.configSchema(), values).getList(AccessModule.DIRECTORIES);
        List<String> out = new ArrayList<>();
        for (ToolModule other : modules()) {
            if (!other.sharedDirectoryFields().isEmpty()) {
                Map<String, String> saved = effective(state(other.id())).values();
                out.addAll(other.saveWarnings(ModuleConfig.of(other.configSchema(),
                        withSharedDirectories(other, saved, dirs))));
            }
        }
        return out;
    }
```

- [ ] **Step 7: Integrationstest** in `McpServerIntegrationTest` (neben `globalSharesAndLiftedRestrictionApplyToModuleTools`):

```java
    @Test
    void savingSharesWarnsAboutOverriddenWindowExclusions(@TempDir Path tools) throws Exception {
        String name = System.getProperty("os.name", "").toLowerCase().contains("win") ? "foo.exe" : "foo";
        Files.writeString(tools.resolve(name), "x").toFile().setExecutable(true);
        registry.updateConfig("window", Map.of("excludeProcesses", "foo"));
        try {
            assertThat(registry.saveWarnings("access", Map.of("directories", tools.toString())))
                    .anyMatch(l -> l.contains("Prozesse ausschließen"));
            assertThat(registry.saveWarnings("access", Map.of())).isEmpty();
        } finally {
            registry.updateConfig("window", Map.of());
        }
    }
```

(Modul-ID der Fenstersteuerung vorher mit `grep -n "String id()" -A2 WindowModule.java` prüfen; Imports `java.nio.file.Files` ggf. ergänzen.)

- [ ] **Step 8: ModuleDetailPane.save()** – Speichern in `store(values)` auslagern, vorher Warnungen im Hintergrund holen:

```java
    private void save() {
        List<String> errors = form.validate();
        if (!errors.isEmpty()) {
            Alert a = new Alert(Alert.AlertType.WARNING, String.join("\n", errors));
            a.setHeaderText("Bitte Eingaben prüfen");
            a.initOwner(getScene().getWindow());
            a.showAndWait();
            return;
        }
        Map<String, String> values = form.values();
        save.setDisable(true);
        showStatus(null, "Prüfe Freigaben …");
        Task<List<String>> task = new Task<>() {
            @Override
            protected List<String> call() {
                return registry.saveWarnings(module.id(), values);
            }
        };
        task.setOnSucceeded(e -> {
            if (task.getValue().isEmpty() || confirm(task.getValue())) {
                store(values);
            } else {
                updateDirty();
                showStatus(null, "Nicht gespeichert.");
            }
        });
        task.setOnFailed(e -> {
            updateDirty();
            showStatus(false, String.valueOf(task.getException().getMessage()));
        });
        Thread.ofVirtual().start(task);
    }

    /** „Trotzdem speichern“ bestätigt die Warnungen. */
    private boolean confirm(List<String> warnings) {
        ButtonType anyway = new ButtonType("Trotzdem speichern", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Abbrechen", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert a = new Alert(Alert.AlertType.WARNING, String.join("\n", warnings), anyway, cancel);
        a.setHeaderText("Freigaben heben Ausschlüsse auf");
        a.initOwner(getScene().getWindow());
        a.getDialogPane().setMinWidth(560);
        return a.showAndWait().filter(anyway::equals).isPresent();
    }

    private void store(Map<String, String> values) {
        registry.updateConfig(module.id(), values);
        savedValues = registry.settings(module.id()).values();
        updateDirty();
        showStatus(true, "Gespeichert. Die Tools wurden neu registriert.");
    }
```

Imports: `javafx.scene.control.ButtonBar`, `javafx.scene.control.ButtonType`, `java.util.List`.

- [ ] **Step 9:** `./gradlew :desktop:compileJava` und `./gradlew :desktop:test --tests '*WindowModuleSaveWarningsTest' --tests '*McpServerIntegrationTest'` → PASS (Git-Tests mit QS-Hook-Fehlern sind lokal, siehe Constraints).
- [ ] **Step 10:** Commit `feat(window): Warnung, wenn Freigaben Ausschlüsse aufheben`.

---

### Task 5: WindowCandidates

**Files:**
- Create: `desktop/src/main/java/systems/grebe/devtools/mcp/modules/window/WindowCandidates.java`
- Test: `desktop/src/test/java/systems/grebe/devtools/mcp/modules/window/WindowCandidatesTest.java`

**Interfaces:**
- Consumes: `WindowSystem` (natives), `ProcessFilter`, `ProcessFilter.info(long)`
- Produces: `public final class WindowCandidates` mit `public record Candidate(NativeWindow window, String processName, String executable)` (+ `long pid()`); `public static WindowCandidates current()`; Paket-Konstruktor `WindowCandidates(WindowSystem windows, long self)`; `public Optional<String> unsupportedReason()`; `public List<Candidate> list()`; `public Optional<BufferedImage> preview(Candidate c)`.

- [ ] **Step 1: Failing test**

```java
package systems.grebe.devtools.mcp.modules.window;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;

import systems.grebe.devtools.mcp.modules.window.platform.NativeWindow;

import static org.assertj.core.api.Assertions.assertThat;

class WindowCandidatesTest {

    private final FakeDesktop desktop = new FakeDesktop();
    private final long me = ProcessHandle.current().pid();

    private NativeWindow window(boolean minimized) {
        NativeWindow w = new NativeWindow(0x10 + desktop.windows.size(), me, 1L, "Fenster",
                new Rectangle(0, 0, 400, 300), minimized);
        desktop.windows.add(w);
        return w;
    }

    @Test
    void listsWindowsWithProcessNameAndPath() {
        window(false);

        var list = new WindowCandidates(desktop, -1).list();

        assertThat(list).hasSize(1);
        assertThat(list.getFirst().pid()).isEqualTo(me);
        assertThat(list.getFirst().processName()).isNotBlank();
        assertThat(list.getFirst().executable()).isNotBlank();
    }

    @Test
    void hidesThisApp() {
        window(false);

        assertThat(new WindowCandidates(desktop, me).list()).isEmpty();
    }

    @Test
    void previewFallsBackToEmptyWithoutImageOrWhenMinimized() {
        NativeWindow open = window(false);
        NativeWindow min = window(true);
        WindowCandidates c = new WindowCandidates(desktop, -1);
        var candidates = c.list();

        assertThat(c.preview(candidates.getFirst())).isEmpty(); // FakeDesktop ohne Bild

        desktop.backgroundImage = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        assertThat(c.preview(candidates.stream().filter(x -> x.window().equals(open)).findFirst().orElseThrow()))
                .isPresent();
        assertThat(c.preview(candidates.stream().filter(x -> x.window().equals(min)).findFirst().orElseThrow()))
                .isEmpty();
    }
}
```

- [ ] **Step 2:** Test → FAIL.

- [ ] **Step 3: Implementierung**

```java
package systems.grebe.devtools.mcp.modules.window;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import systems.grebe.devtools.mcp.modules.window.platform.NativeWindow;
import systems.grebe.devtools.mcp.modules.window.platform.WindowSystem;

/**
 * Fenster für den Auswahldialog der Einstellungen: alle sichtbaren Fenster mit Prozessname und Programmpfad – ohne
 * diese App und ohne immer ausgeschlossene Prozesse (Anmeldung, Passwortmanager), die ohnehin nicht steuerbar sind.
 */
public final class WindowCandidates {

    /** Ein wählbares Fenster. */
    public record Candidate(NativeWindow window, String processName, String executable) {

        public long pid() {
            return window.pid();
        }
    }

    private final WindowSystem windows;
    private final ProcessFilter filter;

    WindowCandidates(WindowSystem windows, long self) {
        this.windows = windows;
        this.filter = new ProcessFilter(null, null, self);
    }

    public static WindowCandidates current() {
        return new WindowCandidates(WindowSystem.current(), ProcessHandle.current().pid());
    }

    public Optional<String> unsupportedReason() {
        return windows.unsupportedReason();
    }

    public List<Candidate> list() {
        List<Candidate> out = new ArrayList<>();
        for (NativeWindow w : windows.windows()) {
            if (filter.rejection(w.pid()).isPresent()) {
                continue;
            }
            ProcessFilter.info(w.pid()).ifPresent(i -> out.add(new Candidate(w, i.name(), i.command())));
        }
        return out;
    }

    /** Vorschaubild ohne das Fenster zu aktivieren; leer, wenn minimiert oder nicht erfassbar. */
    public Optional<BufferedImage> preview(Candidate c) {
        if (c.window().minimized()) {
            return Optional.empty();
        }
        try {
            return windows.captureInBackground(c.window());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
```

(`FakeDesktop` implementiert `WindowSystem` – prüfen, dass `unsupportedReason()` dort `Optional.empty()` liefert.)

- [ ] **Step 4:** Test → PASS.
- [ ] **Step 5:** Commit `feat(window): wählbare Fenster mit Vorschau für die Einstellungen`.

---

### Task 6: Feldtyp, Formular und Auswahldialog

**Files:**
- Modify: `shared/src/main/java/systems/grebe/devtools/mcp/core/FieldType.java`
- Modify: `desktop/src/main/java/systems/grebe/devtools/mcp/ui/ConfigForm.java` (Case vor `default`)
- Create: `desktop/src/main/java/systems/grebe/devtools/mcp/ui/WindowPickerDialog.java`
- Modify: `WindowModule.java` (INCLUDE/EXCLUDE als `PROCESS_PATTERN`, Hilfetexte)
- Modify: `server/src/main/java/systems/grebe/devtools/mcp/web/FieldEditor.java` nur falls der `default` nicht greift (er greift: `default -> new Text(f)`)
- Modify: `README.md`, Spec

**Interfaces:**
- Consumes: `WindowCandidates`, `WindowCandidates.Candidate`, `ProcessPatterns.append`
- Produces: `FieldType.PROCESS_PATTERN`; `static Optional<WindowCandidates.Candidate> WindowPickerDialog.choose(Window owner, WindowCandidates candidates, String title)`

- [ ] **Step 1: FieldType** (nach `STRING_LIST`):

```java
    /**
     * Regulärer Ausdruck auf Prozessname und Kommandozeile. Die Desktop-App bietet dazu eine grafische Fensterauswahl,
     * der Team-Server ein Textfeld.
     */
    PROCESS_PATTERN,
```

- [ ] **Step 2: WindowModule** – `FieldType.STRING` → `FieldType.PROCESS_PATTERN` für INCLUDE und EXCLUDE; Hilfetexte:
  - INCLUDE: „Regulärer Ausdruck auf Prozessname und Kommandozeile; „Fenster wählen…“ fügt ein Programm hinzu. Leer = alle Prozesse des Benutzers mit Fenstern. Programme aus Ordnern unter „Freigaben“ sind immer freigegeben.“
  - EXCLUDE: „Regulärer Ausdruck; „Fenster wählen…“ fügt ein Programm hinzu. Gilt nicht für Programme aus Ordnern unter „Freigaben“. Immer ausgeschlossen: diese App, Anmelde-/Berechtigungsdialoge des Systems und gängige Passwortmanager.“

- [ ] **Step 3: ConfigForm** – Case vor `default` (Imports `systems.grebe.devtools.mcp.modules.window.ProcessPatterns`, `systems.grebe.devtools.mcp.modules.window.WindowCandidates`):

```java
            case PROCESS_PATTERN -> {
                TextField tf = new TextField(value);
                HBox.setHgrow(tf, Priority.ALWAYS);
                Button pick = new Button("Fenster wählen…");
                WindowCandidates candidates = WindowCandidates.current();
                candidates.unsupportedReason().ifPresentOrElse(r -> {
                    pick.setDisable(true);
                    tf.setTooltip(new Tooltip("Fensterauswahl nicht verfügbar: " + r));
                }, () -> pick.setTooltip(new Tooltip("Programm über sein Fenster auswählen")));
                pick.setOnAction(e -> WindowPickerDialog.choose(tf.getScene().getWindow(), candidates, f.label())
                        .ifPresent(c -> tf.setText(ProcessPatterns.append(tf.getText(), c.processName()))));
                tf.textProperty().addListener(changed);
                getters.put(f.key(), tf::getText);
                yield new HBox(6, tf, pick);
            }
```

- [ ] **Step 4: WindowPickerDialog**

```java
package systems.grebe.devtools.mcp.ui;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import systems.grebe.devtools.mcp.modules.window.WindowCandidates;
import systems.grebe.devtools.mcp.modules.window.WindowCandidates.Candidate;

/**
 * Fensterauswahl wie beim Teilen in Discord oder Teams: Übersicht aller Fenster als Kacheln mit Vorschau (Platzhalter
 * mit Prozessname und PID, solange bzw. wenn es keine gibt); ein Klick zeigt die große Vorschau, „Hinzufügen“ übernimmt
 * das Fenster.
 */
final class WindowPickerDialog {

    private static final double TILE_W = 240;
    private static final double TILE_H = 150;

    private final Stage stage = new Stage();
    private final BorderPane root = new BorderPane();
    private final WindowCandidates candidates;
    private final AtomicReference<Candidate> chosen = new AtomicReference<>();
    private Node overview;

    private WindowPickerDialog(Window owner, WindowCandidates candidates, String title) {
        this.candidates = candidates;
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle("Fenster wählen – " + title);
        root.setPadding(new Insets(12));
        Scene scene = new Scene(root, 860, 600);
        if (owner != null && owner.getScene() != null) {
            scene.getStylesheets().setAll(owner.getScene().getStylesheets());
        }
        stage.setScene(scene);
    }

    /** Öffnet den Dialog; leer bei Abbrechen. */
    static Optional<Candidate> choose(Window owner, WindowCandidates candidates, String title) {
        WindowPickerDialog d = new WindowPickerDialog(owner, candidates, title);
        d.showOverview(candidates.list());
        d.stage.showAndWait();
        return Optional.ofNullable(d.chosen.get());
    }

    private void showOverview(List<Candidate> list) {
        FlowPane tiles = new FlowPane(12, 12);
        tiles.setPadding(new Insets(4));
        for (Candidate c : list) {
            StackPane image = placeholder(c, TILE_W, TILE_H);
            loadPreview(c, image, TILE_W, TILE_H);
            Label title = new Label(c.window().title().isBlank() ? "(ohne Titel)" : c.window().title());
            title.setMaxWidth(TILE_W);
            Label process = new Label(c.processName() + " · PID " + c.pid());
            process.getStyleClass().add("form-help");
            VBox tile = new VBox(4, image, title, process);
            tile.getStyleClass().add("window-tile");
            tile.setPadding(new Insets(6));
            tile.setOnMouseClicked(e -> showPreview(c));
            tile.setCursor(javafx.scene.Cursor.HAND);
            tiles.getChildren().add(tile);
        }
        ScrollPane scroll = new ScrollPane(tiles);
        scroll.setFitToWidth(true);
        Label hint = new Label(list.isEmpty() ? "Keine wählbaren Fenster gefunden."
                : "Fenster anklicken, um es in der Vorschau anzusehen.");
        Button cancel = new Button("Abbrechen");
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> stage.close());
        HBox bottom = new HBox(8, cancel);
        bottom.setAlignment(Pos.CENTER_RIGHT);
        bottom.setPadding(new Insets(10, 0, 0, 0));
        overview = new VBox(8, hint, scroll);
        VBox.setVgrow(scroll, javafx.scene.layout.Priority.ALWAYS);
        root.setCenter(overview);
        root.setBottom(bottom);
    }

    private void showPreview(Candidate c) {
        double w = 820;
        double h = 440;
        StackPane image = placeholder(c, w, h);
        loadPreview(c, image, w, h);
        Label title = new Label(c.window().title().isBlank() ? "(ohne Titel)" : c.window().title());
        title.getStyleClass().add("form-label");
        Label details = new Label(c.processName() + " · PID " + c.pid()
                + (c.executable() == null ? "" : "\n" + c.executable()));
        details.getStyleClass().add("form-help");
        details.setWrapText(true);
        Button back = new Button("Zurück");
        back.setCancelButton(true);
        back.setOnAction(e -> {
            root.setCenter(overview);
            showOverviewButtons();
        });
        Button add = new Button("Hinzufügen");
        add.getStyleClass().add("accent");
        add.setDefaultButton(true);
        add.setOnAction(e -> {
            chosen.set(c);
            stage.close();
        });
        HBox bottom = new HBox(8, back, add);
        bottom.setAlignment(Pos.CENTER_RIGHT);
        bottom.setPadding(new Insets(10, 0, 0, 0));
        root.setCenter(new VBox(8, image, title, details));
        root.setBottom(bottom);
    }

    private void showOverviewButtons() {
        Button cancel = new Button("Abbrechen");
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> stage.close());
        HBox bottom = new HBox(8, cancel);
        bottom.setAlignment(Pos.CENTER_RIGHT);
        bottom.setPadding(new Insets(10, 0, 0, 0));
        root.setBottom(bottom);
    }

    /** Platzhalter mit Prozessname und PID; das Vorschaubild ersetzt ihn, sobald es geladen ist. */
    private static StackPane placeholder(Candidate c, double w, double h) {
        Label name = new Label(c.processName() + "\nPID " + c.pid());
        name.setWrapText(true);
        name.setAlignment(Pos.CENTER);
        name.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        StackPane box = new StackPane(name);
        box.getStyleClass().add("window-placeholder");
        box.setStyle("-fx-background-color: rgba(127,127,127,0.18); -fx-background-radius: 6;");
        box.setMinSize(w, h);
        box.setPrefSize(w, h);
        box.setMaxSize(w, h);
        return box;
    }

    /** Lädt die Vorschau im Hintergrund; ohne Bild bleibt der Platzhalter. */
    private void loadPreview(Candidate c, StackPane target, double w, double h) {
        Thread.ofVirtual().start(() -> {
            Optional<BufferedImage> img = candidates.preview(c);
            img.ifPresent(i -> Platform.runLater(() -> {
                ImageView view = new ImageView(SwingFXUtils.toFXImage(i, null));
                view.setPreserveRatio(true);
                view.setFitWidth(w);
                view.setFitHeight(h);
                target.getChildren().setAll(view);
            }));
        });
    }
}
```

(Falls `javafx.embed.swing` nicht auf dem Klassenpfad ist – `desktop/build.gradle.kts` lädt nur `base`, `graphics`, `controls` –, statt `SwingFXUtils` eine eigene Umwandlung nutzen: `WritableImage fx = new WritableImage(i.getWidth(), i.getHeight()); fx.getPixelWriter().setPixels(0, 0, i.getWidth(), i.getHeight(), PixelFormat.getIntArgbInstance(), i.getRGB(0, 0, i.getWidth(), i.getHeight(), null, 0, i.getWidth()), 0, i.getWidth());` – keine neue Abhängigkeit.)

Doppelte Button-Leiste vermeiden: in `showOverview` statt eigener `bottom`-Erzeugung `showOverviewButtons()` aufrufen.

- [ ] **Step 5:** `./gradlew :shared:compileJava :server:compileJava :desktop:compileJava` → ohne Fehler.
- [ ] **Step 6:** `./gradlew :desktop:test --tests 'systems.grebe.devtools.mcp.modules.window.*'` → PASS.
- [ ] **Step 7: Manuell:** App starten (`./gradlew :desktop:bootRun`), Modul Fenstersteuerung → „Fenster wählen…“ neben beiden Feldern: Übersicht mit Kacheln, Platzhalter (Name + PID) für minimierte Fenster, Klick → große Vorschau mit Pfad, „Hinzufügen“ ergänzt das Feld (`charmap` → `charmap|winword`), „Zurück“/„Abbrechen“ ändern nichts. Unter „Freigaben“ einen Ordner mit einem ausgeschlossenen Programm eintragen → Speichern zeigt Warnung mit „Trotzdem speichern“/„Abbrechen“.
- [ ] **Step 8: Doku** – README Abschnitt Fenstersteuerung: Fensterauswahl-Button und Regel zu freigegebenen Ordnern (Überschreibt Include/Exclude, nicht die immer ausgeschlossenen; Warnung beim Speichern). Spec bei Abweichungen nachziehen.
- [ ] **Step 9:** Commit `feat(window): grafische Fensterauswahl für die Prozessfilter`.
