package systems.grebe.devtools.mcp.modules.http;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ToolHints;
import systems.grebe.devtools.mcp.modules.http.HttpProxy.Exchange;
import systems.grebe.devtools.mcp.modules.http.HttpProxy.Header;
import systems.grebe.devtools.mcp.modules.http.HttpProxy.Mock;

/** HTTP-Proxys starten/stoppen, ihren Mitschnitt lesen und auswerten, Antworten mocken. */
@ToolHints(destructive = false)
public class HttpProxyTools {

    /** Laufende Proxys je Benutzer/Profil (lokaler Port → Proxy); schließt mit dem Scope. */
    static final class Proxies implements AutoCloseable {
        final Map<Integer, HttpProxy> byPort = new ConcurrentSkipListMap<>();

        @Override
        public void close() {
            byPort.values().forEach(HttpProxy::close);
            byPort.clear();
        }
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault());
    private static final Pattern CHARSET = Pattern.compile("charset=\"?([\\w.:-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ID_SEGMENT = Pattern.compile(
            "/(\\d+|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}|[0-9a-fA-F]{16,})(?=/|$)");

    private final Path caDir;
    private final Proxies proxies;

    HttpProxyTools(Path caDir, Proxies proxies) {
        this.caDir = caDir;
        this.proxies = proxies;
    }

