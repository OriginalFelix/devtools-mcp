package systems.grebe.devtools.mcp.modules.ide.eclipse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.modules.ide.CliIde;
import systems.grebe.devtools.mcp.modules.ide.IdePrograms;
import systems.grebe.devtools.mcp.modules.ide.spi.Ide;
import systems.grebe.devtools.mcp.modules.ide.spi.IdeProvider;
import systems.grebe.devtools.mcp.modules.ticket.spi.ProviderSettings;

/**
 * Eclipse über den Starter: {@code eclipse --launcher.openFile <pfad>} reicht Dateien an die laufende Instanz weiter –
 * eine Datei mit {@code :zeile:spalte} wird an der Stelle geöffnet, ein Ordner per Smart Import als Projekt
 * übernommen. Unter macOS geht das über {@code open -a Eclipse.app <pfad>} (ohne Zeile). Projekte schließen lässt sich
 * Eclipse von außen nicht.
 */
public class EclipseIdeProvider implements IdeProvider {

    static final String WORKSPACE = "workspace";

    @Override
    public String id() {
        return "eclipse";
    }

    @Override
    public String displayName() {
        return "Eclipse";
    }

    @Override
    public int priority() {
        return 20;
    }

    @Override
    public List<ConfigField> configFields() {
        return List.of(
                CliIde.executableField("eclipse(.exe) aus der Installation oder unter macOS die App (Eclipse.app)."),
                ConfigField.of(WORKSPACE, "Workspace", FieldType.DIRECTORY)
                        .withHelp("Optional: Workspace, mit dem Eclipse gestartet wird, falls es noch nicht läuft (-data)."));
    }

    @Override
    public boolean recognizes(Path projectDir) {
        return Files.isRegularFile(projectDir.resolve(".project"));
    }

    @Override
    public Ide create(ProviderSettings settings) {
        return new EclipseIde(settings);
    }

    static List<String> candidates() {
        List<String> out = new ArrayList<>();
        if (IdePrograms.WINDOWS) {
            out.add(IdePrograms.home("eclipse/*/eclipse/eclipse.exe"));
            IdePrograms.env("ProgramFiles", "eclipse/eclipse.exe").ifPresent(out::add);
            out.add("C:/eclipse/eclipse.exe");
        } else if (IdePrograms.MAC) {
            out.add("/Applications/Eclipse*.app");
            out.add(IdePrograms.home("Applications/Eclipse*.app"));
            out.add(IdePrograms.home("eclipse/*/Eclipse.app"));
        } else {
            out.add(IdePrograms.home("eclipse/*/eclipse/eclipse"));
            out.add("/opt/eclipse/eclipse");
            out.add("/usr/local/eclipse/eclipse");
        }
        return out;
    }

    static final class EclipseIde extends CliIde {

        EclipseIde(ProviderSettings settings) {
            super("Eclipse", settings);
        }

        @Override
        protected Optional<Launcher> locate() {
            return IdePrograms.find(configured().orElse(null), List.of("eclipse"), candidates()).map(EclipseIde::launcher);
        }

        /** Unter macOS wird eine App (auch über ihr Programm angegeben) per {@code open -a} angesprochen. */
        static Launcher launcher(Path exe) {
            String s = exe.toString();
            int app = s.indexOf(".app");
            if (IdePrograms.MAC && app > 0 && (s.length() == app + 4 || s.charAt(app + 4) == '/')) {
                String bundle = s.substring(0, app + 4);
                return new Launcher(List.of("open", "-a", bundle), bundle);
            }
            return Launcher.of(exe);
        }

        private boolean viaOpen() {
            return launcher().prefix().getFirst().equals("open");
        }

        @Override
        public String openProject(Path dir) {
            return done("An Eclipse übergeben (Smart Import): " + dir, run(args(dir.toString())));
        }

        @Override
        public String show(Location l) {
            if (viaOpen()) {
                String out = run(args(l.file().toString()));
                return done("Datei geöffnet: " + l.file() + (l.line() > 0 ? " – Zeile " + l.line()
                        + " unter macOS nicht übergebbar, bitte selbst anspringen" : ""), out);
            }
            return done("Gezeigt: " + l.spec(), run(args(l.spec())));
        }

        /** Pfad samt Workspace in der passenden Form für {@code open -a} bzw. den Starter. */
        List<String> args(String path) {
            Optional<String> workspace = settings.get(WORKSPACE);
            List<String> args = new ArrayList<>();
            if (viaOpen()) {
                args.add(path);
                workspace.ifPresent(ws -> args.addAll(List.of("--args", "-data", ws)));
            } else {
                workspace.ifPresent(ws -> args.addAll(List.of("-data", ws)));
                args.addAll(List.of("--launcher.openFile", path));
            }
            return args;
        }
    }
}
