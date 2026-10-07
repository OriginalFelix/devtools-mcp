package systems.grebe.devtools.mcp.modules.mail;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import jakarta.mail.Address;
import jakarta.mail.Folder;
import jakarta.mail.FolderClosedException;
import jakarta.mail.MessagingException;
import jakarta.mail.Store;
import jakarta.mail.StoreClosedException;
import jakarta.mail.internet.InternetAddress;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.UserConfirmation;

/** Ausgewertete Konfiguration des Mail-Moduls: Konten, Freigaben, Grenzen – und der Zugriff auf die Ordner. */
final class MailEnvironment {

    @FunctionalInterface
    interface FolderAction<T> {
        T run(Folder folder) throws MessagingException;
    }

    @FunctionalInterface
    interface StoreAction<T> {
        T run(Store store) throws MessagingException;
    }

    private final Map<String, MailAccount> accounts = new LinkedHashMap<>();
    private final List<String> duplicates = new ArrayList<>();
    private final Sessions sessions;
    private final MailWatcher watcher;
    private final Duration timeout;
    private final int maxLines;
    private final int maxChars;
    private final UserConfirmation confirmation;
    /** {@code null} = ohne Rückfrage senden. */
    private final UserConfirmation.Channel sendConfirm;
    private final List<String> sendRecipients;
    private final int sendPerHour;
    private final String saveSent;

    MailEnvironment(ModuleConfig c, Sessions sessions, MailWatcher watcher, UserConfirmation confirmation) {
        this.sessions = sessions;
        this.watcher = watcher;
        this.confirmation = confirmation;
        String confirm = c.getString(MailModule.SEND_CONFIRM, "auto").toLowerCase(Locale.ROOT);
        this.sendConfirm = switch (confirm) {
            case "off" -> null;
            case "client" -> UserConfirmation.Channel.CLIENT;
            case "app" -> UserConfirmation.Channel.APP;
            default -> UserConfirmation.Channel.AUTO;
        };
        this.sendRecipients = c.getList(MailModule.SEND_RECIPIENTS);
        this.sendPerHour = Math.max(1, c.getInt(MailModule.SEND_PER_HOUR, 20));
        this.saveSent = c.getString(MailModule.SAVE_SENT, "auto").toLowerCase(Locale.ROOT);
        this.timeout = Duration.ofSeconds(Math.max(5, c.getInt(MailModule.TIMEOUT, 30)));
        this.maxLines = Math.max(50, c.getInt(MailModule.MAX_LINES, 400));
        this.maxChars = Math.max(1000, c.getInt(MailModule.MAX_CHARS, 20_000));
        for (MailAccount a : accounts(c)) {
            if (accounts.putIfAbsent(a.name().toLowerCase(Locale.ROOT), a) != null) {
                duplicates.add(a.name());
            }
        }
    }

    static List<MailAccount> accounts(ModuleConfig c) {
        String authority = c.getString(MailModule.MS_AUTHORITY, MailAccount.DEFAULT_AUTHORITY);
        return c.getRecords(MailModule.ACCOUNTS).stream().map(r -> MailAccount.of(r, authority))
                .filter(a -> !a.name().isEmpty()).toList();
    }

    List<MailAccount> accounts() {
        return List.copyOf(accounts.values());
    }

    List<String> duplicates() {
        return List.copyOf(duplicates);
    }

    MailWatcher watcher() {
        return watcher;
    }

    int maxLines() {
        return maxLines;
    }

    int maxChars() {
        return maxChars;
    }

    Duration timeout() {
        return timeout;
    }

    // ------------------------------------------------------------------ Senden: Regeln

    /** Nur freigegebene Empfänger (Adresse, @domain oder domain); leer = alle. */
    void requireRecipientsAllowed(Address[] recipients) {
        if (sendRecipients.isEmpty()) {
            return;
        }
        List<String> denied = new ArrayList<>();
        for (Address r : recipients) {
            String address = r instanceof InternetAddress ia && ia.getAddress() != null ? ia.getAddress() : r.toString();
            if (!MailWatcher.matchesAddress(sendRecipients, address)) {
                denied.add(address);
            }
        }
        if (!denied.isEmpty()) {
            throw new IllegalStateException("Nicht gesendet: Empfänger " + denied + " sind nicht freigegeben. Erlaubt: "
                    + sendRecipients + " (DevTools-App → Module → Mail → Erlaubte Empfänger).");
        }
    }

