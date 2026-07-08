import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MiniRabbit — the smart-broker model from scratch:
 * exchanges + bindings route messages into queues; workers compete for
 * messages; per-message ACK with redelivery; dead-lettering after max retries.
 *
 * Contrast with MiniKafka.java: there, the log is permanent and consumers
 * track offsets; here, the BROKER tracks each message's lifecycle and
 * deletes it once acknowledged.
 *
 * Compile & run:  javac MiniRabbit.java && java MiniRabbit
 */
public class MiniRabbit {

    public enum ExchangeType { DIRECT, TOPIC, FANOUT }

    public static final class Message {
        static final AtomicLong IDS = new AtomicLong();
        final long id = IDS.incrementAndGet();
        final String routingKey; final String body;
        int deliveryAttempts = 0;
        Message(String routingKey, String body) { this.routingKey = routingKey; this.body = body; }
        public String toString() { return "#" + id + "[" + routingKey + " | " + body + "]"; }
    }

    /** A queue: ready messages + unacked (in-flight) messages. */
    static final class Queue {
        final String name;
        final BlockingDeque<Message> ready = new LinkedBlockingDeque<>();
        final Map<Long, Message> unacked = new ConcurrentHashMap<>();
        Queue(String name) { this.name = name; }
    }

    record Binding(String pattern, Queue queue) {}
    static final class Exchange {
        final ExchangeType type; final List<Binding> bindings = new CopyOnWriteArrayList<>();
        Exchange(ExchangeType type) { this.type = type; }
    }

    private final Map<String, Exchange> exchanges = new ConcurrentHashMap<>();
    private final Map<String, Queue> queues = new ConcurrentHashMap<>();
    private static final int MAX_ATTEMPTS = 3;
    private Queue deadLetterQueue;

    // ---------------- broker admin ----------------
    public void exchangeDeclare(String name, ExchangeType type) { exchanges.put(name, new Exchange(type)); }
    public void queueDeclare(String name) { queues.put(name, new Queue(name)); }
    public void queueBind(String queue, String exchange, String pattern) {
        exchanges.get(exchange).bindings.add(new Binding(pattern, queues.get(queue)));
    }
    public void enableDeadLettering(String dlqName) { queueDeclare(dlqName); deadLetterQueue = queues.get(dlqName); }

    // ---------------- publish: exchange routes by type + binding ----------------
    public void publish(String exchangeName, String routingKey, String body) {
        Exchange ex = exchanges.get(exchangeName);
        Message msg = new Message(routingKey, body);
        int copies = 0;
        for (Binding b : ex.bindings) {
            boolean match = switch (ex.type) {
                case FANOUT -> true;
                case DIRECT -> b.pattern().equals(routingKey);
                case TOPIC  -> topicMatch(b.pattern(), routingKey);
            };
            if (match) { b.queue().ready.add(msg); copies++; }
        }
        System.out.printf("  published %s -> %d queue(s)%n", msg, copies);
    }

    /** AMQP topic matching: '*' = exactly one word, '#' = zero or more words. */
    static boolean topicMatch(String pattern, String key) {
        return key.matches(pattern.replace(".", "\\.")
                                  .replace("*", "[^.]+")
                                  .replace("#", ".*"));
    }

    // ---------------- consume: competing consumers, prefetch, ack/nack ----------------

    /** A worker with a prefetch limit (QoS): max unacked messages it may hold. */
    public final class Worker {
        final String name; final Queue queue; final int prefetch;
        final List<Message> inFlight = new ArrayList<>();
        Worker(String name, String queueName, int prefetch) {
            this.name = name; this.queue = queues.get(queueName); this.prefetch = prefetch;
        }
        /** Pull up to prefetch messages (real RabbitMQ pushes; the accounting is identical). */
        public List<Message> receive() {
            while (inFlight.size() < prefetch) {
                Message m = queue.ready.poll();
                if (m == null) break;
                m.deliveryAttempts++;
                queue.unacked.put(m.id, m);
                inFlight.add(m);
            }
            return new ArrayList<>(inFlight);
        }
        /** ACK: broker forgets the message forever — consumption deletes. */
        public void ack(Message m) {
            queue.unacked.remove(m.id); inFlight.remove(m);
            System.out.printf("    %s ACK %s%n", name, m);
        }
        /** NACK/crash: requeue at the FRONT for redelivery, or dead-letter after max attempts. */
        public void nack(Message m) {
            queue.unacked.remove(m.id); inFlight.remove(m);
            if (m.deliveryAttempts >= MAX_ATTEMPTS && deadLetterQueue != null) {
                deadLetterQueue.ready.add(m);
                System.out.printf("    %s NACK %s -> DEAD-LETTER (attempt %d)%n", name, m, m.deliveryAttempts);
            } else {
                queue.ready.addFirst(m);
                System.out.printf("    %s NACK %s -> requeued (attempt %d)%n", name, m, m.deliveryAttempts);
            }
        }
    }

    public Worker newWorker(String name, String queueName, int prefetch) {
        return new Worker(name, queueName, prefetch);
    }

    // ---------------- demo ----------------
    public static void main(String[] args) {
        MiniRabbit broker = new MiniRabbit();
        broker.exchangeDeclare("orders", ExchangeType.TOPIC);
        broker.queueDeclare("billing");        // gets all paid orders
        broker.queueDeclare("eu-analytics");   // gets all EU orders
        broker.queueBind("billing", "orders", "order.*.paid");
        broker.queueBind("eu-analytics", "orders", "order.eu.#");
        broker.enableDeadLettering("dlq");

        System.out.println("== topic routing ==");
        broker.publish("orders", "order.eu.paid", "order 1: 99 EUR");      // -> both queues
        broker.publish("orders", "order.us.paid", "order 2: 50 USD");      // -> billing only
        broker.publish("orders", "order.eu.created", "order 3: pending");  // -> eu-analytics only

        System.out.println("\n== competing consumers + ack ==");
        Worker w1 = broker.newWorker("worker-1", "billing", 1);  // prefetch 1 = fair dispatch
        Worker w2 = broker.newWorker("worker-2", "billing", 1);
        Message m1 = w1.receive().get(0);
        Message m2 = w2.receive().get(0);   // second message went to the OTHER worker
        w1.ack(m1);

        System.out.println("\n== crash before ack -> redelivery -> dead-letter after 3 attempts ==");
        w2.nack(m2);                                     // attempt 1 failed
        Message retry = w1.receive().get(0);             // redelivered to w1
        w1.nack(retry);                                  // attempt 2 failed
        Message retry2 = w2.receive().get(0);            // redelivered again
        w2.nack(retry2);                                 // attempt 3 -> DLQ
        System.out.println("  dlq depth: " + broker.queues.get("dlq").ready.size()
                + "  (inspect, fix, replay — the DLQ pattern)");
    }
}
