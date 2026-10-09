package systems.grebe.devtools.mcp.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Reihenfolge, lückenloses Nachholen und Vergessen von Ereignissen. */
class ChannelEventsTest {

    private static final Map<String, String> NO_META = Map.of();

    @Test
    void concurrentPublishersReachTheListenerInIdOrder() throws Exception {
        ChannelEvents channel = new ChannelEvents();
        List<Long> seen = new ArrayList<>();
        AtomicLong last = new AtomicLong();
        AtomicLong outOfOrder = new AtomicLong();
        channel.subscribe(e -> {
            // wie die Brücke: ein Ereignis mit kleinerer ID als das zuletzt gesendete würde verworfen
            if (e.id() <= last.get()) {
                outOfOrder.incrementAndGet();
            }
            last.set(e.id());
            Thread.yield();
            seen.add(e.id());
        }, -1);

        int threads = 8;
        int each = 100;
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> publishers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            Thread p = Thread.ofPlatform().start(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    return;
                }
                for (int i = 0; i < each; i++) {
                    ChannelEvents.Delivery d = channel.deliver("test", "x", NO_META);
                    assertThat(d.delivered()).isEqualTo(1);
                }
            });
            publishers.add(p);
        }
        start.countDown();
        for (Thread p : publishers) {
            p.join(30_000);
        }

        assertThat(outOfOrder.get()).isZero();
        assertThat(seen).hasSize(threads * each).isSorted().doesNotHaveDuplicates();
    }

    @Test
    void subscribeReplaysWhatWasMissedWithoutGapsOrDuplicatesWhilePublishing() throws Exception {
        ChannelEvents channel = new ChannelEvents();
        for (int i = 0; i < 20; i++) {
            channel.publish("test", "alt", NO_META);
        }
        AtomicBoolean running = new AtomicBoolean(true);
        Thread publisher = Thread.ofPlatform().start(() -> {
            while (running.get()) {
                channel.publish("test", "live", NO_META);
                LockSupport.parkNanos(100_000);
            }
        });
        List<Long> seen = new ArrayList<>();
        AtomicLong afterId = new AtomicLong();
        try {
            Thread.sleep(20);
            long after = channel.since(0).getLast().id() - 5;
            channel.subscribe(e -> seen.add(e.id()), after);
            afterId.set(after);
            Thread.sleep(50);
        } finally {
            running.set(false);
            publisher.join(10_000);
        }
        channel.deliver("test", "ende", NO_META); // sorgt dafür, dass der Listener bis zum Ende alles hat

        assertThat(seen).isNotEmpty();
        assertThat(seen.getFirst()).isEqualTo(afterId.get() + 1);
        for (int i = 1; i < seen.size(); i++) {
            assertThat(seen.get(i)).isEqualTo(seen.get(i - 1) + 1);
        }
    }

    @Test
    void failingListenerDuringReplayIsRemovedAndTheExceptionPropagates() {
        ChannelEvents channel = new ChannelEvents();
        channel.publish("test", "a", NO_META);
        assertThatThrownBy(() -> channel.subscribe(e -> {
            throw new IllegalStateException("Verbindung weg");
        }, 0)).isInstanceOf(IllegalStateException.class).hasMessageContaining("Verbindung weg");
        assertThat(channel.subscribers()).isZero();
        assertThat(channel.deliver("test", "b", NO_META).listeners()).isZero();
    }

    @Test
    void forgottenEventIsNotReplayed() {
        ChannelEvents channel = new ChannelEvents();
        long first = channel.publish("test", "a", NO_META);
        long second = channel.publish("test", "b", NO_META);
        channel.forget(first);
        assertThat(channel.since(0)).extracting(ChannelEvents.Event::id).containsExactly(second);
        List<Long> seen = new ArrayList<>();
        channel.subscribe(e -> seen.add(e.id()), 0);
        assertThat(seen).containsExactly(second);
    }
}
