package systems.grebe.devtools.mcp.modules.web;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.IDN;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

/**
 * Lädt eine Webseite und macht daraus lesbaren Text. Nur http(s), Weiterleitungen werden selbst verfolgt, damit jede
 * Station geprüft werden kann: Ohne Freigabe sind Adressen im lokalen Netz (Loopback, private Bereiche, Link-Local)
 * gesperrt – das LLM soll über das Tool nicht an interne Dienste kommen.
 */
final class PageFetcher {

    /** Abgerufene Seite. {@code url} ist die angefragte, {@code finalUrl} die nach Weiterleitungen. */
    record Page(String url, String finalUrl, String title, String contentType, String text, boolean truncated,
                Instant fetchedAt) {
    }

    private static final int MAX_REDIRECTS = 8;
    private static final Pattern CHARSET = Pattern.compile("(?i)charset\\s*=\\s*\"?([\\w.:-]+)");
    /**
     * Schlicht halten: CDNs wie Akamai (z.B. openjdk.org) bremsen Agents nach dem Muster „Mozilla/5.0 (compatible; …)“
     * oder mit Zusätzen in Klammern um rund 9 Sekunden aus; ein einfaches {@code Produkt/Version} kommt sofort durch.
     */
    private static final String USER_AGENT = "DevTools-MCP/0.1";

    private final HttpClient http;
    private final boolean allowPrivate;
    private final int maxBytes;
    private final Duration timeout;
    private final Clock clock;

    PageFetcher(boolean allowPrivate, int maxBytes, Duration timeout, Clock clock) {
        this.http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(timeout).build();
        this.allowPrivate = allowPrivate;
        this.maxBytes = maxBytes;
        this.timeout = timeout;
        this.clock = clock;
    }

    /** Ergänzt ein fehlendes Schema ({@code example.org/x} → {@code https://example.org/x}) und prüft die Adresse. */
    static URI normalize(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Keine URL angegeben.");
        }
        String s = url.strip().replace(" ", "%20");
        if (!s.matches("(?is)^([a-z][a-z0-9+.-]*://|(mailto|file|javascript|data|about|ftp):).*")) {
            s = "https://" + s;
        }
        // Fragment gehört nicht zur Anfrage und würde nur den Cache-Schlüssel verwässern
        int hash = s.indexOf('#');
        if (hash >= 0) {
            s = s.substring(0, hash);
        }
        URI uri;
        try {
            uri = new URI(s).normalize();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Ungültige URL: " + url + " (" + e.getReason() + ")");
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("Nur http- und https-Adressen werden abgerufen, nicht " + scheme + ":");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("URL ohne Host: " + url);
        }
        return uri;
    }

    Page fetch(String url) {
        URI start = normalize(url);
        URI current = start;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            checkHost(current);
            HttpResponse<InputStream> res = send(current);
            int status = res.statusCode();
            if (status >= 300 && status < 400 && status != 304) {
                Optional<String> location = res.headers().firstValue("Location");
                close(res);
                if (location.isEmpty()) {
                    throw new IllegalStateException("HTTP " + status + " ohne Ziel der Weiterleitung: " + current);
                }
                current = normalize(current.resolve(location.get().strip()).toString());
                continue;
            }
            if (status < 200 || status >= 300) {
                close(res);
                throw new IllegalStateException("HTTP " + status + " beim Abruf von " + current);
            }
            return read(start, current, res);
        }
        throw new IllegalStateException("Zu viele Weiterleitungen (> " + MAX_REDIRECTS + ") ab " + start);
    }

    private HttpResponse<InputStream> send(URI uri) {
        HttpRequest req = HttpRequest.newBuilder(uri).timeout(timeout)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,text/markdown;q=0.9,"
                        + "application/json;q=0.8,*/*;q=0.5")
                .header("Accept-Language", "de,en;q=0.8")
                .GET().build();
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new IllegalStateException("Abruf von " + uri + " fehlgeschlagen: " + describe(e), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Abruf abgebrochen", e);
        }
    }

    private Page read(URI requested, URI finalUri, HttpResponse<InputStream> res) {
        String contentType = res.headers().firstValue("Content-Type").orElse("").strip();
        String mime = contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        boolean html = mime.isEmpty() || mime.equals("text/html") || mime.equals("application/xhtml+xml");
        boolean text = mime.startsWith("text/") || mime.endsWith("+json") || mime.endsWith("/json")
                || mime.endsWith("+xml") || mime.endsWith("/xml") || mime.equals("application/javascript");
        if (!html && !text) {
            close(res);
            throw new IllegalStateException("Inhaltstyp " + mime + " wird nicht unterstützt – nur HTML und Text ("
                    + finalUri + ").");
        }
        byte[] body;
        boolean truncated;
        try (InputStream in = res.body()) {
            body = in.readNBytes(maxBytes + 1);
            truncated = body.length > maxBytes;
            if (truncated) {
                body = Arrays.copyOf(body, maxBytes);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Abruf von " + finalUri + " fehlgeschlagen: " + describe(e), e);
        }
        Charset charset = charset(contentType);
        Instant now = clock.instant();
        if (html) {
            Document doc;
            try {
                doc = Jsoup.parse(new ByteArrayInputStream(body), charset == null ? null : charset.name(),
                        finalUri.toString());
            } catch (IOException e) {
                throw new IllegalStateException("HTML von " + finalUri + " nicht lesbar: " + e.getMessage(), e);
            }
            String title = PageText.title(doc);
            return new Page(requested.toString(), finalUri.toString(), title, mime.isEmpty() ? "text/html" : mime,
                    PageText.text(doc), truncated, now);
        }
        String s = new String(body, charset == null ? StandardCharsets.UTF_8 : charset).strip();
        return new Page(requested.toString(), finalUri.toString(), "", mime, s, truncated, now);
    }

    private static Charset charset(String contentType) {
        Matcher m = CHARSET.matcher(contentType);
        if (m.find()) {
            try {
                return Charset.forName(m.group(1));
            } catch (RuntimeException ignored) {
                // unbekannter Zeichensatz: jsoup bzw. UTF-8 entscheidet
            }
        }
        return null;
    }

    /** Sperrt Ziele im lokalen Netz, sofern nicht freigegeben. */
    private void checkHost(URI uri) {
        if (allowPrivate) {
            return;
        }
        String host = uri.getHost();
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(IDN.toASCII(host));
        } catch (UnknownHostException e) {
            throw new IllegalStateException("Host nicht gefunden: " + host);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Ungültiger Hostname: " + host);
        }
        for (InetAddress a : addresses) {
            if (isLocal(a)) {
                throw new IllegalStateException("Adresse im lokalen Netz gesperrt: " + host + " (" + a.getHostAddress()
                        + "). Freigabe über „Lokales Netz erlauben“ in den Einstellungen des Moduls Web-Abruf.");
            }
        }
    }

    static boolean isLocal(InetAddress a) {
        if (a.isLoopbackAddress() || a.isAnyLocalAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (a instanceof Inet6Address) {
            return (b[0] & 0xfe) == 0xfc; // Unique Local fc00::/7
        }
        return (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64; // Carrier-Grade NAT 100.64.0.0/10
    }

    private static void close(HttpResponse<InputStream> res) {
        try {
            res.body().close();
        } catch (IOException ignored) {
            // Verbindung wird verworfen
        }
    }

    private static String describe(IOException e) {
        String m = e.getMessage();
        return e.getClass().getSimpleName() + (m == null || m.isBlank() ? "" : ": " + m);
    }
}
