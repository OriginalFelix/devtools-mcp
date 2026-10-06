package systems.grebe.devtools.mcp.plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Inhalt der {@code plugin.yml} im Wurzelverzeichnis eines Plugin-Jars – angelehnt an Bukkit:
 *
 * <pre>{@code
 * name: jira                       # Pflicht, [a-z][a-z0-9-]*, eindeutig
 * version: 1.2.0                   # Pflicht
 * main: com.acme.jira.JiraPlugin   # Pflicht, Unterklasse von DevToolsPlugin
 * api-version: 1                   # optional (Standard 1), höchstens PluginApi.VERSION
 * description: Tickets lesen
 * author: Felix                    # oder authors: [a, b]
 * website: https://…
 * depend: [git-extras]             # Pflicht-Abhängigkeiten (werden vorher geladen, Klassen sichtbar)
 * softdepend: [sonar-extras]       # optionale Abhängigkeiten
 * libraries:                       # Maven-Koordinaten, beim Laden samt transitiver Abhängigkeiten aufgelöst
 *   - com.squareup.okhttp3:okhttp:4.12.0
 * }</pre>
 *
 * <p>Gelesen und geprüft wird die Datei von der App; Plugins bekommen das Ergebnis über
 * {@link PluginContext#descriptor()}.
 *
 * <p>Signatur (optional): {@value #SIGNATURE_FILE_NAME} neben der {@code plugin.yml} enthält ein signiertes JWT mit
 * den Claims {@code name}, {@code version}, {@code author} und {@code iat} (Signierdatum). Erzeugt wird es mit
 * {@code java -jar devtools-mcp.jar sign-plugin}; die App prüft es beim Laden gegen die vertrauenswürdigen Schlüssel
 * und warnt, wenn Name oder Version nicht zur {@code plugin.yml} passen.
 */
public record PluginDescriptor(
        String name,
        String version,
        String main,
        int apiVersion,
        String description,
        List<String> authors,
        String website,
        List<String> depend,
        List<String> softDepend,
        List<String> libraries) {

    public static final String FILE_NAME = "plugin.yml";

    /** Signatur des Plugins (JWT) im Wurzelverzeichnis des Jars. */
    public static final String SIGNATURE_FILE_NAME = "plugin.jwt";

    public PluginDescriptor {
        authors = List.copyOf(authors == null ? List.of() : authors);
        depend = List.copyOf(depend == null ? List.of() : depend);
        softDepend = List.copyOf(softDepend == null ? List.of() : softDepend);
        libraries = List.copyOf(libraries == null ? List.of() : libraries);
    }

    /** Alle Abhängigkeiten (Pflicht und optional). */
    public List<String> allDependencies() {
        List<String> all = new ArrayList<>(depend);
        softDepend.stream().filter(d -> !all.contains(d)).forEach(all::add);
        return all;
    }
}
