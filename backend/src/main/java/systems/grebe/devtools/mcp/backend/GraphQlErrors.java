package systems.grebe.devtools.mcp.backend;

import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.stereotype.Component;

/**
 * Fachliche Fehler als GraphQL-Fehler mit unveränderter Meldung – sie sind für Menschen und das LLM formuliert
 * („Skill existiert bereits …“). Fehlende Anmeldung und Rechte bekommen eigene Typen.
 */
@Component
public class GraphQlErrors extends DataFetcherExceptionResolverAdapter {

    /** Keine oder ungültige Anmeldung. */
    public static class Unauthorized extends RuntimeException {
        public Unauthorized() {
            super("Desktop-Token fehlt oder ist ungültig");
        }
    }

    /** Angemeldet, aber nicht berechtigt. */
    public static class Forbidden extends RuntimeException {
        public Forbidden(String message) {
            super(message);
        }
    }

    @Override
    protected GraphQLError resolveToSingleError(Throwable ex, DataFetchingEnvironment env) {
        ErrorType type = ex instanceof Unauthorized ? ErrorType.UNAUTHORIZED
                : ex instanceof Forbidden ? ErrorType.FORBIDDEN
                : ex instanceof IllegalArgumentException || ex instanceof IllegalStateException ? ErrorType.BAD_REQUEST
                : null;
        if (type == null) {
            return null; // Standardbehandlung (INTERNAL_ERROR, ohne Details)
        }
        return GraphqlErrorBuilder.newError(env).errorType(type).message(String.valueOf(ex.getMessage())).build();
    }
}
