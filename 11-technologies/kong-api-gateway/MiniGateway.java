import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * MiniGateway — Kong's architecture from scratch on the JDK HttpServer:
 *
 *   ROUTE matching  ->  PLUGIN CHAIN (auth -> rate-limit -> transform -> log)
 *                   ->  REVERSE PROXY to the upstream SERVICE.
 *
 * Plugins are an ordered list of interfaces with an access() phase that can
 * short-circuit (return a response) or pass through — exactly Kong's model.
 *
 * Run it, then:
 *   curl http://localhost:9000/orders                      -> 401 (no key)
 *   curl -H 'apikey: my-secret-key' localhost:9000/orders  -> proxied 200
 *   ...repeat 5x fast                                      -> 429 rate limited
 *
 * Compile & run:  javac MiniGateway.java && java MiniGateway
 */
public class MiniGateway {

    // ---------- Kong's entity model ----------
    record Service(String name, String upstreamUrl) {}
    record Route(String pathPrefix, Service service, List<Plugin> plugins) {}
    record Consumer(String username, String apiKey) {}

    /** A plugin's access phase: return null to continue, or a ShortCircuit to stop. */
    interface Plugin {
        record ShortCircuit(int status, String body) {}
        ShortCircuit access(HttpExchange exchange, Map<String, Object> ctx);
        default void logPhase(HttpExchange exchange, Map<String, Object> ctx, int status) {}
    }

    // ---------- Plugin: key-auth ----------
    static final class KeyAuthPlugin implements Plugin {
        private final Map<String, Consumer> byKey = new ConcurrentHashMap<>();
        KeyAuthPlugin(List<Consumer> consumers) { consumers.forEach(c -> byKey.put(c.apiKey(), c)); }
        public ShortCircuit access(HttpExchange ex, Map<String, Object> ctx) {
            String key = ex.getRequestHeaders().getFirst("apikey");
            Consumer c = key == null ? null : byKey.get(key);
            if (c == null) return new ShortCircuit(401, "{\"message\":\"No API key found\"}");
            ctx.put("consumer", c);              // identified caller — later plugins use this
            return null;
        }
    }

    // ---------- Plugin: token-bucket rate limiting per consumer ----------
    static final class RateLimitPlugin implements Plugin {
        private final double capacity, refillPerSec;
        private final Map<String, double[]> buckets = new ConcurrentHashMap<>(); // {tokens, lastRefillNanos}
        RateLimitPlugin(double capacity, double refillPerSec) {
            this.capacity = capacity; this.refillPerSec = refillPerSec;
        }
        public ShortCircuit access(HttpExchange ex, Map<String, Object> ctx) {
            Consumer c = (Consumer) ctx.get("consumer");
            String id = c != null ? c.username() : ex.getRemoteAddress().getHostString();
            double[] b = buckets.computeIfAbsent(id, k -> new double[]{capacity, System.nanoTime()});
            synchronized (b) {
                long now = System.nanoTime();
                b[0] = Math.min(capacity, b[0] + (now - b[1]) / 1e9 * refillPerSec);  // refill
                b[1] = now;
                if (b[0] < 1.0)
                    return new ShortCircuit(429, "{\"message\":\"API rate limit exceeded\"}");
                b[0] -= 1.0;                                                          // spend a token
                ex.getResponseHeaders().set("X-RateLimit-Remaining", String.valueOf((int) b[0]));
            }
            return null;
        }
    }

    // ---------- Plugin: request-transformer (inject identity headers upstream) ----------
    static final class TransformerPlugin implements Plugin {
        public ShortCircuit access(HttpExchange ex, Map<String, Object> ctx) {
            Consumer c = (Consumer) ctx.get("consumer");
            if (c != null) ctx.put("upstreamHeader:X-Consumer-Username", c.username());
            return null;
        }
    }

