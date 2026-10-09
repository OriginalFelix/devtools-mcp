package systems.grebe.devtools.mcp.backend.discovery;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.discovery.BackendDiscovery;

/**
 * Advertise-Endpunkt des Backends ({@code devtools.discovery.advertise=true}, Standard aus): beantwortet
 * Discovery-Anfragen der Desktop-Apps im lokalen Netzwerk (UDP, siehe {@link BackendDiscovery}) mit der Adresse
 * dieses Backends.
 *
 * <p>Properties: {@code devtools.discovery.url} öffentliche Adresse (z.B. hinter einem Reverse-Proxy; leer = Adresse
 * des Rechners mit dem HTTP-Port), {@code devtools.discovery.name} angezeigter Name (Standard Rechnername),
 * {@code devtools.discovery.kind} {@code server} oder {@code desktop}, {@code devtools.discovery.port} UDP-Port.
 */
@Component
@ConditionalOnProperty(name = "devtools.discovery.advertise", havingValue = "true")
public class DiscoveryAdvertiser implements DisposableBean {

    private static final Logger LOG = LoggerFactory.getLogger(DiscoveryAdvertiser.class);

    private final Environment env;
    private volatile BackendDiscovery.Advertiser advertiser;

    public DiscoveryAdvertiser(Environment env) {
        this.env = env;
    }

    /** Erst wenn der Webserver läuft – vorher ist der Port nicht bekannt und Anfragende fänden niemanden. */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        int httpPort = env.getProperty("local.server.port", Integer.class,
                env.getProperty("server.port", Integer.class, 8080));
        BackendDiscovery.Announcement announcement = new BackendDiscovery.Announcement(
                env.getProperty("devtools.discovery.name"),
                env.getProperty("devtools.discovery.kind", BackendDiscovery.KIND_SERVER),
                env.getProperty("devtools.discovery.url"),
                env.getProperty("server.ssl.enabled", Boolean.class, false) ? "https" : "http",
                httpPort);
        int udpPort = env.getProperty("devtools.discovery.port", Integer.class, BackendDiscovery.PORT);
        try {
            advertiser = BackendDiscovery.Advertiser.start(udpPort, () -> announcement);
            LOG.info("Advertise-Endpunkt aktiv: UDP-Port {} (Multicast {}), meldet {}", udpPort,
                    BackendDiscovery.GROUP, announcement.url().isEmpty()
                            ? announcement.scheme() + "://<Adresse>:" + httpPort : announcement.url());
        } catch (IOException | RuntimeException e) {
            LOG.warn("Advertise-Endpunkt konnte UDP-Port {} nicht öffnen: {}", udpPort, e.getMessage());
        }
    }

    @Override
    public void destroy() {
        BackendDiscovery.Advertiser a = advertiser;
        if (a != null) {
            a.close();
        }
    }
}
