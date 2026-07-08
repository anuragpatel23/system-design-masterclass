# Transactions & ACID

> Every candidate can recite "Atomicity, Consistency, Isolation, Durability." The actual interview signal is in the **Isolation** letter specifically — the four standard isolation levels represent a genuinely important, frequently-misunderstood trade-off between correctness and concurrency/performance, and being able to name the *specific anomaly* each isolation level does or doesn't prevent is what separates real understanding from acronym recitation.

---

## 1. The Four Letters, Precisely

- **Atomicity:** a transaction's operations either **all** happen or **none** do — no partial completion is ever visible, even if the system crashes mid-transaction. Implemented via write-ahead logging (the database logs its intent before making changes, so it can complete or roll back an interrupted transaction on recovery) and rollback mechanisms.
- **Consistency (the database's C, distinct from CAP's C — see the note below):** a transaction takes the database from one **valid** state to another, respecting all defined constraints (foreign keys, unique constraints, check constraints, triggers) — this is fundamentally an application/schema-defined property (you define what "valid" means via constraints), enforced by the database engine on every transaction's commit.
- **Isolation:** concurrently-executing transactions should not interfere with each other in ways that produce incorrect results — precisely how much isolation is guaranteed is a **tunable spectrum**, covered in depth below, and is the part of ACID with genuine engineering trade-offs to reason about.
- **Durability:** once a transaction is committed, it survives any subsequent crash — implemented via the write-ahead log being flushed to durable storage (disk) before a commit is acknowledged to the client.

