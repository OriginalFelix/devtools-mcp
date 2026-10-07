package systems.grebe.devtools.mcp.modules.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import systems.grebe.devtools.mcp.core.ChannelEvents;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolRegistry;
import systems.grebe.devtools.mcp.core.UserConfirmation;
import systems.grebe.devtools.mcp.modules.mail.spi.MailSend;

/** SMTP-Versand ({@code mail_send}) gegen GreenMail: Rückfrage, Antworten, Freigaben, Grenze, Schleifenschutz. */
class MailSendTest {

    @RegisterExtension
    static final GreenMailExtension GREEN_MAIL = new GreenMailExtension(ServerSetupTest.SMTP_IMAP);

    private static final String ME = "felix@example.com";

    private final ChannelEvents channel = new ChannelEvents();
    private final UserConfirmation confirmation = new UserConfirmation();
    private final List<String> questions = new CopyOnWriteArrayList<>();
    private volatile boolean grant = true;
    private final Map<String, String> account = new LinkedHashMap<>();
    private final Map<String, String> values = new LinkedHashMap<>();
    private MailWatcher watcher;
    private GreenMailUser user;

    @BeforeEach
    void setUp() throws Exception {
        user = GREEN_MAIL.setUser(ME, "felix", "geheim");
        watcher = new MailWatcher(new StaticListableBeanFactory().getBeanProvider(ToolRegistry.class), channel,
                MailState.inMemory(), MailOAuth.inMemory());
        confirmation.setDesktopHandler((title, message) -> {
            questions.add(title + "\n" + message);
            return CompletableFuture.completedFuture(grant);
        });
        account.put(MailAccount.NAME, "arbeit");
        account.put(MailAccount.HOST, "127.0.0.1");
        account.put(MailAccount.PORT, Integer.toString(GREEN_MAIL.getImap().getPort()));
        account.put(MailAccount.SECURITY, MailAccount.PLAIN);
        account.put(MailAccount.USERNAME, "felix");
        account.put(MailAccount.PASSWORD, "geheim");
        account.put(MailAccount.SMTP_HOST, "127.0.0.1");
        account.put(MailAccount.SMTP_PORT, Integer.toString(GREEN_MAIL.getSmtp().getPort()));
        account.put(MailAccount.SMTP_SECURITY, MailAccount.PLAIN);
        account.put(MailAccount.FROM, "Felix Grebe <" + ME + ">");
        account.put(MailAccount.WATCH, "INBOX");
        values.put(MailModule.ALLOW_SEND, "true");
        withStore(store -> store.getFolder("Sent").create(Folder.HOLDS_MESSAGES));
    }

    @AfterEach
    void tearDown() {
        watcher.close();
    }

    @Test
    void sendsAfterConfirmationAndKeepsCopy() throws Exception {
        String result = send().sendMail(null, "kunde@kunde-a.de", "chef@firma.de", "archiv@firma.de", "Angebot",
                "Hallo,\nanbei das Angebot.", null, null, true, null);

        assertThat(result).contains("Gesendet von Felix Grebe <" + ME + ">", "kunde@kunde-a.de", "„Angebot“",
                "Kopie in Sent.");
        assertThat(questions).singleElement().asString().contains("E-Mail senden?", "An: kunde@kunde-a.de",
                "Cc: chef@firma.de", "Bcc: archiv@firma.de", "Betreff: Angebot", "anbei das Angebot.");
        // zugestellt an alle Empfänger, auch Bcc (GreenMail legt je Empfänger ein Postfach an)
        for (String r : List.of("kunde@kunde-a.de", "chef@firma.de", "archiv@firma.de")) {
            assertThat(GREEN_MAIL.getUserManager().getUserByEmail(r)).as(r).isNotNull();
        }
        MimeMessage[] received = GREEN_MAIL.getReceivedMessagesForDomain("kunde-a.de");
        // Bcc steht nur in der Kopie in „Gesendet“ (wie bei Mail-Programmen), nicht in der gesendeten Nachricht
        int withBcc = 0;
        for (MimeMessage x : received) {
            withBcc += x.getHeader("Bcc") == null ? 0 : 1;
        }
        assertThat(withBcc).isEqualTo(1);
        MimeMessage m = received[0];
        assertThat(m.getFrom()[0].toString()).contains(ME);
        assertThat(m.getSubject()).isEqualTo("Angebot");
        assertThat(GreenMailUtil.getBody(m)).contains("anbei das Angebot.");
        assertThat(tools().list(null, "Sent", null, null, null, null, null, null)).contains("Angebot");
    }

