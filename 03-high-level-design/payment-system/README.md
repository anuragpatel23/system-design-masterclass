# Design a Payment System

> **The one hard problem this really tests:** moving money correctly, exactly once, across unreliable networks and unreliable downstream processors — where "unreliable" doesn't just mean "might be slow," it means "might charge a customer twice, or not tell you whether it succeeded at all." This is the one system in this vault where **correctness dominates every other concern**, including latency and even availability.

---

## 1. Requirements

### Functional
- Charge a customer's payment method (card, bank transfer, wallet) for an order amount.
- Support refunds.
- Maintain an auditable, immutable record of every money movement.
- Support multiple payment processors (e.g., Stripe, a bank's own rails) with failover between them.

### Non-Functional
- **Idempotency is non-negotiable** — a network timeout on a charge request must never result in a customer being charged twice, even if the client retries the exact same request.
- **No silent money loss or duplication** — every cent must be accounted for; the system must be auditable after the fact (a regulatory as well as an engineering requirement in real payment systems).
- **Strong consistency for balance/ledger operations** — this is one of the clearest real-world CP choices in this entire vault (see [CAP Theorem](../../01-foundations/cap-theorem/README.md)): an unavailable payment system for a few seconds during a partition is a far better outcome than an available-but-wrong one that double-charges or loses money.
- **Reconciliation** — since payment processors are external, sometimes-unreliable systems, the design must include a way to detect and correct discrepancies after the fact, not just prevent them perfectly upfront (because perfect prevention across an external, third-party system is not realistically achievable).

---

## 2. Component Deep Dive: Idempotency Keys (the single most important concept in this design)

The fundamental unreliable-network problem: a client sends a "charge $50" request; the network drops the response before the client sees it; the client, not knowing whether the charge succeeded, must decide whether to retry. **If retrying just re-executes "charge $50" again, and the original request actually did succeed server-side, the customer is now charged $100.**

**The solution:** every charge request carries a client-generated **idempotency key** — a unique identifier for "this logical charge attempt," generated once by the client and reused on every retry of the *same* logical attempt (not regenerated per retry).

```
Client generates idempotencyKey = UUID (once, per logical charge attempt)
                │
        POST /charges { amount: 50, idempotencyKey: "abc-123" }
                │
        Server: has this idempotencyKey been seen before?
                │
        ┌───────┴────────┐
        │ NO               │ YES
        ▼                  ▼
   Process the charge   Return the ORIGINAL result (whatever it was --
   normally; store the  success or failure) WITHOUT re-processing the
   result keyed by       charge again. The retry is a no-op from the
   idempotencyKey        customer's money's perspective, even though it's
                          a completely normal, expected HTTP call.
```

This is the same [at-least-once + idempotent consumer](../../02-building-blocks/message-queues/README.md#2-delivery-guarantees--the-precise-definitions) pattern that recurs throughout this vault, applied here at its highest-stakes instance: the "duplicate" being prevented is literally a duplicate real-money charge, not a duplicate log line or a duplicate "like."

**A critical, often-missed detail:** the idempotency key check and the actual charge processing **must be atomic** — storing "I've seen this key" and actually performing the charge cannot be two separate, non-transactional steps, or a race between two near-simultaneous retries (both arriving before either has recorded the key) could still double-process. This is typically implemented via a unique database constraint on the idempotency key column combined with a single transaction that both checks-and-inserts the key AND performs the charge, so the database itself rejects a concurrent duplicate at the constraint level.

---

## 3. Component Deep Dive: The Double-Entry Ledger

Rather than simply storing a single "balance" number per account (which can drift, be corrupted by a bug, or become impossible to audit after the fact), a serious payment system uses a **double-entry ledger** — the same fundamental technique used in traditional accounting for centuries: **every transaction is recorded as at least two balanced entries — a debit from one account and a credit to another, always summing to zero.**

```
Example: customer pays $50 for an order.

Ledger entries for this ONE transaction:
  DEBIT   customer_payment_method_account   $50
  CREDIT  merchant_receivable_account       $50
  (sum: 0 -- money is not created or destroyed, only moved between accounts)
```

- **Why this matters over a simple balance column:** a corrupted or buggy balance update is immediately detectable, because the ledger's debits and credits must always sum to zero across the whole system — an imbalance is a clear, mechanically-detectable signal that something is wrong, rather than a silent drift that's only noticed when a customer complains.
- **Immutability:** ledger entries are **never updated or deleted** — a correction (e.g., a refund) is recorded as a **new, additional set of balanced entries** reversing the original, not an edit to the original record. This gives a complete, permanent audit trail — a genuine regulatory requirement in real financial systems, and a strong signal of senior-level awareness even in an interview setting where the actual regulation doesn't apply.
- **Balance as a derived value, not a stored one (or a cached derived value):** an account's current balance is conceptually the sum of all its ledger entries — for performance, this is often maintained as a cached, periodically-reconciled running total (an application of [Caching](../../02-building-blocks/caching/README.md), where the ledger entries are the source of truth and the balance is a materialized, cache-aside-style derived value), rather than recomputing a full sum on every balance check.

---

## 4. High-Level Design

```
  Client ──POST /charges (with idempotencyKey)──▶ Payment API
                                                        │
                                          Atomic check-and-insert of
                                          idempotencyKey (unique DB
                                          constraint) -- if already
                                          exists, return the STORED
                                          prior result immediately,
                                          skip everything below
                                                        │
                                          Begin DB transaction:
                                          write PENDING ledger entries
                                          (debit/credit pair)
                                                        │
                                          Call external Payment
                                          Processor (Stripe-like)
                                          to actually move the money
                                                        │
                          ┌─────────────────────────────┴────────────────────────┐
                          │ processor confirms SUCCESS                              │ processor confirms FAILURE
                          │                                                          │ or TIMES OUT (ambiguous!)
                          ▼                                                          ▼
              Update ledger entries to                                  Update ledger entries to FAILED,
              CONFIRMED; commit transaction                             OR mark PENDING for later
                                                                         reconciliation if the processor's
                                                                         response was genuinely ambiguous
                                                                         (timeout, not a clear failure) --
                                                                         see Reconciliation below
```

---

## 5. Component Deep Dive: Handling Ambiguous Processor Responses (Timeouts)

The hardest real-world case: the request to the external payment processor **times out** — you genuinely don't know if the charge succeeded on their side or not. This is a fundamental, unavoidable consequence of calling an external system over a network (see [Availability & Reliability](../../01-foundations/availability-reliability/README.md) — this is a live instance of "the network can and will fail in ambiguous ways").

**The correct handling:**
1. **Never blindly retry a payment charge on ambiguous timeout** — if the original request actually succeeded upstream, a naive retry (even with a new idempotency key, or worse, without one) risks a real double-charge on the processor's side.
2. Instead, **use the processor's own idempotency key support** (major real payment processors support this exact mechanism specifically because this problem is universal) — pass the same idempotency key to the processor itself on any retry, so that even if your own request to them is retried, the processor's own systems recognize it as the same logical attempt and don't double-process it on their end either.
3. Mark the transaction as **PENDING/UNKNOWN** and rely on an asynchronous **reconciliation process** — periodically querying the processor's own transaction status API for any transactions your system has marked as ambiguous/pending, and updating your ledger to match the processor's authoritative record once it's available, rather than guessing.

**Reconciliation is not an optional add-on — it's a core, load-bearing part of the design**, precisely because perfect real-time certainty about an external system's state is not achievable, and a payment system must have an explicit, designed answer for "what happens when we genuinely don't know," rather than treating it as an edge case to handle later.

---

## 6. Data Model

```sql
CREATE TABLE charges (
    charge_id         BIGINT PRIMARY KEY,
    idempotency_key   VARCHAR(64) UNIQUE NOT NULL,  -- the core correctness mechanism
    customer_id       BIGINT NOT NULL,
    amount_cents      BIGINT NOT NULL,               -- always integer cents, NEVER floating point money
    status            ENUM('PENDING','CONFIRMED','FAILED','UNKNOWN_NEEDS_RECONCILIATION'),
    processor_ref     VARCHAR(100) NULL,              -- external processor's own transaction ID
    created_at        TIMESTAMP
);

CREATE TABLE ledger_entries (
    entry_id      BIGINT PRIMARY KEY,
    charge_id     BIGINT NOT NULL,
    account_id    BIGINT NOT NULL,
    entry_type    ENUM('DEBIT','CREDIT'),
    amount_cents  BIGINT NOT NULL,
    created_at    TIMESTAMP
    -- Immutable: rows are NEVER updated after insert. Corrections are new rows.
);
```

**Why integer cents, not floating point:** floating-point arithmetic has well-known rounding/precision errors that are unacceptable in a system tracking exact currency amounts — this is a small but important, frequently-checked detail in senior interviews.

---

## 7. API Design

```
POST /api/v1/charges
  Headers: Idempotency-Key: abc-123
  Request: { "customerId": "...", "amountCents": 5000, "currency": "USD" }
  Response: { "chargeId": "...", "status": "CONFIRMED" }
  -- Retrying with the SAME Idempotency-Key header returns the same stored
  -- result, never re-processes the charge.

POST /api/v1/refunds
  Headers: Idempotency-Key: def-456
  Request: { "chargeId": "...", "amountCents": 5000 }
  -- Refunds are modeled as their OWN new, balanced ledger entries reversing
  -- the original charge -- never as an edit/deletion of the original entries.
```

---

## 8. Trade-offs & Follow-Up Questions to Anticipate

| Follow-up | Strong answer direction |
|---|---|
| "What if the idempotency key store itself is unavailable?" | This should fail CLOSED (reject the request) rather than open, unlike the rate-limiter example in [Rate Limiting](../../02-building-blocks/rate-limiting/README.md#5-spring-boot-example-distributed-token-bucket-rate-limiter-with-redis-fail-open-fallback) — here, allowing a charge through without idempotency protection risks real financial harm, which is a worse outcome than temporary unavailability. |
| "How do you support multiple payment processors with failover?" | Each processor call is still keyed by the same idempotency key; on a processor-level outage, failing over to a secondary processor for a NEW charge attempt is fine, but a request already in flight to processor A that times out should NOT simply be retried against processor B, since you don't know if A actually succeeded — this needs the same UNKNOWN/reconciliation handling, resolved against processor A specifically. |
| "How do you handle currency conversion?" | Store the ledger entries in a consistent base currency (or explicitly tag each entry with its currency and exchange rate at time of transaction) — never silently convert without recording the rate used, since that rate itself is part of the auditable history. |
| "How would you test this without charging real money?" | Payment processors universally provide sandbox/test modes with the exact same idempotency semantics — a system-design-relevant point is designing the payment processor integration behind an interface/abstraction so sandbox vs production processor endpoints are a configuration change, not a code change. |

---

## 9. 60-Second Interview Answer

> "This is the one system where correctness has to dominate every other concern, including availability — a payment system being briefly unavailable during a partition is a far better outcome than one that's available but double-charges someone. The core mechanism is an idempotency key, generated once per logical charge attempt by the client and checked atomically against a unique constraint before any processing happens, so retries of the same logical attempt are safe no-ops rather than duplicate charges. I'd model money movement as a double-entry ledger — every transaction recorded as balanced debit and credit entries that always sum to zero — rather than a single mutable balance column, because that makes corruption or bugs mechanically detectable and gives a complete, immutable audit trail; corrections like refunds are new balancing entries, never edits to history. The hardest real case is a processor call that times out ambiguously — I wouldn't blindly retry that; I'd pass the same idempotency key to the processor itself, mark the transaction as pending, and resolve it through an asynchronous reconciliation process against the processor's own authoritative transaction record, since perfect real-time certainty about an external system isn't achievable."

**Related:** [CAP Theorem](../../01-foundations/cap-theorem/README.md) · [Message Queues](../../02-building-blocks/message-queues/README.md) · [Consistency Models](../../01-foundations/consistency-models/README.md) · [Transactions & ACID](../../06-databases-deep-dive/transactions-acid/README.md)
