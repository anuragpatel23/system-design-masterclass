# Message Queues

> Message queues decouple the producer of work from the consumer of work in time and in failure domain — the producer doesn't need the consumer to be available, fast, or even up at all, to successfully hand off a unit of work. This is the core building block behind asynchronous processing, and it's the primary tool for trading immediate consistency for throughput and resilience (see [Latency vs Throughput](../../01-foundations/latency-vs-throughput/README.md)).

---

## 1. Messaging Patterns

### Point-to-Point (Queue)
A message is delivered to and consumed by **exactly one** consumer, even if multiple consumers are listening (they compete for messages). Classic use: a work queue where each task should be processed once (e.g., "resize this uploaded image").
- **Examples:** Amazon SQS, RabbitMQ (classic queues).

### Publish/Subscribe (Pub/Sub)
A message is broadcast to **all** subscribers of a topic — each subscriber gets its own independent copy. Classic use: an event that multiple, unrelated downstream systems care about (e.g., "order placed" triggers inventory update, email notification, and analytics tracking, independently).
- **Examples:** Kafka (topics with consumer groups), Amazon SNS, Google Pub/Sub, RabbitMQ (fanout exchanges).

**Senior-level nuance:** Kafka blurs this line intentionally — a Kafka topic behaves like pub/sub *across different consumer groups* (each group gets every message) but like a point-to-point queue *within* a single consumer group (each message goes to only one consumer in that group, enabling parallel processing while still guaranteeing each message is processed once per group). Understanding this dual nature is a strong signal in interviews.

---

## 2. Concrete Products, and the Deeper Architectural Split: Queue vs. Log

| Product | Model | Distinguishing trait |
|---|---|---|
| **Apache Kafka** | Distributed commit log | Messages are **retained** for a configured period (or forever) regardless of consumption — consumers track their own read position (offset) and can **replay** history; built for very high sustained throughput |
| **RabbitMQ** | Traditional message broker | Rich routing (exchanges: direct, topic, fanout, headers) for complex routing logic; messages are typically **deleted once acknowledged** by the consumer — no replay by default |
| **Amazon SQS** | Managed queue | Simple, fully managed point-to-point queue; **FIFO queues** available for strict per-group ordering + exactly-once-ish deduplication, at lower throughput than **standard queues** (best-effort ordering, higher throughput) |
| **Amazon SNS / Google Pub/Sub** | Managed pub/sub | Fully managed topic-based broadcast; often paired with SQS (SNS fans out to multiple SQS queues, one per consumer type) for managed pub/sub + durable per-consumer queueing combined |
| **Apache Pulsar** | Distributed log (Kafka alternative) | Separates storage (BookKeeper) from serving layer, enabling independent scaling of the two and multi-tenancy features Kafka added only later |

**The deeper distinction worth naming explicitly: "queue" semantics vs. "log" semantics.**
- A **traditional queue** (RabbitMQ, SQS) treats a message as **consumed and gone** once acknowledged — there is one logical "current state" of the queue, and once you've processed a message, it's not there to be reprocessed or inspected by a second, independent consumer type without explicit fan-out.
- A **log** (Kafka, Pulsar) treats messages as an **append-only, retained sequence** — consumption doesn't delete anything; multiple, entirely independent consumer groups can each read the same log at their own pace and even **replay from an earlier offset** (reprocess the last 24 hours of events after fixing a bug, rebuild a downstream system's state from scratch by replaying history, or onboard a brand-new consumer type without any change to producers). This replayability is *the* reason log-based systems became the default backbone for [event-driven architecture](../../05-distributed-systems/event-driven-architecture/README.md) and [CQRS/event sourcing](../../05-distributed-systems/cqrs-event-sourcing/README.md) — the log itself can serve as the durable source of truth, not just a transient delivery mechanism.