    @Test
    void replyGoesToSenderAndMarksOriginalAnswered() throws Exception {
        deliver("kunde@kunde-a.de", "Lieferung", "Wann kommt die Ware?");
        String result = send().sendMail(null, null, null, null, null, "Morgen.", 1L, "INBOX", true, null);

        assertThat(result).contains("an kunde@kunde-a.de", "„Re: Lieferung“");
        MimeMessage m = GREEN_MAIL.getReceivedMessagesForDomain("kunde-a.de")[0];
        assertThat(m.getSubject()).isEqualTo("Re: Lieferung");
        assertThat(m.getHeader("In-Reply-To")).isNotNull();
        assertThat(GreenMailUtil.getBody(m)).contains("Morgen.", "> Wann kommt die Ware?");
        withStore(store -> {
            Folder f = store.getFolder("INBOX");
            f.open(Folder.READ_ONLY);
            assertThat(f.getMessage(1).isSet(Flags.Flag.ANSWERED)).isTrue();
            f.close();
        });
    }

    @Test
    void declinedOrUnavailableConfirmationSendsNothing() {
        grant = false;
        assertThatThrownBy(() -> send().sendMail(null, "kunde@kunde-a.de", null, null, "x", "y", null, null, true, null))
                .hasMessageContaining("vom Nutzer abgelehnt");
        assertThat(GREEN_MAIL.getReceivedMessages()).isEmpty();

        MailEnvironment noDialog = new MailEnvironment(config(), new MailEnvironment.Sessions(), watcher,
                new UserConfirmation());
        assertThatThrownBy(() -> new MailWriteTools.Send(noDialog).sendMail(null, "kunde@kunde-a.de", null, null, "x", "y",
                null, null, true, null)).hasMessageContaining("keine Rückfrage möglich");
        assertThat(GREEN_MAIL.getReceivedMessages()).isEmpty();

        values.put(MailModule.SEND_CONFIRM, "off");
        questions.clear();
        assertThat(send().sendMail(null, "kunde@kunde-a.de", null, null, "x", "y", null, null, true, null))
                .startsWith("Gesendet");
        assertThat(questions).isEmpty();
    }

    @Test
    void recipientsQuotaAndMissingSmtp() {
        values.put(MailModule.SEND_RECIPIENTS, "@firma.de\nkunde@kunde-a.de");
        assertThatThrownBy(() -> send().sendMail(null, "chef@firma.de", "fremd@evil.example", null, "x", "y", null, null,
                true, null)).hasMessageContaining("[fremd@evil.example] sind nicht freigegeben");
        assertThat(questions).isEmpty(); // gar nicht erst gefragt

        values.put(MailModule.SEND_PER_HOUR, "1");
        assertThat(send().sendMail(null, "chef@firma.de", null, null, "x", "y", null, null, true, null)).startsWith("Gesendet");
        assertThatThrownBy(() -> send().sendMail(null, "chef@firma.de", null, null, "x", "y", null, null, true, null))
                .hasMessageContaining("Grenze von 1 Mails pro Stunde");

        account.put(MailAccount.SMTP_HOST, "");
        assertThatThrownBy(() -> send().sendMail(null, "chef@firma.de", null, null, "x", "y", null, null, true, null))
                .hasMessageContaining("keinen SMTP-Server");
        assertThatThrownBy(() -> send().sendMail(null, null, null, null, "x", "y", null, null, true, null))
                .hasMessageContaining("SMTP-Server");
    }

