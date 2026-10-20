package systems.grebe.devtools.mcp.core;

import java.util.Map;
import java.util.function.UnaryOperator;

import io.modelcontextprotocol.server.McpRequestHandler;

/**
 * Offizielle oder eigene MCP-Extension (SEP-2133), z.B. {@code io.modelcontextprotocol/skills}: wird im
 * {@code initialize}-Ergebnis unter {@code capabilities.extensions} angekündigt und bringt eigene JSON-RPC-Methoden mit
 * bzw. erweitert vorhandene (z.B. {@code resources/read}). Das MCP-SDK kennt beides noch nicht – {@link McpExtensions}
 * hängt es ein.
 */
public interface McpExtension {

    /** Kennung mit Hersteller-Präfix, z.B. {@code io.modelcontextprotocol/skills}. */
    String id();

    /**
     * Einstellungen der Extension für {@code capabilities.extensions} – bei jedem {@code initialize} neu abgefragt;
     * {@code null} = derzeit nicht angeboten (z.B. Modul aus).
     */
    Map<String, Object> settings();

    /**
     * Request-Handler je Methode: bekommt den bisherigen Handler ({@code null}, wenn es die Methode noch nicht gibt) und
     * liefert den neuen – so lassen sich vorhandene Methoden erweitern, statt sie zu ersetzen.
     */
    Map<String, UnaryOperator<McpRequestHandler<?>>> requestHandlers();
}
