# Kafka — The Distributed Commit Log

> **Mental model:** Kafka is not a queue — it's a **distributed, partitioned, replicated append-only log**. Messages aren't deleted on consumption; consumers track their own **offsets** and can re-read. That single inversion (dumb broker, smart consumer — the opposite of [RabbitMQ](../rabbitmq/README.md)) explains almost every Kafka property: throughput, replay, consumer groups, ordering, and retention.

---

## 1. Architecture, in the order interviews probe it

- **Topic → partitions:** a topic is split into N partitions; each partition is an **ordered, immutable log** on disk. Ordering is guaranteed **within a partition only** — the produce key (`hash(key) % partitions`) decides the partition, so *"all events for one user are ordered"* means *keying by user_id*. Total order across a topic requires 1 partition (= no parallelism): say this trade-off out loud.
- **Why so fast:** sequential disk I/O (appends only — the [LSM insight](../../06-databases-deep-dive/b-trees-lsm-trees/README.md) applied to messaging), OS page cache, **zero-copy** `sendfile` to consumers, and batching+compression on the producer. Hundreds of MB/sec per broker is normal.
- **Consumer groups:** each partition is assigned to **exactly one consumer within a group** → parallelism = partition count; two *different* groups each get all messages (pub/sub and queueing unified). A consumer joining/leaving triggers a **rebalance** (brief pause — an operational fact worth naming). **Offsets** are committed per group to an internal topic (`__consumer_offsets`); commit *after* processing = at-least-once (duplicates possible ⇒ [idempotent consumers](../../08-api-design/idempotency/README.md)); commit *before* = at-most-once.
- **Replication:** each partition has a leader + followers; producers/consumers talk to the leader; **ISR** (in-sync replicas) tracks caught-up followers. Producer `acks=all` + `min.insync.replicas=2` = durable writes that survive a broker loss; `acks=1` is faster and can lose acked messages on leader failure — the classic [consistency-vs-latency](../../09-interview-prep/tradeoff-cheatsheet.md) dial.
- **Retention:** time/size-based (e.g., 7 days) regardless of consumption — enabling **replay** (rebuild a service's state, backfill a new consumer, [event sourcing](../../05-distributed-systems/cqrs-event-sourcing/README.md)); or **log compaction** (keep latest value per key — a changelog table).
- **Exactly-once:** idempotent producer (dedup by producer id + sequence) + transactions get you effectively-once *within* Kafka-to-Kafka pipelines; across external systems you still need [consumer idempotency](../../08-api-design/idempotency/README.md).
- **Coordination:** modern Kafka uses **KRaft** ([Raft](../../05-distributed-systems/consensus-algorithms/raft/README.md)) internally, replacing the old [ZooKeeper](../zookeeper/README.md) dependency.

## 2. When Kafka vs RabbitMQ vs SQS

Kafka: high-throughput streams, replay, event sourcing, many independent consumers, ordering by key. RabbitMQ: task queues, complex routing, per-message ack/requeue/priority/TTL, lower ops effort at modest scale. SQS: none of the ops, good-enough queueing. Full comparison in [Message Queues](../../02-building-blocks/message-queues/README.md) and [RabbitMQ](../rabbitmq/README.md).

## 3. Installation

```bash
# Docker (KRaft mode, single node — fine for learning)
docker run -d --name kafka -p 9092:9092 apache/kafka:latest

# Create a topic with 3 partitions
docker exec -it kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --create --topic orders --partitions 3 --replication-factor 1

# Produce / consume from the console
docker exec -it kafka /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 --topic orders --property parse.key=true --property key.separator=:
docker exec -it kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic orders --group g1 --from-beginning

# Inspect consumer-group lag (the #1 production metric)
docker exec -it kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --describe --group g1
```

Real client (Maven): `org.apache.kafka:kafka-clients` — producer with `acks=all`, `enable.idempotence=true`; consumer with manual `commitSync()` after processing.

## 4. The from-scratch implementation

[`MiniKafka.java`](MiniKafka.java) implements the log itself: **topics with partitions as append-only lists, key-hash partition routing, per-group committed offsets, consumer-group assignment, and replay from offset 0** — plus a demo showing at-least-once redelivery when a consumer crashes before committing. After writing offsets by hand, consumer groups stop being magic.

## 5. Interview soundbites

- "Kafka is a log, not a queue — consumption doesn't delete, offsets do the bookkeeping, so replay is free and consumers scale by partition."
- "Ordering is per-partition; I get per-user ordering by keying on user_id, and I size partition count for target consumer parallelism."
- "Durability is a dial: acks=all with min.insync.replicas=2 survives broker loss; acks=1 trades that for latency."
- "At-least-once plus idempotent consumers is the practical 'exactly-once' across system boundaries."

**Related:** [Message Queues](../../02-building-blocks/message-queues/README.md) · [Event-Driven Architecture](../../05-distributed-systems/event-driven-architecture/README.md) · [CQRS/Event Sourcing](../../05-distributed-systems/cqrs-event-sourcing/README.md) · [RabbitMQ](../rabbitmq/README.md)
