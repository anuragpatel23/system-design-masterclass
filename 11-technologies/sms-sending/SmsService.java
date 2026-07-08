import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/**
 * SmsService — a complete SMS pipeline from scratch (zero dependencies):
 *
 *   - MOCK PROVIDER: an embedded HTTP server that accepts POST /send,
 *     returns a message id ("queued"), and later POSTs a delivery receipt
 *     (DLR) to our webhook — exactly the async shape of Twilio & friends.
 *   - CLIENT STACK: idempotent sending, per-phone rate limiting, provider
 *     failover, and the message state machine QUEUED -> SENT -> DELIVERED.
 *   - OTP FLOW: generate (hashed, TTL, rate-limited) and verify (attempt cap).
 *
 * Compile & run:  javac SmsService.java && java SmsService
 */
public class SmsService {

    // ============ the message state machine ============
    enum Status { QUEUED, SENT, DELIVERED, FAILED }
    static final Map<String, Status> messageStatus = new ConcurrentHashMap<>();

    // ============ MOCK PROVIDER (stands in for Twilio) ============
    /** Accepts /send, replies {"id":...}, then posts a DLR to our webhook after a delay. */
    static HttpServer startMockProvider(int port, int webhookPort, boolean healthy) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        ScheduledExecutorService dlrSender = Executors.newSingleThreadScheduledExecutor();
        server.createContext("/send", exchange -> {
            if (!healthy) { exchange.sendResponseHeaders(503, -1); exchange.close(); return; }
            String msgId = "SM" + UUID.randomUUID().toString().substring(0, 8);
            byte[] resp = ("{\"id\":\"" + msgId + "\",\"status\":\"queued\"}").getBytes();
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
            // Simulate the carrier: deliver after ~300ms, then send the DLR webhook.
            dlrSender.schedule(() -> {
                try {
                    HttpClient.newHttpClient().send(HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + webhookPort + "/dlr"))
                            .POST(HttpRequest.BodyPublishers.ofString(msgId + ":delivered"))
                            .build(), HttpResponse.BodyHandlers.discarding());
                } catch (Exception ignored) {}
            }, 300, TimeUnit.MILLISECONDS);
        });
        server.start();
        return server;
    }

    /** Our DLR webhook: the delivery TRUTH arrives here, not in the send response. */
    static HttpServer startWebhook(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/dlr", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String[] parts = body.split(":");
            messageStatus.put(parts[0], "delivered".equals(parts[1]) ? Status.DELIVERED : Status.FAILED);
            System.out.println("  [webhook] DLR: " + parts[0] + " -> " + parts[1].toUpperCase());
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        return server;
    }

    // ============ CLIENT STACK ============
    static final class SmsClient {
        private final List<String> providerUrls;          // primary first, then fallbacks
        private final Set<String> sentEventIds = ConcurrentHashMap.newKeySet();       // idempotency
        private final Map<String, Deque<Long>> perPhoneSends = new ConcurrentHashMap<>(); // rate limit
        private final HttpClient http = HttpClient.newHttpClient();
        private static final int MAX_PER_MINUTE_PER_PHONE = 3;

        SmsClient(List<String> providerUrls) { this.providerUrls = providerUrls; }

        /** Idempotent, rate-limited send with provider failover. Returns message id or null. */
        String send(String eventId, String phone, String body) {
            if (!sentEventIds.add(eventId)) {                       // queue redelivery? skip.
                System.out.println("  [skip] duplicate event " + eventId);
                return null;
            }
            Deque<Long> window = perPhoneSends.computeIfAbsent(phone, p -> new ArrayDeque<>());
            long now = System.currentTimeMillis();
            synchronized (window) {                                  // sliding-window rate limit
                while (!window.isEmpty() && now - window.peekFirst() > 60_000) window.pollFirst();
                if (window.size() >= MAX_PER_MINUTE_PER_PHONE) {
                    System.out.println("  [rate-limited] " + phone + " (SMS-pumping defense)");
                    return null;
                }
                window.addLast(now);
            }
            for (String provider : providerUrls) {                   // failover chain
                try {
                    HttpResponse<String> resp = http.send(HttpRequest.newBuilder()
                                    .uri(URI.create(provider + "/send"))
                                    .POST(HttpRequest.BodyPublishers.ofString(phone + "|" + body))
                                    .timeout(java.time.Duration.ofSeconds(2)).build(),
                            HttpResponse.BodyHandlers.ofString());
                    if (resp.statusCode() == 200) {
                        String msgId = resp.body().replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
                        messageStatus.put(msgId, Status.SENT);
                        System.out.println("  [sent] via " + provider + " id=" + msgId
                                + " (status=SENT — waiting for DLR)");
                        return msgId;
                    }
                    System.out.println("  [provider-error] " + provider + " -> "
                            + resp.statusCode() + ", failing over");
                } catch (Exception e) {
                    System.out.println("  [provider-down] " + provider + ", failing over");
                }
            }
            System.out.println("  [FAILED] all providers exhausted for " + eventId);
            return null;
        }
    }

    // ============ OTP FLOW ============
    static final class OtpService {
        record OtpEntry(String hash, long expiresAt, int attemptsLeft) {}
        private final Map<String, OtpEntry> store = new ConcurrentHashMap<>();
        private final SmsClient sms;
        OtpService(SmsClient sms) { this.sms = sms; }

        String generateAndSend(String phone) {
            String code = String.format("%06d", new Random().nextInt(1_000_000));
            store.put(phone, new OtpEntry(sha256(code), System.currentTimeMillis() + 300_000, 3));
            sms.send("otp-" + phone + "-" + System.nanoTime(), phone, "Your code is " + code);
            return code;   // returned here only so the demo can verify; real code never leaves SMS
        }

        boolean verify(String phone, String code) {
            OtpEntry e = store.get(phone);
            if (e == null || System.currentTimeMillis() > e.expiresAt()) return false;
            if (e.attemptsLeft() <= 0) { store.remove(phone); return false; }   // brute-force cap
            if (e.hash().equals(sha256(code))) { store.remove(phone); return true; }  // single-use
            store.put(phone, new OtpEntry(e.hash(), e.expiresAt(), e.attemptsLeft() - 1));
            return false;
        }

        static String sha256(String s) {
            try {
                StringBuilder sb = new StringBuilder();
                for (byte b : MessageDigest.getInstance("SHA-256").digest(s.getBytes()))
                    sb.append(String.format("%02x", b));
                return sb.toString();
            } catch (Exception ex) { throw new RuntimeException(ex); }
        }
    }

    // ============ demo ============
    public static void main(String[] args) throws Exception {
        HttpServer webhook = startWebhook(8091);
        HttpServer deadProvider = startMockProvider(8092, 8091, false);  // always 503
        HttpServer goodProvider = startMockProvider(8093, 8091, true);

        SmsClient client = new SmsClient(List.of("http://localhost:8092", "http://localhost:8093"));

        System.out.println("== send with failover (primary is down) ==");
        String msgId = client.send("evt-100", "+919800000001", "Order #789 shipped!");

        System.out.println("\n== idempotency: same event redelivered ==");
        client.send("evt-100", "+919800000001", "Order #789 shipped!");

        System.out.println("\n== OTP flow with rate limiting ==");
        OtpService otp = new OtpService(client);
        String code = otp.generateAndSend("+919800000002");
        System.out.println("  verify wrong code: " + otp.verify("+919800000002", "000000"));
        System.out.println("  verify right code: " + otp.verify("+919800000002", code));
        System.out.println("  replay same code:  " + otp.verify("+919800000002", code) + " (single-use)");
        otp.generateAndSend("+919800000002");
        otp.generateAndSend("+919800000002");
        otp.generateAndSend("+919800000002");   // 4th send in a minute -> rate limited

        Thread.sleep(600);   // let the DLR webhook arrive
        System.out.println("\n== final status (updated by webhook, not by the send call) ==");
        System.out.println("  " + msgId + " -> " + messageStatus.get(msgId));

        webhook.stop(0); deadProvider.stop(0); goodProvider.stop(0);
    }
}
