# Caching

> A cache trades a small amount of storage (usually memory) and a staleness risk for a large reduction in latency and load on the system of record. Caching is arguably the single highest-leverage building block in system design — it's often the difference between a system that needs a 10-node database cluster and one that needs 2 nodes.

---

## 1. Cache Placement — Where Caches Live in a Request Path

```
Client → Browser cache → CDN (edge cache) → API Gateway cache
       → Application-level cache (in-process, e.g. Caffeine)
       → Distributed cache (Redis/Memcached)
       → Database's own internal buffer pool/query cache
       → Disk
```

Each layer trades scope for speed: browser cache is instant but only helps that one user; CDN/edge helps everyone geographically near that edge node; a distributed cache (Redis) helps every app server; the database's internal cache is the last line of defense before hitting disk.

---

## 2. Redis vs. Memcached — the Concrete Product Choice

"We'll add a distributed cache" begs the question of which one, and the two dominant options differ enough that the choice is a real design decision, not a coin flip:

| Dimension | Redis | Memcached |
|---|---|---|
| **Data structures** | Rich: strings, hashes, lists, sets, sorted sets, streams, HyperLogLog, bitmaps | Strings (blobs) only — the client serializes/deserializes everything |
| **Persistence** | Optional disk persistence (RDB snapshots, AOF log) — can survive a restart | Purely in-memory — a restart loses everything, by design |
| **Threading model** | Historically single-threaded for command execution (simplifies atomicity of operations — no lock needed for a single `INCR`), with I/O threading added in later versions for network handling | Multi-threaded — can use multiple CPU cores natively for a pure key-value workload |
| **Replication / clustering** | Built-in primary-replica replication and **Redis Cluster** for sharded, highly-available deployments | No native replication or clustering — relies on client-side sharding (consistent hashing across a static server list) |
| **Pub/Sub** | Built-in — usable for real-time messaging, cache-invalidation broadcast (see below), or lightweight event distribution | Not supported |
| **Atomic operations** | Rich atomic primitives (`INCR`, sorted-set operations, Lua scripting for multi-step atomic transactions) | Basic atomic increment/decrement only |
| **Memory efficiency (pure key-value blobs)** | Slightly higher per-key overhead due to richer internal data structures | Generally more memory-efficient for the narrow case of simple key-value blobs, due to a simpler internal design |
| **Best fit** | Anything needing more than a flat key-value cache — rate limiting counters ([Rate Limiting](../rate-limiting/README.md)), leaderboards (sorted sets), session stores, pub/sub-based invalidation, or where persistence/HA matters | A pure, maximally simple, multi-core-parallel key-value cache with no persistence needs — historically the choice when raw throughput-per-dollar for simple blobs was the only concern |

**The practical answer today:** Redis has become the default for most new systems precisely because its extra capabilities (data structures, persistence, clustering, pub/sub) cover Memcached's use case as a strict subset while adding options that are frequently needed later — session storage, counters, leaderboards — without introducing a second technology. Memcached still shows up in legacy systems and in the specific case of an extremely simple, pure-blob cache where multi-core throughput per node matters more than any of Redis's extra features.

### Multi-tier caching and the invalidation problem it creates

A common high-performance pattern layers an **in-process cache** (Caffeine, a plain `HashMap` with eviction — nanosecond access, no network hop) *in front of* a **distributed cache** (Redis) *in front of* the database — the local cache absorbs the hottest fraction of keys, and Redis absorbs the rest. The genuine complication: with N app server instances each running their own local in-process cache, invalidating a key on write means invalidating it **on every instance**, not just the one that handled the write. The standard fix is a **pub/sub invalidation broadcast** — the writer publishes an "invalidate key X" message (via Redis pub/sub, or a lightweight message bus) that every instance subscribes to and reacts to by evicting the key from its own local cache — a good concrete example of trading a small amount of new complexity (a pub/sub subscriber in every instance) for the latency win of a local cache tier.

---

## 3. Caching Strategies (the classic interview table, explained precisely)

### Cache-Aside (Lazy Loading) — the most common pattern
Application code checks the cache first; on a miss, it reads from the database and populates the cache.

```
Read:  App → Cache (miss) → DB → App writes result back to Cache → return to caller
Write: App → DB directly (cache is NOT updated on write; either invalidated or left to expire)
```
- **Pros:** Cache only holds data that's actually been requested (efficient memory use); cache failure doesn't break writes.
- **Cons:** First request after a miss/eviction always pays full DB latency ("cold" cache); risk of stale data between a DB write and the next read triggering a refresh (usually mitigated by explicit invalidation on write).

### Write-Through
Every write goes to the cache **and** the database synchronously, as a single logical operation, before acknowledging the write.
- **Pros:** Cache is never stale — it's always in sync with the DB.
- **Cons:** Every write pays the latency of both cache and DB writes; cache fills with data that may never be read (less memory-efficient than cache-aside).

