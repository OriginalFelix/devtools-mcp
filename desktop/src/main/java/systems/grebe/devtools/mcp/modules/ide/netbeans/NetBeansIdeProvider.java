package systems.grebe.devtools.mcp.modules.ide.netbeans;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.modules.ide.CliIde;
import systems.grebe.devtools.mcp.modules.ide.IdePrograms;
import systems.grebe.devtools.mcp.modules.ide.spi.Ide;
import systems.grebe.devtools.mcp.modules.ide.spi.IdeProvider;
import systems.grebe.devtools.mcp.modules.ticket.spi.ProviderSettings;

/**
 * Apache NetBeans über den Starter: {@code netbeans --open <projektordner>} öffnet ein Projekt (Maven, Gradle, Ant),
 * {@code netbeans --open <datei>:<zeile>} eine Stelle – jeweils in der laufenden Instanz. Projekte schließen lässt sich
 * NetBeans von außen nicht.
 */
public class NetBeansIdeProvider implements IdeProvider {

    @Override
    public String id() {
        return "netbeans";
    }

    @Override
    public String displayName() {
        return "NetBeans";
    }

    @Override
    public int priority() {
        return 20;
    }

    @Override
    public List<ConfigField> configFields() {
        return List.of(CliIde.executableField("bin/netbeans bzw. bin/netbeans64.exe aus der Installation."));
    }

    @Override
    public boolean recognizes(Path projectDir) {
        return Files.isDirectory(projectDir.resolve("nbproject"));
    }

    @Override
    public Ide create(ProviderSettings settings) {
        return new NetBeansIde(settings);
    }

    static List<String> candidates() {
        List<String> out = new ArrayList<>();
        if (IdePrograms.WINDOWS) {
            IdePrograms.env("ProgramFiles", "NetBeans*/netbeans/bin/netbeans64.exe").ifPresent(out::add);
            IdePrograms.env("ProgramFiles", "*NetBeans*/bin/netbeans64.exe").ifPresent(out::add);
        } else if (IdePrograms.MAC) {
            out.add("/Applications/*NetBeans*.app/Contents/Resources/*/bin/netbeans");
            out.add("/Applications/*NetBeans*.app/Contents/Resources/*/netbeans/bin/netbeans");
        } else {
            out.add("/usr/lib/apache-netbeans/bin/netbeans");
            out.add("/opt/*netbeans*/bin/netbeans");
            out.add("/usr/local/*netbeans*/bin/netbeans");
            out.add(IdePrograms.home("*netbeans*/bin/netbeans"));
        }
        return out;
    }

    static final class NetBeansIde extends CliIde {

        NetBeansIde(ProviderSettings settings) {
            super("NetBeans", settings);
        }

        @Override
        protected Optional<Launcher> locate() {
            return IdePrograms.find(configured().orElse(null), List.of("netbeans"), candidates()).map(Launcher::of);
        }

        @Override
        public String openProject(Path dir) {
            return done("Projekt geöffnet: " + dir, run(List.of("--open", dir.toString())));
        }

        /** NetBeans versteht {@code datei:zeile}, aber keine Spalte. */
        @Override
        public String show(Location l) {
            String spec = l.file() + (l.line() > 0 ? ":" + l.line() : "");
            return done("Gezeigt: " + spec, run(List.of("--open", spec)));
        }
    }
}
