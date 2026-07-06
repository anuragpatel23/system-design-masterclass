# Latency vs Throughput

> **Latency** = the time to complete a single unit of work (one request, one packet, one query).
> **Throughput** = the number of units of work completed per unit of time (requests/sec, transactions/sec, bytes/sec).

They are related but often **inversely traded off**, and a senior architect is expected to know exactly when optimizing one hurts the other — this is one of the most common "gotcha" follow-ups in staff-level interviews.

---

## 1. The Highway Analogy (use this in interviews — it lands well)

Think of a highway:
- **Latency** = how long it takes one car to drive from A to B.
- **Throughput** = how many cars pass a given point per hour.

You can increase throughput by adding more lanes (parallelism) without changing any single car's latency. But you can also increase throughput by packing cars closer together (batching), which *increases* each individual car's latency (it has to wait for the batch to fill) even though more cars/sec get through overall. This is precisely the batching trade-off engineers make with things like Kafka producers, HTTP/2 multiplexing, and database bulk writes.

---

## 2. Why They Are Not the Same Thing (Little's Law)

```
L = λ × W
```
Where `L` = average number of requests in the system, `λ` (lambda) = arrival rate (throughput), `W` = average time a request spends in the system (latency).

**The senior-level implication:** if throughput (`λ`) increases while your system's capacity to process concurrent requests (`L`) is fixed (e.g., a fixed-size thread pool or connection pool), latency (`W`) *must* increase to compensate — queueing is mathematically inevitable, not a bug. This is why "just add more requests per second" without also scaling concurrent processing capacity **always** degrades latency — it's not an implementation detail, it's queueing theory.

---

## 3. Averages Lie: You Must Reason in Percentiles

A senior candidate who says "average latency is 50ms" without qualifying it has said almost nothing useful. The correct framing:

| Percentile | What it tells you |
|---|---|
| **p50 (median)** | What a typical request experiences |
| **p90** | 1 in 10 requests are at least this slow |
| **p99** | 1 in 100 requests are at least this slow — this is where real user pain concentrates at scale |
| **p99.9** | 1 in 1000 — at high request volume (e.g., 1M req/day), this is still ~1000 unhappy users/day |

**Why tail latency (p99) matters disproportionately:** at scale, a single user page load often fans out into dozens of backend calls (microservices, DB queries, cache lookups). If each individual call has a p99 of "only" 1%, the **probability that at least one of 20 parallel calls hits its p99** is `1 - (0.99)^20 ≈ 18%` — meaning nearly 1 in 5 *page loads* experience tail latency, even though each individual service looks fine on its own p99. This compounding effect is exactly why companies like Amazon and Google obsess over p99/p99.9 rather than averages — Amazon has publicly noted that every 100ms of added latency measurably costs revenue, and this pain concentrates in the tail, not the median.

---

## 4. The Core Trade-off Techniques

| Technique | Optimizes | At the cost of |
|---|---|---|
| **Batching** (bulk DB writes, Kafka producer batching, gRPC streaming) | Throughput | Latency (waiting to fill a batch) |
| **Parallelism / horizontal scaling** | Throughput | Nothing directly, but adds coordination overhead at high node counts |
| **Caching** | Latency (cache hits) | Slight throughput cost on cache misses/writes, staleness risk |
| **Connection pooling** | Both — avoids per-request connection setup latency AND lets more requests be handled per second | Pool exhaustion becomes a new bottleneck under bursty load |
| **Async / non-blocking I/O** | Throughput (more concurrent requests per thread) | Can slightly increase per-request latency due to event-loop scheduling overhead |
| **Request coalescing / deduplication** | Both, for redundant requests (e.g., cache stampede protection) | Added complexity, slight latency for the "leader" request |

---

## 5. Real-World Example: Netflix's Adaptive Bitrate Streaming — Choosing Throughput Over Instant Latency

When you press play on Netflix, the player doesn't fetch the whole movie instantly (that would be optimizing for a kind of throughput at massive upfront latency cost) nor does it request one frame at a time (minimizing per-request latency but murdering throughput and overwhelming the CDN with tiny requests).

