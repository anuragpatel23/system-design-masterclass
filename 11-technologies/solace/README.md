# Solace PubSub+ — The Enterprise Event Broker

> **Mental model:** Solace PubSub+ is an **enterprise event broker** (available as a hardware appliance, software broker, or cloud service) that unifies pub/sub, queueing, request/reply, and streaming under one broker with **hierarchical topic routing** — heavily used in **financial services** (market data, trading, payments) where low, predictable latency, WAN-spanning **event meshes**, and multi-protocol support (AMQP, MQTT, JMS, REST, WebSocket) matter more than open-source ubiquity. If you interview at a bank or exchange, Solace fluency is a differentiator.

---

## 1. Concepts that differ from Kafka/RabbitMQ

- **Topics are addresses, not resources.** A Solace topic is just a string on a message (`orders/eu/paid/premium`) — nothing is "created." Subscribers use wildcards: `*` (one level), `>` (everything after). Routing granularity therefore scales to millions of distinct topics with zero admin — contrast Kafka, where a topic is provisioned storage.
- **Endpoints receive; topics route.** A **queue** (durable endpoint) can *subscribe to topics*: publishers publish to topics; the queue attracts matching messages and holds them for its consumer with acks/redelivery — RabbitMQ-style guarantees with Kafka-style topic decoupling. Non-durable **direct messaging** skips persistence entirely for microsecond-class fan-out (market data), while **guaranteed messaging** (persistent, acked, once-and-only-once QoS within the broker) covers transactional flows — *per-message QoS choice on one broker* is the headline.
- **Event mesh:** brokers interconnect across regions/clouds and **route subscriptions dynamically** — a subscriber in Singapore automatically pulls matching events published in Frankfurt, no per-topic replication config ([geo-distribution](../../01-foundations/availability-reliability/README.md) as broker infrastructure).
- **Ordering & delivery:** FIFO per topic per publisher; exclusive queues give strict ordering, non-exclusive give competing-consumer scaling — same [trade](../rabbitmq/README.md) as everywhere. Redelivery + DLQ (`#DEAD_MSG_QUEUE`) built in; [consumer idempotency](../../08-api-design/idempotency/README.md) still applies across system boundaries.
- **Vs Kafka in one sentence:** Kafka = retained log optimized for replay/streams; Solace = routing-first broker optimized for many fine-grained topics, mixed QoS, multi-protocol, and WAN meshes; replay exists (Replay Log) but is a feature, not the core abstraction.

## 2. Installation

```bash
# Free standard edition, Docker:
docker run -d --name solace -p 8080:8080 -p 55555:55555 -p 5672:5672 -p 1883:1883 \
  --shm-size=1g --env username_admin_globalaccesslevel=admin \
  --env username_admin_password=admin solace/solace-pubsub-standard
# Admin UI: http://localhost:8080 (admin/admin) — create a queue, add a topic
# subscription "orders/>" to it, publish from the "Try Me!" tab, watch it land.
```

Java client (Maven): `com.solacesystems:sol-jcsmp` (JCSMP) or the newer `com.solace:solace-messaging-client`. Core JCSMP flow: `JCSMPSession` → `XMLMessageProducer.send(msg, topic)` → consumer binds a `FlowReceiver` to a queue, `ackMessage()` after processing.

## 3. The from-scratch implementation

[`MiniSolace.java`](MiniSolace.java) implements the distinctive Solace ideas: **hierarchical topic matching with `*` and `>` wildcards, queues-subscribing-to-topics (topic-to-endpoint attraction), per-message QoS (DIRECT fan-out vs GUARANTEED with ack/redelivery)** — the demo shows one publish fanning out to a direct subscriber (no persistence) while simultaneously being attracted into a durable queue whose consumer crashes and gets redelivery.

## 4. Interview soundbites

- "Solace topics are message addresses, not provisioned resources — wildcard hierarchies give millions of routing keys for free; queues *subscribe* to topics to add durability."
- "Per-message QoS on one broker: direct messaging for microsecond market-data fan-out, guaranteed messaging for the order flow."
- "The event mesh routes subscriptions across regions dynamically — geo-distribution without per-topic replication config."
- "I'd pick Kafka for replayable streams and ecosystem; Solace where fine-grained routing, mixed QoS, multi-protocol, and WAN meshes dominate — which is why it owns financial services."

**Related:** [Message Queues](../../02-building-blocks/message-queues/README.md) · [Kafka](../kafka/README.md) · [RabbitMQ](../rabbitmq/README.md) · [Event-Driven Architecture](../../05-distributed-systems/event-driven-architecture/README.md)
