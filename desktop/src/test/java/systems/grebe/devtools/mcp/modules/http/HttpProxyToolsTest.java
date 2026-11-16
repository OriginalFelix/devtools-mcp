package systems.grebe.devtools.mcp.modules.http;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Proxy über die Tools: HTTP weiterreichen, HTTPS per eigener CA entschlüsseln und mocken, Mitschnitt auswerten. */
class HttpProxyToolsTest {

    @TempDir
    Path dir;

    @Test
    void recordsForwardsMocksAndAnalyzes() throws Exception {
        HttpServer upstream = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        upstream.createContext("/", x -> {
            byte[] body = ("{\"path\":\"" + x.getRequestURI().getPath() + "\"}").getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().add("Content-Type", "application/json");
            x.sendResponseHeaders(x.getRequestURI().getPath().equals("/fail") ? 500 : 200, body.length);
            x.getResponseBody().write(body);
            x.close();
        });
        upstream.start();
        var proxies = new HttpProxyTools.Proxies();
        try (proxies) {
            var tools = new HttpProxyTools(dir, proxies);
            assertThat(tools.proxyStart(null)).contains("HTTP-Proxy läuft auf http://127.0.0.1:", "HTTPS_PROXY=",
                    "-Djavax.net.ssl.trustStore=" + dir.resolve("truststore.p12"));
            int port = proxies.byPort.keySet().iterator().next();
            HttpClient client = client(port);
            String base = "http://127.0.0.1:" + upstream.getAddress().getPort();

            for (int i = 1; i <= 5; i++) {
                assertThat(get(client, base + "/users/" + i)).isEqualTo("200 {\"path\":\"/users/" + i + "\"}");
            }
            assertThat(get(client, base + "/fail")).startsWith("500");

            // HTTPS auf einen Host, den es nicht gibt: CONNECT, TLS mit der eigenen CA, Antwort vom Mock
            assertThat(tools.mockAdd("api\\.example\\.invalid/v1/orders", "GET", 201, "[{\"id\":7}]", "X-Mock: ja",
                    null, 1, null)).contains("Mock #1 angelegt", "GET api\\.example\\.invalid/v1/orders → 201");
            HttpResponse<String> mocked = client.send(HttpRequest.newBuilder(
                    URI.create("https://api.example.invalid/v1/orders")).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(mocked.statusCode()).isEqualTo(201);
            assertThat(mocked.body()).isEqualTo("[{\"id\":7}]");
            assertThat(mocked.headers().firstValue("X-Mock")).hasValue("ja");
            // times=1 verbraucht: jetzt weitergereicht, der Host existiert nicht → 502 vom Proxy
            assertThat(get(client, "https://api.example.invalid/v1/orders")).startsWith("502 DevTools-Proxy:");

            assertThat(tools.traffic(null, "orders", null, null, null))
                    .contains("2 passende von 8 Anfragen", "→ 201", "[Mock #1]", "✗");
            assertThat(tools.traffic(null, null, 7, null, null)).contains("1 passende von 8", "#8 ");
            assertThat(tools.traffic(null, null, null, true, null)).contains("2 passende", "/fail → 500");
            assertThat(tools.exchange(1, null, null)).contains("--- Anfrage ---\nGET " + base + "/users/1",
                    "--- Antwort ---\n200", "{\"path\":\"/users/1\"}");
            assertThat(tools.analyze(null, null, null)).contains("8 Anfragen (1 gemockt, 2 fehlgeschlagen)",
                    "5×", "GET " + base + "/users/{id}", "N+1-Verdacht");
            assertThat(tools.mockRemove(null, null)).contains("Keine Mocks aktiv");
            assertThat(tools.proxyStop(port)).contains("gestoppt", "Keine HTTP-Proxys aktiv");
        } finally {
            upstream.stop(0);
        }
        assertThatThrownBy(() -> new HttpProxyTools(dir, new HttpProxyTools.Proxies()).traffic(null, null, null,
                null, null)).hasMessageContaining("http_proxy_start");
    }

    @Test
    void decodesGzipAndDetectsBinary() throws Exception {
        var gz = new java.io.ByteArrayOutputStream();
        try (var out = new java.util.zip.GZIPOutputStream(gz)) {
            out.write("hallo".getBytes(StandardCharsets.UTF_8));
        }
        byte[] zipped = gz.toByteArray();
        assertThat(HttpProxyTools.body(java.util.List.of(new HttpProxy.Header("Content-Encoding", "gzip")), zipped,
                zipped.length, 100)).isEqualTo("hallo");
        assertThat(HttpProxyTools.body(java.util.List.of(), new byte[] {1, 0, 2}, 3, 100)).startsWith("binär, 3 Bytes");
    }

    /** Client, der den Proxy nimmt und dem Truststore aus http_proxy_start vertraut – wie ein Prozess mit -D-Optionen. */
    private HttpClient client(int port) throws Exception {
        KeyStore trust = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(dir.resolve("truststore.p12"))) {
            trust.load(in, CertificateAuthority.TRUSTSTORE_PASSWORD.toCharArray());
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(null, tmf.getTrustManagers(), null);
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).sslContext(ssl)
                .proxy(ProxySelector.of(new InetSocketAddress("127.0.0.1", port))).build();
    }

    private static String get(HttpClient client, String url) throws Exception {
        HttpResponse<String> r = client.send(HttpRequest.newBuilder(URI.create(url)).build(),
                HttpResponse.BodyHandlers.ofString());
        return r.statusCode() + " " + r.body();
    }
}
