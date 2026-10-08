package systems.grebe.devtools.mcp.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.config.SettingsStore;
import systems.grebe.devtools.mcp.modules.memories.MemoryBackend;
import systems.grebe.devtools.mcp.modules.memories.MemoryViews;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import systems.grebe.devtools.mcp.config.AtomicFiles;

/**
 * Rückrufe an das LLM, wenn eine lang laufende Aktion fertig ist (Global Invocation Service).
 *
 * <p>Vor der Aktion legt das LLM eine Memory vom Typ {@link MemoryViews.Type#INVOCATION} an – kurz, was zu tun ist,
 * wenn das Ergebnis kommt – und gibt ihre ID dem Tool der Aktion (z.B. {@code share_send invocation=12}). Das Modul
 * meldet den Rückruf hier an ({@link #register}) und löst ihn aus, wenn die Aktion fertig ist ({@link #complete}).
 * Ein Modul kann auch selbst einen Rückruf anlegen und sofort auslösen ({@link #notify}), etwa für ein eingehendes
 * Angebot.
 *
 * <p>Zugestellt wird über die {@link ChannelEvents} an <em>alle</em> verbundenen LLM-Sitzungen (stdio-Proxy), mit
 * Ergebnis und hinterlegter Memory. Ist gerade keine verbunden – Claude Code geschlossen –, bleibt der Rückruf liegen
 * (auch über einen Neustart der App, {@code invocations.json}) und geht an die nächste Sitzung, die sich verbindet.
 * Nach der Zustellung wird der Rückruf entfernt und die Memory gelöscht, sobald kein anderer Rückruf mehr an ihr hängt.
 */
