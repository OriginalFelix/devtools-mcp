package systems.grebe.devtools.mcp.modules.ide;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.ConfigGroup;
import systems.grebe.devtools.mcp.core.ConnectionTestResult;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolBeans;
import systems.grebe.devtools.mcp.core.ToolModule;
import systems.grebe.devtools.mcp.modules.ide.spi.Ide;
import systems.grebe.devtools.mcp.modules.ide.spi.IdeProvider;
import systems.grebe.devtools.mcp.modules.ticket.spi.ProviderSettings;
import systems.grebe.devtools.mcp.project.spi.ProjectProvider;

/**
 * Entwicklungsumgebungen über austauschbare Provider ({@link IdeProvider}, per ServiceLoader): Projekte öffnen und
 * schließen, dem Nutzer Code-Stellen zeigen, Dateien vergleichen. Mitgeliefert: IntelliJ IDEA und weitere
 * JetBrains-IDEs, VS Code (auch VSCodium, Cursor), Eclipse, NetBeans, Visual Studio und Xcode.
 */
@Component
public class IdeModule implements ToolModule {

    public static final String ID = "ide";

    static final String DEFAULT_IDE = "defaultIde";
    static final String TIMEOUT = "timeoutSeconds";

    private final IdeProviders providers;
    private final Supplier<ProjectProvider> projects;
    private volatile Map<String, String> installed;

    @Autowired
    public IdeModule(IdeProviders providers, ObjectProvider<ProjectProvider> projects) {
        this(providers, (Supplier<ProjectProvider>) projects::getObject);
    }

    /** Für Tests: Projekte direkt. */
    IdeModule(IdeProviders providers, Supplier<ProjectProvider> projects) {
        this.providers = providers;
        this.projects = projects;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "IDE";
    }

    @Override
    public String description() {
        return "IntelliJ IDEA (und weitere JetBrains-IDEs), VS Code, Eclipse, NetBeans, Visual Studio, Xcode und weitere "
                + "IDEs (erweiterbar per ServiceLoader): Projekte in der IDE des Nutzers öffnen und schließen, ihm "
                + "Code-Stellen zeigen, Dateien vergleichen – nur in freigegebenen Projekten.";
    }

    @Override
    public String instructions() {
        return """
                Die ide_*-Tools wirken auf dem Bildschirm des Nutzers – statt `idea`, `code`, `open -a`, `devenv` oder \
                `xed` in der Shell.
                - `ide_show` (Datei, Zeile, Spalte): dem Nutzer eine Stelle zeigen, über die gerade gesprochen wird – \
                Ursache eines Fehlers, eine geänderte Stelle, eine Fundstelle. Zum eigenen Lesen von Code nicht nötig.
                - `ide_open_project`/`ide_close_project`: Projekt öffnen bzw. schließen, wenn der Nutzer es möchte; \
                `ide_diff` zeigt zwei Dateien im Vergleich.
                - Welche IDEs aktiv sind und was sie können, sagt `ide_providers`. Kann eine IDE etwas nicht von außen \
                (z.B. Projekte schließen in IntelliJ oder VS Code), den Nutzer bitten, es selbst zu tun.""";
    }

    @Override
    public String briefInstructions() {
        return "Dem Nutzer Code-Stellen in seiner IDE zeigen (ide_show) und Projekte öffnen/schließen über ide_* statt "
                + "`idea`/`code`/`open -a` in der Shell.";
    }

    @Override
    public int order() {
        return 195;
    }

    static String key(String providerId, String field) {
        return providerId + "." + field;
    }

    static String enabledKey(String providerId) {
        return key(providerId, "enabled");
    }

    @Override
    public List<ConfigField> configSchema() {
        List<String> options = new ArrayList<>(List.of("auto"));
        providers.providers().forEach(p -> options.add(p.id()));
        List<ConfigField> fields = new ArrayList<>();
        fields.add(ConfigField.of(DEFAULT_IDE, "Standard-IDE", FieldType.ENUM).withDefault("auto")
                .withOptions(options.toArray(String[]::new))
                .withHelp("IDE, wenn das LLM keine angibt. 'auto' = die aktive IDE, deren Spuren im Projekt liegen "
                        + "(.idea, .vscode, .project, *.sln …), sonst die erste aktive."));
        for (IdeProvider p : providers.providers()) {
            fields.addAll(new ConfigGroup(p.id(), p.displayName()).fields(false, p.configFields()));
        }
        fields.add(ConfigField.of(TIMEOUT, "Wartezeit (Sekunden)", FieldType.INT).withDefault("15")
                .withHelp("So lange wird auf den Befehlszeilen-Starter der IDE gewartet. Läuft er danach noch, ist er "
                        + "die gerade gestartete IDE und bleibt offen."));
        return fields;
    }

    @Override
    public List<ToolCallback> createTools(ModuleConfig config) {
        return ToolBeans.callbacks(new IdeTools(new IdeEnvironment(providers, projects.get(), config)));
    }

    @Override
    public ConnectionTestResult testConnection(ModuleConfig config) {
        List<String> errors = config.validate();
        if (!errors.isEmpty()) {
            return ConnectionTestResult.failed(String.join("\n", errors));
        }
        IdeEnvironment env;
        try {
            env = new IdeEnvironment(providers, projects.get(), config);
        } catch (RuntimeException e) {
            return ConnectionTestResult.failed(e.getMessage());
        }
        if (env.entries().isEmpty()) {
            return ConnectionTestResult.failed("Keine IDE aktiviert.");
        }
        StringBuilder sb = new StringBuilder();
        boolean allOk = true;
        for (IdeEnvironment.Entry e : env.entries()) {
            Ide.Availability a = e.ide().probe();
            allOk &= a.available();
            sb.append(e.name()).append(": ").append(a.available() ? a.executable() : "nicht verfügbar – " + a.message())
                    .append(a.available() ? " (" + IdeTools.capabilities(e.ide()) + ")" : "").append('\n');
        }
        String msg = sb.toString().strip();
        return allOk ? ConnectionTestResult.ok(msg) : ConnectionTestResult.failed(msg);
    }

    /**
     * Schaltet die IDEs ein, die auf diesem Rechner installiert sind, solange noch nichts gespeichert ist. Gesucht wird
     * einmal je App-Start – die Vorbelegung wird oft abgefragt.
     */
    @Override
    public Map<String, String> initialValues(Function<String, Map<String, String>> savedValues) {
        Map<String, String> out = installed;
        if (out == null) {
            Map<String, String> found = new LinkedHashMap<>();
            for (IdeProvider p : providers.providers()) {
                try {
                    if (p.create(ProviderSettings.of(Map.of())).probe().available()) {
                        found.put(enabledKey(p.id()), "true");
                    }
                } catch (RuntimeException ignored) {
                    // nicht installiert bzw. hier nicht verfügbar
                }
            }
            out = installed = Map.copyOf(found);
        }
        return out;
    }
}
