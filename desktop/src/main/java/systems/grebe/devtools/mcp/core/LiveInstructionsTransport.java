package systems.grebe.devtools.mcp.core;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
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
 *
 * <p>Außerdem bündelt die Hülle die {@code list_changed}-Meldungen: Das SDK schickt bei <em>jedem</em>
 * {@code addTool}/{@code removeTool} eine eigene Meldung an alle Sessions – ein Neuaufbau aller Module (etwa nach dem
 * Verbinden mit dem Backend) wären so Hunderte Meldungen je Session, bei einer abgerissenen Verbindung jede davon mit
 * einem ERROR im Log. Stattdessen geht nach einer kurzen Ruhepause genau eine Meldung raus; der Inhalt ist ohnehin
 * immer derselbe (der Client lädt die Liste neu).
 */
public final class LiveInstructionsTransport implements McpStreamableServerTransportProvider {

    private static final Logger LOG = LoggerFactory.getLogger(LiveInstructionsTransport.class);

    /** Meldungen ohne Inhalt, bei denen nur zählt, dass sich etwas geändert hat – sie werden gebündelt. */
    private static final Set<String> COALESCED = Set.of(McpSchema.METHOD_NOTIFICATION_TOOLS_LIST_CHANGED,
            McpSchema.METHOD_NOTIFICATION_PROMPTS_LIST_CHANGED, McpSchema.METHOD_NOTIFICATION_RESOURCES_LIST_CHANGED);

    private static final Duration DEFAULT_QUIET_PERIOD = Duration.ofMillis(250);

    private final McpStreamableServerTransportProvider delegate;
    private final Supplier<String> instructions;
    private final Duration quietPeriod;
    /** Je Methode die geplante (noch nicht gesendete) Meldung. */
    private final Map<String, Disposable> pending = new HashMap<>();

    public LiveInstructionsTransport(McpStreamableServerTransportProvider delegate, Supplier<String> instructions) {
        this(delegate, instructions, DEFAULT_QUIET_PERIOD);
    }

    LiveInstructionsTransport(McpStreamableServerTransportProvider delegate, Supplier<String> instructions,
                              Duration quietPeriod) {
        this.delegate = delegate;
        this.instructions = instructions;
        this.quietPeriod = quietPeriod;
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

    /**
     * {@code list_changed}-Meldungen werden entprellt: jede weitere innerhalb der Ruhepause verschiebt das Senden,
     * so dass nach einer Serie von Änderungen genau eine Meldung mit dem Endstand rausgeht.
     */
    @Override
    public Mono<Void> notifyClients(String method, Object params) {
        if (!COALESCED.contains(method)) {
            return delegate.notifyClients(method, params);
        }
        synchronized (pending) {
            Disposable previous = pending.put(method, Mono.delay(quietPeriod)
                    .then(Mono.defer(() -> delegate.notifyClients(method, params)))
                    .subscribe(null, e -> LOG.warn("{} konnte nicht gesendet werden: {}", method, e.toString())));
            if (previous != null) {
                previous.dispose();
            }
        }
        return Mono.empty();
    }

    @Override
    public Mono<Void> notifyClient(String sessionId, String method, Object params) {
        return delegate.notifyClient(sessionId, method, params);
    }

    @Override
    public Mono<Void> closeGracefully() {
        cancelPending();
        return delegate.closeGracefully();
    }

    @Override
    public void close() {
        cancelPending();
        delegate.close();
    }

    private void cancelPending() {
        synchronized (pending) {
            pending.values().forEach(Disposable::dispose);
            pending.clear();
        }
    }

    @Override
    public List<String> protocolVersions() {
        return delegate.protocolVersions();
    }
}
