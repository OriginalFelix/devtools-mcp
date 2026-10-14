package systems.grebe.devtools.mcp.modules.ide.xcode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import systems.grebe.devtools.mcp.core.CommandRunner;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.Text;
import systems.grebe.devtools.mcp.modules.ide.CliIde;
import systems.grebe.devtools.mcp.modules.ide.IdePrograms;
import systems.grebe.devtools.mcp.modules.ide.spi.Ide;
import systems.grebe.devtools.mcp.modules.ide.spi.IdeProvider;
import systems.grebe.devtools.mcp.modules.ticket.spi.ProviderSettings;

/**
 * Xcode (macOS) über {@code xed}: Workspace, Projekt bzw. Swift-Paket eines Ordners öffnen, {@code xed --line <n>
 * <datei>} für eine Stelle. Geschlossen wird per AppleScript – Xcode fragt dabei selbst nach ungespeicherten
 * Änderungen. Vergleiche öffnet FileMerge ({@code opendiff}).
 */
public class XcodeIdeProvider implements IdeProvider {

    /**
     * Schließt alle Workspace-Dokumente unter dem Verzeichnis (Argument, mit {@code /} am Ende); Ausgabe: Anzahl.
     * Rückwärts, weil sich beim Schließen die Nummern der folgenden Dokumente verschieben.
     */
    static final String CLOSE = """
            on run argv
              set target to item 1 of argv
              if application "Xcode" is not running then return 0
              tell application "Xcode"
                set n to 0
                repeat with i from (count of workspace documents) to 1 by -1
                  set d to workspace document i
                  if ((path of d) & "/") starts with target then
                    close d saving ask
                    set n to n + 1
                  end if
                end repeat
                return n
              end tell
            end run
            """;

    @Override
    public String id() {
        return "xcode";
    }

    @Override
    public String displayName() {
        return "Xcode";
    }

    @Override
    public int priority() {
        return 20;
    }

    @Override
    public List<ConfigField> configFields() {
        return List.of(CliIde.executableField("xed (Teil von Xcode, meist /usr/bin/xed)."));
    }

    @Override
    public boolean recognizes(Path projectDir) {
        return Files.isRegularFile(projectDir.resolve("Package.swift")) || projectFiles(projectDir).findAny().isPresent();
    }

    @Override
    public Ide create(ProviderSettings settings) {
        return new XcodeIde(settings);
    }

    /** {@code .xcworkspace} vor {@code .xcodeproj}, direkt im Verzeichnis. */
    static Stream<Path> projectFiles(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(f -> f.getFileName().toString().endsWith(".xcworkspace")
                            || f.getFileName().toString().endsWith(".xcodeproj"))
                    .sorted(Comparator.comparing((Path f) -> f.getFileName().toString().endsWith(".xcodeproj"))
                            .thenComparing(Path::toString)).toList().stream();
        } catch (IOException e) {
            return Stream.empty();
        }
    }

    static final class XcodeIde extends CliIde {

        XcodeIde(ProviderSettings settings) {
            super("Xcode", settings);
        }

        @Override
        public Set<Capability> capabilities() {
            return Set.of(Capability.OPEN_PROJECT, Capability.CLOSE_PROJECT, Capability.SHOW, Capability.DIFF);
        }

        @Override
        protected Optional<Launcher> locate() {
            if (!IdePrograms.MAC) {
                throw new IllegalStateException("Xcode gibt es nur unter macOS.");
            }
            if (configured().isEmpty() && IdePrograms.glob("/Applications/Xcode*.app").isEmpty()) {
                return Optional.empty();
            }
            return IdePrograms.find(configured().orElse(null), List.of("xed"), List.of("/usr/bin/xed")).map(Launcher::of);
        }

        @Override
        public String openProject(Path dir) {
            Path target = projectFiles(dir).findFirst().orElse(dir);
            return done("Geöffnet: " + target, run(List.of(target.toString())));
        }

        @Override
        public String closeProject(Path dir) {
            launcher();
            CommandRunner.Result r = CommandRunner.run(List.of("osascript", "-e", CLOSE, dir + "/"),
                    settings.timeout().plus(Duration.ofSeconds(15)), StandardCharsets.UTF_8);
            if (r.timedOut()) {
                return "Xcode wartet auf den Nutzer (ungespeicherte Änderungen sichern oder verwerfen).";
            }
            if (!r.ok() && r.output().contains("-128")) {
                return "Der Nutzer hat das Schließen abgebrochen.";
            }
            if (!r.ok()) {
                throw new IllegalStateException("Xcode per AppleScript nicht steuerbar: " + Text.limitLines(r.output(), 10)
                        + " – evtl. muss der Nutzer der DevTools-App in den Systemeinstellungen unter Datenschutz & "
                        + "Sicherheit → Automation die Steuerung von Xcode erlauben.");
            }
            String n = r.output().strip();
            return "0".equals(n) ? "In Xcode nicht geöffnet: " + dir : "Geschlossen (" + n + " Fenster): " + dir;
        }

        /** {@code xed} kennt eine Zeile, aber keine Spalte. */
        @Override
        public String show(Location l) {
            List<String> args = l.line() > 0
                    ? List.of("--line", String.valueOf(l.line()), l.file().toString())
                    : List.of(l.file().toString());
            return done("Gezeigt: " + l.file() + (l.line() > 0 ? ":" + l.line() : ""), run(args));
        }

        @Override
        public String diff(Path left, Path right) {
            String out = IdePrograms.launch(List.of("opendiff", left.toString(), right.toString()), settings.timeout());
            return done("Vergleich in FileMerge geöffnet: " + left + " ↔ " + right, out);
        }
    }
}
