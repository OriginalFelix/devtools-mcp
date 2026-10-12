package systems.grebe.devtools.mcp.backend.api;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import graphql.execution.instrumentation.Instrumentation;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.graphql.autoconfigure.GraphQlSourceBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.graphql.execution.BatchLoaderRegistry;
import org.springframework.graphql.execution.ConnectionTypeDefinitionConfigurer;
import org.springframework.graphql.execution.DataFetcherExceptionResolver;
import org.springframework.graphql.execution.GraphQlSource;
import org.springframework.graphql.execution.RuntimeWiringConfigurer;
import org.springframework.graphql.execution.SchemaReport;
import org.springframework.graphql.execution.SubscriptionExceptionResolver;
import org.springframework.graphql.server.webmvc.GraphQlHttpHandler;
import org.springframework.graphql.server.webmvc.GraphQlRequestPredicates;
import org.springframework.graphql.server.webmvc.GraphQlSseHandler;
import org.springframework.graphql.server.webmvc.GraphQlWebSocketHandler;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.HttpRequestHandler;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.function.RequestPredicates;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;
import org.springframework.web.socket.server.support.WebSocketHandlerMapping;
import systems.grebe.devtools.mcp.api.ApiVersions;
import systems.grebe.devtools.mcp.backend.blobs.BlobController;

/**
 * Versionierte API des Backends ({@link ApiVersions}): baut die GraphQL-Schemas aller angebotenen Versionen mit
 * denselben Zutaten wie Spring Boot (Controller, Fehlerbehandlung, Instrumentierung) und legt zu den Pfaden ohne
 * Version ({@code spring.graphql.http.path}/{@code websocket.path} = {@code /graphql}, Version 0) je Version
 * {@code /api/v<n>/graphql} für HTTP, SSE und WebSocket an. {@code GET /api/versions} nennt die Versionen.
 * Die Dateiablage nimmt die Pfade im {@link BlobController} selbst auf; alles andere unter {@code /api/} (z.B. eine
 * nicht angebotene Version) ist 404, statt bei der Web-UI zu landen.
 */
@Configuration(proxyBeanMethods = false)
public class ApiConfig {

    /** Alle Versionen; zugleich der {@code ExecutionGraphQlService} hinter den Handlern von Spring Boot. */
    @Bean
    VersionedGraphQl versionedGraphQl(ObjectProvider<DataFetcherExceptionResolver> exceptionResolvers,
                                      ObjectProvider<SubscriptionExceptionResolver> subscriptionExceptionResolvers,
                                      ObjectProvider<Instrumentation> instrumentations,
                                      ObjectProvider<RuntimeWiringConfigurer> wiringConfigurers,
                                      ObjectProvider<GraphQlSourceBuilderCustomizer> customizers,
                                      BatchLoaderRegistry batchLoaders) {
        Map<Integer, GraphQlSource> sources = new LinkedHashMap<>();
        Map<Integer, SchemaReport> reports = new HashMap<>();
        for (int version : ApiSchemas.versions()) {
            GraphQlSource.SchemaResourceBuilder builder = GraphQlSource.schemaResourceBuilder()
                    .schemaResources(ApiSchemas.resources(version).toArray(Resource[]::new))
                    .exceptionResolvers(exceptionResolvers.orderedStream().toList())
                    .subscriptionExceptionResolvers(subscriptionExceptionResolvers.orderedStream().toList())
                    .instrumentation(instrumentations.orderedStream().toList())
                    .inspectSchemaMappings(report -> reports.put(version, report));
            builder.configureTypeDefinitions(new ConnectionTypeDefinitionConfigurer());
            wiringConfigurers.orderedStream().forEach(builder::configureRuntimeWiring);
            customizers.orderedStream().forEach(c -> c.customize(builder));
            GraphQlSource source = builder.build();
            source.schema(); // Schema jetzt bauen: Fehler beim Start statt bei der ersten Anfrage
            sources.put(version, source);
        }
        return new VersionedGraphQl(sources, reports, batchLoaders);
    }

    /** Schema der neuesten Version für alles, was nur eines kennt (Spring Boot, Tests); ersetzt das von Boot. */
    @Bean
    GraphQlSource graphQlSource(VersionedGraphQl api) {
        return api.source(ApiSchemas.current());
    }

    /**
     * {@code /api/v<n>/graphql} (HTTP, SSE) über die Handler von Spring Boot, {@code GET /api/versions} und 404 für den
     * Rest unter {@code /api/} außer der Dateiablage (ein Controller, der nach den Routen drankommt).
     */
    @Bean
    @Order(0)
    RouterFunction<ServerResponse> versionedGraphQlRoutes(GraphQlHttpHandler http, GraphQlSseHandler sse) {
        RouterFunctions.Builder routes = RouterFunctions.route();
        for (int version : ApiSchemas.versions()) {
            String path = graphQlPath(version);
            routes.route(GraphQlRequestPredicates.graphQlHttp(path), http::handleRequest);
            routes.route(GraphQlRequestPredicates.graphQlSse(path), sse::handleRequest);
        }
        routes.GET(ApiVersions.VERSIONS_PATH, r -> ServerResponse.ok().body(ApiSchemas.info()));
        routes.route(RequestPredicates.path("/api/**")
                        .and(RequestPredicates.path(BlobController.VERSIONED_PATH + "/**").negate()),
                r -> ServerResponse.status(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("error", "Unbekannter Pfad " + r.path() + " – angebotene API-Versionen: "
                                + ApiSchemas.versions())));
        return routes.build();
    }

    /** {@code /api/v<n>/graphql} über WebSocket (Subscriptions), wie {@code /graphql} bei Spring Boot. */
    @Bean
    HandlerMapping versionedGraphQlWebSocketMapping(ObjectProvider<GraphQlWebSocketHandler> handler) {
        WebSocketHandlerMapping mapping = new WebSocketHandlerMapping();
        mapping.setWebSocketUpgradeMatch(true);
        mapping.setOrder(-2); // vor den HTTP-Routen, wie bei Spring Boot
        GraphQlWebSocketHandler ws = handler.getIfAvailable();
        if (ws != null) {
            HttpRequestHandler upgrade = ws.initWebSocketHttpRequestHandler(new DefaultHandshakeHandler());
            Map<String, Object> urls = new LinkedHashMap<>();
            ApiSchemas.versions().forEach(v -> urls.put(graphQlPath(v), upgrade));
            mapping.setUrlMap(urls);
        }
        return mapping;
    }

    static String graphQlPath(int version) {
        return ApiVersions.base(version) + "/graphql";
    }
}
