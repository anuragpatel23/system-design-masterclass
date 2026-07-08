# CQRS & Event Sourcing

> Two distinct but frequently-paired patterns. **CQRS (Command Query Responsibility Segregation)** separates the model used to *write* data from the model used to *read* it. **Event Sourcing** stores state as an append-only log of *events that happened*, rather than as a single mutable "current state" row — the current state becomes a *derived, replayable* value, not the source of truth. They're commonly discussed together because event sourcing naturally produces the kind of write model CQRS wants to pair with a separately-optimized read model, but **each is independently useful and adoptable without the other** — a distinction interviewers specifically probe for.

---

## 1. CQRS: Separating Reads from Writes

In a traditional CRUD architecture, the same model (often the same database table, accessed via the same repository/entity class) serves both writes (validating and persisting a change) and reads (serving a query). CQRS explicitly splits this:

- **The Command side** handles writes: validates business rules, enforces invariants, and persists changes — typically normalized, optimized for correctness and transactional integrity.
- **The Query side** handles reads: a separately-optimized, often denormalized model, shaped specifically around how the data needs to be *displayed or queried*, not how it needs to be *written*.

```
Write path:  Client ──▶ Command Handler ──▶ validates + persists to WRITE model
                                                      │
                                          (asynchronously, or via events)
                                                      ▼
Read path:   Client ──▶ Query Handler ──▶ reads from a separately-shaped
                                            READ model, optimized for the
                                            actual query patterns needed
```

**Why this is valuable, precisely:** the write and read models often have **genuinely conflicting optimal shapes**. A write model for an e-commerce order wants strict normalization (an `orders` table, a separate `order_items` table, foreign keys enforcing referential integrity) to prevent invalid states. A read model serving "show me this customer's order history with product names, images, and current shipping status all in one response" wants a **denormalized, pre-joined, possibly pre-aggregated** shape specifically so that common queries are cheap single-lookups rather than expensive multi-table joins computed on every request. Forcing one shared model to serve both needs means compromising one or both — CQRS explicitly rejects that compromise by allowing each side to be optimized independently.

