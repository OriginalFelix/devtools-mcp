package systems.grebe.devtools.mcp.modules.mail;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import jakarta.annotation.PreDestroy;
import jakarta.mail.FetchProfile;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Store;
import jakarta.mail.UIDFolder;
import jakarta.mail.internet.MimeMessage;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.IMAPStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.config.SettingsStore;
import systems.grebe.devtools.mcp.core.ChannelEvents;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolRegistry;
import systems.grebe.devtools.mcp.core.BoundedMap;

/**
 * Überwacht die eingetragenen Ordner im Hintergrund und meldet neue Mails – sofort per IMAP IDLE, wo der Server es
 * kann, sonst durch regelmäßiges Abfragen. Jeder überwachte Ordner hat eine eigene Verbindung und einen eigenen Thread
 * (IDLE blockiert die Verbindung).
 *
 * <p>Eine neue Mail geht an
 * <ul>
 *   <li>den Eingang für {@code mail_receive} (immer),</li>
 *   <li>die {@link ChannelEvents} – der stdio-Proxy reicht sie an eine laufende Claude-Code-Sitzung weiter,</li>
 *   <li>den „Befehl bei neuer E-Mail“ ({@link MailCommand}), z.B. {@code claude -p …}.</li>
 * </ul>
 * Die letzten beiden nur für freigegebene Absender und – Standard – nur ungelesene Mails.
 *
 * <p>Neu ist, was eine höhere UID hat als die zuletzt gemeldete ({@link MailState}, übersteht Neustarts). Beim ersten
 * Überwachen eines Ordners gilt der Bestand als bekannt; ändert der Server die UIDVALIDITY, beginnt der Ordner neu.
 */
