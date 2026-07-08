# Message Polling — Pull-Based Consumption Done Right

> **Mental model:** brokers either **push** messages to consumers (RabbitMQ delivers to channels) or consumers **pull** (Kafka `poll()`, SQS `ReceiveMessage`, database-as-queue `SELECT ... FOR UPDATE SKIP LOCKED`). Pull looks primitive but is often the *more* robust choice: the consumer controls its own intake rate, which makes **backpressure automatic**. This note covers the polling patterns — short poll, long poll, visibility timeout, batch receive — that appear in every SQS-style design and in the [client-facing versions](../../08-api-design/websockets/README.md) of the same idea.

---

## 1. Push vs pull — the actual trade-off

- **Push:** lowest delivery latency; but the broker must guess each consumer's capacity — overrun consumers need prefetch caps ([RabbitMQ QoS](../rabbitmq/README.md)) to avoid being buried.
- **Pull:** the consumer asks when ready — **backpressure is structural** (a slow consumer simply polls less; nothing piles up in *its* memory); batching is natural (`poll(max=100)`); horizontal scaling is trivial (add pollers). Cost: a latency floor (poll interval) and wasted empty polls — which is exactly what long polling fixes.
- **Long polling a queue:** `ReceiveMessage(waitTimeSeconds=20)` — the server holds the request until a message arrives or timeout. SQS's `WaitTimeSeconds=20` cuts empty responses (and cost) dramatically vs short polling; same mechanic as [HTTP long polling](../../08-api-design/websockets/README.md), applied broker-side. **Default to long polling; short polling is almost never right.**

## 2. The SQS-style reliability loop (the pattern to know cold)

```
loop:
  msgs = receive(max=10, waitTime=20s, visibilityTimeout=30s)   # msgs become INVISIBLE, not deleted
  for m in msgs:
      process(m)                     # must be idempotent — see below
      delete(m.receiptHandle)        # ONLY explicit delete removes it
  # crash before delete? visibility timeout expires -> message REAPPEARS -> redelivered
```

- **Visibility timeout** is the heart: receiving hides the message for T seconds instead of deleting it. Finish + delete within T ⇒ done. Crash ⇒ automatic redelivery to any poller — at-least-once with zero coordination. Two classic bugs: **T shorter than processing time** (message reappears *while still being processed* → concurrent double-processing; fix: T > worst-case processing, or heartbeat-extend the timeout), and forgetting that redelivery ⇒ [idempotent processing](../../08-api-design/idempotency/README.md) is mandatory.
- **Receive count → DLQ:** each redelivery increments a counter; past `maxReceiveCount` the broker moves it to a [dead-letter queue](../../02-building-blocks/message-queues/README.md) — poison messages can't loop forever.
- **Empty-queue etiquette:** long poll + **exponential backoff with jitter** when idle ([the retry discipline](../../07-microservices/resilience-patterns/README.md) applied to polling); scale poller count on queue depth ([observability](../../10-security-observability/observability/README.md): queue depth is the saturation signal).
- **Database-as-queue** (the pragmatic baseline before you add a broker): a `jobs` table + `SELECT ... FOR UPDATE SKIP LOCKED LIMIT 10` — competing pollers claim disjoint rows atomically; visibility timeout ≈ a `locked_until` column. Perfectly respectable at modest scale; know its ceiling (polling load + table churn).

## 3. Installation / try it

No broker needed — the Java file below is self-contained. To try the real thing: **LocalStack** emulates SQS locally:

```bash
docker run -d -p 4566:4566 localstack/localstack
aws --endpoint-url=http://localhost:4566 sqs create-queue --queue-name jobs
aws --endpoint-url=http://localhost:4566 sqs send-message --queue-url .../jobs --message-body '{"job":1}'
aws --endpoint-url=http://localhost:4566 sqs receive-message --queue-url .../jobs --wait-time-seconds 10
```

Java SDK (Maven): `software.amazon.awssdk:sqs` — `ReceiveMessageRequest.builder().waitTimeSeconds(20).maxNumberOfMessages(10).visibilityTimeout(30)`.

## 4. The from-scratch implementation

[`PollingQueue.java`](PollingQueue.java) implements the whole loop: **an in-memory queue with visibility timeout, receipt handles, explicit delete, automatic redelivery on timeout, receive-count tracking with DLQ, long-poll receive (blocks up to N seconds), and a multi-threaded competing-poller demo** — including the visibility-timeout-too-short bug reproduced live so you can watch double-processing happen.

## 5. Interview soundbites

- "Pull gives structural backpressure — the consumer's poll rate *is* its admission control; push needs prefetch caps to fake the same."
- "SQS-style reliability is receive-hide-process-delete: visibility timeout turns crashes into redeliveries, which is at-least-once, which is why processing must be idempotent."
- "Visibility timeout must exceed worst-case processing time — or heartbeat-extend it — otherwise you double-process while the first attempt is still running."
- "Long polling, always; and receive-count → DLQ so poison messages can't spin forever."

**Related:** [Message Queues](../../02-building-blocks/message-queues/README.md) · [Idempotency](../../08-api-design/idempotency/README.md) · [WebSockets/Long Polling](../../08-api-design/websockets/README.md) · [AWS (SQS)](../aws/README.md)
