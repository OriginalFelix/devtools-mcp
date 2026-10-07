package systems.grebe.devtools.mcp.modules.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

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
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import systems.grebe.devtools.mcp.core.ChannelEvents;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolRegistry;
import systems.grebe.devtools.mcp.modules.mail.spi.MailAccountInfo;
import systems.grebe.devtools.mcp.modules.mail.spi.MailDraft;
import systems.grebe.devtools.mcp.modules.mail.spi.MailMessage;
import systems.grebe.devtools.mcp.modules.mail.spi.MailQuery;
import systems.grebe.devtools.mcp.modules.mail.spi.MailSummary;
import systems.grebe.devtools.mcp.modules.mail.spi.NewMail;

/** Plugin-SPI {@code MailAccountProvider}: gleiche Konten, Freigaben, Schalter und Rechte wie die Tools. */
class MailAccountServiceTest {

    @RegisterExtension
    static final GreenMailExtension GREEN_MAIL = new GreenMailExtension(ServerSetupTest.SMTP_IMAP);

    private GreenMailUser user;
    private MailWatcher watcher;
    private final Map<String, String> values = new LinkedHashMap<>();
    private Set<String> denied = Set.of();
    private MailAccountService service;

    @BeforeEach
    void setUp() throws Exception {
        user = GREEN_MAIL.setUser("felix@example.com", "felix", "geheim");
        watcher = new MailWatcher(new StaticListableBeanFactory().getBeanProvider(ToolRegistry.class), new ChannelEvents(),
                MailState.inMemory(), MailOAuth.inMemory());
        Map<String, String> account = new LinkedHashMap<>();
        account.put(MailAccount.NAME, "support");
        account.put(MailAccount.HOST, "127.0.0.1");
        account.put(MailAccount.PORT, Integer.toString(GREEN_MAIL.getImap().getPort()));
        account.put(MailAccount.SECURITY, MailAccount.PLAIN);
        account.put(MailAccount.USERNAME, "felix");
        account.put(MailAccount.PASSWORD, "geheim");
        account.put(MailAccount.FOLDERS, "INBOX\nArchiv\nDrafts");
        account.put(MailAccount.WATCH, "INBOX\nPrivat");
        values.put(MailModule.ACCOUNTS, ModuleConfig.formatRecords(List.of(account)));
        MailModule module = new MailModule(watcher);
        service = new MailAccountService(watcher, () -> ModuleConfig.of(module.configSchema(), values),
                (moduleId, tool) -> !denied.contains(tool));
        Properties p = new Properties();
        Store store = Session.getInstance(p).getStore("imap");
        store.connect("127.0.0.1", GREEN_MAIL.getImap().getPort(), "felix", "geheim");
        for (String f : List.of("Archiv", "Drafts", "Privat")) {
            store.getFolder(f).create(Folder.HOLDS_MESSAGES);
        }
        store.close();
    }

    @AfterEach
    void tearDown() {
        service.close();
        watcher.close();
    }

    @Test
    void accountsWithoutSecrets() {
        assertThat(service.accounts()).singleElement().satisfies(a -> {
            assertThat(a.name()).isEqualTo("support");
            assertThat(a.address()).startsWith("felix@127.0.0.1:");
            assertThat(a.auth()).isEqualTo("password");
            assertThat(a.folders()).containsExactly("INBOX", "Archiv", "Drafts");
            // Privat ist nicht freigegeben, also auch nicht überwacht
            assertThat(a.watched()).containsExactly("INBOX");
            assertThat(a.loggedIn()).isTrue();
            assertThat(a.allows("inbox")).isTrue();
            assertThat(a.allows("Privat")).isFalse();
            assertThat(a.toString()).doesNotContain("geheim");
        });
        assertThat(service.account("SUPPORT")).isPresent();
        assertThat(service.folders(null)).extracting(f -> f.name()).containsExactlyInAnyOrder("INBOX", "Archiv", "Drafts");
    }

    @Test
    void listReadAndAttachment() throws Exception {
        deliver("kunde@kunde-a.de", "Rechnung 4711", "Anbei die Rechnung.", "rechnung.csv", "pos;betrag\n1;99,90");
        deliver("chef@firma.de", "Termin", "Morgen 9 Uhr?", null, null);

        List<MailSummary> all = service.list("support", null, MailQuery.ALL, 10);
        assertThat(all).extracting(MailSummary::subject).containsExactly("Termin", "Rechnung 4711");
        MailSummary rechnung = all.get(1);
        assertThat(rechnung.fromAddress()).isEqualTo("kunde@kunde-a.de");
        assertThat(rechnung.hasAttachments()).isTrue();
        assertThat(rechnung.seen()).isFalse();
        assertThat(service.list(null, "INBOX", new MailQuery("Rechnung", null, null, true, LocalDate.of(2000, 1, 1)), 10))
                .extracting(MailSummary::uid).containsExactly(rechnung.uid());

        MailMessage m = service.read("support", "INBOX", rechnung.uid());
        assertThat(m.text()).isEqualTo("Anbei die Rechnung.");
        assertThat(m.to()).contains("felix@example.com");
        assertThat(m.attachments()).singleElement().satisfies(a -> {
            assertThat(a.index()).isZero();
            assertThat(a.name()).isEqualTo("rechnung.csv");
        });
        var att = service.attachment("support", "INBOX", rechnung.uid(), 0, 1024);
        assertThat(new String(att.data(), StandardCharsets.UTF_8)).isEqualTo("pos;betrag\n1;99,90");
        assertThatThrownBy(() -> service.attachment("support", "INBOX", rechnung.uid(), 0, 5))
                .hasMessageContaining("größer als 5 Bytes");
        assertThatThrownBy(() -> service.attachment("support", "INBOX", rechnung.uid(), 3, 1024))
                .hasMessageContaining("Anhang 3 gibt es nicht");
        // Lesen markiert nicht als gelesen
        assertThat(service.list("support", null, MailQuery.ALL, 10).get(1).seen()).isFalse();

        assertThatThrownBy(() -> service.list("support", "Privat", MailQuery.ALL, 10)).hasMessageContaining("nicht freigegeben");
        denied = Set.of("mail_read");
        assertThatThrownBy(() -> service.read("support", null, rechnung.uid())).hasMessageContaining("mail_read");
    }

