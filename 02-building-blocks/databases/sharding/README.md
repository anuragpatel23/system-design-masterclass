# Database Sharding

> Sharding (horizontal partitioning) splits a single logical dataset across multiple physical database instances, each holding a subset of the rows, so that **both read AND write throughput scale horizontally** — unlike replication, which only scales reads (see [Replication](../replication/README.md)). It is the hardest and most consequential database-scaling decision a senior architect makes, precisely because it's very difficult to undo.

---

## 1. Why Replication Alone Isn't Enough

Read replicas scale read throughput by copying the same data to N machines. But **every write must still eventually reach every replica**, meaning a single logical dataset's *write* throughput is still bounded by whatever a single primary (or single-leader consensus group) can handle. When write volume itself is the bottleneck — not just reads — the data must be **split**, not just **copied**. That's sharding.

---

## 2. Horizontal vs. Vertical Partitioning — and the Easier Middle Step: Functional Sharding

"Sharding" specifically means **horizontal partitioning** (splitting *rows* of the same logical table across machines, the subject of this whole file) — but it's worth precisely distinguishing from two related, less drastic techniques that are often reached for first:

- **Vertical partitioning** splits a table by *columns* rather than rows — e.g., moving a rarely-accessed, large `bio_text` column into a separate table/store from the frequently-queried `user_id, username, last_login` columns, so the hot columns stay compact and cache-friendly while the cold, large column doesn't bloat every scan of the hot table.
- **Functional sharding (a.k.a. federation)** splits *different tables/domains* onto different physical databases — a `users` database, a separate `orders` database, a separate `payments` database — **without** sharding any single table's rows yet. This is usually a natural, low-risk consequence of decomposing a monolith into [microservices](../../../07-microservices/monolith-vs-microservices/README.md) (each service owning its own database), and it's almost always the right *first* move before reaching for true horizontal sharding of any single, still-too-large table — it buys real scale-out with far less of the cross-shard-query/transaction complexity described in §4, because queries that used to join across domains were often already being discouraged by service boundaries.

**Why the distinction matters in an interview:** "how would you scale this database" has a natural escalation path — vertical partitioning and functional sharding (federation) first, since they're comparatively cheap and don't break single-row transactional guarantees or joins *within* a domain, and true horizontal sharding of a single enormous table only once a single logical table's row count or write volume specifically is the bottleneck that federation alone can't solve. Naming this order, rather than jumping straight to "we'd hash-shard the users table," is a stronger signal of having actually made this trade-off before.

---

## 3. Sharding (Partitioning) Strategies

### Range-Based Sharding
Rows are partitioned by a contiguous range of the shard key (e.g., user IDs 1–1,000,000 on shard A, 1,000,001–2,000,000 on shard B).
- **Pros:** Range queries (e.g., "all orders between two dates") stay within a single shard — efficient.
- **Cons:** Prone to **hot shards** — if the shard key correlates with time or sequential IDs (e.g., new signups), all new writes land on the most recent shard, leaving older shards idle.

### Hash-Based Sharding
The shard key is hashed, and the hash determines which shard a row lives on (e.g., `hash(user_id) % num_shards`).
- **Pros:** Distributes load much more evenly than naive range sharding — no natural correlation with load.
- **Cons:** Range queries now require fanning out to *every* shard, since consecutive keys are scattered arbitrarily; **adding/removing a shard with a naive `% N` scheme requires re-hashing and moving almost all data** — a massive, risky operation.

### Consistent Hashing
A refinement of hash-based sharding that maps both shards and keys onto a conceptual ring, so that adding or removing a shard only requires moving the keys that were between the new shard and its neighbor on the ring — **not** a full re-shuffle of the entire dataset.
- This is the technique underlying Cassandra, DynamoDB, and most CDN/cache node selection (see [CDN](../../cdn/README.md)) — whenever you hear "minimal data movement on scale-out," consistent hashing is almost certainly the mechanism.

