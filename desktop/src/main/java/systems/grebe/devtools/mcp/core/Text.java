package systems.grebe.devtools.mcp.core;

import java.util.List;

/** Hilfen für LLM-freundliche, begrenzte Textausgaben. */
public final class Text {

    private Text() {
    }

    /** Kürzt auf maximal {@code maxLines} Zeilen und hängt einen Hinweis an. */
    public static String limitLines(String text, int maxLines) {
        if (text == null) {
            return "";
        }
        String[] lines = text.split("\\R", -1);
        if (lines.length <= maxLines) {
            return String.join("\n", lines); // Zeilenenden vereinheitlichen (jcmd liefert unter Windows CRLF)
        }
        return String.join("\n", List.of(lines).subList(0, maxLines))
                + "\n… [gekürzt: " + (lines.length - maxLines) + " weitere Zeilen. Eingrenzen, z.B. über 'path'.]";
    }

    /** Liefert die letzten {@code maxLines} Zeilen (für Build-Ausgaben). */
    public static String tailLines(List<String> lines, int maxLines) {
        if (lines.size() <= maxLines) {
            return String.join("\n", lines);
        }
        return "… [" + (lines.size() - maxLines) + " frühere Zeilen ausgelassen]\n"
                + String.join("\n", lines.subList(lines.size() - maxLines, lines.size()));
    }

    public static String orDash(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }

    public static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        int i = s.indexOf('\n');
        return (i < 0 ? s : s.substring(0, i)).trim();
    }
}
