package systems.grebe.devtools.mcp.modules.chat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Predicate;

import systems.grebe.devtools.mcp.modules.chat.spi.ChatSystem;

/**
 * Eingang eines Kontos: holt über {@link ChatSystem#poll} neue Nachrichten und hält sie bereit, bis ein Tool sie
 * abholt.
 *
 * <p>Es gibt keinen Hintergrund-Thread. Der Abrufstand bleibt zwischen den Aufrufen erhalten (auch über einen Neustart,
 * siehe {@link ChatState}); was zwischen zwei Tool-Aufrufen eintrifft, liefert der nächste Abruf. Nachrichten, die ein
 * Aufruf mitholt, aber nicht braucht (z.B. eine andere Unterhaltung, während {@code chat_ask} auf eine Antwort wartet),
 * bleiben hier liegen, bis {@code chat_receive} sie abholt.
 *
 * <p>Eigene Nachrichten (vom System erkannt oder unter den zuletzt gesendeten IDs) kommen nicht in den Eingang, merken
 * sich aber ihre Position – Antworten auf eine Frage stehen dahinter. Nachrichten nicht freigegebener Absender werden
 * nur gezählt.
 *
 * <p>Immer nur ein Abruf je Konto gleichzeitig ({@link #syncLock}); wartende Aufrufe teilen sich die Ergebnisse.
 */
final class ChatInbox {

    static final int MAX_PENDING = 500;
    /** Längster einzelner Abruf, wenn nur ein Konto abgefragt wird. */
    static final int MAX_POLL_MILLIS = 30_000;
    /** … bei mehreren Konten, damit keins das andere zu lange blockiert. */
    static final int MULTI_POLL_MILLIS = 5_000;

    /** Nachricht mit ihrer Position im Eingang. */
    record Received(long seq, ChatSystem.Message message, ChatInbox inbox) {
    }

    private final ChatSystem system;
    private final String account;
    private final ChatState state;
    private final ReentrantLock syncLock = new ReentrantLock(true);

    // ------------------------------------------------------------------ geschützt durch this
    private String cursor;
    private long seq;
    private final List<Received> pending = new ArrayList<>();
    /** Eigene Nachrichten → Position im Eingang. */
    private final Map<String, Long> ownMessages = bounded(1000);
    /** Bekannte Nachrichten → Unterhaltung, damit Antworten und Reaktionen ohne Angabe auskommen. */
    private final Map<String, String> conversations = bounded(5000);
    private final List<String> notices = new ArrayList<>();
    private int ignored;
    private int dropped;

    ChatInbox(ChatSystem system, String account, ChatState state) {
        this.system = system;
        this.account = account;
        this.state = state;
        this.cursor = state.cursor(account);
    }

    ChatSystem system() {
        return system;
    }

    // ------------------------------------------------------------------ Abrufen

    /** Holt, was seit dem letzten Abruf eingegangen ist, ohne zu warten. */
    void catchUp() {
        syncLock.lock();
        try {
            poll(0);
        } finally {
            syncLock.unlock();
        }
    }

