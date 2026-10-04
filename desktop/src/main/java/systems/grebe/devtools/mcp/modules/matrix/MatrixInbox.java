package systems.grebe.devtools.mcp.modules.matrix;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import java.util.function.Supplier;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Eingang eines Kontos: holt neue Ereignisse über {@code /sync} und hält Nachrichten bereit, bis ein Tool sie abholt.
 *
 * <p>Es gibt keinen Hintergrund-Thread. Der Sync-Stand ({@code next_batch}) bleibt zwischen den Aufrufen erhalten (auch
 * über einen Neustart, siehe {@link MatrixSyncTokens}); was zwischen zwei Tool-Aufrufen eintrifft, puffert der
 * Homeserver. Nachrichten, die ein Aufruf mitholt, aber nicht braucht (z.B. ein anderer Raum, während
 * {@code matrix_ask} auf eine Antwort wartet), bleiben hier liegen, bis {@code matrix_receive} sie abholt.
 *
 * <p>Beim allerersten Abruf eines Kontos gelten die Nachrichten als neu, die der Server als ungelesen zählt
 * ({@code unread_notifications}, gemessen an der Lesebestätigung des Kontos) – ältere stehen in {@code matrix_history}.
 *
 * <p>Immer nur ein {@code /sync} gleichzeitig ({@link #syncLock}); wartende Aufrufe teilen sich die Ergebnisse.
 */
final class MatrixInbox {

    static final int INITIAL_LIMIT = 20;
    static final int TIMELINE_LIMIT = 50;
    static final int MAX_PENDING = 500;
    static final int MAX_POLL_MILLIS = 30_000;

    /** Was zugestellt wird: freigegebene Räume und Absender, wessen Einladungen angenommen werden. */
    interface Policy {
        boolean roomAllowed(String roomId);

        boolean trusted(String sender);

        boolean autoJoin(String inviter, String roomId);
    }

    /** Offene Einladung in einen Raum. */
    record Invite(String roomId, String inviter, String name) {
    }

    private static final String FILTER = filter();

    private final MatrixClient client;
    private final String userId;
    private final String account;
    private final MatrixSyncTokens tokens;
    private final ReentrantLock syncLock = new ReentrantLock(true);

    // ------------------------------------------------------------------ geschützt durch this
    private String since;
    private long seq;
    private final List<MatrixMessage> pending = new ArrayList<>();
    /** Eigene Ereignisse → Position im Eingang (Antworten auf eine Frage stehen dahinter). */
    private final Map<String, Long> ownEvents = bounded(1000);
    /** Bekannte Ereignisse → Raum, damit Antworten und Reaktionen ohne Raumangabe auskommen. */
    private final Map<String, String> eventRooms = bounded(5000);
    private final Map<String, String> roomNames = new HashMap<>();
    private final Map<String, String> roomAliases = new HashMap<>();
    private final Set<String> encryptedRooms = new HashSet<>();
    /** Räume, deren Name/Alias/Verschlüsselung abgefragt wurden (der Sync liefert nur gesetzte Werte). */
    private final Set<String> described = new HashSet<>();
    private final Map<String, Invite> invites = new LinkedHashMap<>();
    private final List<String> notices = new ArrayList<>();
    private int ignored;
    private int dropped;

    MatrixInbox(MatrixClient client, String userId, String account, MatrixSyncTokens tokens) {
        this.client = client;
        this.userId = userId;
        this.account = account;
        this.tokens = tokens;
        this.since = tokens.get(account);
    }

    String userId() {
        return userId;
    }

    // ------------------------------------------------------------------ Abrufen

    /** Holt, was seit dem letzten Abruf eingegangen ist, ohne zu warten. */
    void catchUp(Policy policy) {
        syncLock.lock();
        try {
            poll(policy, 0);
        } finally {
            syncLock.unlock();
        }
    }

    /**
     * Wartet, bis {@code take} etwas liefert oder {@code waitMillis} verstrichen sind. {@code take} läuft unter der
     * Sperre des Eingangs und entnimmt die passenden Nachrichten.
     *
     * @param onWait wird zwischen zwei Abrufen aufgerufen (Fortschrittsmeldung)
     */
    List<MatrixMessage> await(Policy policy, Supplier<List<MatrixMessage>> take, long waitMillis, Runnable onWait) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, waitMillis));
        catchUp(policy);
        while (true) {
            List<MatrixMessage> found;
            long seen;
            synchronized (this) {
                found = take.get();
                seen = seq;
            }
            long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (!found.isEmpty() || remaining <= 0) {
                return found;
            }
            boolean locked;
            try {
                locked = syncLock.tryLock(remaining, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Abgebrochen", e);
            }
            if (locked) {
                try {
                    // Hat ein anderer Aufruf inzwischen abgerufen, erst dessen Ergebnis prüfen
                    boolean fresh;
                    synchronized (this) {
                        fresh = seq != seen;
                    }
                    if (!fresh) {
                        long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                        poll(policy, (int) Math.max(0, Math.min(left, MAX_POLL_MILLIS)));
                    }
                } finally {
                    syncLock.unlock();
                }
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("Abgebrochen");
            }
            onWait.run();
        }
    }

    /** Ein {@code /sync}; nur mit {@link #syncLock}. Die HTTP-Anfrage läuft ohne die Sperre des Eingangs. */
    private void poll(Policy policy, int timeoutMs) {
        String from;
        synchronized (this) {
            from = since;
        }
        JsonNode res = client.sync(from, from == null ? initialFilter() : FILTER, from == null ? 0 : timeoutMs);
        List<Invite> toJoin = new ArrayList<>();
        synchronized (this) {
            process(res, from == null, policy, toJoin);
            since = res.path("next_batch").asString(since);
            seq++; // auch ein leerer Abruf zählt – Wartende sehen so, dass abgerufen wurde
        }
        tokens.put(account, res.path("next_batch").asString(null));
        for (Invite inv : toJoin) {
            String note;
            try {
                client.join(inv.roomId());
                note = "Einladung von " + inv.inviter() + " in " + label(inv) + " angenommen.";
                synchronized (this) {
                    invites.remove(inv.roomId());
                }
            } catch (RuntimeException e) {
                note = "Einladung von " + inv.inviter() + " in " + label(inv) + " nicht angenommen: " + e.getMessage();
            }
            synchronized (this) {
                notices.add(note);
            }
        }
    }

    private void process(JsonNode res, boolean initial, Policy policy, List<Invite> toJoin) {
        JsonNode rooms = res.path("rooms");
        for (var entry : rooms.path("join").properties()) {
            String roomId = entry.getKey();
            JsonNode room = entry.getValue();
            invites.remove(roomId);
            readState(roomId, room.path("state").path("events"));
            if (!policy.roomAllowed(roomId)) {
                continue;
            }
            JsonNode timeline = room.path("timeline");
            if (!initial && timeline.path("limited").asBoolean(false)) {
                notices.add("In " + roomLabel(roomId) + " kamen mehr Nachrichten als ein Abruf fasst – ältere fehlen hier, "
                        + "siehe matrix_history.");
            }
            List<MatrixMessage> fresh = new ArrayList<>();
            for (JsonNode event : timeline.path("events")) {
                long position = ++seq;
                String eventId = event.path("event_id").asString("");
                eventRooms.put(eventId, roomId);
                if (userId.equals(event.path("sender").asString())) {
                    ownEvents.put(eventId, position);
                    continue;
                }
                MatrixMessage m = MatrixMessage.of(position, roomId, event);
                if (m != null) {
                    fresh.add(m);
                }
            }
            if (initial) {
                int unread = room.path("unread_notifications").path("notification_count").asInt(0);
                fresh = fresh.subList(Math.max(0, fresh.size() - unread), fresh.size());
            }
            for (MatrixMessage m : fresh) {
                if (policy.trusted(m.sender())) {
                    pending.add(m);
                } else {
                    ignored++;
                }
            }
        }
        for (var entry : rooms.path("invite").properties()) {
            String roomId = entry.getKey();
            String inviter = null;
            String name = null;
            for (JsonNode e : entry.getValue().path("invite_state").path("events")) {
                String type = e.path("type").asString("");
                if ("m.room.member".equals(type) && userId.equals(e.path("state_key").asString())) {
                    inviter = e.path("sender").asString(null);
                } else if ("m.room.name".equals(type)) {
                    name = e.path("content").path("name").asString(null);
                }
            }
            Invite inv = new Invite(roomId, inviter == null ? "?" : inviter, name);
            if (inviter != null && policy.autoJoin(inviter, roomId)) {
                toJoin.add(inv);
            } else if (!invites.containsKey(roomId)) {
                notices.add("Einladung von " + inv.inviter() + " in " + label(inv) + " (nicht automatisch angenommen).");
            }
            invites.put(roomId, inv);
        }
        for (var entry : rooms.path("leave").properties()) {
            invites.remove(entry.getKey());
        }
        while (pending.size() > MAX_PENDING) {
            pending.removeFirst();
            dropped++;
        }
    }

    private void readState(String roomId, JsonNode events) {
        for (JsonNode e : events) {
            JsonNode content = e.path("content");
            switch (e.path("type").asString("")) {
                case "m.room.name" -> put(roomNames, roomId, content.path("name").asString(""));
                case "m.room.canonical_alias" -> put(roomAliases, roomId, content.path("alias").asString(""));
                case "m.room.encryption" -> encryptedRooms.add(roomId);
                default -> { }
            }
        }
    }

    // ------------------------------------------------------------------ Entnehmen (nur unter der Sperre, aus await)

    /** Entnimmt bis zu {@code max} passende Nachrichten in Eingangsreihenfolge. */
    List<MatrixMessage> take(Predicate<MatrixMessage> filter, int max) {
        assert Thread.holdsLock(this);
        List<MatrixMessage> out = new ArrayList<>();
        var it = pending.iterator();
        while (it.hasNext() && out.size() < max) {
            MatrixMessage m = it.next();
            if (filter.test(m)) {
                out.add(m);
                it.remove();
            }
        }
        return out;
    }

    /**
     * Antwort auf eine eigene Nachricht: bevorzugt eine ausdrückliche Antwort (Reply/Thread), sonst die erste Nachricht
     * im Raum nach der Frage; dazu weitere Nachrichten desselben Absenders, die direkt danach kamen.
     */
    List<MatrixMessage> takeAnswer(String roomId, String questionId) {
        assert Thread.holdsLock(this);
        MatrixMessage answer = pending.stream()
                .filter(m -> m.roomId().equals(roomId) && m.answers(questionId)).findFirst().orElse(null);
        Long marker = ownEvents.get(questionId);
        if (answer == null && marker != null) {
            answer = pending.stream().filter(m -> m.roomId().equals(roomId) && m.seq() > marker).findFirst().orElse(null);
        }
        if (answer == null) {
            return List.of();
        }
        MatrixMessage first = answer;
        pending.remove(first);
        List<MatrixMessage> out = new ArrayList<>(List.of(first));
        out.addAll(take(m -> m.roomId().equals(roomId) && m.sender().equals(first.sender()) && m.seq() > first.seq(),
                Integer.MAX_VALUE));
        return out;
    }

    // ------------------------------------------------------------------ Zustand

    synchronized int pendingCount(Predicate<MatrixMessage> filter) {
        return (int) pending.stream().filter(filter).count();
    }

    /** Hinweise seit dem letzten Aufruf (angenommene Einladungen, Lücken, ausgeblendete Absender). */
    synchronized List<String> drainNotices() {
        List<String> out = new ArrayList<>(notices);
        notices.clear();
        if (ignored > 0) {
            out.add(ignored + " Nachricht(en) von nicht freigegebenen Absendern ignoriert.");
            ignored = 0;
        }
        if (dropped > 0) {
            out.add(dropped + " ältere ungelesene Nachricht(en) verworfen (Eingang voll).");
            dropped = 0;
        }
        return out;
    }

    synchronized List<Invite> invites() {
        return List.copyOf(invites.values());
    }

    /** Merkt sich den Raum eines Ereignisses (gesendet oder im Verlauf gesehen) für spätere Antworten/Reaktionen. */
    synchronized void noteEvent(String roomId, String eventId) {
        eventRooms.put(eventId, roomId);
    }

    /** Raum eines bekannten Ereignisses oder {@code null}. */
    synchronized String roomOf(String eventId) {
        return eventRooms.get(eventId);
    }

    synchronized void remember(String roomId, String name, String alias, boolean encrypted) {
        described.add(roomId);
        put(roomNames, roomId, name);
        put(roomAliases, roomId, alias);
        if (encrypted) {
            encryptedRooms.add(roomId);
        }
    }

    synchronized boolean described(String roomId) {
        return described.contains(roomId);
    }

    synchronized boolean encrypted(String roomId) {
        return encryptedRooms.contains(roomId);
    }

    /** Anzeigename: „Name“ (#alias) bzw. die Raum-ID, wenn nichts bekannt ist. */
    synchronized String roomLabel(String roomId) {
        String name = roomNames.get(roomId);
        String alias = roomAliases.get(roomId);
        if (name != null) {
            return "„" + name + "“" + (alias != null ? " (" + alias + ")" : "");
        }
        return alias != null ? alias : roomId;
    }

    /** Raumnamen und Aliase, soweit aus dem Sync bekannt. */
    synchronized Map<String, String> knownNames() {
        Map<String, String> out = new LinkedHashMap<>(roomNames);
        roomAliases.forEach(out::putIfAbsent);
        return out;
    }

    private String label(Invite inv) {
        return inv.name() == null ? inv.roomId() : "„" + inv.name() + "“ (" + inv.roomId() + ")";
    }

    private static void put(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }

    private static <K, V> Map<K, V> bounded(int max) {
        return new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > max;
            }
        };
    }

    // ------------------------------------------------------------------ Filter

    /** Nur Nachrichten (auch verschlüsselte, um darauf hinzuweisen), Name/Alias/Verschlüsselung; keine Presence u.ä. */
    private static String filter() {
        return filter(TIMELINE_LIMIT);
    }

    private static String initialFilter() {
        return filter(INITIAL_LIMIT);
    }

    static String filter(int timelineLimit) {
        ObjectNode f = MatrixClient.JSON.createObjectNode();
        f.putObject("presence").putArray("not_types").add("*");
        f.putObject("account_data").putArray("not_types").add("*");
        ObjectNode room = f.putObject("room");
        room.putObject("account_data").putArray("not_types").add("*");
        room.putObject("ephemeral").putArray("not_types").add("*");
        ObjectNode state = room.putObject("state");
        ArrayNode stateTypes = state.putArray("types");
        stateTypes.add("m.room.name").add("m.room.canonical_alias").add("m.room.encryption");
        state.put("lazy_load_members", true);
        ObjectNode timeline = room.putObject("timeline");
        timeline.put("limit", timelineLimit);
        timeline.putArray("types").add("m.room.message").add("m.room.encrypted");
        return MatrixClient.JSON.writeValueAsString(f);
    }
}