    @Tool(name = "proxy_start", description = "Startet einen HTTP(S)-Proxy auf localhost (wie Fiddler): zeichnet "
            + "Anfragen und Antworten des Prozesses auf, der ihn benutzt, entschlüsselt HTTPS mit einer eigenen CA "
            + "und beantwortet Anfragen, auf die ein Mock passt (http_mock_add), ohne sie weiterzureichen. Liefert "
            + "die Umgebungsvariablen bzw. JVM-Optionen, mit denen ein Prozess (Java, Node, Python, curl, Go …) über "
            + "den Proxy läuft. Je Prozess ein eigener Proxy hält den Mitschnitt getrennt.")
    public String proxyStart(
            @ToolParam(required = false, description = "Lokaler Port (Standard: frei gewählt)") Integer port) {
        HttpProxy proxy;
        CertificateAuthority ca;
        try {
            ca = CertificateAuthority.load(caDir);
            proxy = new HttpProxy(port == null ? 0 : port, ca);
        } catch (IOException e) {
            throw new UncheckedIOException("HTTP-Proxy nicht gestartet: " + e.getMessage(), e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("CA nicht geladen: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Abgebrochen", e);
        }
        proxies.byPort.put(proxy.port(), proxy);
        String url = "http://127.0.0.1:" + proxy.port();
        return """
                HTTP-Proxy läuft auf %1$s (HTTPS wird mit der CA %2$s entschlüsselt).

                Prozess damit starten – Umgebung (curl, Python, Node, Go, Ruby, .NET …):
                  HTTP_PROXY=%1$s HTTPS_PROXY=%1$s http_proxy=%1$s https_proxy=%1$s
                  SSL_CERT_FILE=%3$s REQUESTS_CA_BUNDLE=%3$s CURL_CA_BUNDLE=%3$s NODE_EXTRA_CA_CERTS=%2$s
                  Node ≥ 24 zusätzlich NODE_USE_ENV_PROXY=1 (ältere Node-Versionen ignorieren HTTP_PROXY).
                Java (auch per JAVA_TOOL_OPTIONS, ohne die Startzeile zu ändern):
                  -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=%4$d -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=%4$d \
                -Djavax.net.ssl.trustStore=%5$s -Djavax.net.ssl.trustStorePassword=%6$s
                  Apache HttpClient nutzt diese Properties nur mit useSystemProperties(); Aufrufe an localhost gehen \
                nur mit -Dhttp.nonProxyHosts= (leer) über den Proxy.
                Browser/System: %2$s als vertrauenswürdige CA eintragen, Proxy 127.0.0.1:%4$d.

                Danach: http_traffic, http_exchange, http_analyze; Mocks mit http_mock_add.

                %7$s""".formatted(url, ca.pem, ca.bundle, proxy.port(), ca.truststore,
                CertificateAuthority.TRUSTSTORE_PASSWORD, list());
    }

    @Tool(name = "proxy_stop", description = "Stoppt einen mit http_proxy_start gestarteten HTTP-Proxy (oder ohne "
            + "port alle); Mitschnitt und Mocks dieses Proxys sind danach weg.")
    public String proxyStop(
            @ToolParam(required = false, description = "Lokaler Port des Proxys (Standard: alle)") Integer port) {
        if (port == null) {
            int n = proxies.byPort.size();
            proxies.close();
            return n + " HTTP-Proxy(s) gestoppt.";
        }
        HttpProxy proxy = proxies.byPort.remove(port);
        if (proxy == null) {
            throw new IllegalArgumentException("Kein HTTP-Proxy auf Port " + port + ".\n" + list());
        }
        proxy.close();
        return "HTTP-Proxy auf Port " + port + " gestoppt.\n" + list();
    }

    @Tool(name = "traffic", description = "Listet die mitgeschnittenen Anfragen eines HTTP-Proxys: Nummer, Zeit, "
            + "Methode, URL, Status, Dauer, Größe, ggf. Mock. Für eine einzelne Aktion: höchste Nummer merken, Aktion "
            + "auslösen, dann mit since nur das Neue lesen. Details zu einer Anfrage: http_exchange.")
    public String traffic(
            @ToolParam(required = false, description = "Port des Proxys (nötig, wenn mehrere laufen)") Integer port,
            @ToolParam(required = false, description = "Nur Anfragen, deren \"METHODE URL STATUS\" diesen Text "
                    + "enthält (Groß-/Kleinschreibung egal)") String filter,
            @ToolParam(required = false, description = "Nur Anfragen mit höherer Nummer") Integer since,
            @ToolParam(required = false, description = "Nur Fehler (Status ≥ 400, Verbindungsfehler)") Boolean errorsOnly,
            @ToolParam(required = false, description = "Höchstens so viele, die neuesten (Standard 100)") Integer limit) {
        HttpProxy proxy = proxy(port);
        List<Exchange> all = proxy.exchanges();
        List<Exchange> hits = select(all, filter, since).stream()
                .filter(e -> !Boolean.TRUE.equals(errorsOnly) || e.failed()).toList();
        int max = limit == null || limit <= 0 ? 100 : limit;
        StringBuilder sb = new StringBuilder(hits.size() + " passende von " + all.size() + " Anfragen (Proxy :"
                + proxy.port() + ")");
        if (hits.size() > max) {
            sb.append(", die neuesten ").append(max);
        }
        hits.subList(Math.max(0, hits.size() - max), hits.size()).forEach(e -> sb.append('\n').append(line(e)));
        return sb.toString();
    }

    @Tool(name = "exchange", description = "Zeigt eine mitgeschnittene Anfrage vollständig: Header und Body von "
            + "Anfrage und Antwort (gzip/deflate entpackt, Text gekürzt).")
    public String exchange(
            @ToolParam(description = "Nummer aus http_traffic") int id,
            @ToolParam(required = false, description = "Port des Proxys (nötig, wenn mehrere laufen)") Integer port,
            @ToolParam(required = false, description = "Höchstens so viele Zeichen je Body (Standard 4000)")
            Integer maxChars) {
        Exchange e = proxy(port).exchanges().stream().filter(x -> x.id == id).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Keine Anfrage #" + id + " (mehr) im Mitschnitt."));
        int max = maxChars == null || maxChars <= 0 ? 4000 : maxChars;
        StringBuilder sb = new StringBuilder(line(e)).append("\n\n--- Anfrage ---\n").append(e.method).append(' ')
                .append(e.url);
        e.requestHeaders.forEach(h -> sb.append('\n').append(h.name()).append(": ").append(h.value()));
        sb.append("\n\n").append(body(e.requestHeaders, e.requestBody, e.requestSize, max));
        if (e.done) {
            sb.append("\n\n--- Antwort ---\n").append(e.status);
            e.responseHeaders.forEach(h -> sb.append('\n').append(h.name()).append(": ").append(h.value()));
            sb.append("\n\n").append(body(e.responseHeaders, e.responseBody, e.responseSize, max));
        }
        return sb.toString();
    }

    @Tool(name = "mock_add", description = "Beantwortet künftige Anfragen, deren URL auf das Muster passt, mit einer "
            + "festen Antwort statt sie weiterzureichen – auch für Hosts, die es nicht gibt, und für HTTPS. Bei "
            + "mehreren passenden Mocks gewinnt der zuerst angelegte. Entfernen mit http_mock_remove.")
    public String mockAdd(
            @ToolParam(description = "Regulärer Ausdruck, der irgendwo in der vollen URL vorkommen muss, z.B. "
                    + "api\\.example\\.com/v1/users/\\d+") String urlPattern,
            @ToolParam(required = false, description = "Nur für diese Methode (GET, POST …; Standard: alle)")
            String method,
            @ToolParam(required = false, description = "HTTP-Status (Standard 200)") Integer status,
            @ToolParam(required = false, description = "Body der Antwort; Content-Type ohne Angabe in headers: JSON, "
                    + "wenn er mit { oder [ beginnt, sonst Text") String body,
            @ToolParam(required = false, description = "Zusätzliche Header, je Zeile \"Name: Wert\"") String headers,
            @ToolParam(required = false, description = "Antwort um so viele Millisekunden verzögern (Timeouts "
                    + "testen)") Long delayMs,
            @ToolParam(required = false, description = "Nur so oft antworten, danach wieder weiterreichen (Standard: "
                    + "unbegrenzt)") Integer times,
            @ToolParam(required = false, description = "Port des Proxys (nötig, wenn mehrere laufen)") Integer port) {
        HttpProxy proxy = proxy(port);
        Pattern url;
        try {
            url = Pattern.compile(urlPattern);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("urlPattern ist kein gültiger regulärer Ausdruck: " + e.getMessage(), e);
        }
        List<Header> h = new ArrayList<>();
        if (headers != null) {
            for (String l : headers.split("\\R")) {
                int colon = l.indexOf(':');
                if (colon <= 0) {
                    if (!l.isBlank()) {
                        throw new IllegalArgumentException("Header \"" + l + "\" hat nicht die Form \"Name: Wert\".");
                    }
                    continue;
                }
                h.add(new Header(l.substring(0, colon).strip(), l.substring(colon + 1).strip()));
            }
        }
        String text = body == null ? "" : body;
        if (!text.isEmpty() && h.stream().noneMatch(x -> x.name().equalsIgnoreCase("content-type"))) {
            h.add(new Header("Content-Type", text.strip().startsWith("{") || text.strip().startsWith("[")
                    ? "application/json" : "text/plain; charset=utf-8"));
        }
        int s = status == null ? 200 : status;
        if (s < 100 || s > 599) {
            throw new IllegalArgumentException("status muss zwischen 100 und 599 liegen.");
        }
        Mock m = proxy.addMock(method == null || method.isBlank() ? null : method.strip(), url, s, List.copyOf(h),
                text.getBytes(StandardCharsets.UTF_8), delayMs == null ? 0 : Math.max(0, delayMs), times);
        return "Mock #" + m.id() + " angelegt.\n" + mocks(proxy);
    }

    @Tool(name = "mock_remove", description = "Entfernt einen Mock (oder ohne id alle) eines HTTP-Proxys; passende "
            + "Anfragen gehen danach wieder zum echten Server. Zeigt die verbleibenden Mocks samt Trefferzahl.")
    public String mockRemove(
            @ToolParam(required = false, description = "Nummer des Mocks (Standard: alle)") Integer id,
            @ToolParam(required = false, description = "Port des Proxys (nötig, wenn mehrere laufen)") Integer port) {
        HttpProxy proxy = proxy(port);
        if (id == null) {
            proxy.mocks.clear();
        } else if (!proxy.mocks.removeIf(m -> m.id() == id)) {
            throw new IllegalArgumentException("Kein Mock #" + id + ".\n" + mocks(proxy));
        }
        return mocks(proxy);
    }

    @Tool(name = "analyze", description = "Wertet den Mitschnitt eines HTTP-Proxys aus: Endpunkte nach Gesamtdauer "
            + "(IDs in Pfaden zusammengefasst), langsamste Anfragen, Fehler, identisch wiederholte Anfragen und "
            + "N+1-Verdacht (viele Einzelaufrufe desselben Endpunkts mit wechselnder ID).")
    public String analyze(
            @ToolParam(required = false, description = "Port des Proxys (nötig, wenn mehrere laufen)") Integer port,
            @ToolParam(required = false, description = "Nur Anfragen mit höherer Nummer") Integer since,
            @ToolParam(required = false, description = "Nur Anfragen, deren \"METHODE URL STATUS\" diesen Text "
                    + "enthält") String filter) {
        List<Exchange> list = select(proxy(port).exchanges(), filter, since).stream().filter(e -> e.done).toList();
        if (list.isEmpty()) {
            return "Keine abgeschlossenen Anfragen im Mitschnitt.";
        }
        Map<String, List<Exchange>> byEndpoint = list.stream().collect(Collectors.groupingBy(
                e -> e.method + " " + ID_SEGMENT.matcher(stripQuery(e.url)).replaceAll("/{id}"), LinkedHashMap::new,
                Collectors.toList()));
        long mocked = list.stream().filter(e -> e.mockId != null).count();
        long failed = list.stream().filter(Exchange::failed).count();
        StringBuilder sb = new StringBuilder().append(list.size()).append(" Anfragen (").append(mocked)
                .append(" gemockt, ").append(failed).append(" fehlgeschlagen), ")
                .append(TIME.format(list.getFirst().start)).append(" – ").append(TIME.format(list.getLast().start))
                .append(", Summe der Dauer ").append(sum(list)).append(" ms");

        sb.append("\n\nEndpunkte nach Gesamtdauer (Anzahl, Summe/Ø/max ms, Fehler):");
        byEndpoint.entrySet().stream().sorted(Comparator.comparingLong(en -> -sum(en.getValue()))).limit(15)
                .forEach(en -> {
                    List<Exchange> v = en.getValue();
                    sb.append("\n  ").append(v.size()).append("×  ").append(sum(v)).append(" / ")
                            .append(sum(v) / v.size()).append(" / ")
                            .append(v.stream().mapToLong(e -> e.millis).max().orElse(0)).append(" ms  ")
                            .append(v.stream().filter(Exchange::failed).count()).append(" Fehler  ")
                            .append(en.getKey());
                });

        sb.append("\n\nLangsamste Anfragen:");
        list.stream().sorted(Comparator.comparingLong(e -> -e.millis)).limit(5)
                .forEach(e -> sb.append("\n  ").append(line(e)));

        List<Exchange> errors = list.stream().filter(Exchange::failed).toList();
        if (!errors.isEmpty()) {
            sb.append("\n\nFehler (").append(errors.size()).append("):");
            errors.stream().limit(10).forEach(e -> sb.append("\n  ").append(line(e)));
        }

        Map<String, Long> repeated = list.stream().collect(Collectors.groupingBy(e -> e.method + " " + e.url,
                LinkedHashMap::new, Collectors.counting()));
        repeated.values().removeIf(n -> n < 3);
        if (!repeated.isEmpty()) {
            sb.append("\n\nIdentisch wiederholt (Cache/Deduplizierung prüfen):");
            repeated.entrySet().stream().sorted(Map.Entry.comparingByValue(Comparator.reverseOrder())).limit(10)
                    .forEach(en -> sb.append("\n  ").append(en.getValue()).append("×  ").append(en.getKey()));
        }

        List<String> nPlusOne = byEndpoint.entrySet().stream()
                .filter(en -> en.getKey().contains("{id}")
                        && en.getValue().stream().map(e -> e.url).distinct().count() >= 5)
                .map(en -> en.getValue().size() + "×  " + en.getKey()).toList();
        if (!nPlusOne.isEmpty()) {
            sb.append("\n\nN+1-Verdacht (Einzelaufrufe mit wechselnder ID – Sammelabfrage prüfen):");
            nPlusOne.forEach(s -> sb.append("\n  ").append(s));
        }
        return sb.toString();
    }

    private HttpProxy proxy(Integer port) {
        if (port != null) {
            HttpProxy p = proxies.byPort.get(port);
            if (p == null) {
                throw new IllegalArgumentException("Kein HTTP-Proxy auf Port " + port + ".\n" + list());
            }
            return p;
        }
        return switch (proxies.byPort.size()) {
            case 0 -> throw new IllegalStateException("Kein HTTP-Proxy aktiv – zuerst http_proxy_start.");
            case 1 -> proxies.byPort.values().iterator().next();
            default -> throw new IllegalArgumentException("Mehrere HTTP-Proxys aktiv – port angeben.\n" + list());
        };
    }

    private static List<Exchange> select(List<Exchange> all, String filter, Integer since) {
        String f = filter == null ? "" : filter.strip().toLowerCase(Locale.ROOT);
        return all.stream().filter(e -> since == null || e.id > since)
                .filter(e -> f.isEmpty() || (e.method + " " + e.url + " " + e.status).toLowerCase(Locale.ROOT)
                        .contains(f))
                .toList();
    }

    private String list() {
        if (proxies.byPort.isEmpty()) {
            return "Keine HTTP-Proxys aktiv.";
        }
        StringBuilder sb = new StringBuilder("Aktive HTTP-Proxys:");
        proxies.byPort.values().forEach(p -> sb.append("\n127.0.0.1:").append(p.port()).append("  ")
                .append(p.exchanges().size()).append(" Anfragen, ").append(p.mocks.size()).append(" Mocks"));
        return sb.toString();
    }

    private static String mocks(HttpProxy proxy) {
        if (proxy.mocks.isEmpty()) {
            return "Keine Mocks aktiv (Proxy :" + proxy.port() + ").";
        }
        StringBuilder sb = new StringBuilder("Mocks (Proxy :" + proxy.port() + "):");
        proxy.mocks.forEach(m -> sb.append("\n#").append(m.id()).append("  ")
                .append(m.method() == null ? "*" : m.method()).append(' ').append(m.url().pattern()).append(" → ")
                .append(m.status()).append(", ").append(m.body().length).append(" Bytes")
                .append(m.delayMs() > 0 ? ", +" + m.delayMs() + " ms" : "")
                .append(m.remaining().get() >= 0 ? ", noch " + m.remaining().get() + "×" : "")
                .append(", ").append(m.hits().get()).append(" Treffer"));
        return sb.toString();
    }

    private static String line(Exchange e) {
        StringBuilder sb = new StringBuilder("#").append(e.id).append(' ').append(TIME.format(e.start)).append(' ')
                .append(e.method).append(' ').append(e.url);
        if (!e.done) {
            return sb.append(" … läuft").toString();
        }
        if (e.error != null) {
            sb.append(" ✗ ").append(e.error);
        } else {
            sb.append(" → ").append(e.status);
        }
        sb.append(" (").append(e.millis).append(" ms");
        if (e.status > 0) {
            sb.append(", ").append(e.responseSize).append(" B");
        }
        sb.append(')');
        if (e.mockId != null) {
            sb.append(" [Mock #").append(e.mockId).append(']');
        }
        return sb.toString();
    }

    static String body(List<Header> headers, byte[] body, int total, int maxChars) {
        if (total == 0) {
            return "(kein Body)";
        }
        String note = total > body.length ? "\n… (aufgezeichnet " + body.length + " von " + total + " Bytes)" : "";
        String type = header(headers, "content-type");
        String encoding = header(headers, "content-encoding");
        byte[] data = body;
        if (encoding != null && !encoding.isBlank() && !"identity".equalsIgnoreCase(encoding.strip())) {
            try {
                data = switch (encoding.strip().toLowerCase(Locale.ROOT)) {
                    case "gzip", "x-gzip" -> new GZIPInputStream(new ByteArrayInputStream(body)).readAllBytes();
                    case "deflate" -> new InflaterInputStream(new ByteArrayInputStream(body)).readAllBytes();
                    default -> null; // z.B. br: kein Decoder im JDK
                };
            } catch (IOException e) {
                data = null; // gekürzt aufgezeichnet oder beschädigt
            }
            if (data == null) {
                return total + " Bytes, " + encoding + "-kodiert (nicht entpackt), " + type + note;
            }
        }
        Charset cs = StandardCharsets.UTF_8;
        if (type != null) {
            Matcher m = CHARSET.matcher(type);
            if (m.find() && Charset.isSupported(m.group(1))) {
                cs = Charset.forName(m.group(1));
            }
        }
        String text = new String(data, cs);
        if (text.chars().anyMatch(c -> c == 0 || c == 0xFFFD)) {
            return "binär, " + total + " Bytes" + (type == null ? "" : " (" + type + ")") + note;
        }
        if (text.length() > maxChars) {
            text = text.substring(0, maxChars) + "\n… (" + text.length() + " Zeichen, gekürzt)";
        }
        return text + note;
    }

    private static String header(List<Header> headers, String name) {
        return headers.stream().filter(h -> h.name().equalsIgnoreCase(name)).map(Header::value).findFirst()
                .orElse(null);
    }

    private static String stripQuery(String url) {
        int q = url.indexOf('?');
        return q < 0 ? url : url.substring(0, q);
    }

    private static long sum(List<Exchange> list) {
        return list.stream().mapToLong(e -> Math.max(0, e.millis)).sum();
    }
}
