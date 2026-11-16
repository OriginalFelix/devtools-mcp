package systems.grebe.devtools.mcp.modules.http;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.SslHandler;

/**
 * HTTP-Forward-Proxy auf localhost: reicht Anfragen weiter (HTTPS über CONNECT, mit der {@link CertificateAuthority}
 * entschlüsselt), zeichnet sie auf und beantwortet passende Anfragen mit {@link Mock}s statt sie weiterzureichen.
 */
final class HttpProxy implements AutoCloseable {

    // shortcut: Bodies liegen am Stück im Speicher (kein Streaming, also kein SSE/WebSocket); bei Bedarf auf Streaming umbauen
    static final int MAX_CONTENT = 64 * 1024 * 1024;
    /** So viel je Body wird aufgehoben; die Weiterleitung bekommt immer alles. */
    static final int MAX_STORED_BODY = 1024 * 1024;
    static final int MAX_EXCHANGES = 2000;

    /** Hop-by-Hop-Header und solche, die der Proxy selbst setzt. */
    private static final Set<String> SKIPPED_HEADERS = Set.of("connection", "keep-alive", "proxy-connection",
            "proxy-authorization", "proxy-authenticate", "te", "trailer", "transfer-encoding", "upgrade", "host",
            "content-length", "expect", "http2-settings");

    private static final Pattern HOST = Pattern.compile("^https?://(\\[[^]]+]|[^/:?#]+)");

    record Header(String name, String value) {
    }

    record Reply(int status, List<Header> headers, byte[] body) {
    }

    /** Feste Antwort für Anfragen, deren URL auf {@code url} passt (und deren Methode, falls gesetzt). */
    record Mock(int id, String method, Pattern url, int status, List<Header> headers, byte[] body, long delayMs,
            AtomicInteger remaining, AtomicInteger hits) {

        boolean matches(String m, String u) {
            return (method == null || method.equalsIgnoreCase(m)) && url.matcher(u).find() && remaining.get() != 0;
        }
    }

    /** Eine aufgezeichnete Anfrage samt Antwort; Antwortfelder kommen nach, solange {@link #done} {@code false} ist. */
    static final class Exchange {
        final int id;
        final Instant start = Instant.now();
        final String method;
        final String url;
        final List<Header> requestHeaders;
        final byte[] requestBody;
        final int requestSize;
        volatile Integer mockId;
        volatile int status;
        volatile List<Header> responseHeaders = List.of();
        volatile byte[] responseBody = new byte[0];
        volatile int responseSize;
        volatile long millis = -1;
        volatile String error;
        volatile boolean done;

        Exchange(int id, String method, String url, List<Header> headers, byte[] body) {
            this.id = id;
            this.method = method;
            this.url = url;
            this.requestHeaders = headers;
            this.requestSize = body.length;
            this.requestBody = cap(body);
        }

        boolean failed() {
            return error != null || status >= 400;
        }
    }

