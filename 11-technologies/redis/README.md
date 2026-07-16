# Redis — In-Memory Data Structures as a Service

> **Mental model:** Redis is not "a cache" — it's a **single-threaded, in-memory data-structure server** (strings, hashes, lists, sets, sorted sets, streams, HyperLogLog, geo) with optional persistence. Caching is its most common *use*, but sorted-set leaderboards, distributed locks, rate-limit counters, pub/sub, and session stores are equally idiomatic. Interviewers probe three things: the single-threaded model, eviction/expiry mechanics, and persistence/replication trade-offs.

---

## 1. Architecture facts that matter in interviews

- **Single-threaded command execution** (I/O threads exist since v6, but commands execute on one thread). Consequences: every command is **atomic** (INCR is a safe distributed counter with zero locking); throughput is ~100k+ ops/sec/node because everything is RAM + no lock contention; and **one slow command blocks everything** — `KEYS *` on a big keyspace is a production outage (use `SCAN`), and O(n) operations on huge collections need care.
- **Expiry:** TTL per key (`SET k v EX 60`). Expired keys are removed **lazily** (on access) + **actively** (periodic sampling) — so memory isn't reclaimed the instant a key expires.
- **Eviction (when `maxmemory` is hit):** `noeviction` (errors on writes — default!), `allkeys-lru`, `volatile-lru` (LRU among TTL'd keys only), `allkeys-lfu`, `volatile-ttl`, random variants. **Redis LRU is approximate** — it samples N keys (default 5) and evicts the best candidate, trading exactness for zero bookkeeping overhead. For caching, `allkeys-lfu` or `allkeys-lru` is what you want; running a cache with `noeviction` is a classic misconfiguration.
- **Persistence:** **RDB** (point-in-time snapshots via fork — compact, fast restart, loses everything since last snapshot) vs **AOF** (append-only command log, `fsync` configurable: `everysec` = at most 1s of loss — bigger, slower restart, safer). Production caches often disable both; Redis-as-datastore uses AOF `everysec` + periodic RDB. Know that **fork-based RDB on a big instance causes latency spikes** (copy-on-write memory pressure).
- ```mermaid
graph TD
    Client["Client"]
    Client -->|"GET user:42:name<br/>hash slot = CRC16(key) % 16384"| Primary1

    subgraph Cluster["Redis Cluster — 16384 hash slots"]
        Primary1["Primary A<br/>slots 0-5460"]
        Primary2["Primary B<br/>slots 5461-10922"]
        Primary3["Primary C<br/>slots 10923-16383"]
        Replica1["Replica of A"]
        Replica2["Replica of B"]
        Replica3["Replica of C"]
        Primary1 -.->|async replication| Replica1
        Primary2 -.->|async replication| Replica2
        Primary3 -.->|async replication| Replica3
    end

    Primary1 -->|"wrong slot? MOVED redirect"| Client

    style Primary1 fill:#a8d5ff,stroke:#333
    style Primary2 fill:#a8d5ff,stroke:#333
    style Primary3 fill:#a8d5ff,stroke:#333
```

**Take this as the reference for why hash tags matter:** `{user:1}:profile` and `{user:1}:settings` both hash on just the `user:1` portion inside the braces, landing on the **same slot** — this is what makes a multi-key operation across those two keys legal; without the shared hash tag, Redis Cluster has no guarantee both keys live on the same primary and will refuse the multi-key operation.

**Replication & HA:** async primary→replica replication (so failover can lose acknowledged writes — [replication lag](../../02-building-blocks/databases/replication/README.md)); **Sentinel** for monitoring + automatic failover; **Redis Cluster** for sharding — 16384 **hash slots** divided among primaries, client-side routing via `MOVED` redirects, multi-key ops only when keys share a slot (hash tags `{user:1}:a`, `{user:1}:b`). This is [consistent-hashing-style sharding](../../02-building-blocks/databases/sharding/README.md) made concrete.
- **Distributed locks:** `SET lock owner NX PX 30000` — atomic acquire-with-TTL; release must be check-owner-then-delete in a Lua script (atomicity again). Know the caveats (clock/pause hazards; Redlock debate) — for correctness-critical locks, use [CP systems](../zookeeper/README.md).

## 2. The patterns you'll actually cite

- **Cache-aside** with TTL + jitter (stampede protection); see [Caching](../../02-building-blocks/caching/README.md).
- **Rate limiting:** `INCR` + `EXPIRE` per window, or token bucket in Lua ([Rate Limiting](../../02-building-blocks/rate-limiting/README.md)).
- **Leaderboards:** sorted sets — `ZADD scores 4200 user1`, `ZREVRANGE scores 0 9 WITHSCORES` = top-10 in O(log n).
- **Session store / connection registry:** hash per session; the [WebSocket routing registry](../../08-api-design/websockets/README.md).
- **Pub/sub vs Streams:** pub/sub is fire-and-forget (disconnected subscriber misses messages); **Streams** (`XADD`/`XREADGROUP`) add persistence + consumer groups — Kafka-lite for modest scale.

## 3. Installation (Linux/macOS/Docker)

```bash
# Docker (fastest)
docker run -d --name redis -p 6379:6379 redis:7-alpine
docker exec -it redis redis-cli PING        # → PONG

# Ubuntu/Debian
sudo apt update && sudo apt install redis-server
sudo systemctl enable --now redis-server

# macOS
brew install redis && brew services start redis

# Try it
redis-cli
> SET user:1:name "shilpak" EX 3600
> GET user:1:name
> ZADD leaderboard 100 alice 200 bob
> ZREVRANGE leaderboard 0 -1 WITHSCORES
> INFO memory        # watch used_memory, maxmemory_policy
```

Production config essentials (`/etc/redis/redis.conf`): `maxmemory 2gb`, `maxmemory-policy allkeys-lfu`, `appendonly yes` + `appendfsync everysec` (if datastore), bind + `requirepass`/ACLs (never expose 6379 publicly).

## 4. The from-scratch implementation

[`RedisLikeCache.java`](RedisLikeCache.java) builds the core of Redis-as-cache in ~200 lines of plain Java: a thread-safe store with **per-key TTL (lazy + active expiry)**, **LRU eviction via sampling** (the actual Redis approach), atomic `INCR`, and a tiny **pub/sub**. Reading it teaches you exactly why `GET` is O(1), what "approximate LRU" means, and where the single-threaded atomicity comes from.

## 5. Interview soundbites

- "Redis is single-threaded, which is why INCR is atomic without locks and why `KEYS *` is an outage."
- "Its LRU is approximate — it samples 5 keys and evicts the best candidate — a deliberate memory-overhead trade."
- "Replication is async, so a failover can lose acked writes — fine for a cache, a design decision for a store."
- "Cluster mode shards 16384 hash slots across primaries; multi-key ops need hash tags to co-locate keys."

**Related:** [Caching](../../02-building-blocks/caching/README.md) · [Rate Limiting](../../02-building-blocks/rate-limiting/README.md) · [Sharding](../../02-building-blocks/databases/sharding/README.md) · [ZooKeeper](../zookeeper/README.md)