**The CAP-C vs ACID-C disambiguation (worth stating explicitly, since it's a common point of confusion):** ACID's Consistency is about the database enforcing **its own defined integrity constraints** (e.g., a foreign key must reference an existing row) on every transaction. [CAP's Consistency](../../01-foundations/cap-theorem/README.md#1-the-three-letters-precisely-defined) is about **linearizability** — whether a read reflects the most recent write across a distributed system. These are genuinely different concepts that happen to share a letter, and conflating them is a very common, easily-avoidable mistake.

---

## 2. Isolation Levels and the Anomalies They Prevent — the Core of This Document

Perfect isolation (as if every transaction ran one at a time, with no overlap at all — called **serializability**) is the strongest, safest guarantee, but also the most expensive in terms of concurrency (it typically requires either heavy locking or aborting/retrying transactions that would have conflicted). The SQL standard defines four isolation levels, each permitting a specific, named set of "anomalies" (incorrect-looking results that can occur due to concurrent execution) in exchange for better performance/concurrency:

### Dirty Read
Transaction A reads a value that Transaction B has written but **not yet committed** — if B then rolls back, A has read a value that, from B's perspective, never actually happened.

### Non-Repeatable Read
Transaction A reads a row, Transaction B updates and commits a change to that same row, and A reads the row **again within the same transaction**, getting a **different** value than its first read — the same query, run twice in the same transaction, produced two different answers.

### Phantom Read
Transaction A runs a query matching a set of rows (e.g., `WHERE status = 'PENDING'`), Transaction B inserts a **new** row that would match that same query and commits, and A re-runs the same query within its own transaction, seeing a **different set of rows** (a "phantom" row appeared) than its first run.

### The Four Isolation Levels, Mapped to Which Anomalies They Prevent

| Isolation Level | Dirty Read | Non-Repeatable Read | Phantom Read |
|---|---|---|---|
| **Read Uncommitted** | ❌ Possible | ❌ Possible | ❌ Possible |
| **Read Committed** | ✅ Prevented | ❌ Possible | ❌ Possible |
| **Repeatable Read** | ✅ Prevented | ✅ Prevented | ❌ Possible (in the strict standard; many real databases, including Postgres and MySQL/InnoDB, actually prevent phantoms at this level too via MVCC-based implementations — a genuinely important, frequently-tested nuance covered below) |
| **Serializable** | ✅ Prevented | ✅ Prevented | ✅ Prevented |

**Why this table, memorized precisely, is worth having:** a staff-level interviewer will frequently present a **specific concurrency scenario** (e.g., "two users simultaneously update the same bank balance" or "a report re-runs a count query mid-transaction and gets a different answer") and ask **which isolation level would prevent this specific problem** — being able to name the exact anomaly and map it to the minimum sufficient isolation level (rather than reflexively answering "just use Serializable for everything," which sacrifices concurrency far more than most problems actually require) is the real signal being tested.

---

## 3. The Real-World Default: Read Committed, and Why

**Read Committed is the default isolation level in Postgres, Oracle, and SQL Server** (MySQL/InnoDB defaults to Repeatable Read, a notable, frequently-asked exception) — precisely because it eliminates the most jarring anomaly (dirty reads — seeing another transaction's uncommitted, possibly-about-to-be-rolled-back work) while still allowing high concurrency, since it doesn't require holding read locks for a transaction's entire duration. **Most application code, most of the time, is perfectly correct under Read Committed** — non-repeatable reads and phantoms only matter for logic that explicitly depends on **re-reading the same data multiple times within one transaction and expecting consistency across those reads** (a genuinely common pattern in financial/inventory logic, which is exactly why those domains often explicitly opt into stronger isolation, or use explicit row-locking, rather than relying on the default).

---

## 4. MVCC — How Modern Databases Achieve Isolation Without Constant Blocking

**Multi-Version Concurrency Control (MVCC)**, used by Postgres, MySQL/InnoDB, and Oracle, is the mechanism that makes higher isolation levels practically achievable without simply locking readers and writers against each other constantly (which would badly hurt concurrency). The core idea: **instead of readers blocking on writers (or vice versa), the database keeps multiple versions of a row simultaneously** — a writer creates a *new* version of a row rather than overwriting the old one in place, and each transaction sees a consistent **snapshot** of the data as of some point in time (the exact snapshot semantics differ slightly between Read Committed, which takes a new snapshot per *statement*, and Repeatable Read, which takes one snapshot for the *whole transaction* — this distinction is precisely why Postgres's Repeatable Read implementation, built on MVCC snapshots, ends up preventing phantom reads too, unlike the strict SQL standard's minimum requirement for that level, which is a genuinely good "gotcha" fact to have ready).

**Why this matters practically:** MVCC means **readers never block writers, and writers never block readers** (they operate on different row versions) — only writer-vs-writer conflicts on the *same* row require actual locking/blocking or conflict detection. This is a huge practical concurrency win over a naive locking-based isolation implementation, and it's the actual mechanism underneath why Postgres/MySQL can offer strong isolation guarantees without the throughput collapse a lock-everything approach would cause.

---

## 5. Serializable Isolation — the Strongest Guarantee, and Its Real Cost

Serializable isolation guarantees the result of running transactions concurrently is **equivalent to some serial (one-at-a-time) execution order** — the strongest possible guarantee, preventing all three named anomalies plus more subtle ones (like write skew, a scenario where two transactions each read overlapping data, each makes a decision based on what they read, and both commit — producing a combined result that violates an invariant neither transaction violated individually, a classic example being two doctors each independently checking "is at least one other doctor on call" and both going off-call simultaneously because each saw the other as still on call at the time they checked).

**The real cost:** achieving this typically requires either heavyweight locking (serializing genuinely concurrent transactions, hurting throughput) or, in modern MVCC-based implementations, **Serializable Snapshot Isolation (SSI)** — an optimistic approach that lets transactions proceed concurrently but **detects, at commit time, whether their combined effect could have produced a non-serializable result**, and **aborts one of the conflicting transactions**, forcing the application to retry it. **This means Serializable isolation introduces a real, new failure mode your application code must handle: transaction aborts due to serialization conflicts, which must be caught and retried** — a genuinely important, often-overlooked operational detail when adopting Serializable isolation in a real system.

---

## 6. Spring Boot Example: Choosing Isolation Level Explicitly for a Specific Business Rule

```java
@Service
@RequiredArgsConstructor
public class AccountTransferService {

    private final AccountRepository accountRepository;

    // Default (Read Committed) is INSUFFICIENT here: reading the source
    // account's balance, then later re-reading it to confirm sufficient
    // funds before debiting, could see a DIFFERENT balance the second time
    // if another concurrent transfer committed in between -- a classic
    // non-repeatable-read anomaly with real financial consequences.
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public void transfer(String fromAccountId, String toAccountId, BigDecimal amount) {
        Account from = accountRepository.findById(fromAccountId).orElseThrow();

        if (from.getBalance().compareTo(amount) < 0) {
            throw new InsufficientFundsException(fromAccountId);
        }

        // Re-reading "from" here, if it happened, is guaranteed to see the
        // SAME balance as the first read under Repeatable Read -- preventing
        // a concurrent transfer from silently invalidating our earlier check.
        accountRepository.debit(fromAccountId, amount);
        accountRepository.credit(toAccountId, amount);
    }

    // A genuinely SERIALIZABLE-requiring case: a business invariant spanning
    // MULTIPLE rows that must hold even under concurrent execution (the
    // "write skew" scenario from Section 5) -- e.g., "the sum of all account
    // balances in this ledger group must never go negative in aggregate."
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void performLedgerAdjustment(String ledgerGroupId, List<Adjustment> adjustments) {
        // Any conflicting concurrent SERIALIZABLE transaction touching
        // overlapping data will cause ONE of them to be aborted at commit
        // time -- the caller MUST be prepared to catch this and retry.
        applyAdjustments(ledgerGroupId, adjustments);
    }
}
```

```java
// Callers of a SERIALIZABLE-isolated method must handle the real possibility
// of a serialization failure and retry -- this is not an edge case, it's an
// expected, designed-for outcome of choosing this isolation level.
@Service
@RequiredArgsConstructor
public class LedgerAdjustmentRetryWrapper {

    private final AccountTransferService transferService;
    private static final int MAX_RETRIES = 3;

    public void performWithRetry(String ledgerGroupId, List<Adjustment> adjustments) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                transferService.performLedgerAdjustment(ledgerGroupId, adjustments);
                return;
            } catch (CannotSerializeTransactionException e) {
                if (attempt == MAX_RETRIES) throw e;
                // exponential backoff before retrying, per Rate Limiting/resilience patterns
            }
        }
    }
}
```

**Why this matters at senior level:** a candidate who sets `@Transactional(isolation = Isolation.SERIALIZABLE)` everywhere "to be safe," without acknowledging the real throughput cost and the **mandatory retry-on-abort handling** it requires, has not actually understood the trade-off — the correct answer, as shown, is choosing the **minimum sufficient isolation level per specific business rule**, informed by which named anomaly that specific piece of logic is actually vulnerable to.

---

## 7. Common Pitfalls

- Reciting "Atomicity, Consistency, Isolation, Durability" without being able to explain the specific anomalies each isolation level prevents — the actual interview depth is in the isolation-level table, not the acronym.
- Confusing ACID's Consistency (schema/constraint enforcement) with CAP's Consistency (linearizability across a distributed system) — genuinely different concepts sharing a letter.
- Defaulting to Serializable "to be safe" without accounting for its real throughput cost and the mandatory commit-time-abort-and-retry handling it requires in application code.
- Not knowing that MySQL/InnoDB's default isolation level (Repeatable Read) differs from Postgres/Oracle/SQL Server's default (Read Committed) — a small but real, frequently-tested fact.

---

## 8. 60-Second Interview Answer

> "Atomicity, Consistency, and Durability are relatively mechanical guarantees, but Isolation is where the real trade-offs live. The SQL standard defines four levels, each preventing a specific named anomaly — dirty reads, non-repeatable reads, and phantom reads — with Read Committed as the practical default in most databases, since it eliminates dirty reads while still allowing good concurrency, and most application code doesn't actually need stronger guarantees than that. Modern databases implement this via MVCC — multiple versions of a row exist simultaneously, so readers never block writers and vice versa, which is what makes stronger isolation levels practically affordable rather than requiring constant locking. Serializable is the strongest guarantee, preventing subtler issues like write skew too, but real implementations achieve it optimistically — letting transactions run concurrently and aborting one at commit time if their combined effect wasn't actually serializable — which means choosing Serializable isolation isn't free; it introduces a real failure mode of transaction aborts that application code has to explicitly catch and retry. I'd choose the minimum sufficient isolation level per specific business rule, not default to Serializable everywhere, since that sacrifices far more concurrency than most logic actually requires."

**Related:** [CAP Theorem](../../01-foundations/cap-theorem/README.md) · [Consistency Models](../../01-foundations/consistency-models/README.md) · [B-Trees vs LSM-Trees](../b-trees-lsm-trees/README.md) · [Payment System](../../03-high-level-design/payment-system/README.md)
