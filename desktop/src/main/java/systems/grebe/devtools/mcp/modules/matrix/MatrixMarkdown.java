package systems.grebe.devtools.mcp.modules.matrix;

import java.util.List;

import org.commonmark.Extension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

/**
 * Markdown des LLM → HTML für {@code formatted_body} ({@code org.matrix.custom.html}). Element und andere Clients zeigen
 * {@code body} sonst als reinen Text mit Sternchen und Backticks. Rohes HTML im Text wird maskiert, nicht übernommen.
 */
final class MatrixMarkdown {

    private static final List<Extension> EXTENSIONS = List.of(TablesExtension.create(), StrikethroughExtension.create());
    private static final Parser PARSER = Parser.builder().extensions(EXTENSIONS).build();
    private static final HtmlRenderer RENDERER = HtmlRenderer.builder().extensions(EXTENSIONS)
            .escapeHtml(true)
            .sanitizeUrls(true)
            .softbreak("<br />")
            .build();

    private MatrixMarkdown() {
    }

    /** HTML zum Text oder {@code null}, wenn der Text keine Formatierung enthält (dann genügt {@code body}). */
    static String html(String markdown) {
        String html = RENDERER.render(PARSER.parse(markdown)).strip();
        if (html.startsWith("<p>") && html.endsWith("</p>") && html.indexOf("<p>", 1) < 0) {
            String inner = html.substring(3, html.length() - 4);
            if (!inner.contains("<")) {
                return null;
            }
        }
        return html.isEmpty() ? null : html;
    }
}
