import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * MiniSolace — the three ideas that make Solace distinctive, from scratch:
 *
 *  1. HIERARCHICAL TOPICS as addresses (nothing is provisioned):
 *     "orders/eu/paid" matched by subscriptions like "orders/*" or "orders/>".
 *  2. QUEUES SUBSCRIBE TO TOPICS: a durable endpoint attracts matching
 *     messages and holds them (ack/redelivery) for its consumer.
 *  3. PER-MESSAGE QoS: DIRECT (fire-and-forget fan-out, no persistence)
 *     vs GUARANTEED (persisted in matching queues until acked).
 *
 * Compile & run:  javac MiniSolace.java && java MiniSolace
 */
public class MiniSolace {

    public enum QoS { DIRECT, GUARANTEED }

    /** Solace-style wildcard match: '*' = one level, '>' = rest of the topic. */
    static boolean topicMatch(String subscription, String topic) {
        String[] sub = subscription.split("/");
        String[] top = topic.split("/");
        for (int i = 0; i < sub.length; i++) {
            if (sub[i].equals(">")) return true;              // matches everything after
            if (i >= top.length) return false;
            if (!sub[i].equals("*") && !sub[i].equals(top[i])) return false;
        }
        return sub.length == top.length;
    }

    // ---------- direct (non-persistent) subscribers: market-data style ----------
    record DirectSub(String subscription, Consumer<String> handler) {}
    private final List<DirectSub> directSubs = new CopyOnWriteArrayList<>();

    public void subscribeDirect(String subscription, Consumer<String> handler) {
        directSubs.add(new DirectSub(subscription, handler));
    }

    // ---------- durable queues that subscribe to topics ----------
    static final class DurableQueue {
        final String name;
        final List<String> subscriptions = new CopyOnWriteArrayList<>();
        final BlockingDeque<String> messages = new LinkedBlockingDeque<>();
        final Map<Integer, String> unacked = new ConcurrentHashMap<>();
        int nextTag = 0;
        DurableQueue(String name) { this.name = name; }
    }
    private final Map<String, DurableQueue> queues = new ConcurrentHashMap<>();

    public void createQueue(String name) { queues.put(name, new DurableQueue(name)); }
    public void addTopicSubscription(String queueName, String subscription) {
        queues.get(queueName).subscriptions.add(subscription);
    }

    // ---------- publish: one message, both delivery worlds ----------
    public void publish(String topic, String body, QoS qos) {
        System.out.printf("  publish [%s] %s : %s%n", qos, topic, body);
        // Direct delivery: push to matching live subscribers; nothing stored.
        for (DirectSub s : directSubs)
            if (topicMatch(s.subscription(), topic)) s.handler().accept(body);
        // Guaranteed delivery: attract into every matching durable queue.
        if (qos == QoS.GUARANTEED)
            for (DurableQueue q : queues.values())
                for (String sub : q.subscriptions)
                    if (topicMatch(sub, topic)) { q.messages.add(body); break; }
    }

    // ---------- guaranteed consumer: receive -> ack, or crash -> redelivery ----------
    public final class FlowReceiver {
        private final DurableQueue q;
        FlowReceiver(String queueName) { this.q = queues.get(queueName); }

        /** Returns (tag, message) or null. Message moves to 'unacked' until ack(tag). */
        public Map.Entry<Integer, String> receive() {
            String m = q.messages.poll();
            if (m == null) return null;
            int tag = q.nextTag++;
            q.unacked.put(tag, m);
            return Map.entry(tag, m);
        }
        public void ack(int tag) { q.unacked.remove(tag); }
        /** Simulate consumer crash: everything unacked returns to the queue front. */
        public void crash() {
            q.unacked.values().forEach(q.messages::addFirst);
            q.unacked.clear();
        }
        public int depth() { return q.messages.size(); }
    }

    public FlowReceiver bind(String queueName) { return new FlowReceiver(queueName); }

    // ---------- demo ----------
    public static void main(String[] args) {
        MiniSolace broker = new MiniSolace();

        // A market-data style DIRECT subscriber: fast, no persistence.
        broker.subscribeDirect("orders/*/paid",
                msg -> System.out.println("    [direct sub orders/*/paid] got: " + msg));

        // A durable queue that SUBSCRIBES to a topic hierarchy.
        broker.createQueue("billing-queue");
        broker.addTopicSubscription("billing-queue", "orders/>");

        System.out.println("== one publish, two delivery worlds ==");
        broker.publish("orders/eu/paid", "order-1 99 EUR", QoS.GUARANTEED);
        broker.publish("orders/us/created", "order-2 pending", QoS.GUARANTEED); // queue only ('>' matches)
        broker.publish("prices/AAPL", "231.15", QoS.DIRECT);                    // matches nothing durable

        System.out.println("\n== guaranteed consumption with ack ==");
        FlowReceiver flow = broker.bind("billing-queue");
        var first = flow.receive();
        System.out.println("  received: " + first.getValue());
        flow.ack(first.getKey());
        System.out.println("  acked; queue depth now " + flow.depth());

        System.out.println("\n== crash before ack -> redelivery ==");
        var second = flow.receive();
        System.out.println("  received: " + second.getValue() + "  ...crash! (no ack)");
        flow.crash();
        var redelivered = flow.receive();
        System.out.println("  redelivered: " + redelivered.getValue()
                + "  <- guaranteed messaging honored; consumer must be idempotent");
        flow.ack(redelivered.getKey());

        System.out.println("\n== wildcard sanity checks ==");
        System.out.println("  orders/* matches orders/eu?        " + topicMatch("orders/*", "orders/eu"));
        System.out.println("  orders/* matches orders/eu/paid?   " + topicMatch("orders/*", "orders/eu/paid"));
        System.out.println("  orders/> matches orders/eu/paid?   " + topicMatch("orders/>", "orders/eu/paid"));
    }
}
