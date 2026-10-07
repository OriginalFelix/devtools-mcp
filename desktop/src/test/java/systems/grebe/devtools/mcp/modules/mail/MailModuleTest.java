package systems.grebe.devtools.mcp.modules.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import systems.grebe.devtools.mcp.core.ChannelEvents;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolRegistry;

class MailModuleTest {

    @RegisterExtension
    static final GreenMailExtension GREEN_MAIL = new GreenMailExtension(ServerSetupTest.SMTP_IMAP);

    private static final String ADDRESS = "felix@example.com";
    private static final String USER = "felix";
    private static final String PASSWORD = "geheim";

    private final ChannelEvents channel = new ChannelEvents();
    private MailWatcher watcher;
    private GreenMailUser user;

    @BeforeEach
    void setUp() throws Exception {
        user = GREEN_MAIL.setUser(ADDRESS, USER, PASSWORD);
        watcher = new MailWatcher(new StaticListableBeanFactory().getBeanProvider(ToolRegistry.class), channel,
                MailState.inMemory());
        withStore(store -> {
            for (String name : List.of("Projekte", "Projekte/Kunde-A", "Privat", "Archiv", "Drafts")) {
                store.getFolder(name).create(Folder.HOLDS_MESSAGES);
            }
        });
    }

    @AfterEach
    void tearDown() {
        watcher.close();
    }

    // ------------------------------------------------------------------ Lesen und Freigaben

    @Test
    void listsAndReadsOnlyReleasedFolders() throws Exception {
        deliver("INBOX", "chef@firma.de", "Quartalszahlen", "Bitte bis Freitag prüfen.");
        deliver("Privat", "mama@example.org", "Geburtstag", "Privat!");
        MailTools tools = tools(account("INBOX\nProjekte/*", "INBOX"), Map.of());

        assertThat(tools.folders(null)).contains("INBOX", "Projekte/Kunde-A").doesNotContain("Privat")
                .contains("[überwacht]");
        String list = tools.list(null, null, null, null, null, null, null, null);
        assertThat(list).contains("1 Mails").contains("chef@firma.de").contains("Quartalszahlen").contains("*");
        long uid = Long.parseLong(list.lines().skip(1).findFirst().orElseThrow().split("\\s+")[0]);

        String mail = tools.read(null, "inbox", uid, null);
        assertThat(mail).contains("Von: chef@firma.de", "Betreff: Quartalszahlen", "Bitte bis Freitag prüfen.",
                "Status: ungelesen");
        // Lesen markiert nicht als gelesen
        assertThat(tools.read(null, null, uid, null)).contains("Status: ungelesen");

        assertThatThrownBy(() -> tools.list(null, "Privat", null, null, null, null, null, null))
                .hasMessageContaining("nicht freigegeben");
        assertThatThrownBy(() -> tools.read(null, "Privat", 1L, null)).hasMessageContaining("nicht freigegeben");
        assertThat(tools.accounts()).contains("arbeit", USER + "@", "Freigegeben: INBOX, Projekte/*")
                .doesNotContain(PASSWORD);
    }

    @Test
    void wholeAccountAndFilters() throws Exception {
        deliver("INBOX", "chef@firma.de", "Quartalszahlen", "Zahlen anbei.");
        deliver("INBOX", "kunde@kunde-a.de", "Störung im Lager", "Seit heute früh geht nichts.");
        deliver("Privat", "mama@example.org", "Geburtstag", "Privat!");
        MailTools tools = tools(account("", ""), Map.of());

        assertThat(tools.folders("ARBEIT")).contains("Privat", "Archiv", "(ganzes Konto)");
        // GreenMail vergleicht SEARCH FROM mit der ganzen Adresse (echte Server: Teilstring nach RFC 3501)
        assertThat(tools.list(null, null, null, "kunde@kunde-a.de", null, null, null, null))
                .contains("1 Treffer", "Störung im Lager").doesNotContain("Quartalszahlen");
        assertThat(tools.list(null, null, "Lager", null, null, true, "2000-01-01", 5)).contains("Störung im Lager");
        assertThat(tools.list(null, "Privat", null, null, null, null, null, null)).contains("Geburtstag");
        assertThatThrownBy(() -> tools.list(null, null, null, null, null, null, "gestern", null))
                .hasMessageContaining("yyyy-MM-dd");
    }

