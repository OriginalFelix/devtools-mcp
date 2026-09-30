package systems.grebe.devtools.mcp.backend;

import java.util.Map;
import java.util.Optional;

import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.graphql.server.WebSocketGraphQlInterceptor;
import org.springframework.graphql.server.WebSocketGraphQlRequest;
import org.springframework.graphql.server.WebSocketSessionInfo;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import systems.grebe.devtools.mcp.backend.account.TokenService;
import systems.grebe.devtools.mcp.backend.account.UserAccount;

/**
 * Anmeldung an der GraphQL-API per Desktop-Token (JWT aus „Mein Konto“ bzw. das Token des lokalen Benutzers):
 * über HTTP im Header {@code Authorization: Bearer …}, über WebSocket im Payload von {@code connection_init}
 * (gleicher Schlüssel). Der Benutzer landet im GraphQL-Kontext unter {@link #USER}; die Controller lehnen Anfragen
 * ohne ihn ab. Ein ungültiges Token beim WebSocket-Aufbau lehnt die Verbindung ab.
 */
@Component
public class GraphQlAuth implements WebSocketGraphQlInterceptor {

    /** Schlüssel des angemeldeten {@link UserAccount} im GraphQL-Kontext und in der WebSocket-Sitzung. */
    public static final String USER = "devtools.user";

    private final TokenService tokens;

    public GraphQlAuth(TokenService tokens) {
        this.tokens = tokens;
    }

    @Override
    public Mono<WebGraphQlResponse> intercept(WebGraphQlRequest request, Chain chain) {
        Optional<UserAccount> user = request instanceof WebSocketGraphQlRequest ws
                ? Optional.ofNullable((UserAccount) ws.getSessionInfo().getAttributes().get(USER))
                : verify(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
        user.ifPresent(u -> request.configureExecutionInput((input, builder) ->
                builder.graphQLContext(Map.of(USER, u)).build()));
        return chain.next(request);
    }

    @Override
    public Mono<Object> handleConnectionInitialization(WebSocketSessionInfo sessionInfo,
                                                       Map<String, Object> payload) {
        Object header = payload.getOrDefault(HttpHeaders.AUTHORIZATION, payload.get("authorization"));
        Optional<UserAccount> user = verify(header == null ? null : header.toString());
        if (user.isEmpty()) {
            return Mono.error(new IllegalStateException("Desktop-Token fehlt oder ist ungültig"));
        }
        sessionInfo.getAttributes().put(USER, user.get());
        return Mono.empty();
    }

    private Optional<UserAccount> verify(String header) {
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return Optional.empty();
        }
        String token = header.substring(7).trim();
        return token.isEmpty() ? Optional.empty() : tokens.verify(token).map(TokenService.TokenUser::user);
    }
}
