package systems.grebe.devtools.mcp.modules.ide;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.modules.ide.spi.Ide;
import systems.grebe.devtools.mcp.modules.ticket.spi.ProviderSettings;

/**
 * Grundlage der mitgelieferten IDEs: ein Programm auf der Befehlszeile ({@code idea}, {@code code}, {@code eclipse} …),
 * das den Auftrag an die laufende IDE weiterreicht oder sie startet. Das Programm wird beim ersten Aufruf gesucht und
 * dann behalten (die Anbindung lebt so lange wie die Konfiguration).
 */
public abstract class CliIde implements Ide {

    /** Einstellung „Programm“ (alle mitgelieferten IDEs). */
    public static final String EXECUTABLE = "executable";

    /**
     * Wie die IDE aufgerufen wird.
     *
     * @param prefix  Befehl vor den Argumenten, z.B. {@code [/usr/local/bin/code]} oder
     *                {@code [open, -na, …/Contents/MacOS/idea, --args]}
     * @param display gefundenes Programm zur Anzeige
     */
    public record Launcher(List<String> prefix, String display) {

        public Launcher {
            prefix = List.copyOf(prefix);
        }

        public static Launcher of(Path exe) {
            return new Launcher(List.of(exe.toString()), exe.toString());
        }

        public List<String> command(List<String> args) {
            List<String> cmd = new ArrayList<>(prefix);
            cmd.addAll(args);
            return cmd;
        }
    }

    protected final String name;
    protected final ProviderSettings settings;
    private Launcher launcher;

    protected CliIde(String name, ProviderSettings settings) {
        this.name = name;
        this.settings = settings;
    }

    /** Feld „Programm“ mit IDE-spezifischem Hilfetext. */
    public static ConfigField executableField(String help) {
        return ConfigField.of(EXECUTABLE, "Programm", FieldType.STRING)
                .withHelp(help + " Leer = automatisch suchen (PATH und übliche Installationsorte).");
    }

    /** Sucht das Programm; leer, wenn die IDE nicht installiert scheint. */
    protected abstract Optional<Launcher> locate();

    /** Gefundenes Programm (einmal gesucht, dann behalten). */
    protected synchronized Launcher launcher() {
        if (launcher == null) {
            launcher = locate().orElseThrow(() -> new IllegalStateException(name + " nicht gefunden – in der "
                    + "DevTools-App unter Module → IDE bei „" + name + ": Programm“ den Pfad eintragen."));
        }
        return launcher;
    }

    @Override
    public Availability probe() {
        try {
            return Availability.found(launcher().display());
        } catch (RuntimeException e) {
            return Availability.missing(e.getMessage());
        }
    }

    /** Ruft das Programm mit {@code args} auf; Rückgabe wie {@link IdePrograms#launch}. */
    protected String run(List<String> args) {
        return IdePrograms.launch(launcher().command(args), settings.timeout());
    }

    /** Ergebnis einer Aktion; vermerkt, wenn die IDE erst gestartet wird. */
    protected static String done(String message, String launchOutput) {
        return launchOutput == null ? message + " (IDE wird gestartet)" : message;
    }

    protected Optional<String> configured() {
        return settings.get(EXECUTABLE);
    }
}
