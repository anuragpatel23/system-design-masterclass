# CAP Theorem

> **Formal statement (Brewer, 2000; proven by Gilbert & Lynch, 2002):** In a distributed data store, during a **network partition**, you must choose between **Consistency** (every read receives the most recent write or an error) and **Availability** (every request receives a non-error response, without the guarantee it contains the most recent write). You cannot have both, at the same time, during the partition.

The single most misquoted theorem in system design interviews. Get the precise version right and you immediately stand out.

---

## 1. The Three Letters, Precisely Defined

- **C — Consistency** (this is *linearizability*, not the C in ACID): every read receives the result of the most recently completed write, as if there were only a single copy of the data.
- **A — Availability**: every request to a non-failing node receives a response — not an error, not a timeout — though it does not have to be the most recent data.
- **P — Partition Tolerance**: the system continues to operate despite an arbitrary number of dropped or delayed messages between nodes.

## 2. The Interview Mistake Everyone Makes

Candidates say "you can pick 2 of 3, we picked CP" or "we picked AP." **This is imprecise and will get pushed back on by any senior interviewer**, for one crucial reason:

> **Partition tolerance is not optional.** Networks *will* partition — that's a physical reality (a switch fails, a cable is cut, a datacenter loses connectivity). A distributed system that "chooses" CA is really just a system that hasn't experienced a partition yet, or that gives up and stops being distributed (single node) when one occurs. **The real choice CAP forces is C vs A, and only during an actual partition.** Outside of a partition, a well-designed system can offer both full consistency and full availability.

The correct senior-level framing: *"CAP only constrains behavior during a network partition. The real design decision is: when a partition happens, does this specific component reject requests to preserve consistency, or does it keep answering and reconcile later?"* — and that decision can (and should) be made **per component**, not once for the whole system.

## 3. CP vs AP, With Concrete Systems

| | CP (Consistency + Partition tolerance) | AP (Availability + Partition tolerance) |
|---|---|---|
| **Behavior during partition** | Minority-side nodes refuse writes/reads (return errors) to avoid serving stale data | All nodes keep serving, possibly returning stale or conflicting data, reconciled later |
| **Example systems** | ZooKeeper, etcd, HBase, MongoDB (with majority write concern), traditional RDBMS with synchronous replication | Cassandra, DynamoDB, Riak, CouchDB, Eureka (service discovery) |
| **Good fit for** | Configuration management, leader election, financial ledgers, inventory counts where overselling is unacceptable | Shopping carts, social media feeds, presence/status indicators, DNS, session stores |
| **Failure mode if misapplied** | System becomes unavailable more often than necessary (e.g., using ZooKeeper as a general-purpose cache) | System serves incorrect data silently (e.g., using AP store for a bank ledger — double-spending risk) |

## 4. PACELC — The Extension Senior Candidates Should Know

CAP only describes behavior *during a partition*. But even when there is **no partition**, a system still must choose between **Latency and Consistency**, because achieving strong consistency across replicas requires coordination (round trips), which costs latency. Daniel Abadi's **PACELC** theorem captures this:

> **"If Partitioned: Availability or Consistency; Else (normal operation): Latency or Consistency."**

- **PA/EL** systems (e.g., Cassandra, DynamoDB): prioritize availability during a partition, and prioritize low latency during normal operation (async replication).
- **PC/EC** systems (e.g., traditional synchronously-replicated RDBMS, Spanner): prioritize consistency both during a partition and during normal operation, accepting higher latency as the cost.

**Why this matters in interviews:** CAP alone can't explain why a system might choose eventual consistency even when the network is perfectly healthy. PACELC does — and name-dropping it correctly (with the right examples) is a strong senior-level signal.

---

## 5. Real-World Example: DynamoDB's Tunable Consistency (an AP System That Lets You Dial Toward C)

Amazon DynamoDB, and the original Dynamo paper it's based on, is a canonical **AP** system: during a network partition, every reachable replica keeps accepting writes, and conflicting versions are reconciled afterward (via vector clocks in the original Dynamo design, and "last write wins" by default in DynamoDB today).

The senior-level nuance: DynamoDB doesn't force you into pure AP for every read. It exposes a **per-request consistency choice**:
- **Eventually consistent reads** (the default): lower latency, lower cost, may return slightly stale data — appropriate for a product catalog page.
- **Strongly consistent reads**: routes the read to a node guaranteed to have the latest committed write, at the cost of higher latency and reduced availability during a partition (it might have to refuse the read rather than serve a stale one) — appropriate for reading your own just-placed order status immediately after checkout.

