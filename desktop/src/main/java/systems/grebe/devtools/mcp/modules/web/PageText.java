package systems.grebe.devtools.mcp.modules.web;

import java.util.Set;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

/**
 * Lesbarer Text einer HTML-Seite als schlichtes Markdown: Überschriften, Absätze, Listen, Tabellenzeilen und
 * Codeblöcke bleiben erhalten; Navigation, Kopf-/Fußzeilen, Skripte, Formulare und Ähnliches fliegen raus. Gibt es
 * einen Hauptinhalt ({@code main}, {@code article}), zählt nur der.
 */
final class PageText {

    private static final String NOISE = "script, style, noscript, template, svg, canvas, iframe, object, embed, form, "
            + "button, input, select, textarea, nav, aside, footer, dialog, [hidden], [aria-hidden=true], "
            + "[role=navigation], [role=banner], [role=contentinfo], [role=search], [role=dialog]";
    private static final Set<String> BLOCKS = Set.of("p", "div", "section", "article", "main", "header", "table",
            "thead", "tbody", "tfoot", "ul", "ol", "dl", "blockquote", "figure", "figcaption", "details", "summary",
            "address", "hr", "body");
    /** Ab so viel Text gilt {@code main}/{@code article} als Hauptinhalt. */
    private static final int MIN_MAIN_CHARS = 200;

    private PageText() {
    }

    /** Titel der Seite: {@code <title>}, sonst die erste Überschrift. */
    static String title(Document doc) {
        String t = doc.title().strip();
        if (t.isEmpty()) {
            Element h1 = doc.selectFirst("h1");
            t = h1 == null ? "" : h1.text().strip();
        }
        return t;
    }

    /** Text der Seite (verändert {@code doc}). */
    static String text(Document doc) {
        doc.select(NOISE).remove();
        // Kopfbereiche der Seite (Logo, Menü) weg, die Kopfzeile eines Artikels bleibt
        doc.select("header").stream().filter(h -> h.closest("main, article") == null).forEach(Node::remove);
        Element root = doc.body();
        for (String candidate : new String[] {"main", "[role=main]", "article"}) {
            Element e = doc.selectFirst(candidate);
            if (e != null && e.text().length() >= MIN_MAIN_CHARS) {
                root = e;
                break;
            }
        }
        StringBuilder sb = new StringBuilder();
        render(root, sb);
        return tidy(sb.toString());
    }

    private static void render(Node node, StringBuilder sb) {
        for (Node child : node.childNodes()) {
            if (child instanceof TextNode t) {
                inline(sb, t.text());
            } else if (child instanceof Element e) {
                element(e, sb);
            }
        }
    }

    private static void element(Element e, StringBuilder sb) {
        String tag = e.normalName();
        switch (tag) {
            case "br" -> sb.append('\n');
            case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                String text = e.text().strip();
                if (!text.isEmpty()) {
                    block(sb);
                    sb.append("#".repeat(tag.charAt(1) - '0')).append(' ').append(text);
                    block(sb);
                }
            }
            case "pre" -> {
                block(sb);
                sb.append("```\n").append(e.wholeText().strip()).append("\n```");
                block(sb);
            }
            case "li" -> {
                line(sb);
                sb.append("- ");
                render(e, sb);
                line(sb);
            }
            case "dt" -> {
                line(sb);
                render(e, sb);
                sb.append(':');
                line(sb);
            }
            case "dd" -> {
                sb.append("  ");
                render(e, sb);
                line(sb);
            }
            case "tr" -> {
                line(sb);
                render(e, sb);
                line(sb);
            }
            case "td", "th" -> {
                render(e, sb);
                sb.append(" | ");
            }
            case "img" -> {
                String alt = e.attr("alt").strip();
                if (!alt.isEmpty()) {
                    inline(sb, "[Bild: " + alt + "]");
                }
            }
            case "code" -> {
                String code = e.text();
                inline(sb, code.isBlank() ? code : "`" + code.strip() + "`");
            }
            default -> {
                if (BLOCKS.contains(tag)) {
                    block(sb);
                    render(e, sb);
                    block(sb);
                } else {
                    render(e, sb);
                }
            }
        }
    }

    /** Fließtext: Leerraum zusammenfassen, am Zeilenanfang kein Leerzeichen. */
    private static void inline(StringBuilder sb, String text) {
        String s = text.replace('\u00a0', ' ').replaceAll("\\s+", " ");
        if (s.isEmpty()) {
            return;
        }
        boolean atLineStart = sb.isEmpty() || sb.charAt(sb.length() - 1) == '\n';
        if (s.charAt(0) == ' ' && (atLineStart || sb.charAt(sb.length() - 1) == ' ')) {
            s = s.substring(1);
        }
        sb.append(s);
    }

    private static void line(StringBuilder sb) {
        if (!sb.isEmpty() && sb.charAt(sb.length() - 1) != '\n') {
            sb.append('\n');
        }
    }

    private static void block(StringBuilder sb) {
        line(sb);
        if (sb.length() >= 2 && sb.charAt(sb.length() - 2) != '\n') {
            sb.append('\n');
        }
    }

    private static String tidy(String s) {
        return s.replaceAll("[ \\t]+\n", "\n")
                .replaceAll("(?m)^ \\| $", "")
                .replaceAll("\n{3,}", "\n\n")
                .strip();
    }
}
