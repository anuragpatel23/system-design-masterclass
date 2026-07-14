# Database Replication

> Replication keeps multiple copies of the same data on different machines, primarily to scale **read** throughput and to provide fault tolerance (if one node dies, others still have the data). It's usually the *first* database-scaling lever pulled, before the much harder step of [Sharding](../sharding/README.md).

---

## 1. Replication Topologies

### Single-Leader (Master-Slave / Primary-Replica)
One node (the leader/primary) accepts all writes; changes propagate to one or more followers/replicas, which serve read traffic.

- **Pros:** Simple mental model, no write conflicts possible (only one writer), reads scale horizontally by adding more replicas.
- **Cons:** Write throughput is capped by a single node; the leader is a single point of failure for writes until a failover promotes a replica.

### Multi-Leader (Master-Master)
Multiple nodes each accept writes independently and propagate changes to each other.
- **Pros:** Writes can be accepted in multiple regions simultaneously (lower write latency for geographically distributed users), survives a single leader's failure without a failover pause for writes.
- **Cons:** **Write conflicts are now possible** (two leaders accept conflicting writes to the same record before either has heard from the other) and must be resolved — via last-write-wins (simple but can silently lose data), application-level merge logic, or CRDTs (Conflict-free Replicated Data Types, which are mathematically guaranteed to merge consistently regardless of order).

### Leaderless (Dynamo-style, e.g., Cassandra, DynamoDB)
Any replica can accept a write; the client (or a coordinator) writes to multiple replicas directly and uses **quorum** reads/writes to maintain consistency guarantees without a single leader at all.
- **Quorum formula:** with `N` replicas, a write is acknowledged by `W` of them, and a read queries `R` of them. If `W + R > N`, every read is guaranteed to overlap with the most recent write's replica set, giving a strong-ish consistency guarantee without a leader.
- **Pros:** No single point of failure for either reads or writes, highly available during partitions.
- **Cons:** More complex conflict resolution (vector clocks, "read repair"), harder to reason about than single-leader.

---

## 2. How Replication Actually Happens, Mechanically: Log Shipping

Underneath every topology in §1, replication is implemented by shipping a **write-ahead log (WAL)** (Postgres) or **binary log (binlog)** (MySQL) — the same durable, append-only log the database already writes internally to guarantee crash recovery — from the leader to each replica, which replays those log entries to reconstruct the identical sequence of changes.

- **Physical (log) replication:** ships the raw, low-level log records (literally "byte X on disk page Y changed to this value") — replicas end up byte-for-byte identical to the leader, and it's fast since there's no need to reinterpret SQL, but it requires the replica to be running the same database version/architecture.
- **Logical replication:** ships a higher-level, decoded representation of each change ("row with this primary key was updated to these column values") — more flexible (can replicate between different versions, or into a different system entirely, like streaming database changes into Kafka via **Change Data Capture**, see [Event-Driven Architecture](../../../05-distributed-systems/event-driven-architecture/README.md)), at some additional CPU cost to decode/re-encode each change.
- **Replication lag, precisely defined in these terms:** it's the gap between the leader's current log position and the position a given replica has replayed up to — often measured directly as **bytes of unreplayed log** or a **log sequence number (LSN) offset**, which is exactly the metric managed database consoles (RDS, Cloud SQL) surface as "replica lag."

Understanding log shipping as the actual mechanism — not just "the data gets copied" — is what lets you reason correctly about *why* physical replicas must match versions, why logical replication enables cross-version or cross-system streaming, and why CDC pipelines (Debezium and similar tools) work by tailing this exact same log rather than polling the database with queries.

---

## 3. Synchronous vs Asynchronous Replication

