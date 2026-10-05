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
 * Skripte (Groovy oder Java), die zur Laufzeit eigene Module mit Tools ergänzen ({@link ScriptManager}). Die Skripte liegen im
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

    /** Kurzreferenz für Skripte – für {@code scripts_view} ohne Namen und den Reiter „Referenz“ in der App. */
    public static final String REFERENCE = """
            # Skripte für DevTools MCP – Groovy oder Java

            Ein Skript wird zu einem Modul; sein Name (2–32 Kleinbuchstaben/Ziffern, z.B. `jira`) ist Modul-ID und \
            Tool-Präfix (`jira_open_issues`). Sprache: Groovy (Standard) oder Java (`language: java`, braucht ein JDK).

            ## Groovy-DSL

            Der Code auf oberster Ebene läuft beim Laden einmal und beschreibt das Modul, `execute { … }` läuft bei \
            jedem Tool-Aufruf.

            ```groovy
            // devtools: compileStatic      (optional, siehe Typprüfung)
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
                execute { args, cfg ->                            // auch { args -> … } oder { 'fester Text' }
                    progress "Frage ${cfg.baseUrl} ab …"          // Zwischenstand an den Client
                    log.info "Abfrage für {}", args.project       // ins Log der App
                    def max = args.limit ?: cfg.limit
                    [project: args.project, limit: max]           // Text bleibt Text, alles andere wird JSON
                }
            }
            ```

            Parameter-Typen: String, Integer, Long, Double, Boolean, List, Map. `args` und `cfg` sind \
            `Map<String, Object>`. Fehler: eine Exception werfen – die Meldung geht an das LLM.

            **Typprüfung:** Eine Zeile `// devtools: compileStatic` prüft das ganze Skript beim Übersetzen wie \
            Java (unbekannte Methoden, falsche Typen, Tippfehler) und übersetzt es statisch; `// devtools: \
            typeChecked` prüft nur. Werte aus `args`/`cfg` sind dann `Object` – für Methodenaufrufe casten: \
            `(args.project as String).toUpperCase()`. Ohne Parameter `execute { … }` statt `{ -> … }` schreiben. \
            (Ältere Skripte mit `run { … }` laufen weiter, aber nur ohne Typprüfung.) Auch mit Prüfung bleibt Groovy-Semantik: `7 / 2` ist 3.5 \
            (ganzzahlig: `7.intdiv(2)`), `==` vergleicht Inhalte, `"…$x"` ist ein Platzhalter.

            ## Java

            Eine Quelldatei mit einer `public class`, die `ToolModule` implementiert – dieselbe API wie eingebaute \
            Module und Plugins. Tools sind `@Tool`-Methoden (Spring AI), erzeugt mit `ToolBeans.callbacks(…)`; \
            `@ToolHints` setzt die MCP-Hinweise. Die Modul-ID ist immer der Skriptname, `id()` wird ignoriert. \
            Übersetzt wird mit `javac` gegen die Bibliotheken der App; der Konstruktor braucht keine Parameter.

            ```java
            import java.util.List;
            import org.springframework.ai.tool.ToolCallback;
            import org.springframework.ai.tool.annotation.Tool;
            import org.springframework.ai.tool.annotation.ToolParam;
            import systems.grebe.devtools.mcp.core.*;

            public class Jira implements ToolModule {
                public String id() { return "jira"; }
                public String displayName() { return "Jira-Helfer"; }
                public String description() { return "Eigene Jira-Abfragen"; }        // Pflicht
                public List<ConfigField> configSchema() {
                    return List.of(ConfigField.of("baseUrl", "Basis-URL", FieldType.URL).asRequired());
                }
                public List<ToolCallback> createTools(ModuleConfig config) {
                    return ToolBeans.callbacks(new Tools(config.getString("baseUrl", "")));
                }

                public static class Tools {
                    private final String baseUrl;
                    Tools(String baseUrl) { this.baseUrl = baseUrl; }

                    @Tool(name = "open_issues", description = "Offene Issues eines Projekts")
                    @ToolHints(readOnly = true)
                    public String openIssues(@ToolParam(description = "Projektschlüssel") String project) {
                        return "Issues für " + project + " auf " + baseUrl;
                    }
                }
            }
            ```

            ## Für beide

            JSON: `groovy.json.JsonSlurper`/`JsonOutput` (Groovy) oder Jackson (`tools.jackson.databind.json.\
            JsonMapper`), HTTP: `java.net.http.HttpClient`. \
            Skripte laufen mit allen Rechten der App (Bibliotheken der App, Dateisystem, Netz); jeder Aufruf hat \
            ein Zeitlimit (Modul „Skripte“) – Java-Code sollte bei langen Schleifen `Thread.interrupted()` \
            prüfen. Zustand zwischen Aufrufen nicht in Feldern/Skript-Variablen halten – Aufrufe können \
            gleichzeitig laufen. Keine Geheimnisse in den Quelltext, dafür Einstellungen vom Typ SECRET.""";

    /** Sprache aus einem Tool-Parameter; leer = keine Angabe. */
    static ScriptViews.Language language(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return switch (value.strip().toLowerCase(java.util.Locale.ROOT)) {
            case "groovy" -> ScriptViews.Language.GROOVY;
            case "java" -> ScriptViews.Language.JAVA;
            default -> throw new IllegalArgumentException("Unbekannte Sprache '" + value + "' – 'groovy' oder 'java'.");
        };
    }

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
        return "Eigene Tools als Skripte (Groovy oder Java): Jedes Skript wird zur Laufzeit ein Modul mit Tools und "
                + "Einstellungen. "
                + "Gespeichert im Backend (eingebettet oder Team-Server) je Benutzerkonto, plus globale Vorlagen. "
                + "Bearbeiten im Tab „Skripte“; dem LLM per Schalter.";
    }

    @Override
    public String instructions() {
        return """
                Skripte (Groovy oder Java) ergänzen diesen Server zur Laufzeit um eigene Module (Tools \
                `<skriptname>_*`). `scripts_list` zeigt die vorhandenen Skripte, ihren Zustand und ihre Tools; \
                `scripts_view` den Quelltext, ohne Namen die Referenz für beide Sprachen.

                Wenn `scripts_save` angeboten wird und der Nutzer ein wiederkehrendes Werkzeug möchte, das es noch \
                nicht gibt (eigene REST-Abfrage, Auswertung, Konvertierung …): erst mit `scripts_view` (ohne Namen) \
                die Referenz lesen, dann das Skript mit `scripts_save` speichern – Groovy mit \
                `// devtools: compileStatic`, damit Fehler schon beim Speichern auffallen – es wird dabei geprüft und sofort \
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
