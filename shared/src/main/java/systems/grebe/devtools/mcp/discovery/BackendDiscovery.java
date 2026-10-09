package systems.grebe.devtools.mcp.discovery;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Backends im lokalen Netzwerk finden (UDP): Die Desktop-App schickt eine Anfrage per Multicast an
 * {@value #GROUP} und per Broadcast an Port {@value #PORT}; jedes Backend mit eingeschaltetem Advertise-Endpunkt
 * ({@link Advertiser}) antwortet direkt an den Absender mit seiner Adresse als JSON.
 *
 * <p>Die Antwort enthält entweder die öffentliche Adresse ({@code url}, z.B. hinter einem Reverse-Proxy) oder nur
 * Schema und Port – dann setzt der Suchende die Adresse aus dem Absender des Antwortpakets zusammen.
 */
public final class BackendDiscovery {

    /** UDP-Port der Anfragen. */
    public static final int PORT = 47913;
    /** Multicast-Gruppe (organisationslokal, verlässt das Netz nicht). */
    public static final String GROUP = "239.255.47.13";
    /** Kennung der Anfrage. */
    static final String REQUEST = "DEVTOOLS-DISCOVER 1";
    static final String SERVICE = "devtools-backend";
    /** Team-Server bzw. eingebettetes Backend einer Desktop-App. */
    public static final String KIND_SERVER = "server";
    public static final String KIND_DESKTOP = "desktop";

    private static final Logger LOG = LoggerFactory.getLogger(BackendDiscovery.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_PACKET = 2048;

    private BackendDiscovery() {
    }

    /**
     * Gefundenes Backend.
     *
     * @param url  Basisadresse wie im Tab „Backend“ einzutragen, z.B. {@code http://192.168.1.20:8080}
     * @param name Name des Rechners bzw. Servers
     * @param kind {@link #KIND_SERVER} oder {@link #KIND_DESKTOP}
     */
    public record Endpoint(String url, String name, String kind) {

        /** Anzeige in Auswahllisten. */
        public String label() {
            return (KIND_DESKTOP.equals(kind) ? "Desktop-App " : "Team-Server ") + name + " – " + url;
        }
    }

    /**
     * Was ein Backend auf eine Anfrage meldet.
     *
     * @param url    öffentliche Adresse; leer = aus Absender, {@code scheme} und {@code port} bilden
     * @param scheme {@code http} oder {@code https}
     * @param port   HTTP-Port des Backends
     */
    public record Announcement(String name, String kind, String url, String scheme, int port) {

        public Announcement {
            name = name == null || name.isBlank() ? hostName() : name.strip();
            kind = kind == null || kind.isBlank() ? KIND_SERVER : kind.strip();
            url = url == null ? "" : url.strip().replaceAll("/+$", "");
            scheme = scheme == null || scheme.isBlank() ? "http" : scheme.strip();
        }
    }

    // ---------------------------------------------------------------- Suchen

    /** Sucht im lokalen Netzwerk auf dem Standard-Port; wartet {@code timeout} auf Antworten. */
    public static List<Endpoint> search(Duration timeout) throws IOException {
        return search(PORT, timeout);
    }

    /** Sucht auf {@code port}: Multicast über jede Netzwerkschnittstelle, Broadcast und – für denselben Rechner – Loopback. */
    public static List<Endpoint> search(int port, Duration timeout) throws IOException {
        byte[] request = REQUEST.getBytes(StandardCharsets.US_ASCII);
        Map<String, Endpoint> found = new LinkedHashMap<>();
        try (MulticastSocket socket = new MulticastSocket(0)) {
            socket.setBroadcast(true);
            socket.setTimeToLive(4);
            for (InetAddress target : targets()) {
                send(socket, request, target, port, null);
            }
            InetAddress group = InetAddress.getByName(GROUP);
            for (NetworkInterface nif : interfaces()) {
                if (nif.supportsMulticast()) {
                    send(socket, request, group, port, nif);
                }
            }
            long deadline = System.nanoTime() + timeout.toNanos();
            byte[] buffer = new byte[MAX_PACKET];
            while (true) {
                long left = Duration.ofNanos(deadline - System.nanoTime()).toMillis();
                if (left <= 0) {
                    break;
                }
                socket.setSoTimeout((int) left);
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                } catch (SocketTimeoutException e) {
                    break;
                }
                String body = new String(packet.getData(), packet.getOffset(), packet.getLength(),
                        StandardCharsets.UTF_8);
                Endpoint e = parse(body, packet.getAddress());
                if (e != null) {
                    found.putIfAbsent(e.url(), e);
                }
            }
        }
        // Dasselbe Backend antwortet auf demselben Rechner auch über Loopback – die Netzwerkadresse genügt dann
        List<Endpoint> result = new ArrayList<>();
        for (Endpoint e : found.values()) {
            boolean loopback = e.url().matches("https?://(127\\.[0-9.]+|\\[::1]|localhost)(:\\d+)?");
            if (!loopback || found.values().stream().noneMatch(o -> o != e && o.name().equals(e.name())
                    && o.kind().equals(e.kind()))) {
                result.add(e);
            }
        }
        return List.copyOf(result);
    }

    private static void send(MulticastSocket socket, byte[] data, InetAddress target, int port, NetworkInterface nif) {
        try {
            if (nif != null) {
                socket.setNetworkInterface(nif);
            }
            socket.send(new DatagramPacket(data, data.length, target, port));
        } catch (IOException e) {
            LOG.debug("Discovery-Anfrage an {} ({}) nicht gesendet: {}", target, nif == null ? "-" : nif.getName(),
                    e.getMessage());
        }
    }

    /** Broadcast-Adressen der Schnittstellen, das allgemeine Broadcast und Loopback. */
    private static Set<InetAddress> targets() throws IOException {
        Set<InetAddress> targets = new LinkedHashSet<>();
        for (NetworkInterface nif : interfaces()) {
            for (InterfaceAddress a : nif.getInterfaceAddresses()) {
                if (a.getBroadcast() != null) {
                    targets.add(a.getBroadcast());
                }
            }
        }
        targets.add(InetAddress.getByName("255.255.255.255"));
        targets.add(InetAddress.getLoopbackAddress());
        return targets;
    }

    private static List<NetworkInterface> interfaces() {
        List<NetworkInterface> list = new ArrayList<>();
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (nif.isUp() && !nif.isLoopback()) {
                    list.add(nif);
                }
            }
        } catch (SocketException e) {
            LOG.debug("Netzwerkschnittstellen nicht lesbar: {}", e.getMessage());
        }
        return list;
    }

    // ---------------------------------------------------------------- Protokoll

    static String encode(Announcement a) {
        ObjectNode n = JSON.createObjectNode();
        n.put("service", SERVICE);
        n.put("protocol", 1);
        n.put("name", a.name());
        n.put("kind", a.kind());
        if (!a.url().isEmpty()) {
            n.put("url", a.url());
        }
        n.put("scheme", a.scheme());
        n.put("port", a.port());
        return JSON.writeValueAsString(n);
    }

    /** Antwort eines Backends lesen; {@code null}, wenn es keine ist. */
    static Endpoint parse(String body, InetAddress sender) {
        JsonNode n;
        try {
            n = JSON.readTree(body);
        } catch (RuntimeException e) {
            return null;
        }
        if (n == null || !SERVICE.equals(n.path("service").asString(""))) {
            return null;
        }
        String url = n.path("url").asString("").strip().replaceAll("/+$", "");
        if (url.isEmpty()) {
            int port = n.path("port").asInt(0);
            if (port <= 0 || port > 65535) {
                return null;
            }
            String host = sender.getHostAddress();
            if (!(sender instanceof Inet4Address)) {
                host = "[" + host.replaceAll("%.*$", "") + "]";
            }
            url = n.path("scheme").asString("http") + "://" + host + ":" + port;
        }
        if (!url.matches("https?://.+")) {
            return null;
        }
        return new Endpoint(url, n.path("name").asString(sender.getHostAddress()), n.path("kind").asString(KIND_SERVER));
    }

    static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            return "unbekannt";
        }
    }

    // ---------------------------------------------------------------- Antworten

    /**
     * Advertise-Endpunkt: lauscht auf Port {@value #PORT} (Broadcast und Multicast-Gruppe {@value #GROUP}) und
     * beantwortet Anfragen mit der {@link Announcement} des Backends. Läuft in einem eigenen Daemon-Thread.
     */
    public static final class Advertiser implements Closeable {

        private final MulticastSocket socket;
        private final Supplier<Announcement> announcement;
        private final Thread thread;
        private volatile boolean closed;

        private Advertiser(MulticastSocket socket, Supplier<Announcement> announcement) {
            this.socket = socket;
            this.announcement = announcement;
            this.thread = Thread.ofPlatform().daemon().name("backend-advertiser").unstarted(this::loop);
        }

        /** Startet auf dem Standard-Port. */
        public static Advertiser start(Supplier<Announcement> announcement) throws IOException {
            return start(PORT, announcement);
        }

        public static Advertiser start(int port, Supplier<Announcement> announcement) throws IOException {
            MulticastSocket socket = new MulticastSocket(null);
            try {
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(port));
                InetSocketAddress group = new InetSocketAddress(InetAddress.getByName(GROUP), port);
                for (NetworkInterface nif : interfaces()) {
                    if (!nif.supportsMulticast()) {
                        continue;
                    }
                    try {
                        socket.joinGroup(group, nif);
                    } catch (IOException e) {
                        LOG.debug("Multicast-Gruppe auf {} nicht beigetreten: {}", nif.getName(), e.getMessage());
                    }
                }
            } catch (IOException | RuntimeException e) {
                socket.close();
                throw e;
            }
            Advertiser a = new Advertiser(socket, announcement);
            a.thread.start();
            return a;
        }

        /** Lokaler Port (für Tests mit Port 0). */
        public int port() {
            return socket.getLocalPort();
        }

        private void loop() {
            byte[] buffer = new byte[MAX_PACKET];
            while (!closed) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                    String body = new String(packet.getData(), packet.getOffset(), packet.getLength(),
                            StandardCharsets.US_ASCII).strip();
                    if (!REQUEST.equals(body)) {
                        continue;
                    }
                    byte[] reply = encode(announcement.get()).getBytes(StandardCharsets.UTF_8);
                    socket.send(new DatagramPacket(reply, reply.length, packet.getSocketAddress()));
                } catch (IOException | RuntimeException e) {
                    if (!closed) {
                        LOG.debug("Discovery-Anfrage nicht beantwortet: {}", e.getMessage());
                    }
                }
            }
        }

        @Override
        public void close() {
            closed = true;
            socket.close();
        }
    }
}
