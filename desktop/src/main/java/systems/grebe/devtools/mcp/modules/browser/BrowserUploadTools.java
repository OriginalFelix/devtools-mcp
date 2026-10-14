package systems.grebe.devtools.mcp.modules.browser;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.openqa.selenium.WebElement;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.core.ToolHints;

/** Lokale Dateien in Datei-Felder hochladen (nur aus freigegebenen Verzeichnissen). */
@ToolHints(destructive = false)
public class BrowserUploadTools {

    private final BrowserEnvironment env;

    BrowserUploadTools(BrowserEnvironment env) {
        this.env = env;
    }

    @Tool(name = "upload", description = "Wählt lokale Dateien in einem Datei-Feld (<input type=file>) aus, ohne den "
            + "Dateidialog zu öffnen – nur Dateien aus den in der DevTools-App freigegebenen Verzeichnissen."
            + ShellHints.BROWSER)
    public String upload(
            @ToolParam(required = false, description = BrowserTools.SESSION) String session,
            @ToolParam(required = false, description = BrowserTools.REF) String ref,
            @ToolParam(required = false, description = BrowserTools.SELECTOR + "; leer = erstes Datei-Feld der Seite") String selector,
            @ToolParam(description = "Pfade der Dateien") List<String> paths) {
        if (paths == null || paths.isEmpty()) {
            throw new IllegalArgumentException("'paths' fehlt.");
        }
        List<String> files = new ArrayList<>();
        for (String p : paths) {
            Path file = env.uploadFile(p);
            files.add(file.toString());
        }
        BrowserSession s = env.session(session);
        return env.interact(s, d -> {
            boolean noTarget = (ref == null || ref.isBlank()) && (selector == null || selector.isBlank());
            WebElement el = BrowserEnvironment.element(d, ref, noTarget ? "input[type=file]" : selector);
            if (!"file".equalsIgnoreCase(el.getDomAttribute("type"))) {
                throw new IllegalArgumentException(BrowserInteractTools.describe(d, el) + " ist kein Datei-Feld "
                        + "(<input type=file>) – browser_find selector=\"input[type=file]\" findet es.");
            }
            Overlay.ripple(s, d, Overlay.moveTo(s, d, el, "Wählt " + files.size() + " Datei(en)"));
            el.sendKeys(String.join("\n", files));
            return files.size() + " Datei(en) gewählt: " + String.join(", ", files) + "\n" + env.after(s, d);
        });
    }
}