@Component
public class InvocationService implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(InvocationService.class);
    private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    /** So viele Rückrufe nimmt der Dienst höchstens an (Schutz vor Überflutung). */
    static final int MAX = 500;

    /**
     * Ein angemeldeter Rückruf.
     *
     * @param source  Modul, z.B. {@code share} – wird {@code event_source} der Channel-Nachricht
     * @param key     woran das Modul die Aktion erkennt, z.B. {@code answer:<angebot>}
     * @param label   eine Zeile für Übersicht und Nachricht
     * @param expires bis wann auf das Ergebnis gewartet wird ({@code null} = unbegrenzt); danach wird der Rückruf mit
     *                „keine Rückmeldung“ ausgelöst
     * @param fired   wann ausgelöst ({@code null} = wartet noch)
     * @param content Ergebnis für das LLM (nach dem Auslösen)
     */
    public record Invocation(String id, long memoryId, String source, String key, String label, String created,
                             String expires, String fired, String content, Map<String, String> meta) {

        public Invocation {
            meta = meta == null ? Map.of() : Map.copyOf(meta);
        }

        public boolean waiting() {
            return fired == null;
        }

        Invocation fire(String result, Map<String, String> resultMeta) {
            return new Invocation(id, memoryId, source, key, label, created, expires, Instant.now().toString(), result,
                    resultMeta);
        }
    }

    private record Data(List<Invocation> invocations) {
    }

    private final ChannelEvents channel;
    private final Supplier<MemoryBackend> memories;
    private final Path file;
    private final List<Invocation> invocations = new ArrayList<>();
    private final ExecutorService delivery = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name("invocation-delivery").factory());
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("invocation-expiry").factory());

    @Autowired
    public InvocationService(ChannelEvents channel, ObjectProvider<MemoryBackend> memories, SettingsStore store) {
        this(channel, memories::getIfAvailable,
                store.file().toAbsolutePath().getParent().resolve("invocations.json"));
    }

    /** @param file {@code null} = nur im Speicher (Tests) */
    public InvocationService(ChannelEvents channel, Supplier<MemoryBackend> memories, Path file) {
        this.channel = channel;
        this.memories = memories;
        this.file = file;
        load();
        channel.addSubscribeListener(this::deliverLater);
        timer.scheduleWithFixedDelay(this::expire, 1, 1, TimeUnit.MINUTES);
        deliverLater();
    }

    // ------------------------------------------------------------------ Anmelden und Auslösen

    /**
     * Prüft, ob {@code memoryId} eine Rückruf-Memory des Benutzers ist – vor der Aktion aufrufen, damit nichts startet,
     * dessen Rückruf nicht angemeldet werden kann.
     */
    public MemoryViews.Entry requireMemory(long memoryId) {
        MemoryBackend m = memories.get();
        if (m == null) {
            throw new IllegalStateException("Memories sind nicht verfügbar – Rückrufe gehen nicht.");
        }
        MemoryViews.Entry e = m.details(memoryId).orElseThrow(() -> new IllegalArgumentException("Memory #"
                + memoryId + " gibt es nicht – zuerst mit memories_save(type=INVOCATION, title=…, content=was zu tun "
                + "ist, wenn das Ergebnis kommt) anlegen."));
        if (!e.invocation()) {
            throw new IllegalArgumentException("Memory #" + memoryId + " ist " + MemoryViews.Type.orDefault(e.type())
                    .label() + " – für Rückrufe eine Memory mit type=INVOCATION anlegen (sie wird nach der "
                    + "Zustellung gelöscht).");
        }
        return e;
    }

    /** Meldet einen Rückruf an; ausgelöst wird er mit {@link #complete}. */
    public Invocation register(long memoryId, String source, String key, String label, Duration expiresIn) {
        requireMemory(memoryId);
        Instant now = Instant.now();
        Invocation inv = new Invocation(newId(), memoryId, source, key, label, now.toString(),
                expiresIn == null ? null : now.plus(expiresIn).toString(), null, null, null);
        synchronized (this) {
            if (invocations.size() >= MAX) {
                throw new IllegalStateException("Zu viele offene Rückrufe (" + MAX + ") – invocations_list zeigt sie.");
            }
            invocations.add(inv);
            save();
        }
        return inv;
    }

    /**
     * Löst alle wartenden Rückrufe zu {@code source}/{@code key} aus und stellt sie zu.
     *
     * @return wie viele ausgelöst wurden (0 = niemand wartet darauf)
     */
    public int complete(String source, String key, String content, Map<String, String> meta) {
        int n = 0;
        synchronized (this) {
            for (int i = 0; i < invocations.size(); i++) {
                Invocation inv = invocations.get(i);
                if (inv.waiting() && inv.source().equals(source) && inv.key().equals(key)) {
                    invocations.set(i, inv.fire(content, meta));
                    n++;
                }
            }
            if (n > 0) {
                save();
            }
        }
        if (n > 0) {
            deliverLater();
        }
        return n;
    }

    /** Legt einen Rückruf an und löst ihn sofort aus – für Ereignisse, die ohne Aktion des LLM eintreffen. */
    public Invocation notify(long memoryId, String source, String key, String label, String content,
                             Map<String, String> meta) {
        Invocation inv = register(memoryId, source, key, label, null);
        complete(source, key, content, meta);
        return inv;
    }

    // ------------------------------------------------------------------ Auskunft und Abbrechen

    public synchronized List<Invocation> list() {
        return List.copyOf(invocations);
    }

    /** Entfernt einen Rückruf (ohne Zustellung) und löscht die Memory, wenn nichts anderes mehr an ihr hängt. */
    public Optional<Invocation> cancel(String id) {
        Optional<Invocation> found;
        synchronized (this) {
            found = invocations.stream().filter(i -> i.id().equals(id)).findFirst();
        }
        found.ifPresent(this::remove);
        return found;
    }

    /** Löscht die Memory, sofern kein anderer Rückruf an ihr hängt, und dann den Rückruf. */
    private void remove(Invocation inv) {
        boolean shared;
        synchronized (this) {
            shared = invocations.stream().anyMatch(i -> !i.id().equals(inv.id()) && i.memoryId() == inv.memoryId());
        }
        if (!shared) {
            deleteMemory(inv.memoryId());
        }
        synchronized (this) {
            invocations.removeIf(i -> i.id().equals(inv.id()));
            save();
        }
    }

    // ------------------------------------------------------------------ Zustellen

    private void deliverLater() {
        if (!delivery.isShutdown()) {
            delivery.execute(this::deliver);
        }
    }

    /** Stellt ausgelöste Rückrufe zu, sofern mindestens eine Sitzung verbunden ist. */
    void deliver() {
        for (Invocation inv : list()) {
            if (inv.waiting()) {
                continue;
            }
            if (channel.subscribers() == 0) {
                return; // keine Sitzung: liegen lassen bis zur nächsten
            }
            Map<String, String> meta = new LinkedHashMap<>(inv.meta());
            meta.put("invocation", inv.id());
            meta.put("memory", Long.toString(inv.memoryId()));
            ChannelEvents.Delivery d = channel.deliver(inv.source(), text(inv), meta);
            if (d.delivered() == 0) {
                LOG.debug("Rückruf {} nicht zugestellt ({} Sitzungen) – neuer Versuch bei der nächsten", inv.id(),
                        d.listeners());
                channel.forget(d.id()); // sonst käme er beim Nachholen der Brücke zusätzlich zum neuen Versuch an
                continue;
            }
            remove(inv);
        }
    }

    /** Ergebnis und hinterlegte Memory als Text für das LLM. */
    private String text(Invocation inv) {
        StringBuilder sb = new StringBuilder(inv.content() == null ? inv.label() : inv.content());
        sb.append("\n\nRückruf (Memory #").append(inv.memoryId()).append(") zu „").append(inv.label())
                .append("“ – hinterlegt, was jetzt zu tun ist:\n");
        Optional<MemoryViews.Entry> m = memory(inv.memoryId());
        sb.append(m.map(e -> e.title() + (e.content() == null || e.content().isBlank() ? "" : "\n" + e.content()))
                .orElse("(Memory nicht mehr vorhanden)"));
        sb.append("\nDie Memory wird mit dieser Nachricht gelöscht.");
        return sb.toString();
    }

    private Optional<MemoryViews.Entry> memory(long id) {
        try {
            MemoryBackend m = memories.get();
            return m == null ? Optional.empty() : m.details(id);
        } catch (RuntimeException e) {
            LOG.debug("Memory #{} nicht lesbar: {}", id, e.getMessage());
            return Optional.empty();
        }
    }

    private void deleteMemory(long memoryId) {
        try {
            MemoryBackend m = memories.get();
            if (m != null && m.details(memoryId).filter(MemoryViews.Entry::invocation).isPresent()) {
                m.delete(memoryId, true);
            }
        } catch (RuntimeException e) {
            LOG.info("Rückruf-Memory #{} nicht gelöscht: {}", memoryId, e.getMessage());
        }
    }

    /** Löst Rückrufe aus, deren Wartezeit abgelaufen ist. */
    void expire() {
        Instant now = Instant.now();
        int n = 0;
        synchronized (this) {
            for (int i = 0; i < invocations.size(); i++) {
                Invocation inv = invocations.get(i);
                if (inv.waiting() && inv.expires() != null && Instant.parse(inv.expires()).isBefore(now)) {
                    invocations.set(i, inv.fire("Keine Rückmeldung bis " + inv.expires() + ": " + inv.label(),
                            Map.of("kind", "expired")));
                    n++;
                }
            }
            if (n > 0) {
                save();
            }
        }
        if (n > 0) {
            deliverLater();
        }
    }

    @PreDestroy
    @Override
    public void close() {
        timer.shutdownNow();
        delivery.shutdown();
    }

    // ------------------------------------------------------------------ Ablage

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private void load() {
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            Data d = JSON.readValue(Files.readString(file), Data.class);
            if (d.invocations() != null) {
                invocations.addAll(d.invocations());
            }
        } catch (IOException | RuntimeException e) {
            LOG.warn("Rückrufe nicht lesbar ({}): {}", file, e.getMessage());
        }
    }

    private void save() {
        if (file == null) {
            return;
        }
        try {
            AtomicFiles.writeString(file, JSON.writeValueAsString(new Data(invocations)));
        } catch (IOException | RuntimeException e) {
            LOG.warn("Rückrufe nicht gespeichert ({}): {}", file, e.getMessage());
        }
    }
}
