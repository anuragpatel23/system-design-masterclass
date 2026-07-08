import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MiniNginx — an EVENT-LOOP reverse proxy on Java NIO, architected the way
 * Nginx actually is: ONE thread, a Selector multiplexing every connection,
 * non-blocking reads/writes — not a thread per connection.
 *
 * Features implemented:
 *   - upstream pool with round-robin / least-connections selection
 *   - passive health: an upstream that fails to connect is marked down for 10s
 *   - X-Forwarded-For header injection into the proxied request
 *
 * Demo: starts two trivial upstream HTTP servers (on real threads, for
 * simplicity) on :9081/:9082 and the proxy on :9090, then issues requests.
 *
 *   curl http://localhost:9090/      (alternates between upstreams)
 *
 * Compile & run:  javac MiniNginx.java && java MiniNginx
 */
public class MiniNginx {

    // ---------------- upstream pool ----------------
    static final class Upstream {
        final String host; final int port;
        final AtomicInteger activeConns = new AtomicInteger();
        volatile long downUntil = 0;                        // passive health mark
        Upstream(String host, int port) { this.host = host; this.port = port; }
        boolean isUp() { return System.currentTimeMillis() >= downUntil; }
        public String toString() { return host + ":" + port; }
    }

    private final List<Upstream> upstreams;
    private final boolean leastConn;
    private final AtomicInteger rr = new AtomicInteger();

    MiniNginx(List<Upstream> upstreams, boolean leastConn) {
        this.upstreams = upstreams; this.leastConn = leastConn;
    }

    private Upstream pickUpstream() {
        List<Upstream> healthy = upstreams.stream().filter(Upstream::isUp).toList();
        if (healthy.isEmpty()) return null;
        if (leastConn)
            return healthy.stream().min(Comparator.comparingInt(u -> u.activeConns.get())).get();
        return healthy.get(Math.floorMod(rr.getAndIncrement(), healthy.size()));   // round robin
    }

    // ---------------- one proxied connection = a pair of pumps ----------------
    /** State attached to each channel: who its peer is and a buffer of pending bytes. */
    static final class Pipe {
        final SocketChannel peer;
        final ByteBuffer buffer = ByteBuffer.allocate(8192);
        final Upstream upstream;              // non-null on the upstream side (for accounting)
        boolean headerRewritten = false;      // client->upstream side: inject X-Forwarded-For once
        Pipe(SocketChannel peer, Upstream upstream) { this.peer = peer; this.upstream = upstream; }
    }

    // ---------------- THE EVENT LOOP (this is the whole point) ----------------
    public void start(int listenPort) throws IOException {
        Selector selector = Selector.open();
        ServerSocketChannel acceptor = ServerSocketChannel.open();
        acceptor.bind(new InetSocketAddress(listenPort));
        acceptor.configureBlocking(false);
        acceptor.register(selector, SelectionKey.OP_ACCEPT);
        System.out.println("mini-nginx listening on :" + listenPort
                + " (" + (leastConn ? "least_conn" : "round_robin") + ") — ONE thread, all connections");

        while (true) {
            selector.select();                                   // block until ANY socket has an event
            Iterator<SelectionKey> it = selector.selectedKeys().iterator();
            while (it.hasNext()) {
                SelectionKey key = it.next(); it.remove();
                try {
                    if (key.isAcceptable()) accept(selector, acceptor);
                    else if (key.isReadable()) pump(key);
                } catch (IOException e) { closePair(key); }
            }
        }
    }

