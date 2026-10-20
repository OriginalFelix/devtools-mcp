package systems.grebe.devtools.mcp.core;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import io.modelcontextprotocol.server.McpRequestHandler;
import io.modelcontextprotocol.spec.DefaultMcpStreamableServerSessionFactory;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.function.EntityResponse;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * Hängt die {@link McpExtension}s in den MCP-Server ein – am SDK vorbei, das Extensions (SEP-2133) noch nicht kennt:
 * <ul>
 *   <li><b>Methoden:</b> Beim ersten {@code initialize} kommen die Handler der Extensions in die Handler-Tabelle der
 *   Session-Fabrik ({@link DefaultMcpStreamableServerSessionFactory}, Feld per Reflection – es gibt keinen Setter).
 *   Alle Sessions teilen sich diese Tabelle.</li>
 *   <li><b>Ankündigung:</b> {@link #advertise} ergänzt die {@code initialize}-Antwort um
 *   {@code capabilities.extensions} ({@code McpSchema.ServerCapabilities} hat dafür kein Feld).</li>
 * </ul>
 * Klappt das Einhängen nicht (anderes SDK), kündigt der Server keine Extension an – Clients rufen die Methoden dann
 * gar nicht erst auf.
 */
@Component
public class McpExtensions {

    private static final Logger LOG = LoggerFactory.getLogger(McpExtensions.class);

    private final ObjectProvider<McpExtension> extensions;
    private volatile boolean installed;
    private volatile boolean failed;

    public McpExtensions(ObjectProvider<McpExtension> extensions) {
        this.extensions = extensions;
    }

    /** Fabrik, die vor der ersten Session die Handler der Extensions einhängt. */
    public McpStreamableServerSession.Factory wrap(McpStreamableServerSession.Factory factory) {
        return request -> {
            install(factory);
            return factory.startSession(request);
        };
    }

    synchronized void install(McpStreamableServerSession.Factory factory) {
        if (installed || failed) {
            return;
        }
        List<McpExtension> all = extensions.orderedStream().toList();
        if (all.isEmpty()) {
            installed = true;
            return;
        }
        try {
            if (!(factory instanceof DefaultMcpStreamableServerSessionFactory)) {
                throw new IllegalStateException("unbekannte Session-Fabrik " + factory.getClass().getName());
            }
            Field field = DefaultMcpStreamableServerSessionFactory.class.getDeclaredField("requestHandlers");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, McpRequestHandler<?>> current = (Map<String, McpRequestHandler<?>>) field.get(factory);
            field.set(factory, withExtensions(current, all));
            installed = true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            failed = true;
            LOG.warn("MCP-Extensions {} nicht verfügbar: {}", all.stream().map(McpExtension::id).toList(), e.toString());
        }
    }

    static Map<String, McpRequestHandler<?>> withExtensions(Map<String, McpRequestHandler<?>> current,
                                                            List<McpExtension> all) {
        Map<String, McpRequestHandler<?>> handlers = new LinkedHashMap<>(current == null ? Map.of() : current);
        for (McpExtension e : all) {
            for (Map.Entry<String, UnaryOperator<McpRequestHandler<?>>> h : e.requestHandlers().entrySet()) {
                handlers.put(h.getKey(), h.getValue().apply(handlers.get(h.getKey())));
            }
        }
        return handlers;
    }

    /** Angebotene Extensions mit ihren Einstellungen – leer, solange die Handler nicht eingehängt sind. */
    public Map<String, Object> advertised() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!installed) {
            return out;
        }
        extensions.orderedStream().forEach(e -> {
            try {
                Map<String, Object> settings = e.settings();
                if (settings != null) {
                    out.put(e.id(), settings);
                }
            } catch (RuntimeException ex) {
                LOG.warn("Extension {} nicht angekündigt: {}", e.id(), ex.toString());
            }
        });
        return out;
    }

    /**
     * Filter für die Router-Funktion des MCP-Endpunkts: ergänzt die Antwort auf {@code initialize} um
     * {@code capabilities.extensions}; alle anderen Antworten bleiben unverändert.
     */
    public ServerResponse advertise(ServerRequest request, HandlerFunction<ServerResponse> next) throws Exception {
        ServerResponse response = next.handle(request);
        if (!(response instanceof EntityResponse<?> entity)
                || !(entity.entity() instanceof McpSchema.JSONRPCResponse rpc)
                || !(rpc.result() instanceof McpSchema.InitializeResult init)) {
            return response;
        }
        Map<String, Object> advertised = advertised();
        if (advertised.isEmpty()) {
            return response;
        }
        McpSchema.JSONRPCResponse extended = new McpSchema.JSONRPCResponse(rpc.jsonrpc(), rpc.id(),
                withExtensions(init, advertised), rpc.error());
        return EntityResponse.fromObject((Object) extended)
                .status(response.statusCode())
                .headers(h -> h.addAll(response.headers()))
                .build();
    }

    static InitializeResult withExtensions(McpSchema.InitializeResult init, Map<String, Object> extensions) {
        return new InitializeResult(init.protocolVersion(), new Capabilities(init.capabilities(), extensions),
                init.serverInfo(), init.instructions(), init.meta());
    }

    /** {@code ServerCapabilities} plus {@code extensions}. */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    record Capabilities(@JsonUnwrapped McpSchema.ServerCapabilities base, Map<String, Object> extensions) {
    }

    /** {@code InitializeResult} mit {@link Capabilities}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record InitializeResult(String protocolVersion, Capabilities capabilities, McpSchema.Implementation serverInfo,
                            String instructions, @JsonProperty("_meta") Map<String, Object> meta) {
    }
}
