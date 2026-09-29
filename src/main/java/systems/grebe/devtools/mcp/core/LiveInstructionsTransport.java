package systems.grebe.devtools.mcp.core;

import java.util.List;
import java.util.function.Supplier;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Hülle um den Streamable-HTTP-Transport, die die MCP-{@code instructions} bei <em>jedem</em> {@code initialize} neu
 * baut – statt des Textes, den das SDK beim Serveraufbau einfriert.
 *
 * <p>Hintergrund: {@code McpAsyncServer} speichert die Instructions als {@code final}-Feld und kennt keinen Setter. Der
 * Transport bekommt aber über {@link #setSessionFactory} die Fabrik, die für jede neue Client-Session das
 * {@code InitializeResult} liefert – dort wird der Text ersetzt. Alles andere reicht die Hülle unverändert durch.
 *
 * <p>Grenze des Protokolls: MCP hat keine Benachrichtigung für geänderte Instructions. Neue Texte (z.B. nach dem
 * Installieren eines Plugins) sieht jede <em>neue</em> Session sofort; eine bestehende behält den Text ihres
 * {@code initialize}, bis der Client neu verbindet.
 */
public final class LiveInstructionsTransport implements McpStreamableServerTransportProvider {

    private static final Logger LOG = LoggerFactory.getLogger(LiveInstructionsTransport.class);

    private final McpStreamableServerTransportProvider delegate;
    private final Supplier<String> instructions;

    public LiveInstructionsTransport(McpStreamableServerTransportProvider delegate, Supplier<String> instructions) {
        this.delegate = delegate;
        this.instructions = instructions;
    }

    @Override
    public void setSessionFactory(McpStreamableServerSession.Factory sessionFactory) {
        delegate.setSessionFactory(request -> {
            McpStreamableServerSession.McpStreamableServerSessionInit init = sessionFactory.startSession(request);
            return new McpStreamableServerSession.McpStreamableServerSessionInit(init.session(),
                    init.initResult().map(this::withCurrentInstructions));
        });
    }

    McpSchema.InitializeResult withCurrentInstructions(McpSchema.InitializeResult result) {
        String text;
        try {
            text = instructions.get();
        } catch (RuntimeException e) {
            // lieber der Stand vom Serverstart als ein fehlgeschlagener Verbindungsaufbau
            LOG.warn("Instructions konnten nicht neu gebaut werden – sende den Stand vom Start", e);
            return result;
        }
        return new McpSchema.InitializeResult(result.protocolVersion(), result.capabilities(), result.serverInfo(),
                text, result.meta());
    }

    @Override
    public Mono<Void> notifyClients(String method, Object params) {
        return delegate.notifyClients(method, params);
    }

    @Override
    public Mono<Void> notifyClient(String sessionId, String method, Object params) {
        return delegate.notifyClient(sessionId, method, params);
    }

    @Override
    public Mono<Void> closeGracefully() {
        return delegate.closeGracefully();
    }

    @Override
    public void close() {
        delegate.close();
    }

    @Override
    public List<String> protocolVersions() {
        return delegate.protocolVersions();
    }
}
