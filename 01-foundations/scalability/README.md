# Scalability

> **Definition (precise, interview-safe):** Scalability is a system's ability to handle a growing amount of load — more users, more requests, more data — by adding resources, **with a graceful (ideally sub-linear) increase in cost and a bounded, predictable impact on latency**.

A system that "handles more load by falling over less gracefully" is not scalable, even if it technically stays up. Scalability is not "can it survive" — it's "does it survive at a cost/latency curve you can plan around."

---

## 1. The Two Axes: Vertical vs Horizontal

### Vertical Scaling (Scale Up)
Add more power (CPU, RAM, IOPS) to an existing machine.

- **Pros:** No code changes, no distributed-systems complexity, strong consistency is trivial (single node).
- **Cons:** Hard ceiling (biggest instance AWS sells today: ~448 vCPUs / 24TB RAM on `u-24tb1.112xlarge`-class boxes), single point of failure, cost grows **super-linearly** near the ceiling (a 2x bigger box often costs more than 2x), downtime during resize.
- **When it's actually the right call:** Early-stage products, relational databases where sharding isn't yet justified, stateful legacy systems, anything where engineering time is scarcer than money.

### Horizontal Scaling (Scale Out)
Add more machines and distribute load across them.

- **Pros:** Near-linear cost scaling, no theoretical ceiling, fault tolerance (lose one node, others absorb load), can scale elastically (auto-scaling groups).
- **Cons:** Requires statelessness (or careful state partitioning), introduces network calls, consistency becomes a design decision (see [CAP Theorem](../cap-theorem/README.md)), operational complexity (service discovery, load balancing, distributed debugging).

**Senior-level nuance interviewers probe for:** "Everything horizontally scales" is a junior answer. The correct answer is: **stateless compute scales horizontally trivially; stateful components (databases, caches, in-memory sessions) require an explicit partitioning strategy** (sharding, consistent hashing, replication) before they can scale horizontally. This is why "just add more app servers" is easy, but "just add more database servers" is a multi-quarter project.

---

## 2. What Actually Limits Scalability

A senior architect names the *specific* bottleneck instead of saying "the database." Common bottleneck categories, in the order they typically bite:

1. **CPU-bound** — heavy computation per request (image processing, crypto, serialization). Fix: horizontal scale-out of stateless workers, or offload to specialized hardware/async workers.
2. **I/O-bound (disk)** — database reads/writes exceeding disk IOPS. Fix: caching, read replicas, SSD/NVMe, LSM-tree storage engines.
3. **I/O-bound (network)** — chatty microservices, N+1 query patterns, large payloads. Fix: batching, connection pooling, gRPC/binary protocols, response compression.
4. **Memory-bound** — large working sets that don't fit in RAM, causing swapping or cache thrashing. Fix: sharding data, more efficient data structures, eviction policies.
5. **Lock contention / single writer** — a single database row, a global lock, a singleton service instance. This is the most common "invisible" bottleneck at senior interviews — e.g., a single Postgres row being updated by every "like" on a viral post. Fix: sharding the hot key, CRDTs, write-behind buffering, optimistic concurrency.
6. **Coordination overhead (Amdahl's Law)** — the more nodes you add, the more time is spent coordinating rather than doing useful work. This is why some problems don't scale horizontally past a certain node count.

### Amdahl's Law (the formula interviewers expect you to at least gesture at)

```
Speedup(N) = 1 / ( (1 - P) + P/N )
```
Where `P` = the proportion of the workload that can be parallelized, and `N` = number of processors/nodes.

**Implication:** if 10% of your workload is inherently serial (e.g., a global sequence generator, a single-leader commit step), your maximum possible speedup is capped at **10x**, no matter how many nodes you throw at it. This is precisely why systems like Twitter's Snowflake ID generator or a single-leader database's write path become the ceiling on scalability — and why senior candidates are expected to identify the serial 10% before proposing "just add more nodes."

---

## 3. Scalability Patterns (the toolbox)

| Pattern | What it solves | Cost |
|---|---|---|
| **Load balancing** | Distributes stateless requests across N servers | Adds a hop, needs health checks |
| **Caching** | Reduces repeated expensive reads | Staleness risk, invalidation complexity |
| **Database read replicas** | Scales read throughput | Replication lag (eventual consistency for reads) |
| **Sharding** | Scales both read AND write throughput by partitioning data | Cross-shard queries/joins become hard |
| **Asynchronous processing (queues)** | Decouples slow work from the request path | Eventual consistency, needs idempotency |
| **CDN / edge caching** | Moves static content closer to users | Cache invalidation, cost |
| **Microservices decomposition** | Scales each bounded context independently | Network overhead, distributed transactions |
| **Auto-scaling** | Matches capacity to real-time demand | Cold-start latency, needs stateless design |

---

## 4. Real-World Example: Netflix's Horizontal Scaling of the Video Encoding Pipeline

Netflix re-encodes every title into dozens of resolution/codec/bitrate combinations (for adaptive bitrate streaming across device types). This workload is **embarrassingly parallel** — each chunk of a video can be encoded independently.

Netflix's approach (as described in their public engineering blog):
- They split each video into small chunks and encode chunks **in parallel across thousands of EC2 instances** rather than encoding a movie serially on one powerful machine.
- This converts a task that would take **hours on one machine** into **minutes across a fleet**, and the fleet is scaled elastically based on the size of the catalog being processed that day — pure horizontal, stateless, disposable-worker scaling.
- The "stitching" step at the end (reassembling encoded chunks in the correct order) is the small serial portion of the workload — the part Amdahl's Law says limits the maximum achievable speedup. Netflix's engineers explicitly optimized this reassembly step because it doesn't parallelize, unlike the encoding itself.

**The lesson a senior architect extracts:** identify the parallelizable 90% and horizontally scale it aggressively and cheaply (spot instances, disposable workers); identify the serial 10% and optimize it directly since more machines won't help it.

---

## 5. Spring Boot Example: From a Bottlenecked Monolith to a Horizontally Scalable Stateless Service

**The anti-pattern** — storing session state in server memory, which prevents horizontal scaling because a user's second request might hit a different node that doesn't have their session:

```java
// ANTI-PATTERN: in-memory session breaks horizontal scaling
@RestController
public class CartController {

    // Lives in THIS JVM's heap only — node B has no idea this exists
    private final Map<String, Cart> sessionCarts = new ConcurrentHashMap<>();

    @PostMapping("/cart/add")
    public ResponseEntity<Cart> addItem(@RequestParam String sessionId,
                                         @RequestBody Item item) {
        Cart cart = sessionCarts.computeIfAbsent(sessionId, k -> new Cart());
        cart.add(item);
        return ResponseEntity.ok(cart); // works on ONE instance, fails behind a load balancer with >1 instance
    }
}
```

**The fix** — externalize state to Redis so any of N stateless app instances can serve any request, making the service horizontally scalable behind a load balancer:

```java
// SCALABLE PATTERN: stateless app server, state lives in shared Redis
@RestController
@RequiredArgsConstructor
public class CartController {

    private final RedisTemplate<String, Cart> redisTemplate;
    private static final Duration CART_TTL = Duration.ofHours(2);

    @PostMapping("/cart/add")
    public ResponseEntity<Cart> addItem(@RequestHeader("X-Session-Id") String sessionId,
                                         @RequestBody Item item) {
        String key = "cart:" + sessionId;
        Cart cart = Optional.ofNullable(redisTemplate.opsForValue().get(key))
                             .orElseGet(Cart::new);
        cart.add(item);
        redisTemplate.opsForValue().set(key, cart, CART_TTL);
        return ResponseEntity.ok(cart); // any instance behind the LB can serve this now
    }
}
```

```yaml
# application.yml — the app itself declares no affinity to any node
spring:
  redis:
    host: redis-cluster.internal
    port: 6379
    lettuce:
      pool:
        max-active: 50   # tune pool size as instance count grows
```

```yaml
# docker-compose.yml (illustrating N identical, disposable instances behind a shared LB)
services:
  app1:
    image: cart-service:latest
    environment: [SPRING_REDIS_HOST=redis-cluster]
  app2:
    image: cart-service:latest
    environment: [SPRING_REDIS_HOST=redis-cluster]
  app3:
    image: cart-service:latest
    environment: [SPRING_REDIS_HOST=redis-cluster]
  redis-cluster:
    image: redis:7
  nginx-lb:
    image: nginx
    depends_on: [app1, app2, app3]
```

**Why this matters at senior level:** the code change is trivial (a few lines). The *architectural* insight — recognizing that in-memory state is what silently caps your horizontal scalability, and that "add more Kubernetes replicas" doesn't work until state is externalized — is what the interview is actually testing.

---

## 6. Common Pitfalls (senior interviewers listen for these)

- **Confusing scalability with performance.** A system can be fast for one user and still not scale (e.g., a single powerful DB server with no replication). Scalability is about the *shape of the cost curve as load grows*, not the absolute speed at t=0.
- **Ignoring the database while scaling app servers.** App-tier horizontal scaling is "easy mode." The real interview signal is what you do when the single Postgres primary becomes the bottleneck — read replicas first, then vertical scaling of the primary, then sharding as a last resort (sharding has real cost — cross-shard joins, rebalancing, hot shards).
- **Not distinguishing scale of reads vs scale of writes.** Caching and read replicas solve read scalability. They do **nothing** for write scalability. Write scalability needs sharding, queueing, or a different data model (e.g., append-only log, CRDTs).
- **Premature horizontal scaling / premature microservices.** Distributing a system before you understand its bottleneck adds network latency and operational cost for no benefit. "You are not Google" is a legitimate senior-level pushback in an interview.

---

## 7. 60-Second Interview Answer

> "Scalability means the system's cost and latency scale sub-linearly, or at worst linearly, as load grows — not just that it survives. I'd first separate stateless compute (trivial to scale horizontally with a load balancer) from stateful components like databases (need read replicas for read scale, sharding for write scale). I'd also identify the serial, non-parallelizable part of the workload — per Amdahl's Law, that part caps how much horizontal scaling can ever help, so I'd optimize it directly rather than throwing more nodes at it."

**Related:** [Load Balancers](../../02-building-blocks/load-balancers/README.md) · [Database Sharding](../../02-building-blocks/databases/sharding/README.md) · [Database Replication](../../02-building-blocks/databases/replication/README.md) · [Caching](../../02-building-blocks/caching/README.md)