    @Test
    void htmlAndAttachments() throws Exception {
        MimeMessage m = new MimeMessage(session());
        m.setFrom("newsletter@example.com");
        m.setRecipients(Message.RecipientType.TO, ADDRESS);
        m.setSubject("Neuigkeiten");
        MimeBodyPart html = new MimeBodyPart();
        html.setContent("<html><head><style>p{}</style></head><body><p>Hallo&nbsp;Welt &amp; mehr</p>"
                + "<a href=\"https://example.com/x\">Link</a><ul><li>eins</li></ul></body></html>", "text/html; charset=UTF-8");
        MimeBodyPart file = new MimeBodyPart();
        file.setText("a;b\n1;2", "UTF-8");
        file.setFileName("daten.csv");
        file.setDisposition(MimeBodyPart.ATTACHMENT);
        m.setContent(new MimeMultipart(html, file));
        m.saveChanges();
        append("INBOX", m);

        MailTools tools = tools(account("", ""), Map.of());
        String list = tools.list(null, null, null, null, null, null, null, null);
        assertThat(list).contains("@");
        String read = tools.read(null, null, 1L, null);
        assertThat(read).contains("(aus HTML)", "Hallo Welt & mehr", "Link (https://example.com/x)", "- eins",
                "daten.csv").doesNotContain("<p>", "p{}");
    }

    // ------------------------------------------------------------------ Schreibende Tools

    @Test
    void markMoveAndDraft() throws Exception {
        deliver("INBOX", "kunde@kunde-a.de", "Anfrage", "Können Sie bis Montag liefern?");
        MailEnvironment env = env(account("INBOX\nArchiv\nDrafts", ""), Map.of());
        MailTools tools = new MailTools(env, 60);

        assertThat(new MailWriteTools.Mark(env).mark(null, null, "1", "seen")).contains("1 Mail(s)");
        assertThat(tools.read(null, null, 1L, null)).contains("Status: gelesen");
        assertThatThrownBy(() -> new MailWriteTools.Mark(env).mark(null, null, "1", "delete"))
                .hasMessageContaining("seen, unseen");
        assertThatThrownBy(() -> new MailWriteTools.Mark(env).mark(null, null, "99", "seen"))
                .hasMessageContaining("Keine Mail mit UID [99]");

        String draft = new MailWriteTools.Draft(env).draft(null, null, null, null, "Ja, das klappt.", 1L, null);
        assertThat(draft).contains("arbeit/Drafts", "kunde@kunde-a.de", "Re: Anfrage");
        String drafts = tools.list(null, "Drafts", null, null, null, null, null, null);
        assertThat(drafts).contains("Re: Anfrage");
        String text = tools.read(null, "Drafts", 1L, null);
        assertThat(text).contains("Ja, das klappt.", "> Können Sie bis Montag liefern?");

        assertThatThrownBy(() -> new MailWriteTools.Move(env).move(null, null, "1", "Privat"))
                .hasMessageContaining("nicht freigegeben");
        assertThat(new MailWriteTools.Move(env).move(null, null, "1", "Archiv")).contains("verschoben");
        assertThat(tools.list(null, "Archiv", null, null, null, null, null, null)).contains("Anfrage");
        assertThat(tools.list(null, null, null, null, null, null, null, null)).contains("0 Mails");
    }

