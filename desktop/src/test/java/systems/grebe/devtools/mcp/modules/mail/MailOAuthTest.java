package systems.grebe.devtools.mcp.modules.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.sun.net.httpserver.HttpExchange;
import jakarta.mail.Message;
import jakarta.mail.internet.MimeMessage;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import systems.grebe.devtools.mcp.core.ChannelEvents;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolRegistry;

/** Exchange Online: Device-Code-Flow und Refresh gegen einen nachgebauten Entra-Endpunkt, XOAUTH2 gegen einen IMAP-Stub. */
class MailOAuthTest {

    private static final String MAILBOX = "postfach@firma.de";

    private HttpServer entra;
    private final AtomicInteger devicePolls = new AtomicInteger();
    private final List<String> refreshCalls = new CopyOnWriteArrayList<>();
    private ImapStub imap;

    @BeforeEach
    void setUp() throws IOException {
        entra = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        entra.createContext("/tenant-x/oauth2/v2.0/devicecode", ex -> {
            Map<String, String> form = form(ex);
            assertThat(form).containsEntry("client_id", "cid");
            assertThat(form.get("scope")).startsWith(MailOAuth.SCOPES);
            respond(ex, 200, "{\"device_code\":\"DC\",\"user_code\":\"ABCD-1234\",\"verification_uri\":"
                    + "\"https://microsoft.com/devicelogin\",\"expires_in\":600,\"interval\":1}");
        });
        entra.createContext("/tenant-x/oauth2/v2.0/token", ex -> {
            Map<String, String> form = form(ex);
            switch (form.get("grant_type")) {
                case MailOAuth.DEVICE_CODE_GRANT -> {
                    if (devicePolls.incrementAndGet() < 2) {
                        respond(ex, 400, "{\"error\":\"authorization_pending\",\"error_description\":\"warte\"}");
                    } else {
                        respond(ex, 200, "{\"access_token\":\"AT1\",\"refresh_token\":\"RT1\",\"expires_in\":3600}");
                    }
                }
                case "refresh_token" -> {
                    refreshCalls.add(form.get("refresh_token"));
                    if (form.get("refresh_token").equals("RT1")) {
                        respond(ex, 200, "{\"access_token\":\"AT2\",\"refresh_token\":\"RT2\",\"expires_in\":3600}");
                    } else {
                        respond(ex, 400, "{\"error\":\"invalid_grant\",\"error_description\":\"AADSTS70008: abgelaufen\\n"
                                + "Trace ID: 123\"}");
                    }
                }
                default -> respond(ex, 400, "{\"error\":\"unsupported_grant_type\"}");
            }
        });
        entra.start();
        imap = new ImapStub();
    }

    @AfterEach
    void tearDown() throws IOException {
        entra.stop(0);
        imap.close();
    }

    @Test
    void deviceCodeLoginThenXoauth2(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("mail-tokens.json");
        MailOAuth oauth = new MailOAuth(file, s -> "enc:" + s, s -> s.substring(4));
        MailWatcher watcher = watcher(oauth);
        MailModule module = new MailModule(watcher);
        ModuleConfig config = ModuleConfig.of(module.configSchema(), Map.of(MailModule.ACCOUNTS,
                ModuleConfig.formatRecords(List.of(account())), MailModule.MS_AUTHORITY, authority() + "/"));
        MailAccount a = MailEnvironment.accounts(config).getFirst();
        assertThat(a.microsoft()).isTrue();

        // ohne Anmeldung: klare Meldung, der IMAP-Server wird gar nicht erst gefragt
        var before = module.testConnection(config);
        assertThat(before.success()).isFalse();
        assertThat(before.message()).contains("nicht angemeldet", "mail_login");
        assertThat(imap.auths).isEmpty();
        assertThat(module.createTools(config).stream().map(t -> t.getToolDefinition().name())).contains("login");
        assertThat(module.actions().getFirst().targets(config)).containsExactly("exchange");

        List<String> logins = new ArrayList<>();
        oauth.addLoginListener(logins::add);
        List<String> prompts = new ArrayList<>();
        String result = oauth.login(a, prompts::add);
        assertThat(prompts).singleElement().asString().contains("https://microsoft.com/devicelogin", "ABCD-1234", MAILBOX);
        assertThat(result).contains("angemeldet");
        assertThat(logins).containsExactly("exchange");
        assertThat(oauth.status(a)).isEqualTo("angemeldet");
        // Refresh-Token verschlüsselt abgelegt
        assertThat(Files.readString(file)).contains("enc:RT1");

        var after = module.testConnection(config);
        assertThat(after.message()).contains("verbunden", "IDLE ja");
        assertThat(after.success()).isTrue();
        assertThat(imap.auths).singleElement().isEqualTo("user=" + MAILBOX + "\u0001auth=Bearer AT1\u0001\u0001");

        // neue Instanz (Neustart): Refresh-Token aus der Datei, neues Access-Token per Refresh
        MailOAuth restarted = new MailOAuth(file, s -> "enc:" + s, s -> s.substring(4));
        assertThat(restarted.loggedIn(a)).isTrue();
        assertThat(restarted.accessToken(a)).isEqualTo("AT2");
        assertThat(refreshCalls).containsExactly("RT1");
        assertThat(Files.readString(file)).contains("enc:RT2");
        watcher.close();
    }

