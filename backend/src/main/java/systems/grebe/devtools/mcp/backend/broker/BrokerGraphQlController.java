package systems.grebe.devtools.mcp.backend.broker;

import org.springframework.graphql.data.method.annotation.ContextValue;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;
import systems.grebe.devtools.mcp.api.BrokerInfo;
import systems.grebe.devtools.mcp.backend.GraphQlAuth;
import systems.grebe.devtools.mcp.backend.account.UserAccount;

/** {@code broker}: wo die Desktop-Apps den MQTT-Broker des Backends erreichen. */
@Controller
public class BrokerGraphQlController {

    private final BrokerService broker;

    public BrokerGraphQlController(BrokerService broker) {
        this.broker = broker;
    }

    @QueryMapping
    public BrokerInfo broker(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user) {
        GraphQlAuth.require(user);
        return broker.info();
    }
}