    @Test
    void writeToolsOnlyWithSwitches() {
        MailModule module = new MailModule(watcher);
        Map<String, String> values = new LinkedHashMap<>(accountValues(account("", "")));
        List<String> names = module.createTools(ModuleConfig.of(module.configSchema(), values)).stream()
                .map(t -> t.getToolDefinition().name()).toList();
        assertThat(names).containsExactlyInAnyOrder("accounts", "folders", "list", "read", "receive");
        values.put(MailModule.ALLOW_FLAGS, "true");
        values.put(MailModule.ALLOW_MOVE, "true");
        values.put(MailModule.ALLOW_DRAFTS, "true");
        names = module.createTools(ModuleConfig.of(module.configSchema(), values)).stream()
                .map(t -> t.getToolDefinition().name()).toList();
        assertThat(names).contains("mark", "move", "draft");
    }

    @Test
    void connectionTest() {
        MailModule module = new MailModule(watcher);
        var ok = module.testConnection(ModuleConfig.of(module.configSchema(), accountValues(account("INBOX\nGibtsNicht", "INBOX"))));
        assertThat(ok.success()).isTrue();
        assertThat(ok.message()).contains("verbunden", "IDLE ja", "Ordner 'GibtsNicht' gibt es nicht");

        Map<String, String> wrong = account("", "");
        wrong.put(MailAccount.PASSWORD, "falsch");
        var failed = module.testConnection(ModuleConfig.of(module.configSchema(), accountValues(wrong)));
        assertThat(failed.success()).isFalse();
        assertThat(failed.message()).contains("Anmeldung fehlgeschlagen").doesNotContain("falsch");
    }

    // ------------------------------------------------------------------ Überwachung

    @Test
    void watcherReportsNewMailViaIdle() throws Exception {
        deliver("INBOX", "alt@example.com", "Schon da", "vor dem Start");
        startWatching(true, List.of(), null);

        deliver("INBOX", "kunde@kunde-a.de", "Neue Bestellung", "Bitte bestätigen.");
        List<MailWatcher.NewMail> mails = watcher.receive(15_000, 10, () -> { });
        assertThat(mails).singleElement().satisfies(m -> {
            assertThat(m.subject()).isEqualTo("Neue Bestellung");
            assertThat(m.fromAddress()).isEqualTo("kunde@kunde-a.de");
            assertThat(m.uid()).isEqualTo(2);
        });
        List<ChannelEvents.Event> events = channel.since(0);
        assertThat(events).singleElement().satisfies(e -> {
            assertThat(e.source()).isEqualTo("mail");
            assertThat(e.content()).contains("Neue E-Mail in arbeit/INBOX", "Neue Bestellung",
                    "mail_read(account=\"arbeit\", folder=\"INBOX\", uid=2)");
            assertThat(e.meta()).containsEntry("uid", "2").containsEntry("event_source", "mail")
                    .containsEntry("from", "kunde@kunde-a.de");
        });
        assertThat(watcher.receive(0, 10, () -> { })).isEmpty();
    }

    @Test
    void watcherPollsAndFiltersSenders() throws Exception {
        startWatching(false, List.of("@kunde-a.de"), null);
        deliver("INBOX", "spam@werbung.example", "Gewinn!", "Klicken Sie hier");
        deliver("INBOX", "kunde@kunde-a.de", "Rückfrage", "Wann?");

        List<MailWatcher.NewMail> mails = receiveAtLeast(2);
        assertThat(mails).extracting(MailWatcher.NewMail::subject).containsExactly("Gewinn!", "Rückfrage");
        // Channel nur für freigegebene Absender
        assertThat(channel.since(0)).singleElement().satisfies(e -> assertThat(e.meta()).containsEntry("subject", "Rückfrage"));
    }

