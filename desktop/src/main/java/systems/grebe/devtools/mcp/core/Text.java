package systems.grebe.devtools.mcp.core;

import java.util.List;
import java.util.regex.Pattern;

/** Hilfen für LLM-freundliche, begrenzte Textausgaben. */
public final class Text {

    /** {@code password=…}, {@code trustStorePassword=…}, {@code PWD={…}} in URLs und Verbindungszeichenketten. */
    private static final Pattern SECRET_PARAM = Pattern.compile(
            "(?i)([\\w.-]*(?:password|passwd|pwd|secret|token|apikey|api_key)\\s*=\\s*)(\\{[^}]*}|[^;&,\\s]+)");
    /** {@code //benutzer:passwort@host} */
    private static final Pattern USER_INFO = Pattern.compile("(//[^/@\\s:;?]+:)[^@/\\s]+@");
    /** Oracle {@code jdbc:oracle:thin:benutzer/passwort@host} */
    private static final Pattern ORACLE_USER = Pattern.compile("(?i)(jdbc:oracle:\\w+:[^/@:\\s]+/)[^@\\s]+@");

    private Text() {
    }

    /**
     * Maskiert Zugangsdaten in URLs und Verbindungszeichenketten (Passwort-Parameter, {@code benutzer:passwort@}) –
     * für Ausgaben an das LLM, falls jemand ein Passwort in eine URL statt in das Geheimnis-Feld geschrieben hat.
     */
    public static String maskCredentials(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        String out = SECRET_PARAM.matcher(s).replaceAll("$1****");
        out = USER_INFO.matcher(out).replaceAll("$1****@");
        return ORACLE_USER.matcher(out).replaceAll("$1****@");
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