    /**
     * Wartet, bis {@code take} in einem der Eingänge etwas findet oder {@code waitMillis} verstrichen sind. {@code take}
     * läuft unter der Sperre des jeweiligen Eingangs und entnimmt die passenden Nachrichten.
     *
     * @param onWait wird zwischen zwei Abrufrunden aufgerufen (Fortschrittsmeldung)
     */
    static List<Received> await(List<ChatInbox> inboxes, Function<ChatInbox, List<Received>> take, long waitMillis,
                                Runnable onWait) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, waitMillis));
        inboxes.forEach(ChatInbox::catchUp);
        // Systeme, die nicht warten dürfen, wurden eben einmal abgerufen – danach nur noch die übrigen
        List<ChatInbox> waiting = inboxes.stream().filter(i -> i.system.canWait()).toList();
        long slice = waiting.size() == 1 ? MAX_POLL_MILLIS : MULTI_POLL_MILLIS;
        while (true) {
            List<Received> found = new ArrayList<>();
            long[] seen = new long[inboxes.size()];
            for (int i = 0; i < inboxes.size(); i++) {
                ChatInbox inbox = inboxes.get(i);
                synchronized (inbox) {
                    found.addAll(take.apply(inbox));
                    seen[i] = inbox.seq;
                }
            }
            if (!found.isEmpty() || waiting.isEmpty() || System.nanoTime() >= deadline) {
                return found;
            }
            for (int i = 0; i < inboxes.size(); i++) {
                if (inboxes.get(i).system.canWait()) {
                    inboxes.get(i).pollIfIdle(seen[i], deadline, slice);
                }
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("Abgebrochen");
            }
            onWait.run();
        }
    }

    /**
     * Ein Abruf, sobald kein anderer läuft – außer ein anderer Aufruf hat seit {@code seen} schon abgerufen (dann erst
     * dessen Ergebnis prüfen).
     */
    private void pollIfIdle(long seen, long deadline, long slice) {
        long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (remaining <= 0) {
            return;
        }
        boolean locked;
        try {
            locked = syncLock.tryLock(remaining, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Abgebrochen", e);
        }
        if (!locked) {
            return;
        }
        try {
            synchronized (this) {
                if (seq != seen) {
                    return;
                }
            }
            long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (left > 0) {
                poll((int) Math.min(left, slice));
            }
        } finally {
            syncLock.unlock();
        }
    }

    /** Ein Abruf; nur mit {@link #syncLock}. Die Anfrage läuft ohne die Sperre des Eingangs. */
    private void poll(int timeoutMs) {
        String from;
        synchronized (this) {
            from = cursor;
        }
        ChatSystem.Poll res = system.poll(from, timeoutMs);
        synchronized (this) {
            for (ChatSystem.Message m : res.messages()) {
                long position = ++seq;
                conversations.put(m.id(), m.conversationId());
                if (m.own() || state.sent(account, m.id())) {
                    ownMessages.put(m.id(), position);
                } else if (m.trusted()) {
                    pending.add(new Received(position, m, this));
                } else {
                    ignored++;
                }
            }
            notices.addAll(res.notices());
            if (res.cursor() != null && !res.cursor().isBlank()) {
                cursor = res.cursor();
            }
            seq++; // auch ein leerer Abruf zählt – Wartende sehen so, dass abgerufen wurde
            while (pending.size() > MAX_PENDING) {
                pending.removeFirst();
                dropped++;
            }
        }
        state.cursor(account, res.cursor());
    }

    // ------------------------------------------------------------------ Gesendet

    /**
     * Merkt sich eine selbst gesendete Nachricht. Kam sie über einen parallelen Abruf schon herein, bevor ihre ID
     * bekannt war, wird sie aus dem Eingang genommen und als eigene gezählt.
     */
    synchronized void sent(String conversationId, String messageId) {
        state.addSent(account, messageId);
        conversations.put(messageId, conversationId);
        var it = pending.iterator();
        while (it.hasNext()) {
            Received r = it.next();
            if (r.message().id().equals(messageId)) {
                it.remove();
                ownMessages.put(messageId, r.seq());
            }
        }
    }

    /** Ob die Nachricht vom Modul selbst gesendet wurde. */
    boolean own(ChatSystem.Message m) {
        return m.own() || state.sent(account, m.id());
    }

    /** Merkt sich die Unterhaltung einer Nachricht (z.B. aus dem Verlauf) für spätere Antworten und Reaktionen. */
    synchronized void noteMessage(String conversationId, String messageId) {
        conversations.put(messageId, conversationId);
    }

    // ------------------------------------------------------------------ Entnehmen (nur unter der Sperre, aus await)

    /** Entnimmt bis zu {@code max} passende Nachrichten in Eingangsreihenfolge. */
    List<Received> take(Predicate<ChatSystem.Message> filter, int max) {
        return takeReceived(r -> filter.test(r.message()), max);
    }

    private List<Received> takeReceived(Predicate<Received> filter, int max) {
        assert Thread.holdsLock(this);
        List<Received> out = new ArrayList<>();
        var it = pending.iterator();
        while (it.hasNext() && out.size() < max) {
            Received r = it.next();
            if (filter.test(r)) {
                out.add(r);
                it.remove();
            }
        }
        return out;
    }

    /**
     * Antwort auf eine eigene Nachricht: bevorzugt eine ausdrückliche Antwort (Reply/Thread), sonst die erste Nachricht
     * der Unterhaltung nach der Frage; dazu weitere Nachrichten desselben Absenders, die direkt danach kamen.
     */
    List<Received> takeAnswer(String conversationId, String questionId) {
        assert Thread.holdsLock(this);
        Received answer = pending.stream()
                .filter(r -> r.message().conversationId().equals(conversationId) && r.message().answers(questionId))
                .findFirst().orElse(null);
        Long marker = ownMessages.get(questionId);
        if (answer == null && marker != null) {
            answer = pending.stream()
                    .filter(r -> r.message().conversationId().equals(conversationId) && r.seq() > marker)
                    .findFirst().orElse(null);
        }
        if (answer == null) {
            return List.of();
        }
        Received first = answer;
        pending.remove(first);
        List<Received> out = new ArrayList<>(List.of(first));
        out.addAll(takeReceived(r -> r.seq() > first.seq() && r.message().conversationId().equals(conversationId)
                && r.message().sender().equals(first.message().sender()), Integer.MAX_VALUE));
        return out;
    }

    // ------------------------------------------------------------------ Zustand

    synchronized int pendingCount(Predicate<ChatSystem.Message> filter) {
        return (int) pending.stream().map(Received::message).filter(filter).count();
    }

    /** Hinweise seit dem letzten Aufruf (Einladungen, Lücken, ignorierte Absender). */
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

    /** Unterhaltung einer bekannten Nachricht oder {@code null}. */
    synchronized String conversationOf(String messageId) {
        return conversations.get(messageId);
    }

    private static <K, V> Map<K, V> bounded(int max) {
        return new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > max;
            }
        };
    }
}