    private final int port;
    private final CertificateAuthority ca;
    /** Host-Muster wie übergeben, {@code null} = alle. */
    final String hosts;
    private final Predicate<String> intercept;
    private final EventLoopGroup group;
    private final Channel server;
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10)).build();
    private final AtomicInteger exchangeIds = new AtomicInteger();
    private final AtomicInteger mockIds = new AtomicInteger();
    private final Deque<Exchange> exchanges = new ConcurrentLinkedDeque<>();
    final List<Mock> mocks = new CopyOnWriteArrayList<>();

    /**
     * @param hosts Muster der Hosts, die entschlüsselt, aufgezeichnet und gemockt werden (siehe {@link #hosts(String)});
     *              alle anderen werden unbesehen weitergereicht (HTTPS als reiner Tunnel). Leer = alle.
     */
    HttpProxy(int port, CertificateAuthority ca, String hosts) throws InterruptedException {
        this.ca = ca;
        this.hosts = hosts == null || hosts.isBlank() ? null : hosts.strip();
        this.intercept = hosts(hosts);
        this.group = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
        try {
            this.server = new ServerBootstrap().group(group).channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast("codec", new HttpServerCodec())
                                    .addLast("aggregator", new HttpObjectAggregator(MAX_CONTENT))
                                    .addLast("proxy", new Handler());
                        }
                    })
                    // nur lokal: sonst wäre es ein offener Proxy, der HTTPS mit der eigenen CA aufbricht
                    .bind(InetAddress.getLoopbackAddress(), port).sync().channel();
        } catch (InterruptedException | RuntimeException e) {
            group.shutdownGracefully(0, 0, TimeUnit.SECONDS);
            throw e;
        }
        this.port = ((InetSocketAddress) server.localAddress()).getPort();
    }

    int port() {
        return port;
    }

    /** Aufgezeichnete Anfragen, älteste zuerst. */
    List<Exchange> exchanges() {
        return List.copyOf(exchanges);
    }

    void clear() {
        exchanges.clear();
    }

    Mock addMock(String method, Pattern url, int status, List<Header> headers, byte[] body, long delayMs,
            Integer times) {
        Mock m = new Mock(mockIds.incrementAndGet(), method, url, status, headers, body, delayMs,
                new AtomicInteger(times == null ? -1 : times), new AtomicInteger());
        mocks.add(m);
        return m;
    }

    @Override
    public void close() {
        server.close().syncUninterruptibly();
        group.shutdownGracefully(0, 2, TimeUnit.SECONDS);
        client.close();
    }

    private Exchange record(String method, String url, List<Header> headers, byte[] body) {
        Exchange e = new Exchange(exchangeIds.incrementAndGet(), method, url, headers, body);
        exchanges.add(e);
        while (exchanges.size() > MAX_EXCHANGES) {
            exchanges.pollFirst();
        }
        return e;
    }

    private Mock mockFor(String method, String url) {
        for (Mock m : mocks) {
            if (m.matches(method, url) && (m.remaining.get() < 0 || m.remaining.getAndDecrement() > 0)) {
                m.hits.incrementAndGet();
                return m;
            }
        }
        return null;
    }

    private CompletableFuture<Reply> forward(String method, String url, List<Header> headers, byte[] body) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(2))
                .method(method, body.length == 0 ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));
        for (Header h : headers) {
            if (!SKIPPED_HEADERS.contains(h.name().toLowerCase(Locale.ROOT))) {
                try {
                    b.header(h.name(), h.value());
                } catch (IllegalArgumentException e) {
                    // vom JDK-Client gesperrter Header (z.B. Date): setzt der Client selbst
                }
            }
        }
        return client.sendAsync(b.build(), HttpResponse.BodyHandlers.ofByteArray()).thenApply(r -> {
            List<Header> h = new ArrayList<>();
            r.headers().map().forEach((name, values) -> values.forEach(v -> h.add(new Header(name, v))));
            return new Reply(r.statusCode(), h, r.body());
        });
    }

    private static byte[] cap(byte[] body) {
        return body.length <= MAX_STORED_BODY ? body : java.util.Arrays.copyOf(body, MAX_STORED_BODY);
    }

    private static List<Header> headers(FullHttpRequest req) {
        List<Header> h = new ArrayList<>();
        for (Map.Entry<String, String> e : req.headers()) {
            h.add(new Header(e.getKey(), e.getValue()));
        }
        return List.copyOf(h);
    }

    /** Je Client-Verbindung; nach CONNECT gilt die Verbindung für {@link #tunnel} (host[:port]). */
    private final class Handler extends SimpleChannelInboundHandler<FullHttpRequest> {

        private String tunnel;

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
            if (!req.decoderResult().isSuccess()) {
                reply(ctx, new Reply(400, List.of(), bytes("Ungültige Anfrage")), false, false);
                return;
            }
            if (req.method() == HttpMethod.CONNECT) {
                connect(ctx, req.uri());
                return;
            }
            String url = tunnel != null ? "https://" + tunnel + req.uri() : req.uri();
            boolean keepAlive = HttpUtil.isKeepAlive(req);
            boolean head = req.method() == HttpMethod.HEAD;
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                reply(ctx, new Reply(400, List.of(), bytes("Das ist ein HTTP-Proxy: Anfragen mit absoluter "
                        + "URL (http://…) oder CONNECT schicken.")), keepAlive, head);
                return;
            }
            String method = req.method().name();
            List<Header> headers = headers(req);
            byte[] body = ByteBufUtil.getBytes(req.content());
            Matcher host = HOST.matcher(url);
            // nicht ausgewählte Hosts: weiterreichen, aber weder aufzeichnen noch mocken
            Exchange ex = host.find() && intercept.test(host.group(1).replaceAll("^\\[|]$", ""))
                    ? record(method, url, headers, body) : null;
            // eine Anfrage je Verbindung zurzeit: Antworten kommen asynchron, die Reihenfolge muss stimmen
            ctx.channel().config().setAutoRead(false);
            Mock mock = ex == null ? null : mockFor(method, url);
            CompletableFuture<Reply> future;
            if (mock != null) {
                ex.mockId = mock.id();
                Reply r = new Reply(mock.status(), mock.headers(), mock.body());
                future = mock.delayMs() > 0 ? CompletableFuture.supplyAsync(() -> r,
                        CompletableFuture.delayedExecutor(mock.delayMs(), TimeUnit.MILLISECONDS))
                        : CompletableFuture.completedFuture(r);
            } else {
                try {
                    future = forward(method, url, headers, body);
                } catch (IllegalArgumentException e) {
                    future = CompletableFuture.failedFuture(e);
                }
            }
            future.whenComplete((r, err) -> {
                if (err != null) {
                    Throwable cause = err.getCause() != null ? err.getCause() : err;
                    String error = cause.getClass().getSimpleName() + ": " + cause.getMessage();
                    r = new Reply(502, List.of(new Header("Content-Type", "text/plain; charset=utf-8")),
                            bytes("DevTools-Proxy: " + error));
                    if (ex != null) {
                        ex.error = error;
                    }
                }
                if (ex == null) {
                    reply(ctx, r, keepAlive, head);
                    return;
                }
                ex.millis = Duration.between(ex.start, Instant.now()).toMillis();
                ex.status = r.status();
                ex.responseHeaders = List.copyOf(r.headers());
                ex.responseSize = r.body().length;
                ex.responseBody = cap(r.body());
                ex.done = true;
                reply(ctx, r, keepAlive, head);
            });
        }

        private void connect(ChannelHandlerContext ctx, String target) {
            int colon = target.lastIndexOf(':');
            String host = (colon > 0 ? target.substring(0, colon) : target).replaceAll("^\\[|]$", "");
            String port = colon > 0 ? target.substring(colon + 1) : "443";
            if (!intercept.test(host)) {
                passThrough(ctx, host, port);
                return;
            }
            ctx.writeAndFlush(established()).addListener(f -> {
                if (!f.isSuccess()) {
                    ctx.close();
                    return;
                }
                ChannelPipeline p = ctx.pipeline();
                p.remove("aggregator");
                p.remove("codec");
                SslHandler tls = ca.serverContext(host).newHandler(ctx.alloc());
                p.addFirst("tls", tls);
                p.addAfter("tls", "codec", new HttpServerCodec());
                p.addAfter("codec", "aggregator", new HttpObjectAggregator(MAX_CONTENT));
                tunnel = "443".equals(port) ? target.substring(0, colon > 0 ? colon : target.length())
                        : target;
                tls.handshakeFuture().addListener(h -> {
                    if (!h.isSuccess()) {
                        // häufigster Grund: der Client vertraut der CA nicht – sichtbar machen statt still abbrechen
                        Exchange e = record("CONNECT", "https://" + tunnel, List.of(), new byte[0]);
                        e.error = "TLS-Handshake fehlgeschlagen (vertraut der Client der CA?): "
                                + h.cause().getMessage();
                        e.millis = Duration.between(e.start, Instant.now()).toMillis();
                        e.done = true;
                    }
                });
            });
        }

        /** Reiner TCP-Tunnel: der Proxy sieht nur verschlüsselte Bytes, nichts wird aufgezeichnet. */
        private void passThrough(ChannelHandlerContext ctx, String host, String port) {
            Channel in = ctx.channel();
            in.config().setAutoRead(false);
            int p;
            try {
                p = Integer.parseInt(port);
            } catch (NumberFormatException e) {
                reply(ctx, new Reply(400, List.of(), bytes("Ungültiger Port: " + port)), false, false);
                return;
            }
            new Bootstrap().group(in.eventLoop()).channel(NioSocketChannel.class)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000).handler(new Relay(in))
                    .connect(host, p).addListener((ChannelFutureListener) f -> {
                        if (!f.isSuccess()) {
                            reply(ctx, new Reply(502, List.of(), bytes("DevTools-Proxy: " + host + ":" + port
                                    + " nicht erreichbar: " + f.cause().getMessage())), false, false);
                            return;
                        }
                        Channel out = f.channel();
                        ctx.writeAndFlush(established()).addListener(w -> {
                            if (!w.isSuccess()) {
                                out.close();
                                ctx.close();
                                return;
                            }
                            ChannelPipeline pipeline = ctx.pipeline();
                            pipeline.remove("aggregator");
                            pipeline.remove("codec");
                            pipeline.replace(this, "relay", new Relay(out));
                            in.config().setAutoRead(true);
                        });
                    });
        }

        private static FullHttpResponse established() {
            return new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                    new HttpResponseStatus(200, "Connection established"));
        }

        private void reply(ChannelHandlerContext ctx, Reply r, boolean keepAlive, boolean head) {
            FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                    HttpResponseStatus.valueOf(r.status()), Unpooled.wrappedBuffer(r.body()));
            for (Header h : r.headers()) {
                String name = h.name().toLowerCase(Locale.ROOT);
                // HEAD: Content-Length der echten Antwort behalten, der Body ist leer
                if (!SKIPPED_HEADERS.contains(name) || head && "content-length".equals(name)) {
                    resp.headers().add(h.name(), h.value());
                }
            }
            if (!head) {
                resp.headers().set(HttpHeaderNames.CONTENT_LENGTH, r.body().length);
            }
            if (!keepAlive) {
                resp.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            }
            ctx.writeAndFlush(resp).addListener(f -> {
                if (!keepAlive || !f.isSuccess()) {
                    ctx.close();
                } else {
                    ctx.channel().config().setAutoRead(true);
                }
            });
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }

    /** Reicht Bytes an die Gegenseite eines Tunnels weiter und schließt sie mit. */
    // shortcut: ohne Gegendruck (Bytes stauen sich, wenn eine Seite langsamer liest); bei großen Downloads auf autoRead-Steuerung umbauen
    private static final class Relay extends ChannelInboundHandlerAdapter {
        private final Channel peer;

        Relay(Channel peer) {
            this.peer = peer;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            peer.writeAndFlush(msg).addListener((ChannelFutureListener) f -> {
                if (!f.isSuccess()) {
                    ctx.close();
                }
            });
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (peer.isActive()) {
                peer.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }

    /**
     * Filter aus Host-Mustern wie {@code api.example.com, *.intern} ({@code *} = beliebige Zeichen); leer = alle.
     */
    static Predicate<String> hosts(String spec) {
        if (spec == null || spec.isBlank()) {
            return h -> true;
        }
        List<Pattern> patterns = new ArrayList<>();
        for (String s : spec.strip().split("[,;\\s]+")) {
            patterns.add(Pattern.compile(Pattern.quote(s).replace("*", "\\E.*\\Q"), Pattern.CASE_INSENSITIVE));
        }
        return h -> patterns.stream().anyMatch(p -> p.matcher(h).matches());
    }

    static byte[] bytes(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
