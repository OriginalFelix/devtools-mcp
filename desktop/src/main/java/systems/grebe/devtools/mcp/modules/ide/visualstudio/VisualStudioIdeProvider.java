package systems.grebe.devtools.mcp.modules.ide.visualstudio;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import systems.grebe.devtools.mcp.core.CommandRunner;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.core.Text;
import systems.grebe.devtools.mcp.modules.ide.CliIde;
import systems.grebe.devtools.mcp.modules.ide.IdePrograms;
import systems.grebe.devtools.mcp.modules.ide.spi.Ide;
import systems.grebe.devtools.mcp.modules.ide.spi.IdeProvider;
import systems.grebe.devtools.mcp.modules.ticket.spi.ProviderSettings;

/**
 * Visual Studio (Windows). Laufende Instanzen steuert der Provider über die Automatisierung (DTE, gefunden in der
 * Running Object Table) per Windows PowerShell: Ist die Projektmappe schon offen, wird ihr Fenster aktiv, eine leere
 * Instanz öffnet sie, sonst startet {@code devenv.exe <mappe>} eine neue. Schließen geht nur ohne ungespeicherte
 * Änderungen; Code-Stellen öffnet die Instanz des Projekts (sonst {@code devenv /Edit}), Vergleiche
 * {@code devenv /Diff}. Pfade gehen über Umgebungsvariablen an das Skript, nie in den Skripttext.
 */
public class VisualStudioIdeProvider implements IdeProvider {

    @Override
    public String id() {
        return "visualstudio";
    }

    @Override
    public String displayName() {
        return "Visual Studio";
    }

    @Override
    public int priority() {
        return 20;
    }

    @Override
    public List<ConfigField> configFields() {
        return List.of(CliIde.executableField("devenv.exe (Common7\\IDE\\devenv.exe der Installation); sonst über "
                + "vswhere die neueste Installation."));
    }

    @Override
    public boolean recognizes(Path projectDir) {
        return solutions(projectDir).findAny().isPresent();
    }

    @Override
    public Ide create(ProviderSettings settings) {
        return new VisualStudioIde(settings);
    }

