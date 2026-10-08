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
import systems.grebe.devtools.mcp.api.Permission;
import systems.grebe.devtools.mcp.backend.account.TokenService;
import systems.grebe.devtools.mcp.backend.account.UserAccount;

/**
 * Anmeldung an der GraphQL-API per Token – das Sitzungs-Token aus der Mutation {@code login} oder ein persönliches
 * Desktop-Token aus „Mein Konto“: über HTTP im Header {@code Authorization: Bearer …}, über WebSocket im Payload von
 * {@code connection_init} (gleicher Schlüssel). Der Benutzer landet im GraphQL-Kontext unter {@link #USER}, das Token
 * unter {@link #TOKEN}; die Controller lehnen Anfragen ohne Benutzer ab ({@link #require}). Ein ungültiges Token beim
 * WebSocket-Aufbau lehnt die Verbindung ab.
 */
@Component
public class GraphQlAuth implements WebSocketGraphQlInterceptor {

    /** Schlüssel des angemeldeten {@link UserAccount} im GraphQL-Kontext und in der WebSocket-Sitzung. */
    public static final String USER = "devtools.user";
    /** Schlüssel des vorgelegten {@link TokenService.TokenUser} (Token-ID, Art). */
    public static final String TOKEN = "devtools.token";

    private final TokenService tokens;

    public GraphQlAuth(TokenService tokens) {
        this.tokens = tokens;
    }

    @Override
    public Mono<WebGraphQlResponse> intercept(WebGraphQlRequest request, Chain chain) {
        Optional<TokenService.TokenUser> user = request instanceof WebSocketGraphQlRequest ws
                ? Optional.ofNullable((TokenService.TokenUser) ws.getSessionInfo().getAttributes().get(TOKEN))
                : verify(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
        user.ifPresent(u -> request.configureExecutionInput((input, builder) ->
                builder.graphQLContext(Map.of(USER, u.user(), TOKEN, u)).build()));
        return chain.next(request);
    }

    @Override
    public Mono<Object> handleConnectionInitialization(WebSocketSessionInfo sessionInfo,
                                                       Map<String, Object> payload) {
        Object header = payload.getOrDefault(HttpHeaders.AUTHORIZATION, payload.get("authorization"));
        Optional<TokenService.TokenUser> user = verify(header == null ? null : header.toString());
        if (user.isEmpty()) {
            return Mono.error(new IllegalStateException("Anmeldung fehlt oder ist abgelaufen"));
        }
        sessionInfo.getAttributes().put(TOKEN, user.get());
        return Mono.empty();
    }

    private Optional<TokenService.TokenUser> verify(String header) {
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return Optional.empty();
        }
        String token = header.substring(7).trim();
        return token.isEmpty() ? Optional.empty() : tokens.verify(token);
    }

    // ---------------------------------------------------------------- Prüfungen für die Controller

    /** Angemeldet – auch mit ausstehender Passwortänderung (für {@code me}, {@code changePassword}, {@code logout}). */
    public static UserAccount requireSignedIn(UserAccount user) {
        if (user == null) {
            throw new GraphQlErrors.Unauthorized();
        }
        return user;
    }

    /** Angemeldet und arbeitsfähig: ein vom Administrator gesetztes Passwort muss erst geändert sein. */
    public static UserAccount require(UserAccount user) {
        requireSignedIn(user);
        if (user.passwordChangeRequired()) {
            throw new GraphQlErrors.Forbidden("Bitte zuerst das Passwort ändern.");
        }
        return user;
    }

    /** Angemeldet, arbeitsfähig und mit dem Systemrecht. */
    public static UserAccount require(UserAccount user, Permission permission) {
        require(user);
        if (!user.has(permission)) {
            throw new GraphQlErrors.Forbidden("Dafür fehlt das Recht „" + permission.label() + "“.");
        }
        return user;
    }

    /** Angemeldet, arbeitsfähig und mit dem Recht auf das Tool (bzw. sein ganzes Modul). */
    public static UserAccount requireTool(UserAccount user, String moduleId, String toolName) {
        require(user);
        if (!user.grants().tool(moduleId, toolName)) {
            throw new GraphQlErrors.Forbidden("Dafür fehlt das Recht auf das Tool " + toolName + ".");
        }
        return user;
    }
}