    @Test
    void ownMailDoesNotTriggerTheAgentAgain() throws Exception {
        MailAccount a = MailEnvironment.accounts(config()).getFirst();
        watcher.apply(new MailWatcher.Settings(List.of(new MailWatcher.Spec(a, "INBOX")), true, 1, 10, true, List.of(),
                true, new MailCommand.Settings(null, null, 0)));
        long deadline = System.currentTimeMillis() + 10_000;
        while (watcher.status(a, "INBOX") == null || !watcher.status(a, "INBOX").startsWith("überwacht")) {
            assertThat(System.currentTimeMillis()).isLessThan(deadline);
            Thread.sleep(50);
        }
        send().sendMail(null, ME, null, null, "Notiz an mich", "Erledigt.", null, null, true, null);
        assertThat(watcher.receive(10_000, 10, () -> { })).extracting(MailWatcher.NewMail::subject)
                .containsExactly("Notiz an mich"); // mail_receive liefert auch eigene Mails
        deliver("kunde@kunde-a.de", "Echte Mail", "Hallo");
        assertThat(watcher.receive(10_000, 10, () -> { })).extracting(MailWatcher.NewMail::subject)
                .containsExactly("Echte Mail");
        // Channel nur für die fremde Mail – die eigene stößt keinen Agenten an
        assertThat(channel.since(0)).singleElement().satisfies(e -> assertThat(e.meta()).containsEntry("subject",
                "Echte Mail"));
    }

    @Test
    void toolAndSpiOnlyWithSwitch() {
        MailModule module = new MailModule(watcher, confirmation);
        assertThat(module.createTools(config()).stream().map(t -> t.getToolDefinition().name())).contains("send");
        var service = new MailAccountService(watcher, confirmation, this::config, (m, t) -> true);
        assertThat(service.accounts().getFirst().sender()).isEqualTo("Felix Grebe <" + ME + ">");
        assertThat(service.send("arbeit", new MailSend("kunde@kunde-a.de", null, null, "SPI", "Text", null, null, true)))
                .startsWith("Gesendet");
        values.put(MailModule.ALLOW_SEND, "false");
        assertThat(module.createTools(config()).stream().map(t -> t.getToolDefinition().name())).doesNotContain("send");
        assertThatThrownBy(() -> service.send("arbeit", new MailSend("kunde@kunde-a.de", null, null, "x", "y", null,
                null, true))).hasMessageContaining("„Senden erlauben“");
        service.close();
    }

    @Test
    void connectionTestChecksSmtp() {
        var result = new MailModule(watcher, confirmation).testConnection(config());
        assertThat(result.success()).as(result.message()).isTrue();
        assertThat(result.message()).contains("SMTP 127.0.0.1:" + GREEN_MAIL.getSmtp().getPort() + " (none): verbunden, "
                + "Absender Felix Grebe <" + ME + ">");
    }

    // ------------------------------------------------------------------ Hilfen

    private ModuleConfig config() {
        Map<String, String> v = new LinkedHashMap<>(values);
        v.put(MailModule.ACCOUNTS, ModuleConfig.formatRecords(List.of(account)));
        return ModuleConfig.of(new MailModule(watcher).configSchema(), v);
    }

    private MailWriteTools.Send send() {
        return new MailWriteTools.Send(new MailEnvironment(config(), new MailEnvironment.Sessions(), watcher,
                confirmation));
    }

    private MailTools tools() {
        return new MailTools(new MailEnvironment(config(), new MailEnvironment.Sessions(), watcher, confirmation), 60);
    }

    private void deliver(String from, String subject, String text) throws Exception {
        MimeMessage m = new MimeMessage(Session.getInstance(new Properties()));
        m.setFrom(new InternetAddress(from));
        m.setRecipients(Message.RecipientType.TO, ME);
        m.setSubject(subject, "UTF-8");
        m.setText(text, "UTF-8");
        m.setSentDate(new java.util.Date());
        m.saveChanges();
        user.deliver(m);
    }

    private interface StoreBody {
        void run(Store store) throws Exception;
    }

    private void withStore(StoreBody body) throws Exception {
        Store store = Session.getInstance(new Properties()).getStore("imap");
        store.connect("127.0.0.1", GREEN_MAIL.getImap().getPort(), "felix", "geheim");
        try {
            body.run(store);
        } finally {
            store.close();
        }
    }
}
