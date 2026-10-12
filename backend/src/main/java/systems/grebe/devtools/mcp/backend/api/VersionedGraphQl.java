package systems.grebe.devtools.mcp.backend.api;

import java.util.HashMap;
import java.util.Map;

import org.springframework.graphql.ExecutionGraphQlRequest;
import org.springframework.graphql.ExecutionGraphQlResponse;
import org.springframework.graphql.ExecutionGraphQlService;
import org.springframework.graphql.execution.BatchLoaderRegistry;
import org.springframework.graphql.execution.DefaultExecutionGraphQlService;
import org.springframework.graphql.execution.GraphQlSource;
import org.springframework.graphql.execution.SchemaReport;
import org.springframework.graphql.server.WebGraphQlRequest;
import reactor.core.publisher.Mono;
import systems.grebe.devtools.mcp.api.ApiVersions;

/**
 * Die GraphQL-API in allen {@link ApiSchemas angebotenen Versionen}: je Version ein {@link GraphQlSource} mit ihrem
 * Schema und denselben Controllern. Als {@link ExecutionGraphQlService} wählt sie die Version am Pfad der Anfrage
 * ({@code /api/v<n>/graphql}, ohne Version = {@link ApiVersions#LEGACY}) – HTTP, SSE und WebSocket laufen dadurch
 * über dieselben Handler von Spring Boot.
 */
public class VersionedGraphQl implements ExecutionGraphQlService {

    private final Map<Integer, GraphQlSource> sources;
    private final Map<Integer, SchemaReport> reports;
    private final Map<Integer, ExecutionGraphQlService> services = new HashMap<>();

    VersionedGraphQl(Map<Integer, GraphQlSource> sources, Map<Integer, SchemaReport> reports,
                     BatchLoaderRegistry batchLoaders) {
        this.sources = Map.copyOf(sources);
        this.reports = reports;
        sources.forEach((version, source) -> {
            DefaultExecutionGraphQlService service = new DefaultExecutionGraphQlService(source);
            service.addDataLoaderRegistrar(batchLoaders);
            services.put(version, service);
        });
    }

    @Override
    public Mono<ExecutionGraphQlResponse> execute(ExecutionGraphQlRequest request) {
        int version = request instanceof WebGraphQlRequest web ? ApiVersions.fromPath(web.getUri().getPath())
                : ApiSchemas.current();
        ExecutionGraphQlService service = services.get(version);
        if (service == null) { // Routen gibt es nur für angebotene Versionen
            return Mono.error(new IllegalStateException("API-Version " + version + " wird nicht angeboten."));
        }
        return service.execute(request);
    }

    /** Schema einer angebotenen Version, mit den Controllern verdrahtet. */
    public GraphQlSource source(int version) {
        return require(sources, version);
    }

    /**
     * Abgleich Schema ↔ Controller einer Version beim Start. {@link SchemaReport#unmappedFields()} sind Felder, die
     * Clients dieser Version abfragen dürfen, für die aber nichts mehr zuständig ist – das darf nicht vorkommen.
     */
    public SchemaReport report(int version) {
        return require(reports, version);
    }

    private static <T> T require(Map<Integer, T> map, int version) {
        T value = map.get(version);
        if (value == null) {
            throw new IllegalArgumentException("API-Version " + version + " wird nicht angeboten.");
        }
        return value;
    }
}