### Directory-Based (Lookup Table) Sharding
A separate mapping service/table explicitly records which shard each key (or key range) lives on, rather than deriving it algorithmically.
- **Pros:** Maximum flexibility — can rebalance individual keys/tenants without any hashing scheme constraints, ideal for **multi-tenant SaaS** where you deliberately want to move one noisy/large tenant to its own dedicated shard.
- **Cons:** The lookup service itself becomes a new critical dependency and potential bottleneck/single point of failure — must itself be highly available.

---

## 4. Choosing a Shard Key — the Single Most Consequential Decision

A poorly chosen shard key is the #1 cause of sharding regret. Criteria for a good shard key:

- **High cardinality** — enough distinct values to actually spread across many shards (a boolean "is_premium" flag is a terrible shard key — only 2 possible shards, ever).
- **Even distribution of both data volume AND access frequency** — cardinality alone isn't enough; a shard key can have millions of distinct values but still be "hot" if traffic concentrates on a few (e.g., sharding by `merchant_id` when 1% of merchants generate 90% of traffic — the classic **celebrity/hot-key problem**, also seen in [Caching](../../caching/README.md) and [Rate Limiting](../../rate-limiting/README.md)).
- **Alignment with the dominant query pattern** — if 95% of queries filter by `tenant_id`, shard by `tenant_id` so those queries stay single-shard; sharding by an unrelated key would force cross-shard fan-out on the majority of queries.

---

## 5. What Breaks When You Shard (the honest cost list)

- **Cross-shard joins** become expensive or impossible at the database level — must be handled in application code (fetch from multiple shards, join in memory) or avoided by design (denormalization).
- **Cross-shard transactions** lose the simple single-node ACID guarantee — need distributed transaction patterns like two-phase commit (rare, slow) or, more commonly in practice, the **Saga pattern** (see [Saga Pattern](../../../05-distributed-systems/saga-pattern/README.md)) to maintain eventual correctness across shards.
- **Global secondary indexes / uniqueness constraints** (e.g., "email must be globally unique") are no longer enforceable by a single database's native constraint mechanism — must be enforced via a separate lookup table/service or accepted as eventually consistent.
- **Resharding/rebalancing** as data grows unevenly is operationally one of the highest-risk activities a database team performs — this is why consistent hashing and careful shard-key choice up front are so heavily emphasized.

---

## 6. Real-World Example: Instagram's Sharding of Postgres by (Logical Shard ID Embedded in the Primary Key)

Instagram's widely-cited engineering blog post describes their approach to sharding Postgres to handle a rapidly growing volume of photos/likes/comments without moving off Postgres entirely (avoiding the operational cost of a wholesale NoSQL migration):