    /** Projektmappen ({@code .sln}, {@code .slnx}) direkt im Verzeichnis. */
    static Stream<Path> solutions(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(f -> {
                String n = f.getFileName().toString().toLowerCase(Locale.ROOT);
                return n.endsWith(".sln") || n.endsWith(".slnx");
            }).sorted(Comparator.comparing(Path::toString)).toList().stream();
        } catch (IOException e) {
            return Stream.empty();
        }
    }

    /** Projektmappe eines Verzeichnisses: die gleichnamige, sonst die erste; ohne Mappe der Ordner selbst. */
    static Path target(Path dir) {
        List<Path> sln = solutions(dir).toList();
        String name = dir.getFileName() == null ? "" : dir.getFileName().toString().toLowerCase(Locale.ROOT);
        return sln.stream().filter(s -> s.getFileName().toString().toLowerCase(Locale.ROOT).startsWith(name + "."))
                .findFirst().orElse(sln.isEmpty() ? dir : sln.getFirst());
    }

    /**
     * Gemeinsamer Anfang der Skripte: alle laufenden Instanzen aus der Running Object Table und die, deren Projektmappe
     * im Verzeichnis {@code DT_DIR} liegt (oder umgekehrt).
     */
    static final String PRELUDE = """
            $ErrorActionPreference = 'Stop'
            Add-Type -TypeDefinition @'
            using System; using System.Collections.Generic; using System.Runtime.InteropServices;
            using System.Runtime.InteropServices.ComTypes;
            public static class DevToolsRot {
              [DllImport("ole32.dll")] static extern int GetRunningObjectTable(int r, out IRunningObjectTable rot);
              [DllImport("ole32.dll")] static extern int CreateBindCtx(int r, out IBindCtx ctx);
              public static List<object> Dtes() {
                var list = new List<object>(); IRunningObjectTable rot; IEnumMoniker en; IBindCtx ctx;
                GetRunningObjectTable(0, out rot); rot.EnumRunning(out en); CreateBindCtx(0, out ctx);
                var m = new IMoniker[1];
                while (en.Next(1, m, IntPtr.Zero) == 0) {
                  string name; m[0].GetDisplayName(ctx, null, out name);
                  if (name.StartsWith("!VisualStudio.DTE")) { object o; rot.GetObject(m[0], out o); list.Add(o); }
                }
                return list;
              }
            }
            '@
            function Get-SolutionDir($d) {
              $fn = $d.Solution.FullName
              if (-not $fn) { return $null }
              if (Test-Path -LiteralPath $fn -PathType Container) { return $fn.TrimEnd('\\') }
              return (Split-Path -LiteralPath $fn -Parent).TrimEnd('\\')
            }
            function Test-Related($a, $b) {
              return $a -and $b -and ($a -eq $b -or $a.StartsWith($b + '\\', 'OrdinalIgnoreCase') `
                -or $b.StartsWith($a + '\\', 'OrdinalIgnoreCase'))
            }
            $target = $env:DT_DIR.TrimEnd('\\')
            $all = @([DevToolsRot]::Dtes())
            $dte = $all | Where-Object { Test-Related (Get-SolutionDir $_) $target } | Select-Object -First 1
            """;

    /** Exit 3: keine passende Instanz – dann startet der Aufrufer {@code devenv.exe}. */
    static final String OPEN = PRELUDE + """
            if ($dte) { $dte.MainWindow.Activate(); 'Bereits geöffnet – Fenster aktiviert'; exit 0 }
            $idle = $all | Where-Object { -not $_.Solution.IsOpen } | Select-Object -First 1
            if ($idle -and $env:DT_PATH -match '\\.slnx?$') {
              $idle.Solution.Open($env:DT_PATH); $idle.MainWindow.Activate(); 'In laufender Instanz geöffnet'; exit 0
            }
            exit 3
            """;

    /** Exit 4: nicht geöffnet, Exit 5: ungespeicherte Änderungen. */
    static final String CLOSE = PRELUDE + """
            if (-not $dte) { 'In keiner laufenden Instanz geöffnet'; exit 4 }
            $dirty = @($dte.Documents | Where-Object { -not $_.Saved } | ForEach-Object { $_.FullName })
            if (-not $dte.Solution.Saved) { $dirty += $dte.Solution.FullName }
            if ($dirty.Count -gt 0) { 'Ungespeicherte Änderungen: ' + ($dirty -join ', '); exit 5 }
            $name = $dte.Solution.FullName
            $dte.Solution.Close($false)
            'Geschlossen: ' + $name
            """;

    /** Exit 3: keine laufende Instanz. */
    static final String SHOW = PRELUDE + """
            if (-not $dte) { $dte = $all | Select-Object -First 1 }
            if (-not $dte) { exit 3 }
            $null = $dte.ItemOperations.OpenFile($env:DT_FILE)
            $line = [int]$env:DT_LINE
            if ($line -gt 0) { $dte.ActiveDocument.Selection.MoveToLineAndOffset($line, [Math]::Max(1, [int]$env:DT_COL)) }
            $dte.MainWindow.Activate()
            'Gezeigt'
            """;

    static final class VisualStudioIde extends CliIde {

        VisualStudioIde(ProviderSettings settings) {
            super("Visual Studio", settings);
        }

        @Override
        public Set<Capability> capabilities() {
            return Set.of(Capability.OPEN_PROJECT, Capability.CLOSE_PROJECT, Capability.SHOW, Capability.DIFF);
        }

        @Override
        protected Optional<Launcher> locate() {
            if (!IdePrograms.WINDOWS) {
                throw new IllegalStateException("Visual Studio gibt es nur unter Windows.");
            }
            Optional<Path> configured = IdePrograms.find(configured().orElse(null), List.of(), List.of());
            if (configured.isPresent()) {
                return configured.map(Launcher::of);
            }
            Optional<Path> vswhere = IdePrograms.env("ProgramFiles(x86)",
                    "Microsoft Visual Studio/Installer/vswhere.exe").map(Path::of).filter(Files::isRegularFile);
            if (vswhere.isPresent()) {
                CommandRunner.Result r = CommandRunner.run(List.of(vswhere.get().toString(), "-latest", "-prerelease",
                        "-products", "*", "-property", "productPath"), Duration.ofSeconds(20));
                Optional<Path> devenv = r.output().lines().map(String::strip).filter(l -> !l.isEmpty()).findFirst()
                        .map(Path::of).filter(Files::isRegularFile);
                if (r.ok() && devenv.isPresent()) {
                    return devenv.map(Launcher::of);
                }
            }
            return IdePrograms.env("ProgramFiles", "Microsoft Visual Studio/*/*/Common7/IDE/devenv.exe")
                    .flatMap(p -> IdePrograms.glob(p).stream().reduce((a, b) -> b)).map(Launcher::of);
        }

        @Override
        public String openProject(Path dir) {
            Path target = target(dir);
            Optional<String> viaDte = script(OPEN, Map.of("DT_DIR", dir.toString(), "DT_PATH", target.toString()));
            if (viaDte.isPresent()) {
                return viaDte.get() + ": " + target;
            }
            return done((target.equals(dir) ? "Ordner" : "Projektmappe") + " geöffnet: " + target,
                    run(List.of(target.toString())));
        }

        @Override
        public String closeProject(Path dir) {
            launcher(); // nur mit Installation
            return script(CLOSE, Map.of("DT_DIR", dir.toString()))
                    .orElse("In keiner laufenden Instanz geöffnet: " + dir);
        }

        @Override
        public String show(Location l) {
            Path dir = l.project() != null ? l.project() : l.file().getParent();
            Optional<String> viaDte = script(SHOW, Map.of("DT_DIR", dir.toString(), "DT_FILE", l.file().toString(),
                    "DT_LINE", String.valueOf(l.line()), "DT_COL", String.valueOf(l.column())));
            if (viaDte.isPresent()) {
                return viaDte.get() + ": " + l.spec();
            }
            List<String> args = new ArrayList<>(List.of("/Edit", l.file().toString()));
            if (l.line() > 0) {
                args.addAll(List.of("/Command", "Edit.GoTo " + l.line()));
            }
            return done("Gezeigt: " + l.spec(), run(args));
        }

        @Override
        public String diff(Path left, Path right) {
            return done("Vergleich geöffnet: " + left + " ↔ " + right,
                    run(List.of("/Diff", left.toString(), right.toString())));
        }

        /**
         * Windows PowerShell (5.1, auf jedem Windows vorhanden) mit dem Skript als {@code -EncodedCommand}:
         * Anführungszeichen im Skript überstehen so die Befehlszeile von Windows.
         */
        static List<String> command(String script) {
            String encoded = Base64.getEncoder().encodeToString(
                    ("[Console]::OutputEncoding = [Text.Encoding]::UTF8\n" + script).getBytes(StandardCharsets.UTF_16LE));
            return List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                    "-EncodedCommand", encoded);
        }

        /**
         * Führt ein DTE-Skript aus. Leer bei Exit 3 (keine passende Instanz); Exit 4/5 sind Antworten (nicht offen,
         * ungespeichert) und gehen als Fehler an das LLM bzw. als Meldung zurück.
         */
        private Optional<String> script(String script, Map<String, String> env) {
            if (!IdePrograms.WINDOWS) {
                throw new IllegalStateException("Visual Studio gibt es nur unter Windows.");
            }
            CommandRunner.Result r = CommandRunner.run(command(script), settings.timeout().plusSeconds(15),
                    StandardCharsets.UTF_8, null, env);
            String out = r.output().strip();
            if (r.timedOut()) {
                throw new IllegalStateException("Visual Studio antwortet nicht (Zeitüberschreitung) – wartet evtl. ein "
                        + "Dialog auf den Nutzer.");
            }
            return switch (r.exitCode()) {
                case 0 -> Optional.of(out.isEmpty() ? "Erledigt" : Text.limitLines(out, 10));
                case 3 -> Optional.empty();
                case 4 -> Optional.of(out);
                case 5 -> throw new IllegalStateException(out + " – nicht geschlossen; der Nutzer muss zuerst speichern "
                        + "oder verwerfen.");
                default -> throw new IllegalStateException("Visual-Studio-Automatisierung fehlgeschlagen (Exit-Code "
                        + r.exitCode() + "): " + Text.limitLines(out, 20));
            };
        }
    }
}
