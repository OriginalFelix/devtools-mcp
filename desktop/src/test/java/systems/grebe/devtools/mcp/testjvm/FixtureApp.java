package systems.grebe.devtools.mcp.testjvm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ziel-JVM für die Diagnose-Tests. Wird als eigener Prozess gestartet.
 * Erzeugt CPU-Last, ein Speicherleck, einen Deadlock und bietet eine Methode für Breakpoints.
 */
public final class FixtureApp {

    /** Leck: wächst nicht, aber hält 30 MB fest. */
    static final Map<String, byte[]> CACHE = new HashMap<>();
    static volatile double sink;
    static final Object LOCK_A = new Object();
    static final Object LOCK_B = new Object();

    private FixtureApp() {
    }

    public static void main(String[] args) throws Exception {
        for (int i = 0; i < 1500; i++) {
            CACHE.put("eintrag-" + i, new byte[20_000]);
        }
        Thread burner = new Thread(FixtureApp::burn, "fixture-burner");
        burner.setDaemon(true);
        burner.start();
        Thread ticker = new Thread(FixtureApp::tick, "fixture-ticker");
        ticker.setDaemon(true);
        ticker.start();
        if (List.of(args).contains("deadlock")) {
            startDeadlock();
        }
        System.out.println("FIXTURE READY");
        System.out.flush();
        Thread.sleep(Long.MAX_VALUE);
    }

    static void burn() {
        while (true) {
            sink += hotMethod(50_000);
            List<String> garbage = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                garbage.add("x" + i);
            }
            sink += garbage.size();
            byte[] chunk = new byte[256 * 1024]; // sorgt zuverlässig für GC-Ereignisse
            sink += chunk.length;
        }
    }

    static double hotMethod(int n) {
        double s = 0;
        for (int i = 1; i < n; i++) {
            s += Math.sqrt(i) * Math.log(i);
        }
        return s;
    }

    /** Ziel für Debugger-Breakpoints (Zeile mit "int counter"). */
    static void tick() {
        int round = 0;
        while (true) {
            round++;
            int counter = round * 2;
            String label = "runde-" + round;
            sink += counter + label.length();
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    static void startDeadlock() {
        Thread a = new Thread(() -> {
            synchronized (LOCK_A) {
                pause();
                synchronized (LOCK_B) {
                    sink++;
                }
            }
        }, "fixture-deadlock-a");
        Thread b = new Thread(() -> {
            synchronized (LOCK_B) {
                pause();
                synchronized (LOCK_A) {
                    sink++;
                }
            }
        }, "fixture-deadlock-b");
        a.setDaemon(true);
        b.setDaemon(true);
        a.start();
        b.start();
    }

    private static void pause() {
        try {
            Thread.sleep(300);
        } catch (InterruptedException ignored) {
            // egal
        }
    }
}
