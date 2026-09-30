package systems.grebe.devtools.mcp.backend;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import systems.grebe.devtools.mcp.backend.skills.SkillService;

/**
 * Verteilt {@link BackendChanged}-Ereignisse an die GraphQL-Subscriptions. Ereignisse ohne Abonnenten gehen verloren –
 * Subscriptions liefern beim Start ohnehin den aktuellen Stand.
 */
@Component
public class ChangeBus {

    private final Sinks.Many<BackendChanged> sink = Sinks.many().multicast().directBestEffort();

    public ChangeBus(SkillService skills) {
        skills.addChangeListener(() -> publish(BackendChanged.all(BackendChanged.Topic.SKILLS)));
    }

    @EventListener
    public void publish(BackendChanged event) {
        synchronized (sink) {
            sink.tryEmitNext(event);
        }
    }

    /** Ereignisse eines Themas, die den Benutzer betreffen. */
    public Flux<BackendChanged> changes(BackendChanged.Topic topic, long userId) {
        return sink.asFlux().filter(e -> e.topic() == topic && e.concerns(userId));
    }
}