    @Test
    void watcherRunsCommandWithoutShell(@TempDir Path dir) throws Exception {
        assumeThat(System.getProperty("os.name").toLowerCase()).doesNotContain("win");
        Path out = dir.resolve("out.txt");
        Path script = dir.resolve("hook.sh");
        Files.writeString(script, "#!/bin/sh\nprintf '%s|%s|%s|%s|%s' \"$1\" \"$DEVTOOLS_MAIL_ACCOUNT\" \"$DEVTOOLS_MAIL_UID\" "
                + "\"$DEVTOOLS_MAIL_SUBJECT\" \"$2\" > \"" + out + "\"\n");
        script.toFile().setExecutable(true);
        startWatching(true, List.of(), new MailCommand.Settings(script + " \"Mail {account}/{folder} UID {uid}\" "
                + "'{uid}'", dir, 30));

        deliver("INBOX", "kunde@kunde-a.de", "Achtung \"; rm -rf / #", "Text");
        long deadline = System.currentTimeMillis() + 15_000;
        while (!Files.exists(out) || Files.readString(out).isEmpty()) {
            assertThat(System.currentTimeMillis()).isLessThan(deadline);
            Thread.sleep(100);
        }
        assertThat(Files.readString(out)).isEqualTo("Mail arbeit/INBOX UID 1|arbeit|1|Achtung \"; rm -rf / #|1");
    }

    @Test
    void watcherSkipsBacklogOnFirstStartAndResumesAfterRestart() throws Exception {
        MailState state = MailState.inMemory();
        watcher.close();
        watcher = new MailWatcher(new StaticListableBeanFactory().getBeanProvider(ToolRegistry.class), channel, state);
        deliver("INBOX", "a@example.com", "Alt", "x");
        startWatching(true, List.of(), null);
        watcher.close();

        deliver("INBOX", "b@example.com", "Während der Pause", "y");
        watcher = new MailWatcher(new StaticListableBeanFactory().getBeanProvider(ToolRegistry.class), channel, state);
        startWatching(true, List.of(), null);
        assertThat(receiveAtLeast(1)).extracting(MailWatcher.NewMail::subject).containsExactly("Während der Pause");
    }

    @Test
    void receiveToolWaits() throws Exception {
        MailTools tools = tools(account("INBOX", "INBOX"), Map.of());
        startWatching(true, List.of(), null);
        assertThat(tools.receive(0, null)).contains("Keine neuen Mails.");
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(500);
                deliver("INBOX", "kunde@kunde-a.de", "Später", "z");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(tools.receive(15, null)).contains("1 neue Mail(s)", "arbeit/INBOX UID 1", "„Später“");
    }

    // ------------------------------------------------------------------ Hilfsfunktionen

    @Test
    void folderPatterns() {
        assertThat(MailAccount.matches("INBOX", "inbox")).isTrue();
        assertThat(MailAccount.matches("Projekte/*", "Projekte/Kunde-A")).isTrue();
        assertThat(MailAccount.matches("Projekte/*", "Privat")).isFalse();
        assertThat(MailAccount.matches("inbox/*", "INBOX/Rechnungen")).isTrue();
        assertThat(MailAccount.matches("Inboxen", "INBOXEN")).isFalse();
    }

    @Test
    void commandTokenizer() {
        assertThat(MailCommand.tokenize("claude -p \"a b {uid}\" --x 'c d'")).containsExactly("claude", "-p",
                "a b {uid}", "--x", "c d");
        assertThat(MailCommand.arguments("x {account}/{folder} {uid}", new MailWatcher.NewMail("acc", "INBOX", 7,
                "", "", "", null, false))).containsExactly("x", "acc/INBOX", "7");
        assertThatThrownBy(() -> MailCommand.tokenize("claude \"offen")).hasMessageContaining("Anführungszeichen");
    }

    @Test
    void senderFilter() {
        MailWatcher.Settings s = new MailWatcher.Settings(List.of(), true, 60, 10, true,
                List.of("@firma.de", "kunde.de", "chef@privat.org", "*@x.org"), true, new MailCommand.Settings(null, null, 0));
        assertThat(s.notifies("a@firma.de")).isTrue();
        assertThat(s.notifies("a@sub.kunde.de")).isFalse();
        assertThat(s.notifies("b@kunde.de")).isTrue();
        assertThat(s.notifies("chef@privat.org")).isTrue();
        assertThat(s.notifies("other@privat.org")).isFalse();
        assertThat(s.notifies("y@x.org")).isTrue();
        assertThat(s.notifies("a@firma.de.evil.com")).isFalse();
    }