    /** Höchstens {@code sendPerHour} Mails in der letzten Stunde (über alle Konten). */
    void requireSendQuota() {
        if (!watcher.sendQuotaLeft(sendPerHour)) {
            throw new IllegalStateException("Nicht gesendet: Grenze von " + sendPerHour + " Mails pro Stunde erreicht "
                    + "(DevTools-App → Module → Mail → Max. Mails pro Stunde).");
        }
    }

    /** Fragt den Nutzer, sofern eingestellt; wirft, wenn er ablehnt oder niemand gefragt werden kann. */
    void confirmSend(McpSyncServerExchange exchange, String question) {
        if (sendConfirm == null) {
            return;
        }
        if (confirmation == null) {
            throw new IllegalStateException("Nicht gesendet: keine Rückfrage beim Nutzer möglich.");
        }
        UserConfirmation.Result r = confirmation.ask(exchange, sendConfirm, "E-Mail senden?", question);
        switch (r.answer()) {
            case GRANTED -> { }
            case DECLINED -> throw new IllegalStateException("Nicht gesendet: vom Nutzer abgelehnt (" + r.via() + "). "
                    + "Nicht erneut versuchen, ohne dass der Nutzer es ausdrücklich will.");
            default -> throw new IllegalStateException("Nicht gesendet: keine Rückfrage möglich (" + r.via() + "). Der "
                    + "Nutzer kann einen Entwurf (mail_draft) selbst senden oder die Rückfrage in der App umstellen.");
        }
    }

    /** Ob eine Kopie in „Gesendet“ abgelegt wird; {@code auto}: nicht bei Exchange Online und Gmail (legen selbst ab). */
    boolean saveSent(MailAccount a) {
        return switch (saveSent) {
            case "always" -> true;
            case "never" -> false;
            default -> !a.microsoft() && !a.smtpHost().toLowerCase(Locale.ROOT).endsWith("office365.com")
                    && !a.smtpHost().toLowerCase(Locale.ROOT).endsWith("gmail.com")
                    && !a.smtpHost().toLowerCase(Locale.ROOT).endsWith("googlemail.com");
        };
    }

    /** Konto nach Name (ohne Groß-/Kleinschreibung); ohne Name das einzige. */
    MailAccount resolve(String name) {
        if (accounts.isEmpty()) {
            throw new IllegalStateException("Kein Mail-Konto konfiguriert – der Nutzer legt es in der DevTools-App unter "
                    + "Module → Mail an (Name, Host, Benutzer, Passwort, freigegebene Ordner).");
        }
        if (name == null || name.isBlank()) {
            if (accounts.size() == 1) {
                return accounts.values().iterator().next();
            }
            throw new IllegalArgumentException("Mehrere Mail-Konten konfiguriert – 'account' angeben: " + names()
                    + " (siehe mail_accounts).");
        }
        String key = name.trim().toLowerCase(Locale.ROOT);
        if (duplicates.stream().anyMatch(d -> d.equalsIgnoreCase(key))) {
            throw new IllegalStateException("Der Kontoname '" + name + "' ist mehrfach vergeben – der Nutzer muss ihn in "
                    + "der DevTools-App eindeutig machen.");
        }
        MailAccount a = accounts.get(key);
        if (a == null) {
            throw new IllegalArgumentException("Unbekanntes Mail-Konto '" + name + "'. Konfiguriert: " + names() + ".");
        }
        return a;
    }

    private List<String> names() {
        return accounts.values().stream().map(MailAccount::name).toList();
    }

