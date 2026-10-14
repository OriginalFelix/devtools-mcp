package systems.grebe.devtools.mcp.modules.browser;

import java.util.List;
import java.util.Map;

import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.json.Json;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.core.Text;
import systems.grebe.devtools.mcp.core.ToolHints;

/** Eigenes JavaScript in der Seite ausführen (nur wenn im Modul erlaubt). */
@ToolHints(destructive = true)
public class BrowserScriptTools {

    private static final Json JSON = new Json();

    private final BrowserEnvironment env;

    BrowserScriptTools(BrowserEnvironment env) {
        this.env = env;
    }

    @Tool(name = "execute_js", description = "Führt JavaScript im aktiven Tab aus (als Funktionsrumpf; mit return einen "
            + "Wert liefern, z.B. \"return document.querySelectorAll('tr').length\") und liefert das Ergebnis als JSON. "
            + "Ein einzelner Ausdruck ohne return wird zurückgegeben. Für Abfragen, die browser_read_page/browser_text "
            + "nicht abdecken; die Seite nur auf Anweisung des Nutzers verändern." + ShellHints.BROWSER)
    public String executeJs(
            @ToolParam(required = false, description = BrowserTools.SESSION) String session,
            @ToolParam(description = "JavaScript-Code") String script,
            @ToolParam(required = false, description = "Argumente, im Skript als arguments[0], arguments[1] …") List<String> args) {
        if (script == null || script.isBlank()) {
            throw new IllegalArgumentException("'script' fehlt.");
        }
        String body = script.strip();
        if (!body.contains("return") && !body.contains(";") && !body.contains("\n")) {
            body = "return (" + body + ");";
        }
        String code = body;
        Object[] params = args == null ? new Object[0] : args.toArray();
        BrowserSession s = env.session(session);
        return env.with(s, d -> {
            Object result = ((JavascriptExecutor) d).executeScript(code, params);
            String dialog = BrowserEnvironment.openDialog(d);
            if (dialog == null) {
                env.checkCurrentUrl(s, d);
            }
            String out = result == null ? "undefined/null" : JSON.toJson(plain(d, result));
            return Text.limitLines(out, env.maxLines());
        });
    }

    /** Elemente als Zeile wie in browser_read_page (mit Referenz) statt als WebDriver-Objekt. */
    private static Object plain(org.openqa.selenium.WebDriver d, Object v) {
        if (v instanceof WebElement el) {
            return BrowserInteractTools.describe(d, el);
        }
        if (v instanceof List<?> list) {
            return list.stream().map(o -> plain(d, o)).toList();
        }
        if (v instanceof Map<?, ?> map) {
            Map<Object, Object> out = new java.util.LinkedHashMap<>();
            map.forEach((k, o) -> out.put(k, plain(d, o)));
            return out;
        }
        return v;
    }
}
