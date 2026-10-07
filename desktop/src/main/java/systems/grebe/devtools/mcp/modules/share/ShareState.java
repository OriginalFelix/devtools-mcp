package systems.grebe.devtools.mcp.modules.share;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import systems.grebe.devtools.mcp.modules.share.ShareMessages.Offer;
import systems.grebe.devtools.mcp.modules.share.ShareMessages.Receipt;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Eingang und Ausgang in {@code share-state.json}: empfangene Angebote (bis zur Entscheidung mit Inhalt), gesendete
 * mit der Antwort des Empfängers und die ID dieser Instanz. Ohne Datei (Tests) nur im Speicher.
 */
final class ShareState {

    private static final Logger LOG = LoggerFactory.getLogger(ShareState.class);
    private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    /** So viele entschiedene bzw. gesendete Einträge bleiben stehen. */
    static final int KEEP = 100;
    /** Mehr offene Angebote nimmt der Eingang nicht an (Schutz vor Überflutung). */
    static final int MAX_PENDING = 200;

    enum Status { PENDING, ACCEPTED, DECLINED }

    /** Ein empfangenes Angebot; {@code result}: was beim Annehmen übernommen wurde. */
    record Received(Offer offer, Status status, String received, String decided, String result) {

        Received decide(Status s, String r) {
            return new Received(offer.withoutContent(), s, received, Instant.now().toString(), r);
        }
    }

    /** Ein gesendetes Angebot mit der Antwort des Empfängers ({@code PENDING} = noch keine). */
    record Sent(String id, String to, String title, String summary, String sent, Status status, String answeredBy,
                String comment, String answered) {
    }

    record Data(String instanceId, List<Received> received, List<Sent> sent) {
    }

    private final Path file;
    private final String instanceId;
    private final List<Received> received = new ArrayList<>();
    private final List<Sent> sent = new ArrayList<>();

    ShareState(Path file) {
        this.file = file;
        Data d = load();
        if (d != null) {
            received.addAll(d.received() == null ? List.of() : d.received());
            sent.addAll(d.sent() == null ? List.of() : d.sent());
        }
        this.instanceId = d != null && d.instanceId() != null && !d.instanceId().isBlank() ? d.instanceId()
                : UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        if (d == null || !instanceId.equals(d.instanceId())) {
            save();
        }
    }

    static ShareState inMemory() {
        return new ShareState(null);
    }

    /** Stabile ID dieser Installation (Client-ID beim Broker, eigene Nachrichten erkennen). */
    String instanceId() {
        return instanceId;
    }

    /** Nimmt ein Angebot auf; {@code false}, wenn es schon bekannt ist (doppelt zugestellt) oder der Eingang voll ist. */
    synchronized boolean add(Offer offer) {
        if (received.stream().anyMatch(r -> r.offer().id().equals(offer.id()))) {
            return false;
        }
        if (received.stream().filter(r -> r.status() == Status.PENDING).count() >= MAX_PENDING) {
            LOG.warn("Kooperation: Eingang voll ({} offene Angebote) – Angebot {} von {} verworfen", MAX_PENDING,
                    offer.id(), offer.from());
            return false;
        }
        received.add(new Received(offer, Status.PENDING, Instant.now().toString(), null, null));
        trim(received, r -> r.status() != Status.PENDING);
        save();
        return true;
    }

    /** Angebot nach ID oder eindeutigem Anfang der ID. */
    synchronized Optional<Received> find(String idOrPrefix) {
        if (idOrPrefix == null || idOrPrefix.isBlank()) {
            return Optional.empty();
        }
        String q = idOrPrefix.strip();
        List<Received> hits = received.stream().filter(r -> r.offer().id().equals(q)).toList();
        if (hits.isEmpty()) {
            hits = received.stream().filter(r -> r.offer().id().startsWith(q)).toList();
        }
        if (hits.size() > 1) {
            throw new IllegalArgumentException("'" + q + "' passt zu mehreren Angeboten – die ganze ID angeben.");
        }
        return hits.stream().findFirst();
    }

    synchronized void decide(String id, Status status, String result) {
        received.replaceAll(r -> r.offer().id().equals(id) ? r.decide(status, result) : r);
        save();
    }

    synchronized List<Received> received() {
        return List.copyOf(received);
    }

    synchronized List<Received> pending() {
        return received.stream().filter(r -> r.status() == Status.PENDING).toList();
    }

    synchronized void sent(Sent s) {
        sent.add(s);
        trim(sent, x -> true);
        save();
    }

    synchronized List<Sent> sent() {
        return List.copyOf(sent);
    }

    /** Trägt die Antwort des Empfängers ein; leer, wenn das Angebot nicht von hier kam oder schon beantwortet ist. */
    synchronized Optional<Sent> answer(Receipt r) {
        for (int i = 0; i < sent.size(); i++) {
            Sent s = sent.get(i);
            if (s.id().equals(r.offer()) && s.status() == Status.PENDING
                    && ShareMessages.address(s.to()).equals(ShareMessages.address(r.from()))) {
                Sent answered = new Sent(s.id(), s.to(), s.title(), s.summary(), s.sent(),
                        r.accepted() ? Status.ACCEPTED : Status.DECLINED, r.sender(), r.comment(),
                        Instant.now().toString());
                sent.set(i, answered);
                save();
                return Optional.of(answered);
            }
        }
        return Optional.empty();
    }

    /** Entfernt die ältesten Einträge, auf die {@code removable} passt, bis höchstens {@link #KEEP} davon bleiben. */
    private static <T> void trim(List<T> list, java.util.function.Predicate<T> removable) {
        long n = list.stream().filter(removable).count();
        for (var it = list.iterator(); it.hasNext() && n > KEEP; ) {
            if (removable.test(it.next())) {
                it.remove();
                n--;
            }
        }
    }

    private Data load() {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            return JSON.readValue(Files.readString(file), Data.class);
        } catch (IOException | RuntimeException e) {
            LOG.warn("Kooperation: Zustand nicht lesbar ({}): {}", file, e.getMessage());
            return null;
        }
    }

    private void save() {
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, JSON.writeValueAsString(new Data(instanceId, received, sent)));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            LOG.warn("Kooperation: Zustand nicht gespeichert ({}): {}", file, e.getMessage());
        }
    }
}