    /** Ordnername: angegeben, sonst INBOX bzw. der einzige freigegebene; nur freigegebene Ordner. */
    String folderName(MailAccount a, String folder) {
        String name = folder == null || folder.isBlank() ? null : folder.trim();
        if (name == null) {
            List<String> plain = a.folders().stream().filter(f -> !f.endsWith("*")).toList();
            if (a.allows("INBOX")) {
                name = "INBOX";
            } else if (plain.size() == 1 && a.folders().size() == 1) {
                name = plain.getFirst();
            } else {
                throw new IllegalArgumentException("'folder' angeben – freigegeben in " + a.name() + ": " + a.folders()
                        + " (siehe mail_folders).");
            }
        }
        requireAllowed(a, name);
        return MailAccount.isInbox(name) ? "INBOX" : name;
    }

    static void requireAllowed(MailAccount a, String folder) {
        if (!a.allows(folder)) {
            throw new IllegalArgumentException("Ordner '" + folder + "' ist für das Konto " + a.name() + " nicht "
                    + "freigegeben. Freigegeben: " + a.folders() + ". Weitere Ordner gibt der Nutzer in der DevTools-App "
                    + "frei (Module → Mail → Konto → Freigegebene Ordner).");
        }
    }

    // ------------------------------------------------------------------ Zugriff

    /** Führt {@code action} mit dem geöffneten Ordner aus; eine abgerissene Verbindung wird einmal neu aufgebaut. */
    <T> T inFolder(MailAccount a, String folder, boolean write, FolderAction<T> action) {
        requireAllowed(a, folder);
        return withStore(a, store -> {
            Folder f = store.getFolder(folder);
            if (!f.exists()) {
                throw new IllegalArgumentException("Ordner '" + folder + "' gibt es im Konto " + a.name()
                        + " nicht (siehe mail_folders).");
            }
            f.open(write ? Folder.READ_WRITE : Folder.READ_ONLY);
            try {
                return action.run(f);
            } finally {
                if (f.isOpen()) {
                    f.close(false);
                }
            }
        });
    }

    <T> T withStore(MailAccount a, StoreAction<T> action) {
        for (int attempt = 0; ; attempt++) {
            Store store = sessions.get(a, watcher.oauth(), timeout);
            try {
                return action.run(store);
            } catch (FolderClosedException | StoreClosedException e) {
                sessions.evict(a.name());
                if (attempt == 0) {
                    continue;
                }
                throw new IllegalStateException(MailConnector.describe(a, e), e);
            } catch (MessagingException e) {
                if (!store.isConnected()) {
                    sessions.evict(a.name());
                    if (attempt == 0) {
                        continue;
                    }
                }
                throw new IllegalStateException(MailConnector.describe(a, e), e);
            }
        }
    }

    /**
     * Offene IMAP-Verbindungen je Konto für die Tools (die Überwachung hat eigene). Verworfen, wenn sie abgerissen ist,
     * sich das Konto geändert hat oder sie {@link #IDLE_MILLIS} unbenutzt war.
     */
    static final class Sessions implements AutoCloseable {
        static final long IDLE_MILLIS = 10 * 60_000L;

        private record Pooled(MailAccount account, Store store, long lastUsed) {
        }

        private final Map<String, Pooled> pool = new HashMap<>();

        synchronized Store get(MailAccount a, MailOAuth oauth, Duration timeout) {
            Pooled p = pool.get(a.name());
            long now = System.currentTimeMillis();
            if (p != null && (!p.account().equals(a) || now - p.lastUsed() > IDLE_MILLIS || !p.store().isConnected())) {
                close(p.store());
                pool.remove(a.name());
                p = null;
            }
            Store store = p != null ? p.store() : MailConnector.connect(a, oauth, timeout, timeout);
            pool.put(a.name(), new Pooled(a, store, now));
            return store;
        }

        synchronized void evict(String name) {
            Pooled p = pool.remove(name);
            if (p != null) {
                close(p.store());
            }
        }

        synchronized boolean isOpen(String name) {
            return pool.containsKey(name);
        }

        @Override
        public synchronized void close() {
            pool.values().forEach(p -> close(p.store()));
            pool.clear();
        }

        private static void close(Store s) {
            try {
                s.close();
            } catch (MessagingException | RuntimeException ignored) {
                // Verbindung ist ohnehin weg
            }
        }
    }
}