- They partitioned data into thousands of **logical shards**, each mapped to one of a much smaller number of **physical Postgres servers** — the logical-to-physical mapping is a layer of indirection that lets them move logical shards between physical machines to rebalance load, without changing any application-level ID scheme.
- Crucially, they encode the **logical shard ID directly into the generated primary key** of each row (alongside a timestamp and a per-shard sequence, similar in spirit to Twitter's Snowflake ID scheme), so that given any row's ID alone, the application can immediately compute which shard to query — **no separate directory lookup needed** for the common case, avoiding that lookup service becoming a bottleneck.
- This is a hybrid of hash-based sharding (logical shard assignment) and directory-based flexibility (physical placement of logical shards can be rebalanced independently).

**The lesson:** you don't have to choose a "pure" textbook sharding strategy — production systems often blend approaches (embedding shard info in generated IDs to avoid a lookup hop, while keeping a layer of indirection for rebalancing flexibility) to get the benefits of multiple strategies at once.

---

## 7. Spring Boot Example: Routing Queries to the Correct Shard via a Custom Datasource Router

```java
// A simple hash-based shard router: given a tenant/customer ID, determine which
// of N physical datasources holds that tenant's data.
@Component
public class ShardRouter {

    private final int shardCount;

    public ShardRouter(@Value("${app.sharding.shard-count}") int shardCount) {
        this.shardCount = shardCount;
    }

    public String resolveShardKey(String tenantId) {
        // Consistent hashing in a real system would avoid a full reshuffle on shardCount
        // changes; a naive modulo is shown here for clarity of the routing CONCEPT.
        int shardIndex = Math.abs(tenantId.hashCode()) % shardCount;
        return "shard-" + shardIndex;
    }
}
```

```java
// Spring's AbstractRoutingDataSource lets us pick a physical DataSource per-request
// based on a thread-local "current shard key" -- transparent to repository code.
public class ShardingDataSource extends AbstractRoutingDataSource {

    private static final ThreadLocal<String> CURRENT_SHARD = new ThreadLocal<>();

    public static void setShard(String shardKey) {
        CURRENT_SHARD.set(shardKey);
    }

    public static void clear() {
        CURRENT_SHARD.remove();
    }

    @Override
    protected Object determineCurrentLookupKey() {
        return CURRENT_SHARD.get(); // e.g., "shard-0", "shard-1", ...
    }
}
```

```java
@Configuration
public class ShardDataSourceConfig {

    @Bean
    public DataSource routingDataSource(
            @Qualifier("shard0") DataSource shard0,
            @Qualifier("shard1") DataSource shard1,
            @Qualifier("shard2") DataSource shard2) {

        ShardingDataSource routingDataSource = new ShardingDataSource();
        Map<Object, Object> targets = Map.of(
            "shard-0", shard0,
            "shard-1", shard1,
            "shard-2", shard2
        );
        routingDataSource.setTargetDataSources(targets);
        routingDataSource.setDefaultTargetDataSource(shard0);
        return routingDataSource;
    }
}
```

```java
@Service
@RequiredArgsConstructor
public class TenantOrderService {

    private final ShardRouter shardRouter;
    private final OrderRepository orderRepository; // backed by the routing DataSource above

    public List<Order> getOrders(String tenantId) {
        try {
            // Route THIS request's queries to the correct physical shard before
            // any repository call executes.
            ShardingDataSource.setShard(shardRouter.resolveShardKey(tenantId));
            return orderRepository.findByTenantId(tenantId);
        } finally {
            ShardingDataSource.clear(); // always clear -- thread pool reuse would leak the shard key otherwise
        }
    }
}
```

**Why this matters at senior level:** this pattern makes concrete the "sharding pushes complexity into the application layer" cost — the routing logic, the thread-local shard context, the risk of leaking shard state across pooled threads — none of which exists with a single unsharded database. A senior candidate should be able to say precisely *where* that complexity lands, not just that "sharding adds complexity" in the abstract.

---

## 8. Common Pitfalls

- Sharding before it's needed — sharding is close to a one-way door operationally; it should be reached for only after replication, caching, and vertical scaling of the primary are genuinely exhausted for the write path specifically.
- Choosing a shard key based on convenience (e.g., auto-increment row ID) rather than actual query and load patterns, leading to either constant cross-shard fan-out or a hot-shard problem.
- Forgetting that cross-shard uniqueness constraints and joins need an explicit application-level (or separate service-level) solution — they don't come for free just because "the database supports sharding."
- Using naive `hash % N` sharding, which requires touching almost all data on every resize — should default to consistent hashing or a directory-based scheme for anything expected to grow.

---

## 9. 60-Second Interview Answer

> "Sharding splits data across multiple database instances so both read and write throughput scale horizontally, unlike replication, which only helps reads. The hardest part is choosing the shard key — it needs high cardinality, even load distribution, and alignment with the dominant query pattern, or you end up with hot shards or constant cross-shard fan-out. I'd default to consistent hashing over naive modulo hashing so that adding or removing shards doesn't require reshuffling the whole dataset. I'd also flag upfront that sharding breaks cross-shard joins, transactions, and global uniqueness constraints, pushing that complexity into the application layer — which is exactly why I'd only shard after replication and caching are genuinely exhausted, since it's one of the hardest decisions to walk back."

**Related:** [Replication](../replication/README.md) · [SQL vs NoSQL](../sql-vs-nosql/README.md) · [Scalability](../../../01-foundations/scalability/README.md) · [Saga Pattern](../../../05-distributed-systems/saga-pattern/README.md)
