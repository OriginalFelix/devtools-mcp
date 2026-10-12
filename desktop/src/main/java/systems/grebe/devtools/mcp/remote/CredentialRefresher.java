package systems.grebe.devtools.mcp.remote;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.api.CredentialInfo;
import systems.grebe.devtools.mcp.core.CredentialStore;

/**
 * Hält die OAuth-Zugangsdaten frisch: prüft jede Minute, welche Access-Tokens in weniger als
 * {@link CredentialInfo#REFRESH_BEFORE} ablaufen, und lässt sie vom Backend erneuern. Der neue Stand kommt über die
 * Einstellungen zurück, die Tools werden damit neu gebaut. Mehrere Desktop-Apps desselben Benutzers stören sich nicht:
 * das Backend erneuert je Benutzer nacheinander und nur, was wirklich bald abläuft.
 */
@Component
public class CredentialRefresher {

    private static final Logger LOG = LoggerFactory.getLogger(CredentialRefresher.class);

    private final CredentialStore store;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "credential-refresh");
        t.setDaemon(true);
        return t;
    });
    /** Fehler je Eintrag und Ablauf nur einmal melden. */
    private final Set<String> reported = ConcurrentHashMap.newKeySet();

    public CredentialRefresher(CredentialStore store) {
        this.store = store;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        executor.scheduleWithFixedDelay(this::tick, 30, 60, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void stop() {
        executor.shutdownNow();
    }

    void tick() {
        List<CredentialInfo> due;
        try {
            due = due(store.credentials(), Instant.now());
        } catch (RuntimeException e) {
            LOG.debug("Zugangsdaten nicht geprüft: {}", e.getMessage());
            return;
        }
        for (CredentialInfo c : due) {
            try {
                store.refresh(c.id());
                LOG.info("Zugangsdaten „{}“: Access-Token erneuert", c.name());
            } catch (RuntimeException e) {
                if (reported.add(c.id() + "@" + c.expiresAt())) {
                    LOG.warn("Zugangsdaten „{}“ nicht erneuert: {}", c.name(), e.getMessage());
                }
            }
        }
    }

    /** Angemeldete OAuth-Einträge, deren Access-Token in weniger als {@link CredentialInfo#REFRESH_BEFORE} abläuft. */
    static List<CredentialInfo> due(List<CredentialInfo> credentials, Instant now) {
        return credentials.stream()
                .filter(c -> c.oauth() && c.id() != null && c.secret() != null && !c.secret().isBlank())
                .filter(c -> expires(c) != null && !expires(c).isAfter(now.plus(CredentialInfo.REFRESH_BEFORE)))
                .toList();
    }

    private static Instant expires(CredentialInfo c) {
        try {
            return c.expiresAt() == null ? null : Instant.parse(c.expiresAt());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
