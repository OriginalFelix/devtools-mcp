package systems.grebe.devtools.mcp.modules.chat.teams;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** HTML von Teams-Nachrichten → Klartext und Klartext → HTML. */
final class TeamsHtml {

    private static final Pattern EMOJI = Pattern.compile("<emoji[^>]*\\balt=\"([^\"]*)\"[^>]*>(?:</emoji>)?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ENTITY = Pattern.compile("&#(x?)([0-9a-fA-F]+);");

    private TeamsHtml() {
    }

    /** Klartext: Absätze und Zeilenumbrüche bleiben, Erwähnungen werden zu „@Name“, Emojis zu ihrem Zeichen. */
    static String toText(String html) {
        if (html == null) {
            return "";
        }
        String s = EMOJI.matcher(html).replaceAll(m -> Matcher.quoteReplacement(m.group(1)));
        s = s.replaceAll("(?is)<attachment\\b[^>]*>.*?</attachment>", "")
                .replaceAll("(?i)<at\\b[^>]*>", "@")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</(p|div|li|h\\d|tr|pre|blockquote)>", "\n")
                .replaceAll("(?i)<li\\b[^>]*>", "- ")
                .replaceAll("(?i)</t[dh]>", " | ")
                .replaceAll("<[^>]+>", "");
        s = decode(s);
        return s.replace('\u00a0', ' ').replaceAll("[ \\t]+\n", "\n").replaceAll("\n{3,}", "\n\n").strip();
    }

    /** Maskiert Klartext für den HTML-Body; Zeilenumbrüche werden zu {@code <br>}. */
    static String fromText(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
                .replace("\r\n", "\n").replace("\n", "<br>");
    }

    private static String decode(String s) {
        String out = ENTITY.matcher(s).replaceAll(m -> {
            try {
                int cp = Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16);
                return Matcher.quoteReplacement(new String(Character.toChars(cp)));
            } catch (IllegalArgumentException e) {
                return Matcher.quoteReplacement(m.group());
            }
        });
        return out.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&amp;", "&");
    }
}