    private List<MailWatcher.NewMail> receiveAtLeast(int n) {
        List<MailWatcher.NewMail> all = new java.util.ArrayList<>();
        long deadline = System.currentTimeMillis() + 20_000;
        while (all.size() < n && System.currentTimeMillis() < deadline) {
            all.addAll(watcher.receive(1000, 10, () -> { }));
        }
        return all;
    }

    private void startWatching(boolean idle, List<String> senders, MailCommand.Settings command) throws Exception {
        MailAccount a = MailAccount.of(account("INBOX", "INBOX"));
        watcher.apply(new MailWatcher.Settings(List.of(new MailWatcher.Spec(a, "INBOX")), idle, 1, 10, true, senders,
                true, command == null ? new MailCommand.Settings(null, null, 0) : command));
        long deadline = System.currentTimeMillis() + 10_000;
        String status;
        while ((status = watcher.status(a, "INBOX")) == null || !status.startsWith("überwacht")) {
            assertThat(System.currentTimeMillis()).as(status).isLessThan(deadline);
            Thread.sleep(50);
        }
        assertThat(status).contains(idle ? "IDLE" : "Abfrage");
    }

    private Map<String, String> account(String folders, String watch) {
        Map<String, String> a = new LinkedHashMap<>();
        a.put(MailAccount.NAME, "arbeit");
        a.put(MailAccount.HOST, "127.0.0.1");
        a.put(MailAccount.PORT, Integer.toString(GREEN_MAIL.getImap().getPort()));
        a.put(MailAccount.SECURITY, MailAccount.PLAIN);
        a.put(MailAccount.USERNAME, USER);
        a.put(MailAccount.PASSWORD, PASSWORD);
        a.put(MailAccount.FOLDERS, folders);
        a.put(MailAccount.WATCH, watch);
        return a;
    }

    private static Map<String, String> accountValues(Map<String, String> account) {
        return Map.of(MailModule.ACCOUNTS, ModuleConfig.formatRecords(List.of(account)));
    }

    private MailEnvironment env(Map<String, String> account, Map<String, String> extra) {
        MailModule module = new MailModule(watcher);
        Map<String, String> values = new LinkedHashMap<>(accountValues(account));
        values.putAll(extra);
        return new MailEnvironment(ModuleConfig.of(module.configSchema(), values), new MailEnvironment.Sessions(), watcher);
    }

    private MailTools tools(Map<String, String> account, Map<String, String> extra) {
        MailTools tools = new MailTools(env(account, extra), 60);
        if (!account.get(MailAccount.WATCH).isEmpty()) {
            // Status „überwacht“ für folders/accounts
            watcher.apply(new MailWatcher.Settings(List.of(new MailWatcher.Spec(MailAccount.of(account), "INBOX")),
                    true, 1, 10, true, List.of(), false, new MailCommand.Settings(null, null, 0)));
        }
        return tools;
    }

    private Session session() {
        return Session.getInstance(new Properties());
    }

    private void deliver(String folder, String from, String subject, String text) throws Exception {
        MimeMessage m = new MimeMessage(session());
        m.setFrom(new InternetAddress(from));
        m.setRecipients(Message.RecipientType.TO, ADDRESS);
        m.setSubject(subject, "UTF-8");
        m.setText(text, "UTF-8");
        m.setSentDate(new java.util.Date());
        m.saveChanges();
        append(folder, m);
    }

    private void append(String folder, MimeMessage m) throws Exception {
        if (folder.equals("INBOX")) {
            user.deliver(m);
            return;
        }
        withStore(store -> {
            Folder f = store.getFolder(folder);
            f.appendMessages(new Message[]{m});
        });
    }

    private interface StoreBody {
        void run(Store store) throws Exception;
    }

    private void withStore(StoreBody body) throws Exception {
        Properties p = new Properties();
        Store store = Session.getInstance(p).getStore("imap");
        store.connect("127.0.0.1", GREEN_MAIL.getImap().getPort(), USER, PASSWORD);
        try {
            body.run(store);
        } finally {
            store.close();
        }
    }
}