**The practical choice:** reach for a traditional queue (SQS/RabbitMQ) for straightforward task distribution where once-processed-means-gone is exactly right (resize this image, send this email). Reach for a log (Kafka/Pulsar) when multiple independent systems need their own view of the same event stream, when replay/reprocessing is a real requirement, or when sustained throughput at very large scale is the dominant constraint.

---

## 3. Delivery Guarantees — the Precise Definitions

| Guarantee | Meaning | Risk |
|---|---|---|
| **At-most-once** | Message is delivered 0 or 1 times — never redelivered, even on failure | Silent message loss |
| **At-least-once** | Message is guaranteed delivered 1 or more times — redelivered on any doubt | **Duplicate processing** — consumers MUST be idempotent |
| **Exactly-once** | Message is delivered and processed exactly once, no loss, no duplication | Hardest to achieve; usually means "effectively-once" via at-least-once delivery + idempotent processing, not a true distributed exactly-once guarantee (true exactly-once across a network is provably very expensive/impossible in the general case, related to the [Consensus](../../05-distributed-systems/consensus-algorithms/raft/README.md) problem) |

**Interview-critical point:** almost every real system claiming "exactly-once" is actually implementing **at-least-once delivery with idempotent consumers** — i.e., the message might be delivered twice, but processing it twice has the same effect as processing it once (e.g., via a deduplication key, an idempotency token, or a natural upsert operation). Claiming true exactly-once semantics without qualifying this is a red flag in a senior interview.

---

## 4. Dead Letter Queues (DLQ)

When a message repeatedly fails processing (a bug, a malformed payload, a downstream dependency permanently down), it shouldn't be retried forever, blocking the queue behind it (**head-of-line blocking**) or silently dropped. A **Dead Letter Queue** captures messages that exceed a retry limit, moving them aside for manual inspection or automated alerting — keeping the main queue flowing while preserving the failed message for debugging/reprocessing.

---

## 5. Ordering Guarantees

