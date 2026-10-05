package systems.grebe.devtools.mcp.modules.scripts;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolBeans;
import systems.grebe.devtools.mcp.core.ToolModule;

/**
 * Groovy-Skripte, die zur Laufzeit eigene Module mit Tools ergänzen ({@link ScriptManager}). Die Skripte liegen im
 * Backend (eingebettet oder Team-Server) je Benutzerkonto, plus globale Vorlagen der Administratoren. Dieses Modul
 * bietet dem LLM Lesen und – per Schalter, Standard aus – Anlegen/Ändern und Löschen an; in der App (Tab „Skripte“)
 * geht das immer.
 */
@Component
public class ScriptsModule implements ToolModule {

    public static final String ID = "scripts";

    static final String ALLOW_WRITE = "allowWrite";
    static final String ALLOW_DELETE = "allowDelete";
    static final String TIMEOUT = "timeoutSeconds";

    /** Kurzreferenz der DSL – für {@code scripts_view} ohne Namen und als Vorlage in der App. */
    public static final String REFERENCE = """
            # Groovy-Skripte für DevTools MCP – DSL

            Ein Skript wird zu einem Modul; sein Name (2–32 Kleinbuchstaben/Ziffern, z.B. `jira`) ist Modul-ID und \
            Tool-Präfix (`jira_open_issues`). Der Code auf oberster Ebene läuft beim Laden einmal und beschreibt das \
            Modul, `run { … }` läuft bei jedem Tool-Aufruf.

            ```groovy
            module {
                name 'Jira-Helfer'                    // Anzeigename (optional, Standard: Skriptname)
                description 'Eigene Jira-Abfragen'    // Pflicht
                instructions 'Für Jira-Fragen im Team X diese Tools verwenden.'   // optional, für das LLM
                setting 'baseUrl', 'Basis-URL', URL, required: true
                setting 'token', 'API-Token', SECRET
                setting 'limit', 'Standard-Limit', INT, defaultValue: '20'
                // weitere Typen: STRING, BOOLEAN, DIRECTORY, DIRECTORY_LIST, STRING_LIST, ENUM (mit options: [...])
            }

            tool('open_issues') {
                description 'Offene Issues eines Projekts'       // Pflicht: wann das LLM das Tool nimmt
                param 'project', String, 'Projektschlüssel'       // Pflichtparameter
                param 'limit', Integer, 'Höchstens so viele', required: false
                param 'state', String, 'Status', options: ['open', 'closed'], required: false
                readOnly true                                     // MCP-Hinweise: readOnly, destructive, idempotent, openWorld
                run { args, cfg ->                                // auch { args -> … } oder { -> … }
                    progress "Frage ${cfg.baseUrl} ab …"          // Zwischenstand an den Client
                    log.info "Abfrage für {}", args.project       // ins Log der App
                    def max = args.limit ?: cfg.limit
                    [project: args.project, limit: max]           // Text bleibt Text, alles andere wird JSON
                }
            }
            ```

            Parameter-Typen: String, Integer, Long, Double, Boolean, List, Map. Fehler: einfach eine Exception \
            werfen (`throw new IllegalArgumentException('…')`) – die Meldung geht an das LLM. Skripte laufen mit \
            allen Rechten der App (Java-/Groovy-Bibliotheken, Dateisystem, Netz); jeder Aufruf hat ein Zeitlimit \
            (Modul „Skripte“). Zustand zwischen Aufrufen nicht in Skript-Variablen halten – Aufrufe können \
            gleichzeitig laufen.""";

    private final ScriptManager scripts;

    public ScriptsModule(ScriptManager scripts) {
        this.scripts = scripts;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Skripte";
    }

    @Override
    public String description() {
        return "Eigene Tools als Groovy-Skripte: Jedes Skript wird zur Laufzeit ein Modul mit Tools und Einstellungen. "
                + "Gespeichert im Backend (eingebettet oder Team-Server) je Benutzerkonto, plus globale Vorlagen. "
                + "Bearbeiten im Tab „Skripte“; dem LLM per Schalter.";
    }

    @Override
    public String instructions() {
        return """
                Groovy-Skripte ergänzen diesen Server zur Laufzeit um eigene Module (Tools `<skriptname>_*`). \
                `scripts_list` zeigt die vorhandenen Skripte, ihren Zustand und ihre Tools; `scripts_view` den \
                Quelltext, ohne Namen die DSL-Referenz.

                Wenn `scripts_save` angeboten wird und der Nutzer ein wiederkehrendes Werkzeug möchte, das es noch \
                nicht gibt (eigene REST-Abfrage, Auswertung, Konvertierung …): erst mit `scripts_view` (ohne Namen) \
                die DSL lesen, dann das Skript mit `scripts_save` speichern – es wird dabei geprüft und sofort \
                geladen. Ein Skript läuft mit allen Rechten der App: nur auf ausdrücklichen Wunsch des Nutzers \
                anlegen oder ändern, keine Geheimnisse in den Quelltext schreiben (dafür `setting … SECRET`), \
                `scripts_delete` nur auf ausdrücklichen Wunsch.""";
    }

    @Override
    public boolean enabledByDefault() {
        return true;
    }

    @Override
    public int order() {
        return 55;
    }

    @Override
    public List<ConfigField> configSchema() {
        return List.of(
                ConfigField.of(ALLOW_WRITE, "LLM darf Skripte anlegen und ändern", FieldType.BOOLEAN)
                        .withDefault("false")
                        .withHelp("scripts_save. Achtung: Skripte laufen mit allen Rechten der App – das LLM könnte "
                                + "damit beliebigen Code auf diesem Rechner ausführen. In der App geht Bearbeiten "
                                + "immer."),
                ConfigField.of(ALLOW_DELETE, "LLM darf Skripte löschen", FieldType.BOOLEAN).withDefault("false"),
                ConfigField.of(TIMEOUT, "Zeitlimit je Tool-Aufruf (Sekunden)", FieldType.INT)
                        .withDefault(String.valueOf(ScriptManager.DEFAULT_TIMEOUT_SECONDS))
                        .withHelp("Danach wird das Skript unterbrochen (Schleifen prüfen das automatisch)."));
    }

    @Override
    public List<ToolCallback> createTools(ModuleConfig config) {
        List<ToolCallback> tools = new ArrayList<>(ToolBeans.callbacks(new ScriptReadTools(scripts)));
        if (config.getBoolean(ALLOW_WRITE)) {
            tools.addAll(ToolBeans.callbacks(new ScriptWriteTools(scripts)));
        }
        if (config.getBoolean(ALLOW_DELETE)) {
            tools.addAll(ToolBeans.callbacks(new ScriptDeleteTools(scripts)));
        }
        return tools;
    }
}