    /** New client: pick an upstream, open a non-blocking connection, wire the two together. */
    private void accept(Selector selector, ServerSocketChannel acceptor) throws IOException {
        SocketChannel client = acceptor.accept();
        client.configureBlocking(false);
        Upstream target = pickUpstream();
        if (target == null) { respond502(client); return; }
        SocketChannel upstream = SocketChannel.open();
        upstream.configureBlocking(false);
        try {
            upstream.connect(new InetSocketAddress(target.host, target.port));
            while (!upstream.finishConnect()) Thread.onSpinWait();   // demo shortcut (small LAN connect)
        } catch (IOException e) {
            target.downUntil = System.currentTimeMillis() + 10_000;  // passive health: mark down 10s
            System.out.println("  upstream " + target + " DOWN (marked for 10s)");
            respond502(client); return;
        }
        target.activeConns.incrementAndGet();
        // register both directions with the SAME selector — still one thread
        client.register(selector, SelectionKey.OP_READ, new Pipe(upstream, target));
        upstream.register(selector, SelectionKey.OP_READ, new Pipe(client, target));
        System.out.println("  proxying client -> " + target
                + " (active=" + target.activeConns.get() + ")");
    }

    /** Readable socket: move bytes to its peer. Rewrites the request line once for XFF. */
    private void pump(SelectionKey key) throws IOException {
        SocketChannel from = (SocketChannel) key.channel();
        Pipe pipe = (Pipe) key.attachment();
        pipe.buffer.clear();
        int n = from.read(pipe.buffer);
        if (n == -1) { closePair(key); return; }                 // peer hung up
        pipe.buffer.flip();
        byte[] data = new byte[pipe.buffer.remaining()];
        pipe.buffer.get(data);

        // Inject X-Forwarded-For into the first client->upstream chunk (header region).
        if (!pipe.headerRewritten && pipe.upstream != null) {
            String text = new String(data, StandardCharsets.ISO_8859_1);
            int headerEnd = text.indexOf("\r\n");
            if (headerEnd > 0 && text.startsWith("GET") || text.startsWith("POST")) {
                String clientIp = ((InetSocketAddress) from.getRemoteAddress()).getHostString();
                text = text.substring(0, headerEnd + 2)
                        + "X-Forwarded-For: " + clientIp + "\r\n"
                        + text.substring(headerEnd + 2);
                data = text.getBytes(StandardCharsets.ISO_8859_1);
            }
            pipe.headerRewritten = true;
        }
        pipe.peer.write(ByteBuffer.wrap(data));                  // non-blocking write to the peer
    }

    private void closePair(SelectionKey key) {
        Pipe pipe = (Pipe) key.attachment();
        try { key.channel().close(); } catch (IOException ignored) {}
        if (pipe != null) {
            if (pipe.upstream != null) pipe.upstream.activeConns.decrementAndGet();
            try { pipe.peer.close(); } catch (IOException ignored) {}
        }
    }

    private void respond502(SocketChannel client) throws IOException {
        client.write(ByteBuffer.wrap(
                "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 11\r\n\r\nBad Gateway".getBytes()));
        client.close();
    }

    // ---------------- demo upstreams + scripted requests ----------------
    public static void main(String[] args) throws Exception {
        startDemoUpstream(9081, "upstream-A");
        startDemoUpstream(9082, "upstream-B");

        MiniNginx proxy = new MiniNginx(
                List.of(new Upstream("localhost", 9081), new Upstream("localhost", 9082)),
                false /* round robin */);
        new Thread(() -> { try { proxy.start(9090); } catch (IOException e) { throw new RuntimeException(e); } },
                "event-loop").start();
        Thread.sleep(300);

        var http = java.net.http.HttpClient.newHttpClient();
        for (int i = 1; i <= 4; i++) {
            var resp = http.send(java.net.http.HttpRequest.newBuilder()
                            .uri(java.net.URI.create("http://localhost:9090/")).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            System.out.println("  request " + i + " served by: " + resp.body().trim());
        }
        System.exit(0);
    }

    /** A trivial threaded HTTP upstream that reports its own name (and echoes XFF). */
    static void startDemoUpstream(int port, String name) throws IOException {
        var server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", ex -> {
            String xff = ex.getRequestHeaders().getFirst("X-Forwarded-For");
            byte[] b = (name + " (saw X-Forwarded-For: " + xff + ")\n").getBytes();
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b); ex.close();
        });
        server.start();
    }
}
