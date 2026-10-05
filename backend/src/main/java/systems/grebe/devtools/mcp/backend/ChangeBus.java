package systems.grebe.devtools.mcp.backend;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import systems.grebe.devtools.mcp.backend.memories.MemoryService;
import systems.grebe.devtools.mcp.backend.scripts.ScriptService;
import systems.grebe.devtools.mcp.backend.skills.SkillService;

/**
 * Verteilt {@link BackendChanged}-Ereignisse an die GraphQL-Subscriptions. Ereignisse ohne Abonnenten gehen verloren –
 * Subscriptions liefern beim Start ohnehin den aktuellen Stand.
 */
@Component
public class ChangeBus {

    private final Sinks.Many<BackendChanged> sink = Sinks.many().multicast().directBestEffort();
    private final java.util.concurrent.atomic.AtomicLong revision = new java.util.concurrent.atomic.AtomicLong();

    public ChangeBus(SkillService skills, MemoryService memories, ScriptService scripts) {
        skills.addChangeListener(() -> publish(BackendChanged.all(BackendChanged.Topic.SKILLS)));
        memories.addChangeListener(() -> publish(BackendChanged.all(BackendChanged.Topic.MEMORIES)));
        scripts.addChangeListener(() -> publish(BackendChanged.all(BackendChanged.Topic.SCRIPTS)));
    }

    @EventListener
    public void publish(BackendChanged event) {
        revision.incrementAndGet();
        synchronized (sink) {
            sink.tryEmitNext(event);
        }
    }

    /** Änderungszähler: steigt mit jedem Ereignis (vor dem Verteilen). */
    public long revision() {
        return revision.get();
    }

    /** Ereignisse eines Themas, die den Benutzer betreffen. */
    public Flux<BackendChanged> changes(BackendChanged.Topic topic, long userId) {
        return sink.asFlux().filter(e -> e.topic() == topic && e.concerns(userId));
    }
}
