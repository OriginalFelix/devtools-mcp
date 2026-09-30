package systems.grebe.devtools.mcp.server;

import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.function.RequestPredicates;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * Verteilt Anfragen an {@code /mcp} auf den MCP-Server des Absenders ({@link McpAccess}): lokale Runtime oder die des
 * angemeldeten Benutzers ({@link UserRuntimes}). Die Route steht vor der der Autokonfiguration, die dadurch nie
 * direkt erreicht wird; der lokale Transport wird nur noch von hier aus aufgerufen.
 */
@Configuration(proxyBeanMethods = false)
public class McpDispatcherConfig {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    RouterFunction<ServerResponse> mcpDispatcher(McpAccess access, UserRuntimes users,
                                                 WebMvcStreamableServerTransportProvider localTransport,
                                                 @Value("${spring.ai.mcp.server.streamable-http.mcp-endpoint:/mcp}")
                                                 String endpoint) {
        return RouterFunctions.route(RequestPredicates.path(endpoint), request -> {
            McpAccess.Result result = access.check(request.servletRequest());
            if (result.denied()) {
                return ServerResponse.status(HttpStatus.UNAUTHORIZED)
                        .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"devtools-mcp\"").build();
            }
            RouterFunction<ServerResponse> target = result.local()
                    ? localTransport.getRouterFunction()
                    : users.router(result.user().user());
            return target.route(request).orElse(r -> ServerResponse.status(HttpStatus.NOT_FOUND).build())
                    .handle(request);
        });
    }
}
