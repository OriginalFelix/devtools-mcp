package systems.grebe.devtools.mcp.modules.ide.jetbrains;

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
 * IDEs von JetBrains (IntelliJ IDEA, PyCharm, Rider …) über ihren Befehlszeilen-Starter: Er reicht Projekt bzw. Datei
 * an die laufende IDE weiter ({@code idea <ordner>}, {@code idea --line 42 --column 5 <datei>},
 * {@code idea diff <links> <rechts>}). Gesucht werden die Skripte der Toolbox-App, der Starter im {@code PATH} und die
 * üblichen Installationen; unter macOS wird die App wie im Toolbox-Skript über {@code open -na … --args} aufgerufen.
 * Projekte schließen lässt sich eine JetBrains-IDE von außen nicht.
 */
public abstract class JetBrainsIdeProvider implements IdeProvider {

    /**
     * Ein JetBrains-Produkt.
     *
     * @param command    Name des Starters im {@code PATH} bzw. des Toolbox-Skripts, z.B. {@code idea}
     * @param binary     Name des Programms in der Installation, z.B. {@code idea} ({@code bin/idea64.exe},
     *                   {@code bin/idea.sh}, {@code Contents/MacOS/idea})
     * @param macApp     Muster der macOS-App, z.B. {@code IntelliJ IDEA*.app}
     * @param windowsDirs Muster der Installationsverzeichnisse unter Windows (absolut)
     */
    protected record Product(String id, String name, int priority, String command, String binary, String macApp,
                             List<String> windowsDirs) {
    }

    private final Product product;

    protected JetBrainsIdeProvider(Product product) {
        this.product = product;
    }

    Product product() {
        return product;
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
        return List.of(CliIde.executableField("Starter von " + product.name() + ", z.B. das Toolbox-Skript "
                + product.command() + ", bin/" + product.binary() + "64.exe bzw. bin/" + product.binary()
                + ".sh oder unter macOS die App."));
    }

    @Override
    public boolean recognizes(Path projectDir) {
        return Files.isDirectory(projectDir.resolve(".idea"));
    }

    @Override
    public Ide create(ProviderSettings settings) {
        return new JetBrainsIde(product, settings);
    }

    /** Übliche Orte des Starters auf diesem Betriebssystem. */
    static List<String> candidates(Product p) {
        List<String> out = new ArrayList<>();
        if (IdePrograms.WINDOWS) {
            IdePrograms.env("LOCALAPPDATA", "JetBrains/Toolbox/scripts/" + p.command() + ".cmd").ifPresent(out::add);
            for (String dir : p.windowsDirs()) {
                out.add(dir + "/bin/" + p.binary() + "64.exe");
            }
            IdePrograms.env("LOCALAPPDATA", "Programs/" + p.macApp().replace(".app", "") + "/bin/" + p.binary()
                    + "64.exe").ifPresent(out::add);
        } else if (IdePrograms.MAC) {
            out.add(IdePrograms.home("Library/Application Support/JetBrains/Toolbox/scripts/" + p.command()));
            out.add("/Applications/" + p.macApp() + "/Contents/MacOS/" + p.binary());
            out.add(IdePrograms.home("Applications/" + p.macApp() + "/Contents/MacOS/" + p.binary()));
        } else {
            out.add(IdePrograms.home(".local/share/JetBrains/Toolbox/scripts/" + p.command()));
            out.add(IdePrograms.home(".local/share/JetBrains/Toolbox/apps/*/bin/" + p.binary() + ".sh"));
            out.add("/opt/*/bin/" + p.binary() + ".sh");
            out.add("/usr/local/*/bin/" + p.binary() + ".sh");
        }
        return out;
    }

    /**
     * Aufruf eines gefundenen Programms. Unter macOS startet {@code open -na} das Programm der App als eigenen Prozess,
     * der den Auftrag an die laufende IDE weiterreicht – wie das Skript der Toolbox-App.
     */
    static CliIde.Launcher launcher(Path exe, String binary) {
        if (IdePrograms.MAC && exe.toString().endsWith(".app") && Files.isDirectory(exe)) {
            exe = exe.resolve("Contents/MacOS").resolve(binary);
        }
        if (IdePrograms.MAC && exe.toString().contains(".app/Contents/MacOS/")) {
            return new CliIde.Launcher(List.of("open", "-na", exe.toString(), "--args"), exe.toString());
        }
        return CliIde.Launcher.of(exe);
    }

    static final class JetBrainsIde extends CliIde {

        private final Product product;

        JetBrainsIde(Product product, ProviderSettings settings) {
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
                    .map(exe -> JetBrainsIdeProvider.launcher(exe, product.binary()));
        }

        @Override
        public String openProject(Path dir) {
            return done("Projekt geöffnet: " + dir, run(List.of(dir.toString())));
        }

        @Override
        public String show(Location l) {
            return done("Gezeigt: " + l.spec(), run(showArgs(l)));
        }

        static List<String> showArgs(Location l) {
            List<String> args = new ArrayList<>();
            if (l.line() > 0) {
                args.addAll(List.of("--line", String.valueOf(l.line())));
                if (l.column() > 0) {
                    args.addAll(List.of("--column", String.valueOf(l.column())));
                }
            }
            args.add(l.file().toString());
            return args;
        }

        @Override
        public String diff(Path left, Path right) {
            return done("Vergleich geöffnet: " + left + " ↔ " + right,
                    run(List.of("diff", left.toString(), right.toString())));
        }
    }
}