    @Test
    void revokedRefreshTokenAsksForNewLogin() {
        MailOAuth oauth = MailOAuth.inMemory();
        MailAccount a = MailAccount.of(account(), authority());
        // Anmeldung mit einem Refresh-Token, das der Dienst nicht mehr kennt
        oauth.login(a, p -> { });
        refreshCalls.clear();
        oauth.invalidate(a);
        // AT1 verworfen → Refresh mit RT1 klappt
        assertThat(oauth.accessToken(a)).isEqualTo("AT2");
        oauth.invalidate(a);
        // RT2 kennt der Fake nicht → invalid_grant → abgemeldet
        assertThatThrownBy(() -> oauth.accessToken(a)).isInstanceOf(MailOAuth.NotLoggedInException.class)
                .hasMessageContaining("neu anmelden").hasMessageContaining("AADSTS70008").hasMessageNotContaining("Trace");
        assertThat(oauth.loggedIn(a)).isFalse();
        assertThatThrownBy(() -> oauth.accessToken(a)).hasMessageContaining("nicht angemeldet");
    }

    @Test
    void sendsViaSmtpXoauth2AndAsksForSmtpSend() throws Exception {
        GreenMail smtp = new GreenMail(ServerSetupTest.SMTP);
        smtp.start();
        try {
            smtp.setUser(MAILBOX, MAILBOX, "AT1");
            Map<String, String> r = account();
            r.put(MailAccount.SMTP_HOST, "127.0.0.1");
            r.put(MailAccount.SMTP_PORT, Integer.toString(smtp.getSmtp().getPort()));
            r.put(MailAccount.SMTP_SECURITY, MailAccount.PLAIN);
            MailAccount a = MailAccount.of(r, authority());
            assertThat(a.canSend()).isTrue();
            // mit SMTP-Server fordert die Anmeldung zusätzlich SMTP.Send an (gleiche Zielgruppe, ein Token)
            assertThat(MailOAuth.scopes(a)).isEqualTo(MailOAuth.SCOPES + " " + MailOAuth.SMTP_SCOPE);

            MailOAuth oauth = MailOAuth.inMemory();
            oauth.login(a, p -> { });
            MimeMessage m = new MimeMessage(MailSender.session(a, Duration.ofSeconds(10)));
            m.setFrom(MAILBOX);
            m.setRecipients(Message.RecipientType.TO, "kunde@kunde-a.de");
            m.setSubject("Per XOAUTH2");
            m.setText("Hallo");
            MailSender.send(a, oauth, m, Duration.ofSeconds(10));
            assertThat(smtp.getReceivedMessages()).singleElement()
                    .satisfies(x -> assertThat(x.getSubject()).isEqualTo("Per XOAUTH2"));
        } finally {
            smtp.stop();
        }
    }

    @Test
    void passwordAccountsNeedNoLogin() {
        Map<String, String> r = account();
        r.put(MailAccount.AUTH, MailAccount.PASSWORD_AUTH);
        assertThatThrownBy(() -> MailOAuth.inMemory().login(MailAccount.of(r), p -> { }))
                .hasMessageContaining("Passwort");
        Map<String, String> noClient = account();
        noClient.put(MailAccount.CLIENT_ID, "");
        assertThatThrownBy(() -> MailOAuth.inMemory().login(MailAccount.of(noClient), p -> { }))
                .hasMessageContaining("Client-ID");
    }