    // ---------- Plugin: logging (log phase runs after the response) ----------
    static final class LoggingPlugin implements Plugin {
        public ShortCircuit access(HttpExchange ex, Map<String, Object> ctx) {
            ctx.put("startNanos", System.nanoTime());
            return null;
        }
        public void logPhase(HttpExchange ex, Map<String, Object> ctx, int status) {
            long tookMs = (System.nanoTime() - (long) ctx.get("startNanos")) / 1_000_000;
            Consumer c = (Consumer) ctx.get("consumer");
            System.out.printf("  [log] %s %s -> %d (%dms) consumer=%s%n",
                    ex.getRequestMethod(), ex.getRequestURI(), status, tookMs,
                    c == null ? "-" : c.username());
        }
    }

    // ---------- the gateway: match route, run chain, proxy ----------
    private final List<Route> routes = new ArrayList<>();
    private final HttpClient http = HttpClient.newHttpClient();

    void addRoute(Route r) { routes.add(r); }

    void start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.createContext("/", exchange -> {
            Route route = routes.stream()
                    .filter(r -> exchange.getRequestURI().getPath().startsWith(r.pathPrefix()))
                    .findFirst().orElse(null);
            if (route == null) { respond(exchange, 404, "{\"message\":\"no Route matched\"}", null, Map.of()); return; }

            Map<String, Object> ctx = new HashMap<>();
            for (Plugin p : route.plugins()) {                 // ACCESS phase, in order
                Plugin.ShortCircuit sc = p.access(exchange, ctx);
                if (sc != null) { respond(exchange, sc.status(), sc.body(), route, ctx); return; }
            }
            try {                                              // PROXY phase
                HttpRequest.Builder req = HttpRequest.newBuilder()
                        .uri(URI.create(route.service().upstreamUrl() + exchange.getRequestURI().getPath()));
                ctx.forEach((k, v) -> {                        // headers added by transformer plugin
                    if (k.startsWith("upstreamHeader:")) req.header(k.substring(15), v.toString());
                });
                HttpResponse<String> upstream = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
                respond(exchange, upstream.statusCode(), upstream.body(), route, ctx);
            } catch (Exception e) {                            // upstream down -> 502
                respond(exchange, 502, "{\"message\":\"An invalid response was received from the upstream\"}", route, ctx);
            }
        });
        server.start();
        System.out.println("gateway (data plane) on :" + port);
    }

    private void respond(HttpExchange ex, int status, String body, Route route, Map<String, Object> ctx)
            throws IOException {
        byte[] bytes = body.getBytes();
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
        if (route != null)
            for (Plugin p : route.plugins()) p.logPhase(ex, ctx, status);   // LOG phase
    }

    // ---------- demo: an upstream + the gateway + scripted calls ----------
    public static void main(String[] args) throws Exception {
        // A trivial upstream "orders service"
        HttpServer upstream = HttpServer.create(new InetSocketAddress(9080), 0);
        upstream.createContext("/orders", ex -> {
            String user = ex.getRequestHeaders().getFirst("X-Consumer-Username");
            byte[] b = ("{\"orders\":[1,2,3],\"served_for\":\"" + user + "\"}").getBytes();
            ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close();
        });
        upstream.start();

        MiniGateway gw = new MiniGateway();
        Service orders = new Service("orders", "http://localhost:9080");
        gw.addRoute(new Route("/orders", orders, List.of(
                new LoggingPlugin(),
                new KeyAuthPlugin(List.of(new Consumer("shilpak", "my-secret-key"))),
                new RateLimitPlugin(3, 1),          // burst 3, refill 1/sec
                new TransformerPlugin())));
        gw.start(9000);

        // scripted client calls
        HttpClient c = HttpClient.newHttpClient();
        System.out.println("\n== no key ==");
        call(c, null);
        System.out.println("\n== with key, 5 rapid calls (bucket=3) ==");
        for (int i = 0; i < 5; i++) call(c, "my-secret-key");
    }

    static void call(HttpClient c, String key) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create("http://localhost:9000/orders"));
        if (key != null) b.header("apikey", key);
        HttpResponse<String> r = c.send(b.build(), HttpResponse.BodyHandlers.ofString());
        System.out.println("  -> " + r.statusCode() + " " + r.body());
    }
}