Instead, Netflix's adaptive bitrate (ABR) approach fetches video in **small chunks (a few seconds each)**, buffering a small window ahead:
- This deliberately accepts a few hundred milliseconds to a couple of seconds of **initial latency** (buffering before playback starts) in exchange for sustained smooth **throughput** (a continuous stream of chunks arriving faster than they're consumed, in steady state).
- The player continuously measures actual achieved throughput from the CDN and **adapts the requested bitrate** (video quality) to match — trading video quality (a proxy for "throughput per chunk") for a stable end-to-end latency experience (no rebuffering), rather than holding quality fixed and letting playback stutter (latency spikes) when the network throughput dips.

**The lesson:** the "right" trade-off between latency and throughput isn't fixed — it should be **adaptive and measured in real time**, not chosen once at design time and left static, especially for systems (like a global CDN-backed video player) operating over wildly variable network conditions.

---

## 6. Spring Boot Example: Batching Writes for Throughput vs Per-Request Writes for Latency

**Low-latency, low-throughput version** — every event is written individually, giving the caller near-instant confirmation but capping total throughput at whatever the database's per-write latency allows:

```java
@Service
@RequiredArgsConstructor
public class EventServiceSynchronous {

    private final EventRepository eventRepository;

    // Each call round-trips to the DB individually.
    // Great single-request latency (~2-5ms), but throughput caps out
    // around (1000ms / per-write-latency) writes/sec per connection.
    public void recordEvent(AnalyticsEvent event) {
        eventRepository.save(event);
    }
}
```

**High-throughput version** — batch events in memory and flush periodically, trading a small amount of added latency (events are visible up to `flush-interval` later) for dramatically higher sustained throughput via fewer, larger database round trips:

```java
@Service
@RequiredArgsConstructor
public class EventServiceBatched {

    private final EventRepository eventRepository;
    private final BlockingQueue<AnalyticsEvent> buffer = new LinkedBlockingQueue<>(100_000);

    // Producer side: enqueue is near-instant (in-memory), doesn't block on the DB at all.
    public void recordEvent(AnalyticsEvent event) {
        if (!buffer.offer(event)) {
            log.warn("event buffer full, dropping event -- backpressure signal");
        }
    }

    // Consumer side: flush in batches on a fixed schedule.
    // Trades: events are only durably persisted up to 500ms after ingestion (added latency),
    // in exchange for turning N individual round trips into N/batchSize round trips (throughput).
    @Scheduled(fixedDelay = 500)
    public void flush() {
        List<AnalyticsEvent> batch = new ArrayList<>(1000);
        buffer.drainTo(batch, 1000);
        if (!batch.isEmpty()) {
            eventRepository.saveAll(batch); // single bulk INSERT, not 1000 round trips
        }
    }
}
```

```yaml
# application.yml -- tuning the batch/connection-pool knobs is literally tuning
# the latency-vs-throughput trade-off for this service
spring:
  datasource:
    hikari:
      maximum-pool-size: 30        # more connections = more concurrent throughput,
                                    # up to the DB's own connection/lock capacity
  jpa:
    properties:
      hibernate:
        jdbc:
          batch_size: 1000          # larger batch = higher throughput, higher per-item latency
```

**Why this matters at senior level:** a candidate who reflexively says "use async processing and batching everywhere" without acknowledging the added latency cost, or one who insists on synchronous per-request writes "for correctness" without acknowledging the throughput ceiling, both miss the point. The correct answer names the trade-off explicitly and ties the choice to the actual SLA (does this specific data need to be queryable within milliseconds, or is a few-hundred-ms delay acceptable?).

---

## 7. Common Pitfalls

- **Reporting average latency instead of percentiles.** Always ask/state "average or p99?" — they can tell completely different stories about the same system.
- **Assuming throughput scales linearly with more threads/connections forever.** Past a certain point (context-switching overhead, lock contention, downstream capacity), adding concurrency *decreases* both latency and throughput — this is why connection pools and thread pools have sizing limits, not infinite ones.
- **Optimizing latency in isolation from Little's Law.** Reducing per-request processing time is great, but if arrival rate exceeds processing capacity, queueing delay dominates regardless of how fast each individual request is once it starts being processed.
- **Ignoring tail-latency amplification in fan-out architectures.** Assuming "each service has good p99" is sufficient without accounting for the compounding probability across a call graph with many parallel dependencies.

---

## 8. 60-Second Interview Answer

> "Latency is the time for one request; throughput is requests per second — and they're often inversely related through batching: bigger batches raise throughput but raise each individual request's latency. Little's Law ties them together — if arrival rate exceeds your system's processing capacity, queueing delay grows regardless of per-request speed, so scaling throughput capacity is really about scaling concurrent processing capacity, not just making one request faster. I always reason in percentiles, especially p99, not averages, because in a fan-out architecture with many parallel backend calls, even a good per-service p99 compounds into a much higher probability that at least one call in the fan-out is slow, which is what the user actually experiences."

**Related:** [Scalability](../scalability/README.md) · [Caching](../../02-building-blocks/caching/README.md) · [Message Queues](../../02-building-blocks/message-queues/README.md) · [Rate Limiting](../../02-building-blocks/rate-limiting/README.md)
