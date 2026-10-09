package systems.grebe.devtools.mcp.backend;

import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.graphql.execution.SubscriptionExceptionResolverAdapter;
import org.springframework.stereotype.Component;

/**
 * Fachliche Fehler eines laufenden Subscription-Stroms (z.B. {@link GraphQlErrors.Unauthorized}, wenn das Token
 * widerrufen wurde) als GraphQL-Fehler mit unveränderter Meldung – wie {@link GraphQlErrors} für Abfragen.
 */
@Component
public class SubscriptionErrors extends SubscriptionExceptionResolverAdapter {

    @Override
    protected GraphQLError resolveToSingleError(Throwable ex) {
        ErrorType type = GraphQlErrors.typeOf(ex);
        if (type == null) {
            return null; // Standardbehandlung
        }
        return GraphqlErrorBuilder.newError().errorType(type).message(String.valueOf(ex.getMessage())).build();
    }
}
