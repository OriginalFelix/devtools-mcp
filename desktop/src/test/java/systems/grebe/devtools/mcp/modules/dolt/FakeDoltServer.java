package systems.grebe.devtools.mcp.modules.dolt;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/** Datenbank im Speicher für Tests: Branches mit Startpunkt, Standard-Branch, Zähler und abschaltbarer Server. */
final class FakeDoltServer implements DoltBranches.Backends {

    final TreeMap<String, String> branches = new TreeMap<>(); // Branch → Startpunkt
    volatile String defaultBranch = "main";
    volatile boolean running = true;
    volatile boolean canSetDefault = true;
    final List<String> log = new ArrayList<>();
    volatile int opened;

    FakeDoltServer() {
        branches.put("main", "");
    }

    @Override
    public synchronized DoltBackend open(DoltDatabase db, DoltBranches.Settings settings) {
        if (!running) {
            throw new IllegalStateException("Datenbank '" + db.name() + "': Connection refused [SQLState 08S01]");
        }
        opened++;
        return new DoltBackend() {
            @Override
            public String version() {
                return "Fake 1.0";
            }

            @Override
            public String defaultBranch() {
                return branches.containsKey(defaultBranch) ? defaultBranch : null;
            }

            @Override
            public List<String> branches() {
                synchronized (FakeDoltServer.this) {
                    return List.copyOf(branches.keySet());
                }
            }

            @Override
            public void create(String branch, String from) {
                synchronized (FakeDoltServer.this) {
                    if (!branches.containsKey(from)) {
                        throw new IllegalStateException("branch not found: " + from);
                    }
                    branches.put(branch, from);
                    log.add("create " + branch + " from " + from);
                }
            }

            @Override
            public boolean setDefault(String branch) {
                if (!canSetDefault) {
                    return false;
                }
                synchronized (FakeDoltServer.this) {
                    defaultBranch = branch;
                    log.add("default " + branch);
                }
                return true;
            }

            @Override
            public String connectHint(String branch) {
                return "fake/app/" + branch;
            }

            @Override
            public void close() {
            }
        };
    }
}