- **Synchronous:** the leader waits for the replica(s) to confirm they've received/applied the write before acknowledging success to the client. Guarantees zero data loss on leader failure (the replica is always caught up) but adds latency to every write, and if the replica is slow/down, writes can stall or fail entirely (an Availability cost per [CAP Theorem](../../../01-foundations/cap-theorem/README.md)).
- **Asynchronous:** the leader acknowledges the write immediately and propagates to replicas in the background. Low write latency, high availability, but **replication lag** means replicas can serve stale reads, and a leader crash before propagation means those last writes are **lost**.
- **Semi-synchronous (common middle ground):** the leader waits for confirmation from **at least one** replica (not all) before acknowledging — bounds worst-case data loss to "at most everything not yet reached by any replica" while keeping latency mostly in check.

---

## 4. Replication Lag and Its Consequences

Asynchronous replication's central cost is **replication lag** — the delay between a write on the leader and its visibility on a replica. This directly causes the [Consistency Models](../../../01-foundations/consistency-models/README.md) problems discussed earlier — most concretely, **read-your-writes violations**, where a user updates something and then, on the very next request (routed to a lagging replica), doesn't see their own change.

**Monitoring replication lag is a first-class production concern** — most managed database offerings (RDS, Cloud SQL) expose a replication lag metric, and mature systems alert on it and can automatically route "must be fresh" reads to the primary when lag exceeds a threshold.

---

## 5. Failover — What Happens When the Leader Dies

1. **Detection:** a monitoring/consensus mechanism (e.g., a heartbeat via a tool like Patroni for Postgres, or built into the database itself, like MySQL Group Replication) determines the leader is unreachable.
2. **Election:** the replica with the most up-to-date data (least replication lag) is chosen as the new leader — often via a consensus algorithm ([Raft](../../../05-distributed-systems/consensus-algorithms/raft/README.md)) to avoid two nodes both believing they're the new leader (**split-brain**).
3. **Reconfiguration:** clients/application servers must be redirected to the new leader — typically via a DNS update, a virtual IP reassignment, or a service discovery update.

**Split-brain danger:** if failover detection is too aggressive (e.g., a temporary network blip is misdiagnosed as leader death) and the old leader is still actually alive and accepting writes, you can end up with **two leaders accepting conflicting writes simultaneously** — a serious correctness bug. This is precisely why leader election uses consensus protocols requiring a majority vote, rather than any single node unilaterally deciding to promote itself.

---

## 6. Real-World Example: MySQL Replication at GitHub, and Their Use of `orchestrator` for Automated Failover

GitHub's engineering blog has publicly detailed their MySQL replication topology, which serves an enormous, latency-sensitive read/write workload:

- They run a classic **single-leader topology** with multiple asynchronous replicas per cluster, primarily to scale reads (issue/PR listing, search, etc.) away from the single write leader.
- To handle leader failure safely and quickly, they built and open-sourced **`orchestrator`**, a tool that continuously monitors replication topology health and **automates leader failover** — detecting a dead leader, selecting the replica with the least replication lag as the new leader, and reconfiguring the topology, all without a human paging in at 3am for the common case.
- Crucially, GitHub's engineers have publicly discussed tuning failover detection to avoid false positives from transient network issues — precisely the split-brain risk described above — using multiple independent observers agreeing before triggering a failover, rather than a single monitor's opinion.

**The lesson:** automated failover is not "nice to have" tooling — it is a direct application of the [MTTR-reduction principle](../../../01-foundations/availability-reliability/README.md) from the Availability & Reliability foundation: cutting failover time from a human-paged 20+ minutes to an automated sub-minute event is one of the highest-leverage availability investments a database team can make.

---

## 7. Spring Boot Example: Routing Reads to Replicas and Writes to the Primary

```java
// A routing DataSource, similar in spirit to the sharding example, but here
// routing purely on READ vs WRITE rather than on a shard key.
public class ReadWriteRoutingDataSource extends AbstractRoutingDataSource {

    private static final ThreadLocal<Boolean> IS_READ_ONLY = ThreadLocal.withInitial(() -> false);

    public static void markReadOnly() {
        IS_READ_ONLY.set(true);
    }

    public static void clear() {
        IS_READ_ONLY.remove();
    }

    @Override
    protected Object determineCurrentLookupKey() {
        return IS_READ_ONLY.get() ? "replica" : "primary";
    }
}
```

