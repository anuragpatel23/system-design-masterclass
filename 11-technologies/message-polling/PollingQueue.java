import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PollingQueue — the SQS-style pull-consumption loop from scratch:
 *
 *   1. receive(max, waitTimeMs, visibilityTimeoutMs) — LONG POLL: blocks
 *      until a message arrives or the wait expires; received messages become
 *      INVISIBLE (not deleted) and get a one-time receipt handle.
 *   2. delete(receiptHandle) — the ONLY way a message leaves for good.
 *   3. A background reaper returns messages whose visibility timeout expired
 *      (consumer crashed, or was too slow) — automatic redelivery.
 *   4. receiveCount > maxReceiveCount  ->  dead-letter queue.
 *
 * The demo reproduces the classic "visibility timeout too short" bug live.
 *
 * Compile & run:  javac PollingQueue.java && java PollingQueue
 */
public class PollingQueue {

    public static final class Msg {
        final String id, body;
        int receiveCount = 0;
        long invisibleUntil = 0;              // 0 = visible
        String receiptHandle = null;          // rotates on every receive
        Msg(String id, String body) { this.id = id; this.body = body; }
    }

    private final List<Msg> messages = new ArrayList<>();          // guarded by 'this'
    private final List<Msg> deadLetter = new ArrayList<>();
    private final int maxReceiveCount;
    private final AtomicInteger handles = new AtomicInteger();

    public PollingQueue(int maxReceiveCount) {
        this.maxReceiveCount = maxReceiveCount;
        ScheduledExecutorService reaper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r); t.setDaemon(true); return t;
        });
        reaper.scheduleAtFixedRate(this::reapExpiredVisibility, 50, 50, TimeUnit.MILLISECONDS);
    }

    public synchronized void send(String body) {
        messages.add(new Msg(UUID.randomUUID().toString().substring(0, 8), body));
        notifyAll();                                               // wake long-pollers
    }

    /**
     * LONG-POLL receive: waits up to waitTimeMs for at least one visible message.
     * Received messages are hidden for visibilityTimeoutMs and must be deleted
     * via their receipt handle before that expires.
     */
    public synchronized List<Msg> receive(int max, long waitTimeMs, long visibilityTimeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + waitTimeMs;
        while (true) {
            List<Msg> batch = new ArrayList<>();
            long now = System.currentTimeMillis();
            for (Msg m : messages) {
                if (m.invisibleUntil <= now && batch.size() < max) {
                    m.receiveCount++;
                    m.invisibleUntil = now + visibilityTimeoutMs;
                    m.receiptHandle = "rh-" + handles.incrementAndGet();
                    batch.add(m);
                }
            }
            if (!batch.isEmpty()) return batch;
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) return List.of();                  // empty response (like SQS)
            wait(remaining);                                       // ← the "long" in long polling
        }
    }

    /** Delete by receipt handle — succeeds only if the handle is still current. */
    public synchronized boolean delete(String receiptHandle) {
        return messages.removeIf(m -> receiptHandle.equals(m.receiptHandle));
    }

    /** Heartbeat: extend visibility for a long-running job (SQS ChangeMessageVisibility). */
    public synchronized boolean extendVisibility(String receiptHandle, long extraMs) {
        for (Msg m : messages)
            if (receiptHandle.equals(m.receiptHandle)) {
                m.invisibleUntil = System.currentTimeMillis() + extraMs;
                return true;
            }
        return false;
    }

    /** Reaper: expired visibility -> visible again (redelivery) or DLQ if over the limit. */
    private synchronized void reapExpiredVisibility() {
        long now = System.currentTimeMillis();
        Iterator<Msg> it = messages.iterator();
        boolean woke = false;
        while (it.hasNext()) {
            Msg m = it.next();
            if (m.invisibleUntil != 0 && m.invisibleUntil <= now && m.receiptHandle != null) {
                if (m.receiveCount >= maxReceiveCount) {
                    it.remove(); deadLetter.add(m);
                    System.out.printf("  [reaper] %s exceeded maxReceiveCount -> DLQ%n", m.id);
                } else {
                    m.invisibleUntil = 0; m.receiptHandle = null; woke = true;
                    System.out.printf("  [reaper] %s visibility expired -> back in queue (receiveCount=%d)%n",
                            m.id, m.receiveCount);
                }
            }
        }
        if (woke) notifyAll();
    }

    public synchronized int depth() { return messages.size(); }
    public synchronized int dlqDepth() { return deadLetter.size(); }

    // ---------------- demo ----------------

    public static void main(String[] args) throws Exception {
        PollingQueue q = new PollingQueue(3);

        System.out.println("== happy path: receive -> process -> delete ==");
        q.send("job-A");
        List<Msg> batch = q.receive(10, 1000, 500);
        Msg a = batch.get(0);
        System.out.println("  received " + a.id + " (" + a.body + "), processing... deleting.");
        q.delete(a.receiptHandle);
        System.out.println("  depth after delete: " + q.depth());

        System.out.println("\n== crash before delete -> automatic redelivery ==");
        q.send("job-B");
        Msg b = q.receive(10, 1000, 200).get(0);
        System.out.println("  received " + b.id + " ...worker crashes (no delete)");
        Thread.sleep(400);                                  // visibility expires; reaper returns it
        Msg bAgain = q.receive(10, 1000, 500).get(0);
        System.out.println("  redelivered " + bAgain.id + " receiveCount=" + bAgain.receiveCount
                + "  <- at-least-once; processing must be idempotent");
        q.delete(bAgain.receiptHandle);

        System.out.println("\n== THE CLASSIC BUG: visibility timeout < processing time ==");
        q.send("job-C");
        Msg c1 = q.receive(10, 1000, 150).get(0);           // 150ms visibility...
        System.out.println("  worker-1 got " + c1.id + ", processing takes 400ms...");
        Thread.sleep(300);                                  // ...but we're still processing at 300ms
        List<Msg> stolen = q.receive(10, 100, 500);         // worker-2 polls meanwhile
        System.out.println("  worker-2 ALSO got: " + (stolen.isEmpty() ? "nothing" : stolen.get(0).id)
                + "  <- DOUBLE PROCESSING. Fix: timeout > worst case, or heartbeat-extend.");

        System.out.println("\n== poison message -> DLQ after 3 receives ==");
        q.send("job-POISON");
        for (int attempt = 1; attempt <= 3; attempt++) {
            List<Msg> got = q.receive(10, 500, 100);
            if (!got.isEmpty()) System.out.println("  attempt " + attempt + ": received, crashing...");
            Thread.sleep(250);
        }
        Thread.sleep(200);
        System.out.println("  queue depth=" + q.depth() + ", DLQ depth=" + q.dlqDepth());
    }
}
