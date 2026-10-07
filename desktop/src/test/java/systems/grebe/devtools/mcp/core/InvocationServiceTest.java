package systems.grebe.devtools.mcp.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;
import systems.grebe.devtools.mcp.backend.skills.SkillTestSupport;
import systems.grebe.devtools.mcp.modules.memories.MemoryBackend;
import systems.grebe.devtools.mcp.modules.memories.MemoryViews;

/** Rückrufe: anmelden, auslösen, an alle verbundenen Sitzungen zustellen, liegen lassen ohne Sitzung, Ablauf. */
class InvocationServiceTest {

    @TempDir
    Path dir;

    private ConfigurableApplicationContext ctx;
    private MemoryBackend memories;
    private final ChannelEvents channel = new ChannelEvents();
    private InvocationService service;

    @BeforeEach
    void setUp() {
        ctx = SkillTestSupport.start(dir.resolve("backend"));
        memories = ctx.getBean(MemoryBackend.class);
        service = new InvocationService(channel, () -> memories, dir.resolve("invocations.json"));
    }

    @AfterEach
    void tearDown() {
        service.close();
        ctx.close();
    }

    @Test
    void deliversToAllSessionsAndDeletesTheMemory() throws Exception {
        long m = invocationMemory("Warte auf Build", "Bei Erfolg deployen.");
        List<ChannelEvents.Event> a = session();
        List<ChannelEvents.Event> b = session();
        service.register(m, "build", "run:1", "Build von shop", null);

        assertThat(service.complete("build", "run:2", "anderer", Map.of())).isZero();
        assertThat(service.complete("build", "run:1", "Build grün.", Map.of("status", "ok"))).isEqualTo(1);

        await(() -> service.list().isEmpty());
        for (List<ChannelEvents.Event> s : List.of(a, b)) {
            assertThat(s).singleElement().satisfies(e -> {
                assertThat(e.source()).isEqualTo("build");
                assertThat(e.content()).contains("Build grün.", "Rückruf (Memory #" + m + ") zu „Build von shop“",
                        "Warte auf Build", "Bei Erfolg deployen.");
                assertThat(e.meta()).containsEntry("status", "ok").containsEntry("memory", Long.toString(m))
                        .containsKey("invocation");
            });
        }
        assertThat(memories.details(m)).isEmpty();
    }

    @Test
    void keepsFiredInvocationAcrossRestartUntilASessionConnects() throws Exception {
        long m = invocationMemory("Warte auf Antwort", "Nutzer informieren.");
        service.notify(m, "share", "offer:1", "Angebot", "Neues Angebot.", Map.of());
        Thread.sleep(300);
        assertThat(service.list()).singleElement().satisfies(i -> assertThat(i.waiting()).isFalse());

        service.close();
        service = new InvocationService(channel, () -> memories, dir.resolve("invocations.json"));
        assertThat(service.list()).hasSize(1);
        assertThat(memories.details(m)).isPresent();

        List<ChannelEvents.Event> later = session();

        await(() -> !later.isEmpty());
        assertThat(later.getFirst().content()).contains("Neues Angebot.", "Nutzer informieren.");
        await(() -> service.list().isEmpty());
        assertThat(memories.details(m)).isEmpty();
    }

    @Test
    void sessionThatFailsDoesNotCount() throws Exception {
        long m = invocationMemory("x", "y");
        channel.subscribe(e -> {
            throw new IllegalStateException("Verbindung weg");
        }, -1);

        service.notify(m, "share", "k", "Label", "Ergebnis", Map.of());
        Thread.sleep(300);
        assertThat(service.list()).hasSize(1);

        List<ChannelEvents.Event> ok = session();
        await(() -> !ok.isEmpty());
        await(() -> service.list().isEmpty());
    }

    @Test
    void sharedMemoryIsDeletedAfterTheLastInvocation() throws Exception {
        long m = invocationMemory("Zwei Aktionen", "Beide abwarten.");
        session();
        service.register(m, "build", "a", "A", null);
        service.register(m, "build", "b", "B", null);

        service.complete("build", "a", "A fertig", Map.of());
        await(() -> service.list().size() == 1);
        assertThat(memories.details(m)).isPresent();

        service.complete("build", "b", "B fertig", Map.of());
        await(() -> service.list().isEmpty());
        assertThat(memories.details(m)).isEmpty();
    }

    @Test
    void expiredInvocationFiresWithoutResult() throws Exception {
        long m = invocationMemory("Warte", "Nachfassen.");
        List<ChannelEvents.Event> s = session();
        service.register(m, "share", "answer:1", "Antwort von anna", Duration.ofMillis(-1));

        service.expire();

        await(() -> !s.isEmpty());
        assertThat(s.getFirst().content()).contains("Keine Rückmeldung bis", "Antwort von anna", "Nachfassen.");
        assertThat(s.getFirst().meta()).containsEntry("kind", "expired");
    }

    @Test
    void cancelRemovesInvocationAndMemory() {
        long m = invocationMemory("x", "y");
        InvocationService.Invocation i = service.register(m, "build", "a", "A", null);

        assertThat(service.cancel(i.id())).isPresent();
        assertThat(service.list()).isEmpty();
        assertThat(memories.details(m)).isEmpty();
        assertThat(service.cancel("gibtsnicht")).isEmpty();
    }

    @Test
    void onlyInvocationMemoriesCanBeRegistered() {
        long temp = id(memories.save("t", "c", MemoryViews.Type.TEMPORARY, null, null, null, null, 1_000));

        assertThatThrownBy(() -> service.register(temp, "build", "a", "A", null))
                .hasMessageContaining("type=INVOCATION");
        assertThatThrownBy(() -> service.register(4711, "build", "a", "A", null))
                .hasMessageContaining("gibt es nicht");
        // Rückruf-Memories sind ohne Freigabe änderbar und löschbar
        long inv = invocationMemory("r", "s");
        assertThat(memories.update(inv, null, null, "Nachtrag", null, null, null, null, null, true, 1_000))
                .contains("aktualisiert");
        assertThat(memories.search(null, null, null, null, MemoryViews.Type.INVOCATION, null, null))
                .contains("#" + inv).doesNotContain("#" + temp);
        assertThat(memories.search(null, null, null, null, MemoryViews.Type.PERMANENT, null, null))
                .doesNotContain("#" + inv);
        assertThat(memories.delete(inv, true)).contains("gelöscht");
    }

    private long invocationMemory(String title, String content) {
        return id(memories.save(title, content, MemoryViews.Type.INVOCATION, null, null, null, null, 1_000));
    }

    private List<ChannelEvents.Event> session() {
        List<ChannelEvents.Event> s = new CopyOnWriteArrayList<>();
        channel.subscribe(s::add, -1);
        return s;
    }

    private static long id(String saved) {
        return Long.parseLong(saved.replaceAll("(?s)^Memory #(\\d+).*", "$1"));
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Bedingung nicht in 10 s erfüllt");
            }
            Thread.sleep(25);
        }
    }
}