    @Test
    void writesNeedSwitchAndPermission() throws Exception {
        deliver("kunde@kunde-a.de", "Anfrage", "Geht das?", null, null);
        long uid = service.list("support", null, MailQuery.ALL, 1).getFirst().uid();

        assertThatThrownBy(() -> service.mark("support", null, List.of(uid), "seen", true))
                .hasMessageContaining("„Markieren erlauben“");
        values.put(MailModule.ALLOW_FLAGS, "true");
        values.put(MailModule.ALLOW_MOVE, "true");
        values.put(MailModule.ALLOW_DRAFTS, "true");
        denied = Set.of("mail_move");
        assertThatThrownBy(() -> service.move("support", null, List.of(uid), "Archiv")).hasMessageContaining("mail_move");
        denied = Set.of();

        assertThat(service.mark("support", null, List.of(uid), "seen", true)).contains("1 Mail(s)");
        assertThat(service.list("support", null, MailQuery.ALL, 1).getFirst().seen()).isTrue();
        assertThat(service.draft("support", new MailDraft(null, null, null, "Ja.", null, uid))).contains("Re: Anfrage");
        assertThat(service.move("support", null, List.of(uid), "Archiv")).contains("verschoben");
        assertThat(service.list("support", "Archiv", MailQuery.ALL, 5)).extracting(MailSummary::subject)
                .containsExactly("Anfrage");
    }

    @Test
    void newMailListener() throws Exception {
        List<NewMail> received = new CopyOnWriteArrayList<>();
        AutoCloseable sub = service.onNewMail(received::add);
        MailAccountInfo info = service.accounts().getFirst();
        MailAccount a = MailEnvironment.accounts(ModuleConfig.of(new MailModule(watcher).configSchema(), values))
                .getFirst();
        watcher.apply(new MailWatcher.Settings(List.of(new MailWatcher.Spec(a, "INBOX")), true, 1, 10, true, List.of(),
                false, new MailCommand.Settings(null, null, 0)));
        long deadline = System.currentTimeMillis() + 10_000;
        while (watcher.status(a, "INBOX") == null || !watcher.status(a, "INBOX").startsWith("überwacht")) {
            assertThat(System.currentTimeMillis()).isLessThan(deadline);
            Thread.sleep(50);
        }
        deliver("kunde@kunde-a.de", "Eilig", "Bitte melden.", null, null);
        while (received.isEmpty()) {
            assertThat(System.currentTimeMillis()).isLessThan(deadline + 10_000);
            Thread.sleep(50);
        }
        assertThat(received).singleElement().satisfies(n -> {
            assertThat(n.summary().account()).isEqualTo(info.name());
            assertThat(n.summary().subject()).isEqualTo("Eilig");
            assertThat(n.summary().fromAddress()).isEqualTo("kunde@kunde-a.de");
            assertThat(n.seen()).isFalse();
        });
        sub.close();
        watcher.receive(0, 50, () -> { }); // Eingang leeren
        deliver("kunde@kunde-a.de", "Nach dem Abmelden", "x", null, null);
        assertThat(watcher.receive(10_000, 10, () -> { })).extracting(MailWatcher.NewMail::subject)
                .containsExactly("Nach dem Abmelden"); // Watcher meldet weiter …
        assertThat(received).hasSize(1); // … der abgemeldete Listener nicht
    }

    private void deliver(String from, String subject, String text, String fileName, String fileContent) throws Exception {
        MimeMessage m = new MimeMessage(Session.getInstance(new Properties()));
        m.setFrom(new InternetAddress(from));
        m.setRecipients(Message.RecipientType.TO, "felix@example.com");
        m.setSubject(subject, "UTF-8");
        if (fileName == null) {
            m.setText(text, "UTF-8");
        } else {
            MimeBodyPart body = new MimeBodyPart();
            body.setText(text, "UTF-8");
            MimeBodyPart file = new MimeBodyPart();
            file.setText(fileContent, "UTF-8");
            file.setFileName(fileName);
            file.setDisposition(MimeBodyPart.ATTACHMENT);
            m.setContent(new MimeMultipart(body, file));
        }
        m.setSentDate(new java.util.Date());
        m.saveChanges();
        user.deliver(m);
    }
}
