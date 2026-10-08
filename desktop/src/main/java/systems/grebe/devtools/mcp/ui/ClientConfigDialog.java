package systems.grebe.devtools.mcp.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import systems.grebe.devtools.mcp.channel.BridgeJar;
import systems.grebe.devtools.mcp.config.ServerSettings;

/** Zeigt fertige Konfigurationsschnipsel für gängige MCP-Clients. */
public class ClientConfigDialog extends Dialog<Void> {

    /** @param bridgeJar feste Kopie des Jars für stdio-Proxy und Channel ({@link BridgeJar}) */
    public ClientConfigDialog(Window owner, String endpoint, ServerSettings settings, Path bridgeJar) {
        initOwner(owner);
        setTitle("Client verbinden");
        setHeaderText("MCP-Endpunkt: " + endpoint + "  (Transport: Streamable HTTP)");

        String token = settings.authToken();
        boolean auth = settings.authEnabled();
        String headerJson = auth ? ",\n      \"headers\": { \"Authorization\": \"Bearer " + token + "\" }" : "";

        Map<String, String> snippets = new LinkedHashMap<>();
        snippets.put("Claude Code", "claude mcp add --transport http devtools " + endpoint
                + (auth ? " --header \"Authorization: Bearer " + token + "\"" : ""));
        snippets.put("Claude Desktop / Cursor (JSON)", """
                {
                  "mcpServers": {
                    "devtools": {
                      "type": "http",
                      "url": "%s"%s
                    }
                  }
                }""".formatted(endpoint, headerJson));
        snippets.put("VS Code (.vscode/mcp.json)", """
                {
                  "servers": {
                    "devtools": {
                      "type": "http",
                      "url": "%s"%s
                    }
                  }
                }""".formatted(endpoint, headerJson));
        snippets.put("Hermes (config.yaml)", """
                mcp_servers:
                  devtools:
                    url: %s%s""".formatted(endpoint,
                auth ? "\n    headers:\n      Authorization: \"Bearer " + token + "\"" : ""));
        snippets.put("Nur stdio-Clients (Bridge)", "npx -y mcp-remote " + endpoint
                + (auth ? " --header \"Authorization: Bearer " + token + "\"" : ""));
        snippets.put("Claude Code (stdio + Channel)", """
                # Tools UND Benachrichtigungen (neue Mails) in einem Eintrag – statt des HTTP-Eintrags oben:
                claude mcp add devtools -- %1$s -jar %2$s stdio

                # Claude Code mit Channel starten (Research Preview, nur interaktiv) – sonst normal "claude":
                claude --dangerously-load-development-channels server:devtools

                # Nur Benachrichtigungen, wenn die Tools schon über HTTP eingebunden sind:
                claude mcp add devtools-events -- %1$s -jar %2$s channel""".formatted(quote(javaCommand()),
                quote(jarPath(bridgeJar))));

        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        snippets.forEach((name, text) -> {
            TextArea area = new TextArea(text);
            area.setEditable(false);
            area.getStyleClass().add("mono");
            area.setPrefRowCount(12);
            Button copy = new Button("In Zwischenablage kopieren");
            copy.setOnAction(e -> {
                ClipboardContent cc = new ClipboardContent();
                cc.putString(text);
                Clipboard.getSystemClipboard().setContent(cc);
                copy.setText("Kopiert ✔");
            });
            VBox box = new VBox(8, area, copy);
            box.setPadding(new Insets(10));
            tabs.getTabs().add(new Tab(name, box));
        });

        Label hint = new Label("Nach Änderungen an Modulen informiert der Server verbundene Clients automatisch "
                + "(tools/list_changed). Manche Clients laden die Tool-Liste trotzdem erst nach einem Neustart. Der "
                + "Channel liest Port und Token selbst aus den Einstellungen der App. Das Jar dafür hält die App beim "
                + "Start unter " + bridgeJar + " aktuell – der Eintrag bleibt auch nach Updates gültig.");
        hint.setWrapText(true);
        hint.getStyleClass().add("form-help");
        VBox content = new VBox(10, tabs, hint);
        content.setPrefWidth(720);
        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
    }

    /** Java dieser App (die Brücke braucht dieselbe Version). */
    static String javaCommand() {
        return ProcessHandle.current().info().command().orElse("java");
    }

    /** Die feste Kopie des Jars; beim Start aus der IDE, solange es keine Kopie gibt, ein Platzhalter. */
    static String jarPath(Path bridgeJar) {
        return BridgeJar.runningJar(System.getProperty("java.class.path", "")).isPresent()
                || Files.isRegularFile(bridgeJar) ? bridgeJar.toString() : "/pfad/zu/devtools-mcp.jar";
    }

    private static String quote(String s) {
        return s.contains(" ") ? "\"" + s + "\"" : s;
    }
}