```java
// An AOP aspect that inspects @Transactional(readOnly = true) and routes
// accordingly -- application code doesn't need to manually flag every call.
@Aspect
@Component
@Order(0) // must run BEFORE Spring's own transaction interceptor
public class ReadWriteRoutingAspect {

    @Before("@annotation(transactional) && execution(* *(..))")
    public void routeBasedOnReadOnly(Transactional transactional) {
        if (transactional.readOnly()) {
            ReadWriteRoutingDataSource.markReadOnly(); // -> replica
        }
        // else: default lookup key is "primary" (see determineCurrentLookupKey default handling)
    }

    @After("@annotation(transactional)")
    public void clear(Transactional transactional) {
        ReadWriteRoutingDataSource.clear();
    }
}
```

```java
@Service
@RequiredArgsConstructor
public class OrderQueryService {

    private final OrderRepository orderRepository;

    // readOnly = true -> routed to a replica, scaling this read horizontally
    // away from the single write-capable primary.
    @Transactional(readOnly = true)
    public List<Order> getOrderHistory(String customerId) {
        return orderRepository.findByCustomerId(customerId);
    }

    // Default (readOnly = false, or no annotation) -> routed to the primary,
    // since writes MUST go to the single leader in a single-leader topology.
    @Transactional
    public Order createOrder(OrderRequest request) {
        return orderRepository.save(Order.from(request));
    }
}
```

```yaml
# application.yml
app:
  datasource:
    primary:
      url: jdbc:postgresql://db-primary.internal:5432/orders
    replica:
      url: jdbc:postgresql://db-replica.internal:5432/orders
      # Note: this replica endpoint might itself be a load-balanced DNS name
      # fronting several replicas, combining replication with load balancing.
```

**Why this matters at senior level:** this pattern is one of the most common real production setups, and understanding that it introduces the exact **read-your-writes problem** discussed in [Consistency Models](../../../01-foundations/consistency-models/README.md) — and knowing the mitigation (sticky reads to primary for a short window after a user's own write) — is the difference between reciting "we use read replicas" and actually understanding what that decision costs.

---

## 8. Common Pitfalls

- Treating replication as a solution to write scalability — it only scales reads; write throughput is still capped by the single leader (or requires multi-leader/leaderless topologies with their own conflict-resolution costs).
- Ignoring replication lag in the application design, causing intermittent, hard-to-reproduce "stale data" bugs that are actually a direct, predictable consequence of asynchronous replication.
- Overly aggressive automated failover triggering on transient network blips, causing split-brain or unnecessary failovers — detection should require multiple corroborating signals, not a single missed heartbeat.
- Forgetting that synchronous replication trades availability for durability — if all synchronous replicas are unreachable, writes can stall entirely, which may be the wrong trade-off for a workload with different priorities.

---

## 9. 60-Second Interview Answer

> "Replication copies data across machines mainly to scale reads and provide fault tolerance, not to scale writes. Single-leader is the simplest and most common topology — one write path, multiple read replicas — but it introduces replication lag with asynchronous replication, which can cause read-your-writes violations if a user's own write hasn't propagated to the replica serving their next read yet. I'd mitigate that by routing a user's own reads to the primary for a short window after they write. For failover, I'd want automated detection and leader election via consensus, requiring multiple corroborating health signals rather than a single missed heartbeat, to avoid triggering split-brain on a transient network blip."

**Related:** [Sharding](../sharding/README.md) · [Consistency Models](../../../01-foundations/consistency-models/README.md) · [CAP Theorem](../../../01-foundations/cap-theorem/README.md) · [Consensus Algorithms — Raft](../../../05-distributed-systems/consensus-algorithms/raft/README.md)