    @Test
    void microsoftDefaultsHost() {
        Map<String, String> r = account();
        r.remove(MailAccount.HOST);
        r.remove(MailAccount.PORT);
        r.remove(MailAccount.SECURITY);
        MailAccount a = MailAccount.of(r);
        assertThat(a.host()).isEqualTo("outlook.office365.com");
        assertThat(a.port()).isEqualTo(993);
        assertThat(a.authority()).isEqualTo(MailAccount.DEFAULT_AUTHORITY);
    }

    // ------------------------------------------------------------------ Hilfen

    private MailWatcher watcher(MailOAuth oauth) {
        return new MailWatcher(new StaticListableBeanFactory().getBeanProvider(ToolRegistry.class), new ChannelEvents(),
                MailState.inMemory(), oauth);
    }

    private String authority() {
        return "http://127.0.0.1:" + entra.getAddress().getPort();
    }

    private Map<String, String> account() {
        Map<String, String> a = new LinkedHashMap<>();
        a.put(MailAccount.NAME, "exchange");
        a.put(MailAccount.AUTH, MailAccount.MICROSOFT);
        a.put(MailAccount.HOST, "127.0.0.1");
        a.put(MailAccount.PORT, Integer.toString(imap == null ? 1 : imap.port()));
        a.put(MailAccount.SECURITY, MailAccount.PLAIN);
        a.put(MailAccount.USERNAME, MAILBOX);
        a.put(MailAccount.TENANT, "tenant-x");
        a.put(MailAccount.CLIENT_ID, "cid");
        a.put(MailAccount.WATCH, "");
        return a;
    }

    private static Map<String, String> form(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> out = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    private static void respond(HttpExchange ex, int status, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(b);
        }
    }

    /**
     * Minimaler IMAP-Server für die Anmeldung per {@code AUTHENTICATE XOAUTH2} (GreenMail kann das nicht): merkt sich die
     * dekodierte Anmeldung und nimmt nur {@code AT1} an.
     */
    private static final class ImapStub implements AutoCloseable {
        private static final String CAPS = "IMAP4rev1 AUTH=XOAUTH2 SASL-IR IDLE";
        final List<String> auths = new CopyOnWriteArrayList<>();
        private final ServerSocket server = new ServerSocket(0, 10, java.net.InetAddress.getLoopbackAddress());

        ImapStub() throws IOException {
            Thread.ofVirtual().start(() -> {
                while (!server.isClosed()) {
                    try {
                        Socket s = server.accept();
                        Thread.ofVirtual().start(() -> serve(s));
                    } catch (IOException e) {
                        return;
                    }
                }
            });
        }

        int port() {
            return server.getLocalPort();
        }

        private void serve(Socket socket) {
            try (socket; BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(),
                    StandardCharsets.US_ASCII)); PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
                send(out, "* OK [CAPABILITY " + CAPS + "] Stub bereit");
                String line;
                while ((line = in.readLine()) != null) {
                    String[] parts = line.split(" ", 3);
                    String tag = parts[0];
                    String cmd = parts.length > 1 ? parts[1].toUpperCase() : "";
                    switch (cmd) {
                        case "CAPABILITY" -> {
                            send(out, "* CAPABILITY " + CAPS);
                            send(out, tag + " OK CAPABILITY completed");
                        }
                        case "AUTHENTICATE" -> {
                            String[] args = parts.length > 2 ? parts[2].split(" ") : new String[0];
                            String b64 = args.length > 1 ? args[1] : null;
                            if (b64 == null) {
                                send(out, "+ ");
                                b64 = in.readLine();
                            }
                            String decoded = new String(Base64.getDecoder().decode(b64.strip()), StandardCharsets.UTF_8);
                            auths.add(decoded);
                            send(out, decoded.contains("auth=Bearer AT1") ? tag + " OK AUTHENTICATE completed"
                                    : tag + " NO AUTHENTICATE failed");
                        }
                        case "LIST" -> {
                            send(out, "* LIST (\\HasNoChildren) \"/\" INBOX");
                            send(out, tag + " OK LIST completed");
                        }
                        case "LOGOUT" -> {
                            send(out, "* BYE");
                            send(out, tag + " OK LOGOUT completed");
                            return;
                        }
                        default -> send(out, tag + " OK " + cmd + " completed");
                    }
                }
            } catch (IOException ignored) {
                // Verbindung beendet
            }
        }

        private static void send(PrintWriter out, String line) {
            out.print(line + "\r\n");
            out.flush();
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }
}