@Component
public class MailWatcher implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(MailWatcher.class);

    static final String SOURCE = "mail";
    static final int MAX_PENDING = 500;
    /** Mehr neue Mails auf einmal (z.B. nach langer Pause) werden nur zusammengefasst gemeldet. */
    static final int MAX_NOTIFY = 20;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);
    private static final long MIN_BACKOFF = 5_000;
    private static final long MAX_BACKOFF = 5 * 60_000;

    /** Eine neu eingegangene Mail. */
    record NewMail(String account, String folder, long uid, String from, String fromAddress, String subject,
                   Date date, boolean seen, String messageId) {

        String header() {
            return account + "/" + folder + " UID " + uid + " – " + MailText.time(date) + " – von " + from
                    + " – „" + subject + "“";
        }
    }

    /** Ein überwachter Ordner. */
    record Spec(MailAccount account, String folder) {
        String label() {
            return account.name() + "/" + folder;
        }
    }

    /** Wirksame Einstellungen der Überwachung und der Meldungen. */
    record Settings(List<Spec> specs, boolean idle, int pollSeconds, int refreshMinutes, boolean onlyUnseen,
                    List<String> senders, boolean channel, MailCommand.Settings command) {

        static final Settings OFF = new Settings(List.of(), true, 60, 10, true, List.of(), false,
                new MailCommand.Settings(null, null, 0));

        static Settings of(ModuleConfig c) {
            List<Spec> specs = new ArrayList<>();
            Set<String> names = new LinkedHashSet<>();
            for (MailAccount a : MailEnvironment.accounts(c)) {
                if (!names.add(a.name().toLowerCase(Locale.ROOT))) {
                    continue; // doppelter Name: nur der erste gilt (wie bei den Tools)
                }
                for (String f : new LinkedHashSet<>(a.watch())) {
                    if (!f.endsWith("*") && a.allows(f)) {
                        specs.add(new Spec(a, MailAccount.isInbox(f) ? "INBOX" : f));
                    } else {
                        // läuft bei jeder Moduländerung – „Verbindung testen“ und mail_accounts sagen es dem Nutzer
                        LOG.debug("Mail-Konto {}: überwachter Ordner '{}' ist nicht freigegeben bzw. ein Muster – "
                                + "wird nicht überwacht", a.name(), f);
                    }
                }
            }
            String dir = c.getString(MailModule.COMMAND_DIR, "");
            return new Settings(specs, c.getBoolean(MailModule.IDLE), Math.max(10, c.getInt(MailModule.POLL_SECONDS, 60)),
                    Math.min(28, Math.max(1, c.getInt(MailModule.IDLE_REFRESH, 10))), c.getBoolean(MailModule.ONLY_UNSEEN),
                    c.getList(MailModule.NOTIFY_SENDERS), c.getBoolean(MailModule.NOTIFY_CHANNEL),
                    new MailCommand.Settings(c.getString(MailModule.COMMAND, ""), dir.isBlank() ? null : Path.of(dir),
                            c.getInt(MailModule.COMMAND_TIMEOUT, 900)));
        }

        /** Ob Meldungen (Channel, Befehl) für diesen Absender rausgehen: leer = alle. */
        boolean notifies(String address) {
            return senders.isEmpty() || matchesAddress(senders, address);
        }

        /** Was die Verbindung eines Ordners betrifft – ändert sich das, wird neu verbunden. */
        private record WatchKey(Spec spec, boolean idle, int pollSeconds, int refreshMinutes) {
        }

        private List<WatchKey> keys() {
            return specs.stream().map(s -> new WatchKey(s, idle, pollSeconds, refreshMinutes)).toList();
        }
    }

    private final ObjectProvider<ToolRegistry> registry;
    private final ChannelEvents channel;
    private final MailState state;
    private final MailOAuth oauth;
    private final MailCommand command = new MailCommand();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("mail-idle-refresh").factory());
    private final Map<Settings.WatchKey, FolderWatch> watches = new LinkedHashMap<>();
    private volatile Settings settings = Settings.OFF;
    private volatile boolean stopped;

    // ------------------------------------------------------------------ Eingang (geschützt durch inbox)
    private final List<NewMail> inbox = new ArrayList<>();
    private int dropped;

    @Autowired
    public MailWatcher(ObjectProvider<ToolRegistry> registry, ChannelEvents channel, SettingsStore store,
                       MailOAuth oauth) {
        this(registry, channel, new MailState(store.file().toAbsolutePath().getParent().resolve("mail-state.json")),
                oauth);
    }

    MailWatcher(ObjectProvider<ToolRegistry> registry, ChannelEvents channel, MailState state, MailOAuth oauth) {
        this.registry = registry;
        this.channel = channel;
        this.state = state;
        this.oauth = oauth;
        // nach einer Anmeldung sofort verbinden statt den nächsten Versuch abzuwarten
        oauth.addLoginListener(this::wake);
    }

    MailOAuth oauth() {
        return oauth;
    }

    /** Weckt wartende Überwachungen eines Kontos (nach Fehler oder fehlender Anmeldung) für einen neuen Versuch. */
    synchronized void wake(String accountName) {
        watches.values().stream().filter(w -> w.spec.account().name().equals(accountName))
                .forEach(FolderWatch::wake);
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(10) // nach dem Registrieren der Tools
    public void start() {
        ToolRegistry r = registry.getIfAvailable();
        if (r == null) {
            return;
        }
        r.addChangeListener(this::reconfigure);
        reconfigure();
    }

    /** Übernimmt Schalter und Einstellungen des Moduls (bei jeder Änderung an einem Modul aufgerufen – billig). */
    void reconfigure() {
        ToolRegistry r = registry.getIfAvailable();
        if (r == null || !r.hasModule(MailModule.ID)) {
            return;
        }
        apply(r.settings(MailModule.ID).enabled() ? Settings.of(r.config(MailModule.ID)) : Settings.OFF);
    }

    /** Setzt die überwachten Ordner; unveränderte Überwachungen laufen weiter. */
    synchronized void apply(Settings next) {
        if (stopped) {
            return;
        }
        settings = next;
        List<Settings.WatchKey> keys = next.keys();
        watches.entrySet().removeIf(e -> {
            if (!keys.contains(e.getKey())) {
                e.getValue().stop();
                return true;
            }
            return false;
        });
        for (Settings.WatchKey k : keys) {
            watches.computeIfAbsent(k, key -> new FolderWatch(key).start());
        }
    }

    @PreDestroy
    @Override
    public synchronized void close() {
        stopped = true;
        watches.values().forEach(FolderWatch::stop);
        watches.clear();
        timer.shutdownNow();
        command.close();
    }

    // ------------------------------------------------------------------ Auskunft

    /** Zustand der Überwachung eines Ordners für die Tools, {@code null} = nicht überwacht. */
    synchronized String status(MailAccount a, String folder) {
        for (FolderWatch w : watches.values()) {
            if (w.spec.account().name().equals(a.name()) && MailAccount.matches(w.spec.folder(), folder)) {
                return w.status;
            }
        }
        return null;
    }

    /** Wohin neue Mails gemeldet werden – für mail_accounts. */
    String notifications() {
        Settings s = settings;
        List<String> out = new ArrayList<>();
        out.add("mail_receive");
        if (s.channel()) {
            int n = channel.subscribers();
            out.add("Claude-Code-Channel (" + (n == 0 ? "kein stdio-Proxy verbunden" : n + " stdio-Proxy(s) verbunden") + ")");
        }
        if (s.command().active()) {
            out.add("Befehl bei neuer E-Mail" + (command.lastRun() == null ? "" : " – zuletzt " + command.lastRun()));
        }
        String filter = s.senders().isEmpty() ? "alle Absender" : "nur Absender " + s.senders();
        return String.join(", ", out) + "; Channel/Befehl: " + filter + (s.onlyUnseen() ? ", nur ungelesene" : "");
    }

    // ------------------------------------------------------------------ mail_receive

    /**
     * Entnimmt bis zu {@code limit} neue Mails; wartet bis {@code waitMillis}, falls noch keine da ist.
     *
     * @param onWait wird etwa jede Sekunde aufgerufen, solange gewartet wird (Fortschrittsmeldung)
     */
    List<NewMail> receive(long waitMillis, int limit, Runnable onWait) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, waitMillis));
        synchronized (inbox) {
            while (inbox.isEmpty()) {
                long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (left <= 0) {
                    return List.of();
                }
                try {
                    inbox.wait(Math.min(left, 1000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Abgebrochen", e);
                }
                if (inbox.isEmpty()) {
                    onWait.run();
                }
            }
            List<NewMail> out = new ArrayList<>(inbox.subList(0, Math.min(limit, inbox.size())));
            inbox.subList(0, out.size()).clear();
            return out;
        }
    }

    int pending() {
        synchronized (inbox) {
            return inbox.size();
        }
    }

    /** Verworfene Mails seit dem letzten Aufruf (Eingang voll). */
    int takeDropped() {
        synchronized (inbox) {
            int d = dropped;
            dropped = 0;
            return d;
        }
    }

    /** Ob {@code address} zu einem Eintrag passt: Adresse, {@code @domain}/{@code *@domain} oder {@code domain}. */
    static boolean matchesAddress(List<String> patterns, String address) {
        String a = address.toLowerCase(Locale.ROOT);
        for (String raw : patterns) {
            String s = raw.strip().toLowerCase(Locale.ROOT);
            if (s.isEmpty()) {
                continue;
            }
            if (s.startsWith("*@")) {
                s = s.substring(1);
            }
            boolean match = s.startsWith("@") ? a.endsWith(s) : s.contains("@") ? a.equals(s) : a.endsWith("@" + s);
            if (match) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ Gesendet (Schleifen vermeiden, Grenze)

    private final Set<String> sentIds = BoundedMap.fifoSet(500);
    private final Deque<Long> sendTimes = new ArrayDeque<>();

    /**
     * Merkt sich eine selbst gesendete Mail: kommt sie in einem überwachten Ordner an (z.B. an sich selbst gesendet),
     * gehen dafür weder Channel noch Befehl los – sonst könnte ein Agent auf seine eigene Mail antworten, endlos.
     */
    synchronized void sent(String messageId) {
        sendTimes.addLast(System.currentTimeMillis());
        if (messageId != null) {
            sentIds.add(messageId);
        }
    }

    synchronized boolean ownMail(String messageId) {
        return messageId != null && sentIds.contains(messageId);
    }

    /** Ob in der letzten Stunde weniger als {@code perHour} Mails gesendet wurden. */
    synchronized boolean sendQuotaLeft(int perHour) {
        long hourAgo = System.currentTimeMillis() - 3_600_000;
        while (!sendTimes.isEmpty() && sendTimes.peekFirst() < hourAgo) {
            sendTimes.removeFirst();
        }
        return sendTimes.size() < perHour;
    }

    // ------------------------------------------------------------------ Melden

    private final List<Consumer<List<NewMail>>> listeners =
            new CopyOnWriteArrayList<>();

    /** Meldet neue Mails zusätzlich an {@code listener} (z.B. für Plugins); schließen meldet ihn ab. */
    AutoCloseable addListener(Consumer<List<NewMail>> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /** Meldet die neuen Mails eines Ordners (aufsteigend nach UID). */
    void deliver(Spec spec, List<NewMail> mails) {
        if (mails.isEmpty()) {
            return;
        }
        for (var l : listeners) {
            try {
                l.accept(mails);
            } catch (RuntimeException e) {
                LOG.warn("Listener für neue Mails ({}) fehlgeschlagen", l.getClass().getName(), e);
            }
        }
        Settings s = settings;
        List<NewMail> notify = mails.stream()
                .filter(m -> !(s.onlyUnseen() && m.seen()) && s.notifies(m.fromAddress()) && !ownMail(m.messageId()))
                .toList();
        int skipped = Math.max(0, notify.size() - MAX_NOTIFY);
        if (skipped > 0) {
            notify = notify.subList(skipped, notify.size());
            if (s.channel()) {
                channel.publish(SOURCE, skipped + " weitere neue E-Mails in " + spec.label() + " (ältere, hier nicht "
                        + "einzeln gemeldet) – mail_list bzw. mail_receive zeigen sie.", Map.of(
                        "account", spec.account().name(), "folder", spec.folder(), "count", Integer.toString(skipped)));
            }
        }
        for (NewMail m : notify) {
            if (s.channel()) {
                channel.publish(SOURCE, channelText(m), Map.of("account", m.account(), "folder", m.folder(),
                        "uid", Long.toString(m.uid()), "from", m.fromAddress(), "subject", m.subject()));
            }
            command.submit(s.command(), m);
        }
        // zuletzt: wer per mail_receive wartet, sieht die Mail erst, wenn Channel und Befehl schon Bescheid wissen
        synchronized (inbox) {
            inbox.addAll(mails);
            while (inbox.size() > MAX_PENDING) {
                inbox.removeFirst();
                dropped++;
            }
            inbox.notifyAll();
        }
    }

    static String channelText(NewMail m) {
        return "Neue E-Mail in " + m.account() + "/" + m.folder() + "\n"
                + "Von: " + m.from() + "\n"
                + "Betreff: " + m.subject() + "\n"
                + "Datum: " + MailText.time(m.date()) + "\n"
                + "Lesen: mail_read(account=\"" + m.account() + "\", folder=\"" + m.folder() + "\", uid=" + m.uid() + ")";
    }

    // ------------------------------------------------------------------ Überwachung eines Ordners

    private final class FolderWatch implements Runnable {
        final Spec spec;
        final Settings.WatchKey key;
        final String stateKey;
        private volatile boolean running = true;
        private volatile boolean waiting;
        private volatile Store store;
        private volatile Folder folder;
        private Thread thread;
        volatile String status = "verbinde …";

        FolderWatch(Settings.WatchKey key) {
            this.key = key;
            this.spec = key.spec();
            this.stateKey = MailState.key(spec.account(), spec.folder());
        }

        FolderWatch start() {
            thread = Thread.ofPlatform().daemon().name("mail-watch-" + spec.label()).start(this);
            return this;
        }

        /** Bricht das Warten auf den nächsten Versuch ab (nur zwischen zwei Versuchen wirksam). */
        void wake() {
            if (waiting) {
                thread.interrupt();
            }
        }

        void stop() {
            running = false;
            thread.interrupt();
            // Schließen bricht ein laufendes IDLE ab (Angus beendet es mit DONE); nicht im Aufrufer-Thread warten
            Thread.ofVirtual().start(this::disconnect);
        }

        @Override
        public void run() {
            long backoff = MIN_BACKOFF;
            while (running && !stopped) {
                try {
                    connectAndWatch();
                    backoff = MIN_BACKOFF;
                } catch (RuntimeException | MessagingException e) {
                    if (!running) {
                        break;
                    }
                    String msg = MailConnector.describe(spec.account(), e);
                    boolean auth = e instanceof jakarta.mail.AuthenticationFailedException
                            || e.getCause() instanceof jakarta.mail.AuthenticationFailedException
                            || e instanceof MailOAuth.NotLoggedInException;
                    if (auth) {
                        backoff = MAX_BACKOFF; // nicht durch Wiederholen das Konto sperren lassen
                    }
                    status = "Fehler (" + Instant.now().toString().substring(11, 19) + " UTC): " + msg
                            + " – neuer Versuch in " + backoff / 1000 + " s";
                    LOG.warn("Mail-Überwachung {}: {}", spec.label(), msg);
                } finally {
                    disconnect();
                }
                waiting = true;
                sleep(backoff);
                waiting = false;
                backoff = Math.min(MAX_BACKOFF, backoff * 2);
            }
        }

        private void connectAndWatch() throws MessagingException {
            // Lesezeitlimit über der IDLE-Auffrischung, sonst bräche jedes ruhige IDLE mit Zeitüberschreitung ab
            Duration read = Duration.ofMinutes(key.refreshMinutes() + 2L);
            Store s = MailConnector.connect(spec.account(), oauth, CONNECT_TIMEOUT, read);
            store = s;
            Folder f = s.getFolder(spec.folder());
            if (!f.exists()) {
                throw new IllegalStateException("IMAP '" + spec.account().name() + "': Ordner '" + spec.folder()
                        + "' gibt es nicht.");
            }
            f.open(Folder.READ_ONLY);
            folder = f;
            if (!running) {
                return;
            }
            baseline((UIDFolder) f);
            boolean idle = key.idle() && f instanceof IMAPFolder && s instanceof IMAPStore is && is.hasCapability("IDLE");
            status = idle ? "überwacht per IDLE seit " + MailText.time(new Date())
                    : "überwacht durch Abfrage alle " + key.pollSeconds() + " s seit " + MailText.time(new Date())
                    + (key.idle() ? " (Server kann kein IDLE)" : "");
            LOG.info("Mail-Überwachung {}: {}", spec.label(), status);
            check((UIDFolder) f);
            while (running && f.isOpen()) {
                if (idle) {
                    idleOnce((IMAPFolder) f);
                } else {
                    sleep(key.pollSeconds() * 1000L);
                    if (!running) {
                        return;
                    }
                    ((IMAPFolder) f).doCommand(p -> {
                        p.noop(); // liefert EXISTS für neue Mails
                        return null;
                    });
                }
                if (running) {
                    check((UIDFolder) f);
                }
            }
        }

        /** Ein IDLE bis zur ersten Meldung des Servers; nach {@code refreshMinutes} per NOOP aufgefrischt. */
        private void idleOnce(IMAPFolder f) throws MessagingException {
            // eigener Thread je Auffrischung: eine hängende Verbindung soll die der anderen Ordner nicht aufhalten
            ScheduledFuture<?> refresh = timer.schedule(() -> Thread.ofVirtual().start(() -> {
                try {
                    f.doCommand(p -> {
                        p.noop(); // beendet das IDLE, die Schleife beginnt ein neues
                        return null;
                    });
                } catch (MessagingException | RuntimeException e) {
                    LOG.debug("IDLE-Auffrischung {}: {}", spec.label(), e.toString());
                }
            }), key.refreshMinutes(), TimeUnit.MINUTES);
            try {
                f.idle(true);
            } finally {
                refresh.cancel(false);
            }
        }

        /** Erster Abgleich: unbekannter Ordner oder neue UIDVALIDITY → der Bestand gilt als gemeldet. */
        private void baseline(UIDFolder f) throws MessagingException {
            long validity = f.getUIDValidity();
            MailState.Position p = state.get(stateKey);
            if (p != null && p.uidValidity() == validity) {
                return;
            }
            long last = f.getUIDNext() - 1;
            if (last < 0) {
                Folder folder = (Folder) f;
                int count = folder.getMessageCount();
                last = count == 0 ? 0 : f.getUID(folder.getMessage(count));
            }
            state.put(stateKey, new MailState.Position(validity, last));
        }

        /** Meldet Mails mit höherer UID als der gemeldeten. */
        private void check(UIDFolder f) throws MessagingException {
            MailState.Position p = state.get(stateKey);
            long last = p == null ? 0 : p.lastUid();
            Message[] found = f.getMessagesByUID(last + 1, UIDFolder.LASTUID);
            List<Message> fresh = new ArrayList<>();
            for (Message m : found) {
                if (m != null && !m.isExpunged() && f.getUID(m) > last) {
                    fresh.add(m);
                }
            }
            if (fresh.isEmpty()) {
                return;
            }
            FetchProfile fp = new FetchProfile();
            fp.add(FetchProfile.Item.ENVELOPE);
            fp.add(FetchProfile.Item.FLAGS);
            fp.add(UIDFolder.FetchProfileItem.UID);
            Message[] msgs = fresh.toArray(Message[]::new);
            ((Folder) f).fetch(msgs, fp);
            Arrays.sort(msgs, Comparator.comparingLong(m -> uid(f, m)));
            List<NewMail> out = new ArrayList<>();
            long max = last;
            for (Message m : msgs) {
                long uid = f.getUID(m);
                max = Math.max(max, uid);
                out.add(new NewMail(spec.account().name(), spec.folder(), uid,
                        MailText.addresses(m.getFrom()), MailText.senderAddress(m), MailText.subject(m),
                        m.getReceivedDate() != null ? m.getReceivedDate() : m.getSentDate(),
                        m.isSet(Flags.Flag.SEEN), m instanceof MimeMessage mm ? mm.getMessageID() : null));
            }
            // erst merken, dann melden: ein Absturz beim Melden soll nicht zu doppelten Läufen führen
            state.put(stateKey, new MailState.Position(f.getUIDValidity(), max));
            LOG.info("Mail-Überwachung {}: {} neue Mail(s)", spec.label(), out.size());
            deliver(spec, out);
        }

        private long uid(UIDFolder f, Message m) {
            try {
                return f.getUID(m);
            } catch (MessagingException e) {
                return Long.MAX_VALUE;
            }
        }

        private void disconnect() {
            Folder f = folder;
            Store s = store;
            folder = null;
            store = null;
            try {
                if (f != null && f.isOpen()) {
                    f.close(false);
                }
            } catch (MessagingException | RuntimeException ignored) {
                // Verbindung ohnehin weg
            }
            try {
                if (s != null) {
                    s.close();
                }
            } catch (MessagingException | RuntimeException ignored) {
                // Verbindung ohnehin weg
            }
        }

        private void sleep(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                // stop() – die Schleife prüft running
            }
        }
    }
}