**This is not a new idea invented from scratch — it's the same principle behind [Database Replication's](../../02-building-blocks/databases/replication/README.md) read-replica pattern and [Netflix's](../../03-high-level-design/netflix/README.md#4-component-deep-dive-precomputation-as-the-core-personalization-strategy) precomputed recommendation lookups, generalized into an explicit architectural pattern** — read replicas are, in effect, a simple, same-schema form of CQRS; full CQRS goes further by letting the read model have a genuinely *different schema/shape*, not just a copy of the same one.

---

## 2. When CQRS Is Overkill (Say This Unprompted)

CQRS adds real complexity — two models to build, test, and keep in sync (often via the events discussed below), and genuine eventual consistency between the write and read sides (a write isn't instantly reflected in the read model unless you add extra synchronous synchronization, which partially defeats the purpose). **For a typical CRUD service with simple, uniform access patterns, plain CRUD with one shared model is simpler, easier to reason about, and entirely sufficient** — a senior-level answer should proactively state that CQRS is justified specifically when read and write patterns/loads genuinely diverge (e.g., a system that's write-light but has many differently-shaped, high-volume read views, like a dashboard aggregating data many different ways), not applied reflexively to every service "because it's a best practice."

---

## 3. Event Sourcing: State as a Log, Not a Snapshot

In a traditional model, an entity's current state is stored directly (e.g., an `orders` table row with a `status` column that gets overwritten on each update — the history of *how* it got to its current status is lost unless separately logged). **Event sourcing instead stores every state-changing event** (`OrderPlaced`, `OrderPaid`, `OrderShipped`, `OrderCancelled`) as an **immutable, append-only sequence**, and the entity's current state is computed by **replaying all its events in order**.

```
Traditional model:
  orders table:  { orderId: 123, status: "SHIPPED", total: 50.00 }
  -- history of HOW it got to SHIPPED is gone unless logged elsewhere

Event-sourced model:
  event log for order 123:
    1. OrderPlaced   { total: 50.00 }
    2. OrderPaid     { paymentId: "pay_1" }
    3. OrderShipped  { trackingNumber: "1Z..." }

  Current state = fold/replay all events in order:
    status = "SHIPPED" (the result of applying all 3 events in sequence)
```

**This is precisely the [double-entry ledger](../../03-high-level-design/payment-system/README.md#3-component-deep-dive-the-double-entry-ledger) principle from the Payment System HLD, generalized from "money movement" to "any entity's entire lifecycle"** — the same reasoning applies: an immutable log of what happened is more auditable, more debuggable (you can literally see and replay the exact sequence of events that led to any given state, including for a state that turned out to reveal a bug), and structurally prevents a whole class of "silent state corruption" bugs, compared to a single mutable row that simply reflects whatever it was last overwritten to.

---

## 4. Component Deep Dive: Snapshots — Solving Event Sourcing's Replay-Cost Problem

A naive event-sourced system replays **every event from the beginning of time** to reconstruct current state — for a long-lived entity with thousands of events (e.g., a bank account active for 10 years with daily transactions), this becomes prohibitively slow to reconstruct on every read.

**The fix: periodic snapshots.** Periodically (e.g., every 100 events, or on a time-based schedule), persist a **snapshot** — the fully-computed current state at that point — alongside the event log. Reconstructing current state then only requires loading the **most recent snapshot** plus replaying **only the events that occurred after it**, not the entire history.

```java
public class EventSourcedAccount {

    public static Account reconstructState(String accountId,
                                            SnapshotStore snapshotStore,
                                            EventStore eventStore) {
        // Load the most recent snapshot, if any -- avoids replaying from event #1.
        Optional<AccountSnapshot> snapshot = snapshotStore.getLatestSnapshot(accountId);

        Account account = snapshot.map(AccountSnapshot::toAccount)
                                   .orElse(Account.empty(accountId));
        long fromVersion = snapshot.map(AccountSnapshot::getVersion).orElse(0L);

        // Only replay events AFTER the snapshot's version -- this is what
        // keeps reconstruction fast regardless of how old the entity is.
        List<AccountEvent> eventsSinceSnapshot = eventStore.getEventsSince(accountId, fromVersion);
        for (AccountEvent event : eventsSinceSnapshot) {
            account = account.apply(event); // each event knows how to fold itself into the state
        }
        return account;
    }
}

public abstract class AccountEvent {
    public abstract Account applyTo(Account account); // polymorphic "how do I change the state" logic
}

public class MoneyDepositedEvent extends AccountEvent {
    private final BigDecimal amount;
    public MoneyDepositedEvent(BigDecimal amount) { this.amount = amount; }

    @Override
    public Account applyTo(Account account) {
        return account.withBalance(account.getBalance().add(amount)); // pure, no side effects
    }
}
```

**Why each event knowing how to `applyTo` the state (rather than a central `switch` in the reconstruction logic) matters — this is exactly the same [Open/Closed Principle payoff from polymorphic pieces in Chess](../../04-low-level-design/chess-game/README.md#3-piece-polymorphism--the-core-design-decision):** adding a new event type (e.g., `InterestAppliedEvent`) means adding one new class implementing `applyTo` — zero changes to the reconstruction/replay logic itself.

---

## 5. Where CQRS and Event Sourcing Naturally Pair Up

Event sourcing produces, as a direct byproduct, exactly the kind of event stream that's perfect for driving a **separately-updated CQRS read model**: every event appended to the write-side event log can also be published (or the event store itself can be the publishing mechanism, e.g., via change-data-capture), and one or more **read model projectors** subscribe to that stream, updating their own independently-shaped, denormalized read tables in response.

```java
// A read-model "projector" -- subscribes to the event stream and maintains
// a denormalized table specifically shaped for a common query pattern.
@Component
@RequiredArgsConstructor
public class OrderHistoryReadModelProjector {

    private final OrderHistoryReadRepository readRepository; // a SEPARATE, denormalized table/store

    @KafkaListener(topics = "order-events", groupId = "order-history-projector")
    public void onEvent(OrderEvent event) {
        // This projector's job is ENTIRELY about shaping data for reads --
        // it has no business-rule validation responsibility at all, since
        // that already happened on the write/command side before this
        // event was ever produced.
        switch (event) {
            case OrderPlacedEvent e -> readRepository.insertNewOrderRow(e.orderId(), e.customerName(), e.items());
            case OrderShippedEvent e -> readRepository.updateShippingStatus(e.orderId(), e.trackingNumber());
            case OrderCancelledEvent e -> readRepository.markCancelled(e.orderId());
            default -> { /* event type not relevant to THIS particular read model */ }
        }
    }
}
```

**Note the eventual consistency this introduces, explicitly:** a query against the read model immediately after a write may briefly not reflect that write yet, since the read model is updated asynchronously via the event stream — this is the same [read-your-writes](../../01-foundations/consistency-models/README.md#5-read-your-writes-consistency) consideration from Consistency Models, and a genuinely important thing to flag when proposing CQRS: **the write side is the source of truth for correctness; the read side is a (briefly-lagging) optimized view**, and any UI/UX depending on "see my own change immediately" needs a specific mitigation (e.g., optimistically updating the UI from the command response itself, rather than waiting for the read model to catch up).

---

## 6. Real-World Example: Event Sourcing in Banking and Financial Ledger Systems

Financial systems (and the double-entry ledger design in [Payment System](../../03-high-level-design/payment-system/README.md#3-component-deep-dive-the-double-entry-ledger)) are the most natural, commonly-cited real-world fit for event sourcing: regulatory and audit requirements in real banking systems generally **already mandate** an immutable, complete history of every transaction — which is precisely what an event-sourced ledger provides natively, rather than needing a bolted-on separate audit log alongside a traditional mutable-state model. Several publicly-documented banking and fintech engineering blogs describe using event sourcing specifically because "what is the account's current balance" and "show me every single event that ever affected this account, in order, for a regulator" are **both** first-class, natively-supported queries against the exact same underlying data — rather than requiring two separately-maintained systems (a current-state database plus a separate audit trail) that could, if not perfectly kept in sync, disagree with each other.

---

## 7. Common Pitfalls

- Adopting CQRS or Event Sourcing reflexively, for simple CRUD services with no genuine read/write divergence or audit requirement, and absorbing real complexity for no corresponding benefit.
- Forgetting to implement snapshotting for long-lived, high-event-count entities, leading to slow, ever-worsening reconstruction times as an entity accumulates history over months or years.
- Treating the CQRS read model as a second source of truth rather than a derived, replaceable projection — if it ever becomes inconsistent (a bug in a projector), the correct fix is **rebuilding it by replaying the event log from scratch**, not manually patching the read-model data directly (which risks it silently diverging permanently from what the events actually say happened).
- Not accounting for the read model's inherent lag when designing user-facing flows, leading to a "why doesn't my change show up immediately" bug class.

---

## 8. 60-Second Interview Answer

> "CQRS separates the model used for writes from the model used for reads, since the two often have genuinely conflicting optimal shapes — a normalized write model enforcing invariants versus a denormalized read model optimized for actual query patterns. Event Sourcing is a related but independent idea — storing every state-changing event as an immutable, append-only log, with current state computed by replaying those events, rather than storing a single mutable row that only reflects the latest value and loses the history of how it got there. They pair naturally because an event-sourced write side produces exactly the event stream a CQRS read-model projector needs to subscribe to and build its own independently-shaped view from. The two real engineering costs I'd flag: event replay gets slow for long-lived, high-event-count entities without periodic snapshotting, and the read model is inherently eventually consistent relative to the write side, so any UI needing to reflect a user's own change immediately needs an explicit mitigation rather than assuming the read model has caught up yet. I wouldn't reach for either pattern by default — they're justified specifically when read and write patterns genuinely diverge, or when a complete, immutable audit history is a real requirement, like in financial ledger systems."

**Related:** [Payment System](../../03-high-level-design/payment-system/README.md) · [Event-Driven Architecture](../event-driven-architecture/README.md) · [Database Replication](../../02-building-blocks/databases/replication/README.md) · [Consistency Models](../../01-foundations/consistency-models/README.md)
