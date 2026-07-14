# Consistency Models

> Consistency is not binary. Between "every read sees the latest write, globally, instantly" and "reads might return anything, eventually catching up," there is a well-defined spectrum, and knowing exactly where a given system (and each *component* within it) sits on that spectrum is a core senior-architect skill.

---

## 1. The Spectrum, Strongest to Weakest

### Strict Consistency (theoretical ceiling)
Every read reflects the most recent write, with the write appearing to happen instantaneously across all replicas, respecting real, wall-clock time. **Not achievable in real distributed systems** due to the speed of light and network latency — it's a useful theoretical upper bound, not an engineering target.

### Linearizability (the practical "strong consistency")
Every operation appears to take effect atomically at some point between its invocation and its completion, and all nodes agree on a single global order of operations that is consistent with real time. This is what people usually mean when they say "strong consistency" in an interview. Achieved via consensus protocols (Raft, Paxos) or a single-leader architecture with synchronous replication.

### Sequential Consistency
All operations appear in *some* single global order that all nodes agree on, and each process's own operations appear in the order it issued them — but that global order does not have to match real, wall-clock time. Weaker than linearizability, still strong enough for many distributed algorithms.

### Causal Consistency
Operations that are causally related (e.g., a comment posted in reply to a specific post) are seen by everyone in the same order. Operations that are *not* causally related (two unrelated users posting at the same time) can be seen in different orders by different observers. This is the model that matches human intuition about conversations and is popular in collaborative and social systems.

**The mechanism: how "causally related" is actually tracked.** A **Lamport timestamp** is a simple logical counter each node increments on every event and attaches to outgoing messages, giving a partial order sufficient to say "A happened-before B" when there's a causal chain between them — but it can't distinguish true causality from coincidence for concurrent, unrelated events. A **vector clock** fixes this by giving each node its own counter *and* tracking every other node's counter it has observed (an array of counters, one per node) — comparing two vector clocks tells you definitively whether one event causally preceded another, or whether they were truly concurrent (neither vector dominates the other). This is the actual data structure the original Dynamo paper used to detect conflicting concurrent writes to the same key, handing genuinely concurrent conflicting versions back to the application (or a last-write-wins policy) to resolve — "causal consistency" isn't a vague promise, it's implemented with a specific, comparable data structure attached to every write.

### Read-Your-Writes Consistency
A specific, narrower, and extremely practical guarantee: a user is guaranteed to see their *own* writes in subsequent reads, even if other users might not see that write yet. This is the single most commonly needed consistency guarantee in consumer products (e.g., "I just changed my profile picture, why do I still see the old one?").