### Write-Behind (Write-Back)
Writes go to the cache immediately (fast ack to the caller) and are asynchronously flushed to the database later, often batched.
- **Pros:** Very low write latency; can batch/deduplicate writes for high throughput (see [Latency vs Throughput](../../01-foundations/latency-vs-throughput/README.md)).
- **Cons:** Real risk of data loss if the cache node crashes before the flush happens — this pattern is only acceptable when losing the last few seconds of writes is tolerable (e.g., view counts, not financial transactions).

### Read-Through
Similar to cache-aside, but the cache library/service itself is responsible for loading from the DB on a miss (transparent to the application code), rather than the application explicitly handling the miss.
- **Pros:** Simpler application code — cache-miss logic lives in one place (the cache layer), not scattered through every caller.
- **Cons:** Requires a cache implementation that supports pluggable loaders (e.g., Caffeine's `LoadingCache`, or a dedicated caching proxy).

**Senior-level nuance:** the choice isn't "which pattern is best" — it's "which pattern matches this data's write-vs-read ratio and tolerance for staleness/loss." Read-heavy, rarely-changing data (product catalog) → cache-aside. Data that must never appear stale even for a moment (account balance shown right after an update) → write-through, or skip caching that specific value entirely. High-frequency, loss-tolerant counters (page view counts) → write-behind.

---

## 4. Eviction Policies — What Happens When the Cache Is Full

| Policy | Rule | Good for |
|---|---|---|
| **LRU (Least Recently Used)** | Evict the item not accessed for the longest time | General-purpose, most common default |
| **LFU (Least Frequently Used)** | Evict the item accessed the fewest total times | Workloads with a stable "hot set" that shouldn't be evicted by a temporary burst of unrelated traffic |
| **FIFO** | Evict the oldest inserted item, regardless of access pattern | Simple, predictable, rarely optimal for real workloads |
| **TTL-based expiry** | Evict/invalidate after a fixed time regardless of access | Data with a known natural staleness bound (e.g., a stock quote good for 5 seconds) |

See also [LRU Cache](../../04-low-level-design/lru-cache/README.md) for the actual data-structure implementation (hash map + doubly linked list) behind LRU eviction — a very common LLD/coding interview question in its own right.

---

## 5. Cache Invalidation — "The Two Hard Problems in Computer Science"

The famous joke ("there are only two hard things in computer science: cache invalidation and naming things") exists because invalidation genuinely is subtle:

- **TTL expiry:** simplest approach — just let stale data expire naturally. Fine when a bounded staleness window is acceptable.
- **Explicit invalidation on write:** the write path actively deletes or updates the corresponding cache key. More precise, but requires the write path to know about every cache that might hold that data — a source of bugs in complex systems with multiple caching layers.
- **Cache stampede / thundering herd problem:** when a popular key expires, many concurrent requests simultaneously miss the cache and hammer the database at once. Mitigations: request coalescing (only one request per key is allowed to go to the DB; others wait for its result), staggered/jittered TTLs (avoid many keys expiring at the exact same moment), and probabilistic early expiration (refresh slightly before actual expiry, proportionally to how expensive the recompute is).

---

## 6. Real-World Example: Twitter's Timeline Caching with Memcached (and the "Cache-Aside at Massive Scale" Pattern)

Twitter's home timeline generation is a canonical caching case study. Generating a user's timeline (a fan-in of tweets from everyone they follow, ranked) from scratch on every page load would be prohibitively expensive at Twitter's request volume.

Twitter's publicly described architecture uses **fan-out-on-write**: when a user tweets, the tweet ID is pushed into a **precomputed timeline cache** (historically backed by a heavily sharded Memcached/Redis layer) for each of their followers, so that reading a timeline becomes a **cheap cache read** of an already-materialized list, rather than an expensive real-time aggregation query across potentially thousands of followed accounts.

- This is a cache-aside-like pattern applied at write time rather than read time — sometimes called "fan-out-on-write" caching — chosen because **reads (timeline views) vastly outnumber writes (tweets)** for the overwhelming majority of users, so precomputing on the rarer write and serving cheaply on the frequent read is the right latency/throughput trade (see [Latency vs Throughput](../../01-foundations/latency-vs-throughput/README.md)).
- For accounts with extremely large follower counts (celebrities), Twitter's architecture famously special-cases this into a **hybrid fan-out-on-read** approach instead, because fanning out a single tweet to tens of millions of follower timelines synchronously would be its own scalability disaster — a great example of "the general pattern breaks at the tail, and the tail needs a different design."

**The lesson:** caching strategy should be chosen based on the actual read/write ratio of the specific data, and extreme outliers (celebrity accounts, viral content) often need a genuinely different strategy than the 99% case, not just "more cache."

---

## 7. Spring Boot Example: Cache-Aside with Redis, Including Stampede Protection

```java
// build.gradle: spring-boot-starter-data-redis, spring-boot-starter-cache

@Service
@RequiredArgsConstructor
public class ProductCatalogService {

    private final RedisTemplate<String, Product> redisTemplate;
    private final ProductRepository productRepository; // backed by Postgres
    private final RedissonClient redissonClient;        // for distributed locks (stampede protection)

    private static final Duration TTL = Duration.ofMinutes(10);

    public Product getProduct(String sku) {
        String cacheKey = "product:" + sku;
        Product cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            return cached; // cache hit -- fast path, no DB touched
        }

        // Cache miss: use a distributed lock so that if 1000 concurrent requests
        // miss on the same hot key at once, only ONE of them queries the DB
        // (stampede/thundering-herd protection) -- the rest wait briefly and re-check cache.
        RLock lock = redissonClient.getLock("lock:" + cacheKey);
        try {
            if (lock.tryLock(200, 2000, TimeUnit.MILLISECONDS)) {
                try {
                    // Double-check: another thread may have already populated the cache
                    // while we were waiting for the lock.
                    cached = redisTemplate.opsForValue().get(cacheKey);
                    if (cached != null) return cached;

                    Product fromDb = productRepository.findBySku(sku)
                        .orElseThrow(() -> new NotFoundException(sku));

                    // Add jitter to TTL to avoid many keys expiring in the same instant
                    // and re-triggering a stampede across many DIFFERENT keys simultaneously.
                    long jitterSeconds = ThreadLocalRandom.current().nextLong(0, 60);
                    redisTemplate.opsForValue().set(cacheKey, fromDb, TTL.plusSeconds(jitterSeconds));
                    return fromDb;
                } finally {
                    lock.unlock();
                }
            } else {
                // Couldn't get the lock in time -- fall back to a direct DB read
                // rather than blocking the caller indefinitely.
                return productRepository.findBySku(sku).orElseThrow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    // Explicit invalidation on write -- this is the cache-aside write path.
    @Transactional
    public void updatePrice(String sku, BigDecimal newPrice) {
        productRepository.updatePrice(sku, newPrice);
        redisTemplate.delete("product:" + sku); // invalidate; next read repopulates from DB
    }
}
```

```java
// Declarative alternative using Spring's @Cacheable for the simple (non-stampede-critical) case
@Service
public class SimpleCatalogService {

    @Cacheable(value = "products", key = "#sku")
    public Product getProduct(String sku) {
        return productRepository.findBySku(sku).orElseThrow(); // only runs on cache miss
    }

    @CacheEvict(value = "products", key = "#sku")
    public void updatePrice(String sku, BigDecimal newPrice) {
        productRepository.updatePrice(sku, newPrice); // eviction on write = cache-aside invalidation
    }
}
```

```yaml
# application.yml
spring:
  cache:
    type: redis
    redis:
      time-to-live: 600000   # 10 min TTL, matches the manual example above
  redis:
    host: redis-cluster.internal
    port: 6379
```

**Why this matters at senior level:** the naive `@Cacheable` annotation is a fine answer for a mid-level question, but a staff-level interview expects you to proactively raise the **thundering herd problem** and demonstrate a concrete mitigation (locking, jitter, or a stale-while-revalidate approach) without being prompted.

---

## 8. Common Pitfalls

- Caching everything by default — increases memory cost and invalidation complexity for data that's rarely re-read, and can push out genuinely hot data (see eviction policy mismatch).
- No TTL at all — "we'll invalidate manually on every write" is a common source of production bugs, since it's easy to miss a code path that mutates data without invalidating the corresponding cache entry. A TTL as a safety net, even a long one, is cheap insurance.
- Not accounting for cache stampede on hot keys — leads to periodic DB latency spikes exactly correlated with popular-item TTL expiry, a classic production incident pattern.
- Caching at only one layer when the access pattern would benefit from multiple (e.g., no CDN for static assets that never change, forcing every request through the app tier).

---

## 9. 60-Second Interview Answer

> "Caching trades memory and a staleness risk for reduced latency and database load. I'd pick the strategy based on read/write ratio and staleness tolerance: cache-aside for read-heavy, rarely-changing data with explicit invalidation on write; write-through when the cache must never be stale; write-behind only when losing the last few seconds of data on a crash is acceptable, in exchange for very low write latency. For eviction I'd default to LRU, and for invalidation I'd combine TTLs as a safety net with explicit invalidation on write. I'd also proactively guard against cache stampede on hot keys — using request coalescing or a short-lived lock so a single expiring popular key doesn't send a flood of concurrent requests to the database at once."

**Related:** [Latency vs Throughput](../../01-foundations/latency-vs-throughput/README.md) · [CDN](../cdn/README.md) · [Database Replication](../databases/replication/README.md) · [LRU Cache (LLD)](../../04-low-level-design/lru-cache/README.md)
