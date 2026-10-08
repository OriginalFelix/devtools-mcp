package systems.grebe.devtools.mcp.core;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BoundedMapTest {

    @Test
    void lruDropsTheLeastRecentlyUsed() {
        Map<String, Integer> m = BoundedMap.lru(2);
        m.put("a", 1);
        m.put("b", 2);
        m.get("a"); // a ist jetzt jünger als b
        m.put("c", 3);
        assertThat(m).containsOnlyKeys("a", "c");
    }

    @Test
    void fifoDropsTheOldestInsertedRegardlessOfAccess() {
        Map<String, Integer> m = BoundedMap.fifo(2);
        m.put("a", 1);
        m.put("b", 2);
        m.get("a");
        m.put("c", 3);
        assertThat(m).containsOnlyKeys("b", "c");
    }

    @Test
    void fifoSetKeepsTheLastElements() {
        Set<Integer> s = BoundedMap.fifoSet(3);
        for (int i = 1; i <= 5; i++) {
            s.add(i);
        }
        assertThat(s).containsExactly(3, 4, 5);
    }
}