Read-your-writes is one of four related **session guarantees** (from Terry et al.'s foundational work on session consistency) that are frequently tested as a group:
- **Read-your-writes** — a session always sees its own prior writes (defined above).
- **Monotonic reads** — once a session has seen a value, it will never see an *older* value on a subsequent read, even from a different, lagging replica — without this, a user could refresh a page and see a comment "disappear" because their second request happened to land on a more-stale replica than their first.
- **Monotonic writes** — a session's writes are applied in the order it issued them, even if they land on different replicas — without this, a "delete account" write issued after an "update email" write could theoretically be applied out of order.
- **Writes-follow-reads** — a write that happens after a read within a session is guaranteed to be applied *after* the write that produced the value that was read — e.g., if you read a forum post and then reply to it, your reply is guaranteed to be causally ordered after the post you're replying to, everywhere.

**Why grouping them matters:** these four are typically implemented **together** as "session consistency," usually via **sticky routing** (pin a client's session to one replica/region, or track a per-session version/offset like the Spring example below) — naming all four, not just read-your-writes, signals a more complete grasp of what "the user's own experience feels consistent" actually requires.

### Eventual Consistency
Given no new writes, all replicas will *eventually* converge to the same value — but with no bound on how long "eventually" takes, and no guarantee about what order intermediate reads see. The weakest useful model; the default in most AP systems.

---

## 2. Why This Spectrum Exists (the trade-off it encodes)

Every step down this spectrum from Linearizable → Eventual buys you:
- **Lower write/read latency** (fewer synchronous cross-replica round trips required before acknowledging a write or serving a read)
- **Higher availability during partitions** (replicas don't need to coordinate before answering)
- **Higher throughput** (less coordination overhead)

...at the cost of correctness guarantees a client can rely on. The senior-level skill is not "always pick strong consistency to be safe" (this destroys latency and availability unnecessarily) nor "always pick eventual consistency for scale" (this causes real correctness bugs) — it's matching the model to what the *specific data* actually requires.

## 3. A Practical Decision Framework

| Question to ask about the data | If yes → lean toward |
|---|---|
| Does a wrong/stale value cause real-world harm (double-spend, overselling, security bypass)? | Linearizable / strong |
| Does the user need to see their own write immediately, but others' staleness is fine? | Read-your-writes |
| Does the data represent a conversation/thread where causal order matters to make sense? | Causal |
| Is staleness invisible or cosmetic (like counts, presence, recommendations)? | Eventual |

---

## 4. Real-World Example: Facebook/Meta's TAO and TAO-Read-After-Write

Meta's social graph datastore, **TAO**, serves the social graph (friends, likes, comments — trillions of reads a day) with a deliberately **eventually consistent, causally-aware** model layered on top of MySQL, because:
- Strong linearizable consistency across a globally distributed graph at that request volume would make every "like" button click a multi-datacenter round trip — unacceptable latency at Facebook's scale.
- But pure eventual consistency with no guarantees would mean a user posts a comment and, on refreshing their own screen a second later, doesn't see their own comment — an obviously broken user experience.

Their solution (as publicly described in Meta's engineering literature) leans on **read-your-writes** for the writer's own region (their write is applied and immediately readable from the region/replica that served it) combined with **eventual, causally-ordered propagation** to the rest of the world's read replicas — giving the illusion of strong consistency to the person who *matters most for that write* (the author) while keeping the system as a whole loosely coupled and horizontally scalable.

**The lesson:** you don't need global linearizability to solve the consistency problems that actually matter to users — read-your-writes for the actor, causal ordering for related events, and eventual consistency for everyone/everything else, gets you 95% of the perceived-correctness benefit at a fraction of the latency/availability cost.

---

## 5. Spring Boot Example: Implementing Read-Your-Writes on Top of an Eventually-Consistent Read Replica Setup

A common real production problem: you have a primary database for writes and read replicas for scale (see [Database Replication](../../02-building-blocks/databases/replication/README.md)), and replication lag means a user who just updated their profile might read a replica that hasn't caught up yet.

```java
// Naive version: ALWAYS reads from a replica -- breaks read-your-writes.
@Service
@RequiredArgsConstructor
public class ProfileServiceNaive {

    private final ProfileRepository replicaRepository; // routed to read-replica datasource

    public Profile getProfile(String userId) {
        return replicaRepository.findById(userId)
            .orElseThrow(); // might return STALE data right after this same user's own write
    }
}
```

```java
// Read-your-writes fix: track "this user just wrote, and when" and route accordingly.
// A common lightweight technique: after a write, remember the write's timestamp (or replica
// LSN/offset) in a short-lived cookie/cache entry, and force reads from the PRIMARY
// (or a replica confirmed caught-up past that offset) for a short window afterward.
@Service
@RequiredArgsConstructor
public class ProfileService {

    private final ProfileRepository primaryRepository;   // writes + "must be fresh" reads
    private final ProfileRepository replicaRepository;    // default, scalable reads
    private final RedisTemplate<String, Instant> recentWriteTracker;

    private static final Duration STICKY_WINDOW = Duration.ofSeconds(10);

    @Transactional
    public void updateProfile(String userId, ProfileUpdate update) {
        primaryRepository.updateProfile(userId, update);
        // Mark that THIS user just wrote, so their next read is routed to primary
        recentWriteTracker.opsForValue().set("write:" + userId, Instant.now(), STICKY_WINDOW);
    }

    public Profile getProfile(String userId) {
        boolean recentWriter = recentWriteTracker.hasKey("write:" + userId);
        // Route to primary only for the user who just wrote -- everyone else keeps
        // hitting cheap, horizontally-scaled replicas. This is the read-your-writes trade-off
        // applied surgically instead of forcing strong consistency for all reads.
        ProfileRepository repo = recentWriter ? primaryRepository : replicaRepository;
        return repo.findById(userId).orElseThrow();
    }
}
```

```yaml
# application.yml -- two datasources, one routed by AbstractRoutingDataSource in real setups
spring:
  datasource:
    primary:
      url: jdbc:postgresql://db-primary.internal:5432/app
    replica:
      url: jdbc:postgresql://db-replica.internal:5432/app
```

**Why this matters at senior level:** it demonstrates that consistency models aren't just theory — they translate directly into concrete routing decisions (which datasource serves which read), and the interviewer is looking for exactly this kind of "surgical strength, not blanket strength" application.

---

## 6. Common Pitfalls

- Treating "eventual consistency" as a single model — in practice, it's a spectrum, and vague hand-waving about "it'll converge eventually" without a bound or mechanism (anti-entropy, read-repair, gossip) is a red flag.
- Assuming strong consistency is "free" if you just use a single database instance — it isn't free, it's just that the coordination cost hasn't shown up yet because you haven't scaled out.
- Forgetting that consistency requirements can differ *per field*, not just per service — e.g., a user's email address needs strong consistency (security-relevant), but their "last active" timestamp does not.
- Not distinguishing consistency (CAP's C, about operation ordering/visibility) from ACID's Consistency (about invariants like foreign key constraints) — these are different concepts sharing a name.

---

## 7. 60-Second Interview Answer

> "Consistency isn't binary — there's a spectrum from linearizability, where every read sees the latest write in a real-time-consistent global order, down through causal consistency, read-your-writes, and finally eventual consistency, where replicas converge with no timing guarantee. Each step down that spectrum trades correctness guarantees for lower latency and higher availability. I wouldn't pick one model for an entire system — I'd match the model to the data: strong consistency for things like inventory or payments where staleness causes real harm, read-your-writes for user-facing state like their own profile or posts, and eventual consistency for things like recommendation counts or presence indicators where staleness is invisible."

**Related:** [CAP Theorem](../cap-theorem/README.md) · [Database Replication](../../02-building-blocks/databases/replication/README.md) · [Caching](../../02-building-blocks/caching/README.md)
