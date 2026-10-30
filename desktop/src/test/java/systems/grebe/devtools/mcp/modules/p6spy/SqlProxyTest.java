package systems.grebe.devtools.mcp.modules.p6spy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import systems.grebe.devtools.mcp.core.LocalFiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SQL im Datenverkehr erkennen und als p6spy-Protokoll schreiben. */
class SqlProxyTest {

    @TempDir
    Path dir;

    @Test
    void findsSqlInWireMessages() {
        // PostgreSQL Query: 'Q', Länge (druckbar: '*'), SQL, 0
        assertThat(SqlProxy.find(bytes("Q\0\0\0*select * from t where id = 1\0")))
                .containsExactly("select * from t where id = 1");
        // MySQL COM_QUERY mit mehrzeiligem SQL
        assertThat(SqlProxy.find(bytes("\u001d\0\0\0\u0003UPDATE t\n  SET a = 'x||y'")))
                .containsExactly("UPDATE t SET a = 'x||y'");
        // SQL Server: UTF-16LE
        byte[] tds = ("\u0001\u0001" + "SELECT name FROM sys.tables").getBytes(StandardCharsets.UTF_16LE);
        assertThat(SqlProxy.find(tds)).containsExactly("SELECT name FROM sys.tables");
        // Anmeldung, Spaltennamen mit Schlüsselwort, kein SQL
        assertThat(SqlProxy.find(bytes("\0\0\0Puser\0felix\0database\0last_update set\0client_encoding\0UTF8\0")))
                .isEmpty();
        assertThat(SqlProxy.find(bytes("\u0003COMMIT"))).containsExactly("COMMIT");
    }

    @Test
    void forwardsTrafficAndLogsStatementsForP6SpyTools() throws Exception {
        try (ServerSocket db = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            Thread.ofVirtual().start(() -> answerEach(db));
            var files = new LocalFiles(List.of(dir.toString()), "Test");
            var proxies = new P6SpyProxyTools.Proxies();
            try (proxies) {
                String started = new P6SpyProxyTools(files, proxies)
                        .proxyStart("localhost:" + db.getLocalPort(), dir.toString(), null);
                assertThat(started).contains("SQL-Proxy läuft auf localhost:", "0 Statements");
                SqlProxy proxy = proxies.byPort.values().iterator().next();

                try (Socket app = new Socket(InetAddress.getLoopbackAddress(), proxy.port())) {
                    assertThat(exchange(app, "Q\0\0\0*select * from customer where id = 1\0")).isEqualTo("ok");
                    for (int i = 1; i <= 5; i++) {
                        exchange(app, "Q\0\0\0*select * from orders where customer_id = " + i + "\0");
                    }
                    exchange(app, "Q\0\0\0\u000bCOMMIT\0");
                }

                P6SpyTools tools = new P6SpyTools(files, 1 << 20);
                String log = proxy.log().toString();
                assertThat(tools.statements(log, null, "customer where", null, null, null, null))
                        .contains("1 passende von 7 Einträgen", "conn 1", "select * from customer where id = 1");
                assertThat(tools.analyze(log, null, null, null, null))
                        .contains("6 Statements", "1 Commits", "N+1-Verdacht",
                                "select * from orders where customer_id = ?");
                assertThat(new P6SpyProxyTools(files, proxies).proxyStop(proxy.port()))
                        .contains("gestoppt", "Keine SQL-Proxys aktiv");
            }
        }
        assertThatThrownBy(() -> new P6SpyProxyTools(new LocalFiles(List.of(dir.toString()), "Test"),
                new P6SpyProxyTools.Proxies()).proxyStart("localhost", dir.toString(), null))
                .hasMessageContaining("host:port");
    }

    /** Antwortet auf jede Anfrage mit "ok". */
    private static void answerEach(ServerSocket db) {
        try (Socket s = db.accept()) {
            byte[] buf = new byte[4096];
            while (s.getInputStream().read(buf) >= 0) {
                s.getOutputStream().write(bytes("ok"));
                s.getOutputStream().flush();
            }
        } catch (IOException e) {
            // Test vorbei
        }
    }

    private static String exchange(Socket app, String request) throws IOException {
        app.getOutputStream().write(bytes(request));
        app.getOutputStream().flush();
        ByteArrayOutputStream answer = new ByteArrayOutputStream();
        while (answer.size() < 2) {
            answer.write(app.getInputStream().read());
        }
        return answer.toString(StandardCharsets.ISO_8859_1);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }
}
