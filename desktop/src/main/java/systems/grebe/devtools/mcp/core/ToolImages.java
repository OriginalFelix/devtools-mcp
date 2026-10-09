package systems.grebe.devtools.mcp.core;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.BiFunction;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Bilder als Teil eines Tool-Ergebnisses (z.B. Screenshots). {@code @Tool}-Methoden liefern nur Text; Bilder hängen sie
 * während des Aufrufs mit {@link #attach} an, {@link #wrap} fügt sie dem {@code CallToolResult} als
 * {@code ImageContent} hinzu. Der Text bleibt das protokollierte Ergebnis – Base64-Daten landen nicht im Aufrufprotokoll.
 *
 * <p>Wie bei {@link ToolProgress} liegt die Sammlung für die Dauer des Aufrufs in einem {@link ThreadLocal}.
 */
public final class ToolImages {

    /** Ein angehängtes Bild. */
    public record Image(String mimeType, byte[] data) {
    }

    private static final ThreadLocal<List<Image>> CURRENT = new ThreadLocal<>();

    private ToolImages() {
    }

    /** Hüllt den Handler so ein, dass angehängte Bilder ins Ergebnis kommen. */
    public static McpServerFeatures.SyncToolSpecification wrap(McpServerFeatures.SyncToolSpecification spec) {
        BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> handler = spec.callHandler();
        return new McpServerFeatures.SyncToolSpecification(spec.tool(), (exchange, request) -> {
            Captured<McpSchema.CallToolResult> c = capture(() -> handler.apply(exchange, request));
            McpSchema.CallToolResult result = c.value();
            if (c.images().isEmpty() || Boolean.TRUE.equals(result.isError())) {
                return result;
            }
            List<McpSchema.Content> content = new ArrayList<>(result.content());
            for (Image image : c.images()) {
                content.add(McpSchema.ImageContent.builder(Base64.getEncoder().encodeToString(image.data()),
                        image.mimeType()).build());
            }
            return new McpSchema.CallToolResult(content, result.isError(), result.structuredContent(), result.meta());
        });
    }

    /** Hängt ein Bild an das Ergebnis des laufenden Aufrufs; außerhalb eines Aufrufs wirkungslos. */
    public static void attach(String mimeType, byte[] data) {
        List<Image> images = CURRENT.get();
        if (images != null) {
            images.add(new Image(mimeType, data));
        }
    }

    /** Ergebnis eines Aufrufs samt angehängter Bilder. */
    public record Captured<T>(T value, List<Image> images) {
    }

    /** Führt {@code body} aus und sammelt die dabei angehängten Bilder (auch für Tests). */
    public static <T> Captured<T> capture(java.util.function.Supplier<T> body) {
        List<Image> previous = CURRENT.get();
        List<Image> images = new ArrayList<>();
        CURRENT.set(images);
        try {
            return new Captured<>(body.get(), List.copyOf(images));
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
