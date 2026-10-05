package systems.grebe.devtools.mcp.modules.window;

import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Tastenkombinationen wie {@code ctrl+shift+s}, {@code enter} oder {@code alt+f4}; mehrere nacheinander durch
 * Leerzeichen getrennt ({@code ctrl+a ctrl+c}). Ergebnis sind {@link KeyEvent}-Codes für {@link java.awt.Robot}.
 */
final class KeySpec {

    /** Eine Kombination: Modifier in Drückreihenfolge, dann die Taste. */
    record Combo(List<Integer> modifiers, int key, String text) {

        /** Windows-/Super-Taste (wirkt auf die Shell des Systems); unter macOS ist Cmd die normale Befehlstaste. */
        boolean usesSystemKey(boolean mac) {
            List<Integer> all = new ArrayList<>(modifiers);
            all.add(key);
            return all.contains(KeyEvent.VK_WINDOWS) || (!mac && all.contains(KeyEvent.VK_META));
        }
    }

    private static final Map<String, Integer> MODIFIERS = Map.ofEntries(
            Map.entry("ctrl", KeyEvent.VK_CONTROL), Map.entry("control", KeyEvent.VK_CONTROL), Map.entry("strg", KeyEvent.VK_CONTROL),
            Map.entry("shift", KeyEvent.VK_SHIFT), Map.entry("alt", KeyEvent.VK_ALT), Map.entry("option", KeyEvent.VK_ALT),
            Map.entry("altgr", KeyEvent.VK_ALT_GRAPH),
            Map.entry("cmd", KeyEvent.VK_META), Map.entry("command", KeyEvent.VK_META), Map.entry("meta", KeyEvent.VK_META),
            Map.entry("win", KeyEvent.VK_WINDOWS), Map.entry("super", KeyEvent.VK_WINDOWS));

    private static final Map<String, Integer> KEYS = keys();

    private KeySpec() {
    }

    private static Map<String, Integer> keys() {
        Map<String, Integer> m = new HashMap<>(Map.ofEntries(
                Map.entry("enter", KeyEvent.VK_ENTER), Map.entry("return", KeyEvent.VK_ENTER),
                Map.entry("tab", KeyEvent.VK_TAB), Map.entry("esc", KeyEvent.VK_ESCAPE), Map.entry("escape", KeyEvent.VK_ESCAPE),
                Map.entry("space", KeyEvent.VK_SPACE), Map.entry("backspace", KeyEvent.VK_BACK_SPACE),
                Map.entry("delete", KeyEvent.VK_DELETE), Map.entry("del", KeyEvent.VK_DELETE), Map.entry("entf", KeyEvent.VK_DELETE),
                Map.entry("insert", KeyEvent.VK_INSERT), Map.entry("ins", KeyEvent.VK_INSERT),
                Map.entry("home", KeyEvent.VK_HOME), Map.entry("pos1", KeyEvent.VK_HOME), Map.entry("end", KeyEvent.VK_END),
                Map.entry("pageup", KeyEvent.VK_PAGE_UP), Map.entry("pagedown", KeyEvent.VK_PAGE_DOWN),
                Map.entry("up", KeyEvent.VK_UP), Map.entry("down", KeyEvent.VK_DOWN),
                Map.entry("left", KeyEvent.VK_LEFT), Map.entry("right", KeyEvent.VK_RIGHT),
                Map.entry("contextmenu", KeyEvent.VK_CONTEXT_MENU), Map.entry("menu", KeyEvent.VK_CONTEXT_MENU),
                Map.entry("printscreen", KeyEvent.VK_PRINTSCREEN)));
        int[] f = {KeyEvent.VK_F1, KeyEvent.VK_F2, KeyEvent.VK_F3, KeyEvent.VK_F4, KeyEvent.VK_F5, KeyEvent.VK_F6,
                KeyEvent.VK_F7, KeyEvent.VK_F8, KeyEvent.VK_F9, KeyEvent.VK_F10, KeyEvent.VK_F11, KeyEvent.VK_F12};
        for (int i = 0; i < f.length; i++) {
            m.put("f" + (i + 1), f[i]);
        }
        for (char c = 'a'; c <= 'z'; c++) {
            m.put(String.valueOf(c), KeyEvent.VK_A + (c - 'a'));
        }
        for (char c = '0'; c <= '9'; c++) {
            m.put(String.valueOf(c), KeyEvent.VK_0 + (c - '0'));
        }
        return Map.copyOf(m);
    }

    static List<Combo> parse(String spec) {
        if (spec == null || spec.isBlank()) {
            throw new IllegalArgumentException("Keine Taste angegeben, z.B. \"enter\" oder \"ctrl+s\".");
        }
        List<Combo> out = new ArrayList<>();
        for (String part : spec.strip().split("\\s+")) {
            out.add(combo(part));
        }
        return out;
    }

    private static Combo combo(String text) {
        String[] parts = text.toLowerCase(Locale.ROOT).split("\\+");
        if (text.endsWith("+")) { // "ctrl++" = Strg und Plus
            parts = (text.substring(0, text.length() - 1).toLowerCase(Locale.ROOT) + "plus").split("\\+");
        }
        List<Integer> mods = new ArrayList<>();
        for (int i = 0; i < parts.length - 1; i++) {
            Integer m = MODIFIERS.get(parts[i]);
            if (m == null) {
                throw new IllegalArgumentException("Unbekannter Modifier \"" + parts[i] + "\" in \"" + text
                        + "\". Erlaubt: ctrl, shift, alt, altgr, cmd/meta.");
            }
            mods.add(m);
        }
        String last = parts[parts.length - 1];
        Integer key = KEYS.get(last);
        if (key == null) {
            key = MODIFIERS.get(last);
        }
        if (key == null && last.equals("plus")) {
            key = KeyEvent.VK_PLUS;
        }
        if (key == null && last.length() == 1) {
            int code = KeyEvent.getExtendedKeyCodeForChar(last.charAt(0));
            key = code == KeyEvent.VK_UNDEFINED ? null : code;
        }
        if (key == null) {
            throw new IllegalArgumentException("Unbekannte Taste \"" + last + "\" in \"" + text + "\". Beispiele: enter, "
                    + "tab, esc, backspace, delete, up, pagedown, home, f5, a, 7.");
        }
        return new Combo(List.copyOf(mods), key, text);
    }

    /** Tastencode für ein Zeichen, das sich ohne Tastaturlayout-Annahmen tippen lässt (Buchstabe, Ziffer, Leerraum). */
    static int simpleKey(char c) {
        if (c >= 'a' && c <= 'z') {
            return KeyEvent.VK_A + (c - 'a');
        }
        if (c >= 'A' && c <= 'Z') {
            return KeyEvent.VK_A + (c - 'A');
        }
        if (c >= '0' && c <= '9') {
            return KeyEvent.VK_0 + (c - '0');
        }
        return switch (c) {
            case ' ' -> KeyEvent.VK_SPACE;
            case '\n' -> KeyEvent.VK_ENTER;
            case '\t' -> KeyEvent.VK_TAB;
            default -> KeyEvent.VK_UNDEFINED;
        };
    }
}