**The lesson:** CAP/PACELC trade-offs don't have to be made once for an entire database — modern systems let you make the trade-off **per query**, and a senior architect's job is knowing which queries need which guarantee, rather than picking one setting globally "to be safe."

---

## 6. Spring Boot Example: Modeling a CP Decision vs an AP Decision in the Same Application

Consider an e-commerce checkout flow with two very different consistency needs in the *same* request: **inventory decrement (must be CP — cannot oversell)** and **"recently viewed items" tracking (fine as AP — staleness is invisible to the user)**.

```java
// CP-style component: inventory must never oversell.
// We use a strongly-consistent, single-source-of-truth relational store with
// optimistic locking, and we FAIL FAST (return an error) rather than risk overselling
// -- this is deliberately choosing Consistency over Availability under contention.
@Service
@RequiredArgsConstructor
public class InventoryService {

    private final InventoryRepository inventoryRepository; // JPA repo backed by Postgres

    @Transactional
    public void reserveStock(String sku, int quantity) {
        InventoryItem item = inventoryRepository.findBySkuForUpdate(sku)
            .orElseThrow(() -> new NotFoundException(sku));

        if (item.getAvailableQty() < quantity) {
            // Deliberately unavailable (reject) rather than risk overselling: a CP choice.
            throw new InsufficientStockException(sku);
        }
        item.setAvailableQty(item.getAvailableQty() - quantity);
        inventoryRepository.save(item); // @Version field enforces optimistic locking
    }
}
```

```java
// Repository using a pessimistic lock to guarantee linearizable reads/writes on this row
public interface InventoryRepository extends JpaRepository<InventoryItem, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM InventoryItem i WHERE i.sku = :sku")
    Optional<InventoryItem> findBySkuForUpdate(@Param("sku") String sku);
}
```

```java
// AP-style component: "recently viewed" is fine to be eventually consistent.
// We fire-and-forget to Cassandra / DynamoDB-style store; if a node is partitioned,
// we still accept the write locally and let it reconcile later -- an Availability choice.
@Service
@RequiredArgsConstructor
public class RecentlyViewedService {

    private final CassandraTemplate cassandraTemplate;

    @Async // does not block the checkout critical path; failures here never fail the request
    public void recordView(String userId, String sku) {
        try {
            cassandraTemplate.insert(new RecentlyViewedEntry(userId, sku, Instant.now()));
        } catch (Exception e) {
            // deliberately swallow -- staleness or loss here is invisible to the user,
            // unlike a failed inventory reservation, which would be a correctness bug
            log.warn("recently-viewed write failed, ignoring (AP tolerance)", e);
        }
    }
}
```

**Why this matters at senior level:** the interview signal is recognizing that **CAP is a per-component decision, not a whole-system label**. The same checkout request legitimately makes a CP choice for inventory and an AP choice for recommendation data, in the same transaction boundary.

---

## 7. Common Pitfalls

- Saying "we chose CA" — signals you don't understand that partitions are a physical inevitability, not a choice.
- Applying one consistency model to an entire system instead of per-component (e.g., using a strongly consistent DB for session/presence data that would be fine as AP, needlessly sacrificing availability and latency).
- Forgetting PACELC — failing to explain why a system might sacrifice consistency for latency even when there's no partition at all.
- Confusing CAP's "C" (linearizability) with ACID's "C" (constraints/invariants like foreign keys) — they are unrelated uses of the same letter.

---

## 8. 60-Second Interview Answer

> "CAP says that during an actual network partition, a distributed system must choose between consistency — every read reflects the latest write — and availability — every request gets a non-error response. Partition tolerance isn't really optional since networks do partition, so the real decision is CP vs AP, and it should be made per component, not for the whole system: I'd make inventory decrements CP, since overselling is a real cost, but recommendation or presence data AP, since staleness there is invisible. I'd also bring in PACELC, since even without a partition, strongly consistent systems still trade off latency for consistency during normal operation, which is why some AP systems like DynamoDB let you choose consistency level per query instead of system-wide."

**Related:** [Consistency Models](../consistency-models/README.md) · [Database Replication](../../02-building-blocks/databases/replication/README.md) · [Availability & Reliability](../availability-reliability/README.md)
