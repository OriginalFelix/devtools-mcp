package systems.grebe.devtools.mcp.modules.browser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.openqa.selenium.ElementClickInterceptedException;
import org.openqa.selenium.HasCapabilities;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Keys;
import org.openqa.selenium.Platform;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.interactions.WheelInput;
import org.openqa.selenium.support.ui.Select;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.core.ToolHints;

/** Seiten bedienen: klicken, tippen, Tasten, Auswahllisten, Maus, Scrollen, Ziehen. */
@ToolHints(destructive = false)
public class BrowserInteractTools {

    private static final String X = "Statt ref/selector: x in Pixeln des letzten Screenshots (ohne Screenshot: CSS-Pixel im Ausschnitt)";
    private static final String Y = "y passend zu x";

    private final BrowserEnvironment env;

    BrowserInteractTools(BrowserEnvironment env) {
        this.env = env;
    }

    @Tool(name = "click", description = "Klickt auf ein Element (ref aus browser_read_page/browser_find oder Selektor) "
            + "oder auf eine Stelle (x/y aus dem letzten Screenshot) – links, rechts (Kontextmenü) oder doppelt, optional "
            + "mit gedrückten Tasten (z.B. ctrl für neuen Tab). Wartet danach kurz auf eine ausgelöste Navigation."
            + ShellHints.BROWSER)
    public String click(
            @ToolParam(required = false, description = BrowserTools.SESSION) String session,
            @ToolParam(required = false, description = BrowserTools.REF) String ref,
            @ToolParam(required = false, description = BrowserTools.SELECTOR) String selector,
            @ToolParam(required = false, description = X) Integer x,
            @ToolParam(required = false, description = Y) Integer y,
            @ToolParam(required = false, description = "left (Standard), right oder double") String button,
            @ToolParam(required = false, description = "Gedrückte Tasten, z.B. ctrl, shift, alt, meta (cmd) oder ctrl+shift") String modifiers) {
        String b = button == null || button.isBlank() ? "left" : button.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("left", "right", "double").contains(b)) {
            throw new IllegalArgumentException("button: left, right oder double.");
        }
        List<CharSequence> mods = modifiers == null || modifiers.isBlank() ? List.of() : Keyboard.modifiers(modifiers);
        BrowserSession s = env.session(session);
        return env.interact(s, d -> {
            Set<String> tabsBefore = d.getWindowHandles();
            Target t = target(s, d, ref, selector, x, y);
            String what = t.describe(d);
            Actions a = new Actions(d);
            if (t.element != null && b.equals("left") && mods.isEmpty()) {
                try {
                    t.element.click();
                } catch (ElementClickInterceptedException e) {
                    throw new IllegalStateException("Klick auf " + what + " abgefangen – ein anderes Element liegt "
                            + "darüber (Overlay, Cookie-Banner, Dialog?). Erst das schließen oder browser_screenshot "
                            + "ansehen und per x/y klicken.");
                }
            } else {
                t.moveTo(a);
                mods.forEach(a::keyDown);
                switch (b) {
                    case "right" -> a.contextClick();
                    case "double" -> a.doubleClick();
                    default -> a.click();
                }
                mods.reversed().forEach(a::keyUp);
                a.perform();
            }
            String state = env.after(s, d);
            String result = (b.equals("left") ? "Geklickt" : b.equals("right") ? "Rechtsklick" : "Doppelklick")
                    + " auf " + what + ".";
            if (BrowserEnvironment.openDialog(d) == null && d.getWindowHandles().size() > tabsBefore.size()) {
                result += " Ein neuer Tab wurde geöffnet – mit browser_tabs (action=switch) hineinwechseln.";
            }
            return result + "\n" + state;
        });
    }

    @Tool(name = "type", description = "Tippt Text in ein Eingabefeld (ref oder Selektor; vorheriger Inhalt wird "
            + "gelöscht, außer clear=false) oder ohne Ziel in das fokussierte Element. Mit submit=true danach Enter. "
            + "Für Tasten und Kürzel browser_key, für Auswahllisten browser_select." + ShellHints.BROWSER)
    public String type(
            @ToolParam(required = false, description = BrowserTools.SESSION) String session,
            @ToolParam(required = false, description = BrowserTools.REF) String ref,
            @ToolParam(required = false, description = BrowserTools.SELECTOR) String selector,
            @ToolParam(description = "Einzugebender Text") String text,
            @ToolParam(required = false, description = "Vorher leeren (Standard true, nur mit ref/selector)") Boolean clear,
            @ToolParam(required = false, description = "Danach Enter drücken (Standard false)") Boolean submit) {
        if (text == null) {
            throw new IllegalArgumentException("'text' fehlt.");
        }
        BrowserSession s = env.session(session);
        return env.interact(s, d -> {
            boolean hasTarget = (ref != null && !ref.isBlank()) || (selector != null && !selector.isBlank());
            String what;
            if (hasTarget) {
                WebElement el = BrowserEnvironment.element(d, ref, selector);
                BrowserEnvironment.scrollIntoView(d, el);
                if (!Boolean.FALSE.equals(clear)) {
                    clear(d, el);
                }
                el.sendKeys(text);
                if (Boolean.TRUE.equals(submit)) {
                    el.sendKeys(Keys.ENTER);
                }
                what = describe(d, el);
            } else {
                Actions a = new Actions(d).sendKeys(text);
                if (Boolean.TRUE.equals(submit)) {
                    a.sendKeys(Keys.ENTER);
                }
                a.perform();
                what = "fokussiertes Element";
            }
            return "Eingegeben (" + text.length() + " Zeichen" + (Boolean.TRUE.equals(submit) ? " + Enter" : "")
                    + ") in " + what + "\n" + env.after(s, d);
        });
    }

    /** Leert ein Feld; für Frameworks, die {@code clear()} nicht mitbekommen, zusätzlich alles markieren + löschen. */
    private static void clear(WebDriver d, WebElement el) {
        try {
            el.clear();
        } catch (WebDriverException e) {
            // contenteditable u.ä. – unten per Tastatur
        }
        Object value = ((JavascriptExecutor) d).executeScript(
                "const e = arguments[0]; return e.isContentEditable ? e.innerText : e.value;", el);
        if (value != null && !String.valueOf(value).isEmpty()) {
            boolean mac = d instanceof HasCapabilities hc && hc.getCapabilities().getPlatformName() != null
                    && hc.getCapabilities().getPlatformName().is(Platform.MAC);
            el.sendKeys(Keys.chord(mac ? Keys.COMMAND : Keys.CONTROL, "a"), Keys.DELETE);
        }
    }

    @Tool(name = "key", description = "Drückt Tasten oder Tastenkombinationen im aktiven Tab (auf dem fokussierten "
            + "Element), z.B. \"Enter\", \"Escape\", \"Tab Tab Enter\" (nacheinander), \"ctrl+a\", \"shift+Tab\", "
            + "\"meta+Enter\". Namen: Enter, Tab, Escape, Backspace, Delete, Space, ArrowUp/Down/Left/Right, Home, End, "
            + "PageUp, PageDown, F1–F12; Modifikatoren ctrl, shift, alt, meta (cmd)." + ShellHints.BROWSER)
    public String key(
            @ToolParam(required = false, description = BrowserTools.SESSION) String session,
            @ToolParam(description = "Tasten, Kombinationen mit +, mehrere durch Leerzeichen getrennt") String keys,
            @ToolParam(required = false, description = "So oft wiederholen (Standard 1, max. 50)") Integer repeat,
            @ToolParam(required = false, description = "Vorher dieses Element fokussieren: " + BrowserTools.REF) String ref) {
        if (keys == null || keys.isBlank()) {
            throw new IllegalArgumentException("'keys' fehlt.");
        }
        List<Keyboard.Chord> chords = Keyboard.parse(keys);
        int n = repeat == null ? 1 : Math.clamp(repeat, 1, 50);
        BrowserSession s = env.session(session);
        return env.interact(s, d -> {
            if (ref != null && !ref.isBlank()) {
                WebElement el = BrowserEnvironment.element(d, ref, null);
                ((JavascriptExecutor) d).executeScript("arguments[0].focus()", el);
            }
            Actions a = new Actions(d);
            for (int i = 0; i < n; i++) {
                for (Keyboard.Chord c : chords) {
                    c.modifiers().forEach(a::keyDown);
                    a.sendKeys(c.key());
                    c.modifiers().reversed().forEach(a::keyUp);
                }
            }
            a.perform();
            return "Gedrückt: " + keys.strip() + (n > 1 ? " ×" + n : "") + "\n" + env.after(s, d);
        });
    }

    @Tool(name = "select", description = "Wählt in einer Auswahlliste (<select>) eine oder mehrere Optionen nach "
            + "sichtbarem Text oder Wert. Für selbst gebaute Dropdowns (role=combobox ohne <select>): aufklicken und die "
            + "Option mit browser_click wählen." + ShellHints.BROWSER)
    public String select(
            @ToolParam(required = false, description = BrowserTools.SESSION) String session,
            @ToolParam(required = false, description = BrowserTools.REF) String ref,
            @ToolParam(required = false, description = BrowserTools.SELECTOR) String selector,
            @ToolParam(description = "Option(en): sichtbarer Text oder value; mehrere nur bei Mehrfachauswahl") List<String> options) {
        if (options == null || options.isEmpty()) {
            throw new IllegalArgumentException("'options' fehlt.");
        }
        BrowserSession s = env.session(session);
        return env.interact(s, d -> {
            WebElement el = BrowserEnvironment.element(d, ref, selector);
            if (!el.getTagName().equalsIgnoreCase("select")) {
                throw new IllegalArgumentException(describe(d, el) + " ist keine <select>-Liste – aufklicken und die "
                        + "Option mit browser_click wählen.");
            }
            Select sel = new Select(el);
            if (sel.isMultiple()) {
                sel.deselectAll();
            }
            for (String o : options) {
                choose(sel, o);
            }
            String state = env.after(s, d);
            return "Ausgewählt: " + describe(d, el) + "\n" + state;
        });
    }

    private static void choose(Select sel, String option) {
        String o = option.strip();
        try {
            sel.selectByVisibleText(o);
            return;
        } catch (WebDriverException e) {
            // weiter mit value
        }
        try {
            sel.selectByValue(o);
            return;
        } catch (WebDriverException e) {
            // weiter mit Teiltext
        }
        List<WebElement> all = sel.getOptions();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).getText().toLowerCase(Locale.ROOT).contains(o.toLowerCase(Locale.ROOT))) {
                sel.selectByIndex(i);
                return;
            }
        }
        List<String> names = all.stream().limit(40).map(WebElement::getText).toList();
        throw new IllegalArgumentException("Keine Option „" + o + "“ – vorhanden: " + names
                + (all.size() > 40 ? " … (" + all.size() + ")" : ""));
    }

    @Tool(name = "hover", description = "Bewegt die Maus über ein Element oder eine Stelle (Menüs aufklappen, Tooltips "
            + "zeigen)." + ShellHints.BROWSER)
    public String hover(
            @ToolParam(required = false, description = BrowserTools.SESSION) String session,
            @ToolParam(required = false, description = BrowserTools.REF) String ref,
            @ToolParam(required = false, description = BrowserTools.SELECTOR) String selector,
            @ToolParam(required = false, description = X) Integer x,
            @ToolParam(required = false, description = Y) Integer y) {
        BrowserSession s = env.session(session);
        return env.interact(s, d -> {
            Target t = target(s, d, ref, selector, x, y);
            String what = t.describe(d);
            Actions a = new Actions(d);
            t.moveTo(a);
            a.perform();
            return "Maus über " + what + ".\n" + env.after(s, d);
        });
    }

    @Tool(name = "scroll", description = "Scrollt die Seite oder den Bereich unter einer Stelle/einem Element in eine "
            + "Richtung (Standard: etwa eine Bildschirmhöhe) – oder, nur mit ref/selector, bis das Element sichtbar ist."
            + ShellHints.BROWSER)
    public String scroll(
            @ToolParam(required = false, description = BrowserTools.SESSION) String session,
            @ToolParam(required = false, description = "up, down, left oder right") String direction,
            @ToolParam(required = false, description = "Pixel (Standard: 80 % des Ausschnitts)") Integer amount,
            @ToolParam(required = false, description = BrowserTools.REF + " (Bereich, der gescrollt wird, bzw. Ziel)") String ref,
            @ToolParam(required = false, description = BrowserTools.SELECTOR) String selector,
            @ToolParam(required = false, description = X) Integer x,
            @ToolParam(required = false, description = Y) Integer y) {
        String dir = direction == null ? "" : direction.trim().toLowerCase(Locale.ROOT);
        boolean hasElement = (ref != null && !ref.isBlank()) || (selector != null && !selector.isBlank());
        if (dir.isEmpty() && !hasElement) {
            throw new IllegalArgumentException("'direction' (up, down, left, right) oder ein Element angeben.");
        }
        if (!dir.isEmpty() && !Set.of("up", "down", "left", "right").contains(dir)) {
            throw new IllegalArgumentException("direction: up, down, left oder right.");
        }
        BrowserSession s = env.session(session);
        return env.interact(s, d -> {
            JavascriptExecutor js = (JavascriptExecutor) d;
            if (dir.isEmpty()) {
                WebElement el = BrowserEnvironment.element(d, ref, selector);
                BrowserEnvironment.scrollIntoView(d, el);
                return "Sichtbar: " + describe(d, el) + "\n" + position(s, d);
            }
            List<?> view = (List<?>) js.executeScript("return [innerWidth, innerHeight]");
            int w = ((Number) view.get(0)).intValue();
            int h = ((Number) view.get(1)).intValue();
            boolean vertical = dir.equals("up") || dir.equals("down");
            int px = amount != null && amount > 0 ? amount : (int) ((vertical ? h : w) * 0.8);
            int dx = dir.equals("left") ? -px : dir.equals("right") ? px : 0;
            int dy = dir.equals("up") ? -px : dir.equals("down") ? px : 0;
            WheelInput.ScrollOrigin origin;
            if (hasElement) {
                WebElement el = BrowserEnvironment.element(d, ref, selector);
                BrowserEnvironment.scrollIntoView(d, el);
                origin = WheelInput.ScrollOrigin.fromElement(el);
            } else if (x != null && y != null) {
                int[] p = css(s, x, y);
                origin = WheelInput.ScrollOrigin.fromViewport(p[0], p[1]);
            } else {
                origin = WheelInput.ScrollOrigin.fromViewport(w / 2, h / 2);
            }
            try {
                new Actions(d).scrollFromOrigin(origin, dx, dy).perform();
            } catch (WebDriverException e) {
                // Browser ohne Wheel-Aktionen (Safari): Seite per Skript scrollen
                js.executeScript("window.scrollBy(arguments[0], arguments[1])", dx, dy);
            }
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "Gescrollt: " + dir + " " + px + " px.\n" + position(s, d);
        });
    }

    private static String position(BrowserSession s, WebDriver d) {
        Object p = ((JavascriptExecutor) d).executeScript(
                "return 'Position x=' + Math.round(scrollX) + ', y=' + Math.round(scrollY) + ' von '"
                        + " + document.documentElement.scrollHeight + ' Seitenhöhe (Ausschnitt ' + innerHeight + ')'");
        return p + "\n" + BrowserEnvironment.status(s, d);
    }

    @Tool(name = "drag", description = "Zieht mit gedrückter Maustaste von einem Element/einer Stelle zu einem anderen "
            + "Element/einer anderen Stelle (Schieberegler, sortierbare Listen, Canvas). HTML5-Drag-and-Drop "
            + "(draggable=true) reagiert in Chromium nicht immer auf simulierte Mausbewegungen." + ShellHints.BROWSER)
    public String drag(
            @ToolParam(required = false, description = BrowserTools.SESSION) String session,
            @ToolParam(required = false, description = "Start: " + BrowserTools.REF) String fromRef,
            @ToolParam(required = false, description = "Start: Selektor") String fromSelector,
            @ToolParam(required = false, description = "Start: x (Screenshot-Pixel)") Integer fromX,
            @ToolParam(required = false, description = "Start: y") Integer fromY,
            @ToolParam(required = false, description = "Ziel: " + BrowserTools.REF) String toRef,
            @ToolParam(required = false, description = "Ziel: Selektor") String toSelector,
            @ToolParam(required = false, description = "Ziel: x (Screenshot-Pixel)") Integer toX,
            @ToolParam(required = false, description = "Ziel: y") Integer toY) {
        BrowserSession s = env.session(session);
        return env.interact(s, d -> {
            Target from = target(s, d, fromRef, fromSelector, fromX, fromY);
            String fromText = from.describe(d);
            Target to = target(s, d, toRef, toSelector, toX, toY);
            String toText = to.describe(d);
            Actions a = new Actions(d);
            from.moveTo(a);
            a.clickAndHold();
            to.moveTo(a);
            a.release().perform();
            return "Gezogen von " + fromText + " nach " + toText + ".\n" + env.after(s, d);
        });
    }

    // ---- Ziele -------------------------------------------------------------------------------------------------

    /** Element oder Stelle im Ausschnitt (CSS-Pixel). */
    private record Target(WebElement element, int cssX, int cssY, int imageX, int imageY) {

        void moveTo(Actions a) {
            if (element != null) {
                a.moveToElement(element);
            } else {
                a.moveToLocation(cssX, cssY);
            }
        }

        String describe(WebDriver d) {
            if (element != null) {
                return BrowserInteractTools.describe(d, element);
            }
            Object hit = ((JavascriptExecutor) d).executeScript(
                    "const e = document.elementFromPoint(arguments[0], arguments[1]);"
                            + "return e ? e.tagName.toLowerCase() + (e.id ? '#' + e.id : '') + ' „' + "
                            + "(e.innerText || e.value || e.getAttribute('aria-label') || '').trim().slice(0, 60) + '“' : null;",
                    cssX, cssY);
            return "Stelle " + imageX + "/" + imageY + (hit == null ? "" : " (" + hit + ")");
        }
    }

    private Target target(BrowserSession s, WebDriver d, String ref, String selector, Integer x, Integer y) {
        if ((ref != null && !ref.isBlank()) || (selector != null && !selector.isBlank())) {
            WebElement el = BrowserEnvironment.element(d, ref, selector);
            BrowserEnvironment.scrollIntoView(d, el);
            return new Target(el, 0, 0, 0, 0);
        }
        if (x == null || y == null) {
            throw new IllegalArgumentException("ref, selector oder x/y angeben.");
        }
        int[] p = css(s, x, y);
        return new Target(null, p[0], p[1], x, y);
    }

    /** Bildkoordinaten des letzten Screenshots in CSS-Pixel des Ausschnitts. */
    private static int[] css(BrowserSession s, int x, int y) {
        double f = s.cssPerImagePixel > 0 ? s.cssPerImagePixel : 1.0;
        return new int[]{(int) Math.round(x * f), (int) Math.round(y * f)};
    }

    /** Eine Zeile wie in browser_read_page. */
    @SuppressWarnings("unchecked")
    static String describe(WebDriver d, WebElement el) {
        try {
            Object r = ((JavascriptExecutor) d).executeScript(PageScripts.DESCRIBE, List.of(el));
            List<String> lines = (List<String>) ((Map<?, ?>) r).get("lines");
            return lines.isEmpty() ? "Element" : lines.getFirst();
        } catch (WebDriverException | ClassCastException e) {
            return "Element";
        }
    }

    /** Tastennamen und Kombinationen. */
    static final class Keyboard {

        record Chord(List<CharSequence> modifiers, CharSequence key) {
        }

        private Keyboard() {
        }

        static List<Chord> parse(String keys) {
            List<Chord> out = new ArrayList<>();
            for (String part : keys.strip().split("\\s+")) {
                List<String> tokens = splitPlus(part);
                List<CharSequence> mods = new ArrayList<>();
                for (String m : tokens.subList(0, tokens.size() - 1)) {
                    CharSequence k = modifier(m);
                    if (k == null) {
                        throw new IllegalArgumentException("„" + m + "“ ist kein Modifikator (ctrl, shift, alt, meta).");
                    }
                    mods.add(k);
                }
                out.add(new Chord(mods, key(tokens.getLast())));
            }
            return out;
        }

        static List<CharSequence> modifiers(String spec) {
            List<CharSequence> out = new ArrayList<>();
            for (String m : spec.strip().split("[+,\\s]+")) {
                CharSequence k = modifier(m);
                if (k == null) {
                    throw new IllegalArgumentException("„" + m + "“ ist kein Modifikator (ctrl, shift, alt, meta).");
                }
                out.add(k);
            }
            return out;
        }

        /** „ctrl++“ = Strg und Plus. */
        private static List<String> splitPlus(String part) {
            List<String> tokens = new ArrayList<>();
            StringBuilder cur = new StringBuilder();
            for (int i = 0; i < part.length(); i++) {
                char c = part.charAt(i);
                if (c == '+' && !cur.isEmpty()) {
                    tokens.add(cur.toString());
                    cur.setLength(0);
                } else {
                    cur.append(c);
                }
            }
            tokens.add(cur.toString());
            return tokens;
        }

        private static CharSequence modifier(String name) {
            return switch (name.toLowerCase(Locale.ROOT)) {
                case "ctrl", "control", "strg" -> Keys.CONTROL;
                case "shift" -> Keys.SHIFT;
                case "alt", "option", "opt" -> Keys.ALT;
                case "meta", "cmd", "command", "super", "win", "windows" -> Keys.META;
                default -> null;
            };
        }

        static CharSequence key(String name) {
            CharSequence mod = modifier(name);
            if (mod != null) {
                return mod;
            }
            String n = name.toLowerCase(Locale.ROOT);
            if (n.matches("f([1-9]|1[0-2])")) {
                return Keys.valueOf(n.toUpperCase(Locale.ROOT));
            }
            return switch (n) {
                case "enter", "return" -> Keys.ENTER;
                case "tab" -> Keys.TAB;
                case "escape", "esc" -> Keys.ESCAPE;
                case "backspace" -> Keys.BACK_SPACE;
                case "delete", "del" -> Keys.DELETE;
                case "space" -> Keys.SPACE;
                case "arrowup", "up" -> Keys.ARROW_UP;
                case "arrowdown", "down" -> Keys.ARROW_DOWN;
                case "arrowleft", "left" -> Keys.ARROW_LEFT;
                case "arrowright", "right" -> Keys.ARROW_RIGHT;
                case "home" -> Keys.HOME;
                case "end" -> Keys.END;
                case "pageup" -> Keys.PAGE_UP;
                case "pagedown" -> Keys.PAGE_DOWN;
                case "insert" -> Keys.INSERT;
                default -> {
                    if (name.codePointCount(0, name.length()) == 1) {
                        yield name;
                    }
                    throw new IllegalArgumentException("Unbekannte Taste „" + name + "“ – z.B. Enter, Tab, Escape, "
                            + "ArrowDown, F5 oder ein einzelnes Zeichen.");
                }
            };
        }
    }
}