- **Global ordering across an entire topic/queue is expensive and rarely actually needed** — it typically requires a single partition/consumer, which caps throughput.
- **Partitioned ordering** (Kafka's model) — messages with the same partition key (e.g., the same `order_id`) are guaranteed to be processed in order **relative to each other**, while different keys can be processed in parallel across partitions, with no ordering guarantee *between* different keys. This is almost always the right trade-off: identify what actually needs relative ordering (usually: events about the *same entity*) and partition by that key.

---

## 6. Real-World Example: LinkedIn's Kafka — Built to Decouple Hundreds of Independent Consumers from a Firehose of Events

Kafka was originally built at LinkedIn specifically to solve a scaling problem: as the number of systems needing to consume the same activity-stream data (page views, profile updates, etc.) grew, point-to-point integrations between every producer and every consumer became an unmanageable `O(n²)` mesh of custom pipelines.

LinkedIn's engineering blog describes the shift to a **central, durable, replayable log** (Kafka) that any number of independent consumer systems (search indexing, recommendation systems, monitoring, data warehousing) could subscribe to, each reading at its own pace, without the producer needing to know or care how many consumers exist or how fast they process — a textbook application of the pub/sub decoupling principle at massive organizational and technical scale.

**The lesson:** message queues don't just solve a technical throughput problem — at organizational scale, they solve an integration/coupling problem, decoupling not just producer and consumer *speed*, but producer and consumer *teams*, letting new consumers be added without ever touching the producer's code.

---

## 7. Spring Boot Example: Idempotent, At-Least-Once Consumer with a Dead Letter Queue

```java
// build.gradle: spring-kafka

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderEventConsumer {

    private final ProcessedEventRepository processedEventRepository; // dedup store
    private final InventoryService inventoryService;

    // Kafka delivers at-least-once by default -- this consumer MUST be idempotent,
    // since the same message can legitimately arrive twice (e.g., after a consumer
    // crash before it committed its offset, forcing redelivery of the last batch).
    @KafkaListener(topics = "order-events", groupId = "inventory-service")
    public void onOrderPlaced(OrderPlacedEvent event, Acknowledgment ack) {
        try {
            // Idempotency check: has this exact event ID already been processed?
            // This turns "at-least-once delivery" into "effectively-once processing."
            if (processedEventRepository.existsById(event.getEventId())) {
                log.info("Duplicate event {} ignored", event.getEventId());
                ack.acknowledge();
                return;
            }

            inventoryService.decrementStock(event.getSku(), event.getQuantity());
            processedEventRepository.save(new ProcessedEvent(event.getEventId(), Instant.now()));

            ack.acknowledge(); // manually commit offset only AFTER successful processing
        } catch (Exception e) {
            log.error("Failed to process order event {}, will retry", event.getEventId(), e);
            throw e; // don't ack -- triggers Kafka's retry/DLQ mechanism below
        }
    }
}
```

```java
@Configuration
public class KafkaErrorHandlingConfig {

    // After a bounded number of retries, route the failed message to a dead letter
    // topic instead of blocking this partition's processing indefinitely (head-of-line blocking).
    @Bean
    public DefaultErrorHandler errorHandler(KafkaTemplate<String, Object> template) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
            (record, ex) -> new TopicPartition("order-events.DLQ", record.partition()));

        // Exponential backoff: 1s, 2s, 4s, then give up and send to DLQ
        ExponentialBackOff backOff = new ExponentialBackOff(1000L, 2.0);
        backOff.setMaxElapsedTime(30_000L);

        return new DefaultErrorHandler(recoverer, backOff);
    }
}
```

```yaml
# application.yml
spring:
  kafka:
    consumer:
      group-id: inventory-service
      enable-auto-commit: false        # manual ack -- see Acknowledgment usage above
      properties:
        max.poll.records: 50
    listener:
      ack-mode: manual
    producer:
      properties:
        enable.idempotence: true       # producer-side idempotency (dedup on broker for retried sends)
        acks: all                      # wait for all in-sync replicas -- durability over latency
```

**Why this matters at senior level:** the code demonstrates the two things a staff interview specifically listens for on this topic — **explicit idempotency handling** (not just "Kafka guarantees exactly-once," which is an overclaim) and **an explicit dead-letter strategy** to prevent a single poison-pill message from blocking an entire partition's throughput.

---

## 8. Common Pitfalls

- Claiming "exactly-once" delivery without qualifying it as at-least-once + idempotent consumer — a precise, senior-level correction to make even when the interviewer's question uses the looser term.
- Building consumers that are not idempotent, then being surprised by duplicate processing (double-charging a customer, double-decrementing inventory) under at-least-once delivery, which is the *default and expected* behavior of nearly every production message queue.
- Requiring global ordering when only per-entity ordering was actually needed, unnecessarily capping throughput by funneling everything through a single partition/consumer.
- No dead letter queue — a single malformed or poison-pill message can block an entire partition's processing indefinitely under naive infinite-retry logic.

---

## 9. 60-Second Interview Answer

> "Message queues decouple producers from consumers in time and failure domain. Point-to-point delivers each message to one consumer for competing work; pub/sub broadcasts to every subscriber for independent downstream reactions — Kafka actually gives you both, since it's pub/sub across consumer groups but point-to-point within a group. For delivery guarantees, I'd assume at-least-once by default and design consumers to be idempotent, since true exactly-once across a network is effectively at-least-once plus deduplication in practice, not a free guarantee. I'd also always include a dead letter queue with bounded retries, so a single poison-pill message can't block an entire partition's throughput, and I'd only require ordering per-entity, via partition keys, rather than globally, to avoid unnecessarily serializing unrelated work."

**Related:** [Latency vs Throughput](../../01-foundations/latency-vs-throughput/README.md) · [Event-Driven Architecture](../../05-distributed-systems/event-driven-architecture/README.md) · [Saga Pattern](../../05-distributed-systems/saga-pattern/README.md) · [CQRS & Event Sourcing](../../05-distributed-systems/cqrs-event-sourcing/README.md)
