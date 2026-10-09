package systems.grebe.devtools.mcp.core;

/**
 * Ein Client hat seine MCP-Session beendet ({@code DELETE} auf den MCP-Endpunkt, z.B. beim Neuverbinden oder Beenden).
 * Als Spring-Ereignis veröffentlicht; Module mit Zustand je Session ({@link ToolSession}) räumen ihn damit sofort auf,
 * statt auf eine Zeitgrenze zu warten.
 *
 * @param sessionId ID der beendeten Session – dieselbe wie {@link ToolSession#id()}
 */
public record McpSessionClosed(String sessionId) {
}
