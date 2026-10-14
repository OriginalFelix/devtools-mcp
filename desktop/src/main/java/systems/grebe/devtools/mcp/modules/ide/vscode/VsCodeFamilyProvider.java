package systems.grebe.devtools.mcp.modules.ide.vscode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.modules.ide.CliIde;
import systems.grebe.devtools.mcp.modules.ide.IdePrograms;
import systems.grebe.devtools.mcp.modules.ide.spi.Ide;
import systems.grebe.devtools.mcp.modules.ide.spi.IdeProvider;
import systems.grebe.devtools.mcp.modules.ticket.spi.ProviderSettings;

/**
 * Visual Studio Code und Abkömmlinge (VSCodium, Cursor) über ihre Befehlszeile: {@code code <ordner>},
 * {@code code <ordner> -g <datei>:<zeile>:<spalte>} (die Datei im Fenster des Projekts) und {@code code -d <links>
 * <rechts>}. Gesucht wird der Befehl im {@code PATH}, sonst in der üblichen Installation (macOS:
 * {@code <App>/Contents/Resources/app/bin/<befehl>}). Ordner schließen lässt sich VS Code von außen nicht.
 */
public abstract class VsCodeFamilyProvider implements IdeProvider {

    /**
     * Eine IDE der Familie.
     *
     * @param command    Befehl, z.B. {@code code}
     * @param macApp     Name der macOS-App, z.B. {@code Visual Studio Code.app}
     * @param windowsDir Installationsverzeichnis unter {@code %LOCALAPPDATA%\Programs} bzw. {@code Program Files}
     * @param linuxDirs  Installationsverzeichnisse unter Linux (mit {@code bin/<befehl>})
     */
    protected record Product(String id, String name, int priority, String command, String macApp, String windowsDir,
                             List<String> linuxDirs) {
    }

    private final Product product;

    protected VsCodeFamilyProvider(Product product) {
        this.product = product;
    }

    @Override
    public String id() {
        return product.id();
    }

    @Override
    public String displayName() {
        return product.name();
    }

    @Override
    public int priority() {
        return product.priority();
    }

    @Override
    public List<ConfigField> configFields() {
        return List.of(CliIde.executableField("Befehl " + product.command() + " (unter Windows " + product.command()
                + ".cmd), z.B. " + (IdePrograms.MAC ? "/Applications/" + product.macApp()
                + "/Contents/Resources/app/bin/" + product.command() : "aus dem bin-Ordner der Installation") + "."));
    }

    @Override
    public boolean recognizes(Path projectDir) {
        return Files.isDirectory(projectDir.resolve(".vscode"));
    }

    @Override
    public Ide create(ProviderSettings settings) {
        return new VsCodeIde(product, settings);
    }

    static List<String> candidates(Product p) {
        List<String> out = new ArrayList<>();
        if (IdePrograms.WINDOWS) {
            IdePrograms.env("LOCALAPPDATA", "Programs/" + p.windowsDir() + "/bin/" + p.command() + ".cmd")
                    .ifPresent(out::add);
            IdePrograms.env("LOCALAPPDATA", "Programs/" + p.windowsDir() + "/resources/app/bin/" + p.command()
                    + ".cmd").ifPresent(out::add);
            IdePrograms.env("ProgramFiles", p.windowsDir() + "/bin/" + p.command() + ".cmd").ifPresent(out::add);
        } else if (IdePrograms.MAC) {
            out.add("/Applications/" + p.macApp() + "/Contents/Resources/app/bin/" + p.command());
            out.add(IdePrograms.home("Applications/" + p.macApp() + "/Contents/Resources/app/bin/" + p.command()));
        } else {
            for (String dir : p.linuxDirs()) {
                out.add(dir + "/bin/" + p.command());
            }
        }
        return out;
    }

    /** Eine eingetragene macOS-App wird zu ihrem Befehl. */
    static CliIde.Launcher launcher(Path exe, String command) {
        if (exe.toString().endsWith(".app") && Files.isDirectory(exe)) {
            exe = exe.resolve("Contents/Resources/app/bin").resolve(command);
        }
        return CliIde.Launcher.of(exe);
    }

    static final class VsCodeIde extends CliIde {

        private final Product product;

        VsCodeIde(Product product, ProviderSettings settings) {
            super(product.name(), settings);
            this.product = product;
        }

        @Override
        public Set<Capability> capabilities() {
            return Set.of(Capability.OPEN_PROJECT, Capability.SHOW, Capability.DIFF);
        }

        @Override
        protected Optional<Launcher> locate() {
            return IdePrograms.find(configured().orElse(null), List.of(product.command()), candidates(product))
                    .map(exe -> VsCodeFamilyProvider.launcher(exe, product.command()));
        }

        @Override
        public String openProject(Path dir) {
            return done("Ordner geöffnet: " + dir, run(List.of(dir.toString())));
        }

        @Override
        public String show(Location l) {
            return done("Gezeigt: " + l.spec(), run(showArgs(l)));
        }

        /** Mit Projekt öffnet VS Code die Datei in dessen Fenster (bzw. öffnet es dafür). */
        static List<String> showArgs(Location l) {
            List<String> args = new ArrayList<>();
            if (l.project() != null) {
                args.add(l.project().toString());
            }
            args.addAll(List.of("-g", l.spec()));
            return args;
        }

        @Override
        public String diff(Path left, Path right) {
            return done("Vergleich geöffnet: " + left + " ↔ " + right,
                    run(List.of("-d", left.toString(), right.toString())));
        }
    }
}
