import java.io.*;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;

/**
 * EmailSender — two things in one file:
 *
 *  PART 1: A raw-socket SMTP client — the actual EHLO / MAIL FROM / RCPT TO /
 *          DATA dialogue, so SMTP stops being a black box.
 *          Test against Mailpit:  docker run -p 1025:1025 -p 8025:8025 axllent/mailpit
 *          then view "sent" mail at http://localhost:8025
 *
 *  PART 2: A mini production pipeline: queue -> idempotent worker with
 *          suppression list and transient(4xx)-vs-permanent(5xx) handling.
 *
 * Compile & run:  javac EmailSender.java && java EmailSender
 * (Part 1 runs only if an SMTP server is listening on localhost:1025.)
 */
public class EmailSender {

    // ======================= PART 1: SMTP on raw sockets =======================

    public static final class SmtpClient {
        private final String host; private final int port;
        public SmtpClient(String host, int port) { this.host = host; this.port = port; }

        /** Sends one email by speaking the SMTP line protocol. Throws on 4xx/5xx. */
        public void send(String from, String to, String subject, String body) throws IOException {
            try (Socket socket = new Socket(host, port);
                 BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                 PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true)) {

                expect(in, 220);                       // server greeting
                command(out, in, "EHLO localhost", 250);
                command(out, in, "MAIL FROM:<" + from + ">", 250);
                command(out, in, "RCPT TO:<" + to + ">", 250);
                command(out, in, "DATA", 354);         // 354 = "go ahead, end with ."
                out.print("From: " + from + "\r\n");
                out.print("To: " + to + "\r\n");
                out.print("Subject: " + subject + "\r\n");
                out.print("Date: " + new Date() + "\r\n");
                out.print("\r\n");                     // blank line: headers end, body begins
                out.print(body + "\r\n");
                out.print(".\r\n");                    // lone dot terminates DATA
                out.flush();
                expect(in, 250);
                command(out, in, "QUIT", 221);
            }
        }

        private void command(PrintWriter out, BufferedReader in, String cmd, int expectCode)
                throws IOException {
            System.out.println("  C: " + cmd);
            out.print(cmd + "\r\n"); out.flush();
            expect(in, expectCode);
        }

        private void expect(BufferedReader in, int expectCode) throws IOException {
            String line;
            do { line = in.readLine(); System.out.println("  S: " + line); }
            while (line != null && line.length() > 3 && line.charAt(3) == '-');   // multi-line reply
            int code = Integer.parseInt(line.substring(0, 3));
            if (code != expectCode)
                throw new SmtpException(code, "expected " + expectCode + " got: " + line);
        }
    }

    /** Carries the response code so the pipeline can split transient vs permanent. */
    public static final class SmtpException extends IOException {
        final int code;
        SmtpException(int code, String msg) { super(msg); this.code = code; }
        boolean isTransient() { return code >= 400 && code < 500; }   // 4xx: retry
        boolean isPermanent() { return code >= 500; }                  // 5xx: suppress, never retry
    }

    // ================= PART 2: the pipeline around the protocol =================

    public record EmailJob(String eventId, String to, String subject, String body) {}

    public static final class EmailPipeline {
        private final BlockingQueue<EmailJob> queue = new LinkedBlockingQueue<>();
        private final Set<String> processedEventIds = ConcurrentHashMap.newKeySet(); // idempotency
        private final Set<String> suppressionList = ConcurrentHashMap.newKeySet();   // bounces/unsubs
        private final SmtpClient smtp;
        private static final int MAX_RETRIES = 3;

        public EmailPipeline(SmtpClient smtp) { this.smtp = smtp; }

        public void enqueue(EmailJob job) { queue.add(job); }
        public void suppress(String address) { suppressionList.add(address); }

        /** One worker iteration: exactly the checks a real email worker performs. */
        public void processOne() throws InterruptedException {
            EmailJob job = queue.poll(100, TimeUnit.MILLISECONDS);
            if (job == null) return;

            // 1. IDEMPOTENCY — queue redelivery must not double-send.
            if (!processedEventIds.add(job.eventId())) {
                System.out.println("  [skip] duplicate event " + job.eventId());
                return;
            }
            // 2. SUPPRESSION — never mail bounced/unsubscribed addresses.
            if (suppressionList.contains(job.to())) {
                System.out.println("  [skip] " + job.to() + " is suppressed");
                return;
            }
            // 3. SEND with transient-retry / permanent-suppress semantics.
            for (int attempt = 1; ; attempt++) {
                try {
                    smtp.send("noreply@example.com", job.to(), job.subject(), job.body());
                    System.out.println("  [sent] " + job.eventId() + " -> " + job.to());
                    return;
                } catch (SmtpException e) {
                    if (e.isPermanent()) {                        // 5xx: hard bounce
                        suppressionList.add(job.to());
                        System.out.println("  [hard-bounce] suppressed " + job.to());
                        return;
                    }
                    if (attempt >= MAX_RETRIES) {                 // give up -> DLQ in real life
                        System.out.println("  [dlq] " + job.eventId() + " after " + attempt + " attempts");
                        return;
                    }
                    long backoff = (long) (100 * Math.pow(2, attempt) * (0.5 + Math.random()));
                    System.out.println("  [retry] transient " + e.code + ", backoff " + backoff + "ms");
                    Thread.sleep(backoff);
                } catch (IOException e) {                          // connect failure = transient
                    if (attempt >= MAX_RETRIES) { System.out.println("  [dlq] " + job.eventId()); return; }
                    Thread.sleep(200L * attempt);
                }
            }
        }
    }

    // ---------------- demo ----------------

    public static void main(String[] args) throws Exception {
        SmtpClient smtp = new SmtpClient("localhost", 1025);

        System.out.println("== PART 1: raw SMTP conversation (needs Mailpit on :1025) ==");
        try {
            smtp.send("noreply@example.com", "shilpak@example.com",
                      "Welcome!", "Hello from a hand-rolled SMTP client.");
            System.out.println("  delivered — open http://localhost:8025 to see it\n");
        } catch (IOException e) {
            System.out.println("  (no local SMTP server: " + e.getMessage() + ") — skipping\n");
        }

        System.out.println("== PART 2: pipeline semantics (no server needed for the checks) ==");
        EmailPipeline pipeline = new EmailPipeline(smtp);
        pipeline.suppress("bounced@example.com");
        pipeline.enqueue(new EmailJob("evt-1", "shilpak@example.com", "Order shipped", "..."));
        pipeline.enqueue(new EmailJob("evt-1", "shilpak@example.com", "Order shipped", "..."));  // duplicate!
        pipeline.enqueue(new EmailJob("evt-2", "bounced@example.com", "Hi", "..."));             // suppressed
        for (int i = 0; i < 3; i++) pipeline.processOne();
    }
}
