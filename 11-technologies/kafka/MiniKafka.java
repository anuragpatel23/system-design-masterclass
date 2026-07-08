import java.util.*;
import java.util.concurrent.*;

/**
 * MiniKafka — the essence of Kafka in one file: a partitioned, append-only,
 * offset-tracked log.
 *
 * What this demonstrates, mechanically:
 *   1. A TOPIC = N partitions; each partition is an ordered, immutable list.
 *   2. Producers route by key: partition = hash(key) % N  → per-key ordering.
 *   3. Consumption does NOT delete. Each consumer GROUP commits its own
 *      offset per partition; different groups read independently (replay!).
 *   4. Consumer-group semantics: each partition is owned by exactly one
 *      consumer in a group → parallelism == partition count.
 *   5. At-least-once: crash after processing but BEFORE commit ⇒ redelivery.
 *
 * Compile & run:  javac MiniKafka.java && java MiniKafka
 */
public class MiniKafka {

    public record Record(String key, String value, long offset) {}

    /** A partition: an append-only log. Synchronized appends; reads are index-based. */
    static final class Partition {
        private final List<Record> log = Collections.synchronizedList(new ArrayList<>());
        long append(String key, String value) {
            synchronized (log) {
                long offset = log.size();
                log.add(new Record(key, value, offset));
                return offset;
            }
        }
        /** Fetch up to max records starting at fromOffset (like a consumer poll). */
        List<Record> fetch(long fromOffset, int max) {
            synchronized (log) {
                if (fromOffset >= log.size()) return List.of();
                int to = (int) Math.min(log.size(), fromOffset + max);
                return new ArrayList<>(log.subList((int) fromOffset, to));
            }
        }
        long endOffset() { return log.size(); }
    }

    /** A topic: partitions + per-group committed offsets (the broker-side state). */
    static final class Topic {
        final String name;
        final Partition[] partitions;
        /** groupId -> (partition -> committed offset). Kafka stores this in __consumer_offsets. */
        final Map<String, Map<Integer, Long>> committed = new ConcurrentHashMap<>();
        Topic(String name, int numPartitions) {
            this.name = name;
            this.partitions = new Partition[numPartitions];
            for (int i = 0; i < numPartitions; i++) partitions[i] = new Partition();
        }
        int partitionFor(String key) { return Math.floorMod(key.hashCode(), partitions.length); }
    }

    private final Map<String, Topic> topics = new ConcurrentHashMap<>();

    public void createTopic(String name, int partitions) { topics.put(name, new Topic(name, partitions)); }

    // ---------------- Producer ----------------

    /** Route by key hash — this is what guarantees per-key ordering. */
    public void produce(String topicName, String key, String value) {
        Topic t = topics.get(topicName);
        int p = t.partitionFor(key);
        long offset = t.partitions[p].append(key, value);
        System.out.printf("  produced %s -> partition %d @ offset %d (%s)%n", key, p, offset, value);
    }

    // ---------------- Consumer ----------------

    /**
     * A consumer in a group, assigned a set of partitions.
     * poll() reads from the last committed offset; commit() advances it.
     * Separating poll from commit is exactly what creates at-least-once.
     */
    public final class Consumer {
        final String group; final Topic topic; final List<Integer> assigned;
        final Map<Integer, Long> position = new HashMap<>();   // in-memory read position

        Consumer(String group, String topicName, List<Integer> assignedPartitions) {
            this.group = group;
            this.topic = topics.get(topicName);
            this.assigned = assignedPartitions;
            Map<Integer, Long> groupOffsets =
                    topic.committed.computeIfAbsent(group, g -> new ConcurrentHashMap<>());
            for (int p : assignedPartitions)
                position.put(p, groupOffsets.getOrDefault(p, 0L));  // resume from committed
        }

        public List<Record> poll(int maxPerPartition) {
            List<Record> out = new ArrayList<>();
            for (int p : assigned) {
                List<Record> batch = topic.partitions[p].fetch(position.get(p), maxPerPartition);
                if (!batch.isEmpty()) position.put(p, batch.get(batch.size() - 1).offset() + 1);
                out.addAll(batch);
            }
            return out;
        }

        /** Commit AFTER processing => at-least-once. (Commit before => at-most-once.) */
        public void commit() {
            Map<Integer, Long> groupOffsets = topic.committed.get(group);
            position.forEach(groupOffsets::put);
        }
    }

    /** Simplified group assignment: spread partitions round-robin across members. */
    public List<Consumer> subscribeGroup(String group, String topicName, int members) {
        Topic t = topics.get(topicName);
        List<List<Integer>> assignment = new ArrayList<>();
        for (int m = 0; m < members; m++) assignment.add(new ArrayList<>());
        for (int p = 0; p < t.partitions.length; p++) assignment.get(p % members).add(p);
        List<Consumer> consumers = new ArrayList<>();
        for (int m = 0; m < members; m++) consumers.add(new Consumer(group, topicName, assignment.get(m)));
        return consumers;
    }

    // ---------------- Demo ----------------

    public static void main(String[] args) {
        MiniKafka kafka = new MiniKafka();
        kafka.createTopic("orders", 3);

        System.out.println("== produce keyed events (same key -> same partition -> ordered) ==");
        kafka.produce("orders", "user-1", "created");
        kafka.produce("orders", "user-2", "created");
        kafka.produce("orders", "user-1", "paid");        // same partition as user-1 "created"
        kafka.produce("orders", "user-3", "created");
        kafka.produce("orders", "user-1", "shipped");

        System.out.println("\n== consumer group 'billing' with 2 members shares partitions ==");
        List<Consumer> billing = kafka.subscribeGroup("billing", "orders", 2);
        for (int i = 0; i < billing.size(); i++) {
            Consumer c = billing.get(i);
            System.out.println(" member" + i + " assigned partitions " + c.assigned
                    + " got " + c.poll(10).stream().map(Record::value).toList());
            c.commit();
        }

        System.out.println("\n== at-least-once: crash BEFORE commit -> redelivery on restart ==");
        List<Consumer> mailer = kafka.subscribeGroup("mailer", "orders", 1);
        Consumer m1 = mailer.get(0);
        System.out.println(" first poll: " + m1.poll(10).stream().map(Record::value).toList());
        System.out.println(" ...consumer crashes WITHOUT commit()...");
        Consumer m2 = kafka.subscribeGroup("mailer", "orders", 1).get(0);  // restart
        System.out.println(" after restart, redelivered: "
                + m2.poll(10).stream().map(Record::value).toList()
                + "  <-- duplicates! this is why consumers must be idempotent");
        m2.commit();

        System.out.println("\n== replay: a brand-new group reads from offset 0 (the log is retained) ==");
        Consumer analytics = kafka.subscribeGroup("analytics", "orders", 1).get(0);
        System.out.println(" analytics got all " + analytics.poll(100).size() + " records from history");
    }
}
