# Saga Pattern

> **The practical answer to "how do you get correctness across microservices without 2PC's blocking problem."** A saga breaks a distributed transaction into a sequence of **local transactions**, each committed independently within its own service, with an explicit **compensating transaction** defined for every step to semantically "undo" it if a later step in the sequence fails. This is the pattern referenced throughout this vault's HLD designs — [Payment System](../../03-high-level-design/payment-system/README.md), [Hotel Booking](../../03-high-level-design/hotel-booking/README.md) — wherever a business operation spans multiple independently-owned services.

---

## 1. The Core Idea

```
Instead of ONE atomic distributed transaction across services A, B, C:

  A.commit() ── B.commit() ── C.commit()      <- 2PC tries to make this all-or-nothing

A saga does this instead:

  A.commit() ── B.commit() ── C.commit()      <- happy path: each step is its OWN
                                                   local ACID transaction, committed
                                                   independently and immediately

  If C fails:
  A.commit() ── B.commit() ── C.FAILS
                    │
              B.compensate()   <- undo B's effect
                    │
              A.compensate()   <- undo A's effect
                                   (in REVERSE order of the original steps)
```

**The fundamental trade-off being made, stated precisely:** a saga gives up the *atomicity* guarantee (there is a real window of time where A and B have committed but C hasn't yet — the system is observably in an intermediate, "in-progress" state) in exchange for **not requiring any participant to hold locks across a network round trip**, and for **each service remaining independently available** — no coordinator can block the whole operation by crashing. This is a direct, deliberate instance of choosing eventual consistency (per [Consistency Models](../../01-foundations/consistency-models/README.md)) over strict, instantaneous consistency, specifically because instantaneous cross-service consistency (2PC) has an unacceptable availability cost at real production scale.

---

## 2. Two Implementation Styles: Choreography vs Orchestration

### Choreography (Event-Driven, No Central Coordinator)
Each service publishes an event when its local transaction completes; other services subscribe to the events relevant to them and react by performing their own local transaction (and publishing their own resulting event).

```
Order Service: creates order (PENDING) ──▶ publishes "OrderCreated"
                                                    │
Inventory Service (subscribes to "OrderCreated"):
   reserves stock ──▶ publishes "StockReserved" (or "StockReservationFailed")
                                                    │
Payment Service (subscribes to "StockReserved"):
   charges customer ──▶ publishes "PaymentCompleted" (or "PaymentFailed")
                                                    │
Order Service (subscribes to "PaymentCompleted"):
   marks order CONFIRMED

-- IF PaymentFailed is published instead --
Inventory Service (subscribes to "PaymentFailed"):
   releases the reserved stock (COMPENSATING action)
Order Service (subscribes to "PaymentFailed"):
   marks order CANCELLED
```

- **Pros:** no single point of coordination/failure; each service only needs to know about the events it cares about, not the whole saga's shape — genuinely loosely coupled, directly building on the [pub/sub pattern](../../02-building-blocks/message-queues/README.md#1-messaging-patterns) from Message Queues.
- **Cons:** as the number of steps grows, **the overall saga's logic becomes implicit, scattered across every participating service's event handlers** — there's no single place to look to understand "what does the whole order-placement flow actually do," making the system harder to reason about, test, and debug (a genuinely important, frequently-raised drawback in real production post-mortems).

### Orchestration (a Central Saga Orchestrator)
A dedicated orchestrator component explicitly calls each service in sequence (or triggers each step and waits for confirmation), and is exclusively in charge of running compensating actions in reverse order if any step fails.

```
                     ┌─────────────────────────┐
                     │   Saga Orchestrator      │
                     │  (explicit state machine  │
                     │   of the WHOLE saga)      │
                     └────────────┬─────────────┘
        1. reserve stock ─────────┤
        (Inventory Service)        │
                                   │  on success, continue
        2. charge payment ────────┤
        (Payment Service)          │
                                   │  on FAILURE:
                                   ▼
                     Orchestrator explicitly calls
                     compensating actions in REVERSE:
                       - refund payment (if it had succeeded)
                       - release reserved stock
                       - mark order CANCELLED
```

- **Pros:** the entire saga's logic lives in **one place** — readable, testable, and debuggable as a single unit; this single orchestrator is also the natural place to add cross-cutting concerns like timeouts, retries, and monitoring for the whole flow.
- **Cons:** the orchestrator becomes a real, additional service that must itself be highly available (though notably, unlike a 2PC coordinator, **the orchestrator failing doesn't leave participants holding locks** — each participant's local transaction already fully committed or didn't; the orchestrator's job is only to decide and trigger the *next* step, and it can safely resume from persisted state after a restart, discussed below) — and it does introduce a degree of central coupling, since the orchestrator needs to know about every participating service.

**Senior-level answer on which to choose:** **orchestration is generally the more maintainable choice for sagas with more than a small handful of steps or non-trivial branching/compensation logic**, precisely because the "logic scattered across services" drawback of choreography becomes a genuine operational and cognitive burden as complexity grows — while choreography remains attractive for genuinely simple, small-step-count flows where the loose coupling benefit outweighs the loss of centralized visibility. Stating this trade-off explicitly, rather than declaring one universally superior, is exactly the kind of evenhanded, experience-grounded answer a staff-level interview is listening for.

---

## 3. Component Deep Dive: Making the Orchestrator's State Durable and Resumable

If the orchestrator crashes mid-saga (after step 1 committed, before step 2 has been triggered), it must be able to **resume exactly where it left off** after restart — this requires the orchestrator to persist its own state (which steps have completed, in what order) durably, typically in its own database, **updated as part of each step's local transaction handling**, not held only in memory.

```java
public class OrderSagaOrchestrator {

    private final SagaStateRepository sagaStateRepository; // durable persistence of saga progress
    private final InventoryServiceClient inventoryClient;
    private final PaymentServiceClient paymentClient;
    private final OrderRepository orderRepository;

    public void startSaga(OrderRequest request) {
        SagaState state = new SagaState(request.getOrderId(), SagaStep.STARTED);
        sagaStateRepository.save(state); // persist BEFORE attempting the first step

        try {
            executeReserveStock(state, request);
        } catch (Exception e) {
            compensate(state); // begin unwinding from wherever we got to
        }
    }

    private void executeReserveStock(SagaState state, OrderRequest request) {
        inventoryClient.reserveStock(request.getSku(), request.getQty());
        state.markCompleted(SagaStep.STOCK_RESERVED);
        sagaStateRepository.save(state); // persist progress -- critical for resumability
        executeChargePayment(state, request);
    }

    private void executeChargePayment(SagaState state, OrderRequest request) {
        try {
            // Idempotency key per the Payment System HLD -- this call is itself
            // safe to retry, since the orchestrator might crash and resume here.
            paymentClient.charge(request.getPaymentDetails(), state.getIdempotencyKey());
            state.markCompleted(SagaStep.PAYMENT_CHARGED);
            sagaStateRepository.save(state);
            orderRepository.markConfirmed(request.getOrderId());
        } catch (PaymentFailedException e) {
            compensate(state); // roll back everything completed SO FAR, in reverse
        }
    }

    // Compensation runs in REVERSE order of completed steps -- this is the
    // core semantic of a saga's "rollback."
    private void compensate(SagaState state) {
        if (state.isCompleted(SagaStep.PAYMENT_CHARGED)) {
            paymentClient.refund(state.getOrderId());
        }
        if (state.isCompleted(SagaStep.STOCK_RESERVED)) {
            inventoryClient.releaseStock(state.getOrderId());
        }
        orderRepository.markCancelled(state.getOrderId());
        state.markCompensated();
        sagaStateRepository.save(state);
    }

    // Called by a recovery process on orchestrator startup -- finds any sagas
    // that were IN PROGRESS when the orchestrator last crashed, and resumes
    // (or compensates) them based on exactly how far they'd gotten.
    public void recoverInFlightSagas() {
        sagaStateRepository.findAllInProgress().forEach(state -> {
            if (state.isCompleted(SagaStep.STOCK_RESERVED) && !state.isCompleted(SagaStep.PAYMENT_CHARGED)) {
                // resume from exactly the next un-executed step, using the
                // PERSISTED state, not assuming anything from in-memory context
                executeChargePayment(state, state.getOriginalRequest());
            }
            // ... additional resumption branches per possible persisted state
        });
    }
}
```

**Why persisting state after every single step, not just at the start/end, is the crux of correctness here:** without this, an orchestrator crash mid-saga would have no way to know whether a given step actually completed before the crash — it might re-execute an already-completed step (a duplicate charge!) or fail to compensate a step that did complete. This directly connects to the [idempotency](../../03-high-level-design/payment-system/README.md#2-component-deep-dive-idempotency-keys-the-single-most-important-concept-in-this-design) principle from the Payment System design: **every individual step call in a saga should itself be idempotent**, precisely because the orchestrator's own crash-recovery logic may legitimately re-invoke a step it's unsure completed.

---

## 4. Compensating Transactions Are Not Always a Perfect Inverse

A crucial, often-missed nuance: a compensating transaction **semantically undoes** a step's business effect — it is not always a literal database rollback, and sometimes **cannot** perfectly restore the exact prior state:

- "Release reserved stock" is a clean, near-perfect inverse of "reserve stock."
- "Refund a payment" is **not** a perfect inverse of "charge a payment" — it's a *new*, separate transaction (see the [double-entry ledger](../../03-high-level-design/payment-system/README.md#3-component-deep-dive-the-double-entry-ledger) principle: a refund is additional balancing entries, never an undo of the original charge record) that may itself take days to actually settle on the customer's statement, and may incur its own fees.
- Some steps are **not compensable at all** — e.g., "sent a confirmation email" can't be un-sent. The correct handling for a non-compensable step is either to **sequence it last** in the saga (after every genuinely risky/failure-prone step has already succeeded, minimizing the chance it needs to be "undone") or to accept the residual risk explicitly (e.g., send a follow-up "please disregard the previous email" notification instead of pretending the original can be erased).

**This nuance — that compensation is a business-semantic reversal, not a literal undo, and that some actions are irreversible — is exactly the kind of subtlety a staff-level interviewer is listening for**, and naming it unprompted (e.g., when designing the [Payment System](../../03-high-level-design/payment-system/README.md) or [Hotel Booking](../../03-high-level-design/hotel-booking/README.md) sagas) is a strong signal.

---

## 5. Real-World Example: Uber's Saga-Based Trip Lifecycle (Illustrative)

A ride-hailing trip lifecycle ([Uber](../../03-high-level-design/uber/README.md)) is a natural saga: "match a driver" → "start the trip" → "process payment" → "rate the trip," spanning multiple independently-scaled services (matching, trip management, payment, ratings). If payment fails after a trip has completed, the system can't "un-drive" the trip (a physically irreversible action, much like the "unsend an email" example above) — a real production system in this shape must instead route the failure to a **collections/retry process** (attempt the charge again later, potentially with a different payment method on file) rather than modeling it as a symmetric, fully-compensable rollback. This is a concrete, realistic illustration of the Section 4 nuance: **not every saga step has a clean compensating action, and the design has to explicitly account for the steps that don't**, rather than assuming every real-world saga looks as cleanly reversible as the inventory/payment textbook example.

---

## 6. Common Pitfalls

- Assuming a saga gives the same atomicity guarantee as a single-node ACID transaction — it explicitly does not; there's a real, observable window where the system is in a partially-completed state, and any code reading data during that window must be written with that in mind (e.g., an order showing as "processing" rather than either fully "confirmed" or entirely absent).
- Forgetting to make each individual saga step idempotent — since orchestrator crash-recovery (or choreography's at-least-once event delivery) can cause a step to be invoked more than once.
- Treating compensation as always a literal, perfect inverse of the original action — some actions need a different, business-appropriate compensating action (a refund, not an "un-charge"), and some aren't compensable at all.
- Choosing choreography for a saga with many steps and complex branching, ending up with saga logic implicitly scattered and hard to reason about across many services' event handlers.

---

## 7. 60-Second Interview Answer

> "A saga replaces one distributed transaction with a sequence of local transactions, each committed independently, plus an explicit compensating action per step to undo it if a later step fails — trading the instantaneous atomicity of something like Two-Phase Commit for eventual consistency achieved through compensation, specifically to avoid 2PC's coordinator-blocking risk. I'd implement it via orchestration rather than choreography once there's more than a handful of steps or any real branching logic, since choreography's event-driven, no-central-coordinator style is elegant for simple flows but scatters the overall saga's logic across every participating service's event handlers, making it hard to reason about as a whole. The orchestrator has to persist its progress after every single step, not just at the start and end, so it can correctly resume or compensate after its own crash — and every individual step needs to be idempotent, since that crash-recovery logic might re-invoke a step it's not certain completed. I'd also flag that compensation is a business-semantic reversal, not always a literal undo — refunding a payment is a new transaction, not an erasure of the original charge, and some actions, like a trip that's already been driven, aren't compensable at all and need a different failure-handling path entirely."

**Related:** [Distributed Transactions](../distributed-transactions/README.md) · [Message Queues](../../02-building-blocks/message-queues/README.md) · [Payment System](../../03-high-level-design/payment-system/README.md) · [Consistency Models](../../01-foundations/consistency-models/README.md)
