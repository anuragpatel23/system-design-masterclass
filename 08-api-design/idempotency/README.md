# Idempotency — Making Retries Safe

> **The question this answers, precisely:** a client calls `POST /payments`, the request times out — did the charge happen? The client can't know, and *must* retry (see [Resilience Patterns](../../07-microservices/resilience-patterns/README.md)) — so how do you guarantee that retrying **cannot** double-charge? This is arguably the single most-probed API design topic in payment/commerce interviews, and it's the glue between retries, [at-least-once message delivery](../../02-building-blocks/message-queues/README.md), and correctness.

---

## 1. Why this is forced on you, not optional

The network gives a failed call **three indistinguishable outcomes**: the request never arrived; it arrived and failed; it arrived, **succeeded, and the response was lost**. Case 3 is the killer — from the client's view it's identical to the others, so the *only* safe client behavior is retry, and the *only* safe system is one where retries are harmless. Formally: **an operation is idempotent if performing it N times has the same effect as performing it once.** Every at-least-once delivery mechanism in this vault — HTTP retries, queue redeliveries, [saga](../../05-distributed-systems/saga-pattern/README.md) step re-executions — converts "exactly-once processing" into "at-least-once delivery + idempotent handling." That reframing is the senior sentence.

## 2. What's already idempotent, and what isn't

- **Idempotent by nature:** `GET`/`HEAD` (safe — no effect at all), `PUT /orders/456 {status: "cancelled"}` (absolute assignment — setting twice = setting once), `DELETE /orders/456` (deleted twice = deleted). Note idempotent ≠ same *response* (second DELETE may return 404) — it means same *state*.
- **Not idempotent:** `POST /orders` (creates a new resource each time), and **any relative update** — `UPDATE balance = balance - 100` applied twice removes 200. Two mitigations flow straight from this: prefer **absolute over relative updates** where possible (`SET balance = 400` vs `balance -= 100`), and where you can't (ledgers, counters), you need idempotency keys.

## 3. The idempotency-key pattern (the core mechanism)

The client generates a unique key per **logical operation** (UUID per checkout attempt — *not* per HTTP request; the retry reuses the same key) and sends it as a header: `Idempotency-Key: ck_7f3d...`.

Server-side, atomically:

```
1. INSERT the key into an idempotency table with state=IN_PROGRESS
   (UNIQUE constraint on key — this insert is the mutex)
2a. Insert succeeded  → you own it: execute the operation,
    store the response (status + body) against the key, mark COMPLETED
2b. Insert conflicted → someone already owns it:
    - COMPLETED  → return the STORED response verbatim (don't re-execute)
    - IN_PROGRESS → a concurrent duplicate is racing you:
      return 409/425 or wait — do NOT run the operation twice in parallel
```

The details that separate a working design from a hand-wave:

- **Atomicity via unique constraint.** Check-then-insert as two steps is a race — two concurrent retries both pass the check. The unique index makes the database the arbiter ([Transactions & ACID](../../06-databases-deep-dive/transactions-acid/README.md)).
- **Store the full response**, not just "done" — the retry must receive *what the original would have returned* (the created payment ID), or the client still can't make progress.
- **The IN_PROGRESS state matters:** the classic bug is handling sequential retries but letting two *concurrent* duplicates both execute.
- **Key + request-hash:** store a hash of the request body with the key; same key with *different* body ⇒ `422` — catches client bugs reusing keys.
- **Scope and TTL:** keys are scoped per API-key/user (one tenant can't collide with another) and expire (e.g., Stripe: 24h) — the table isn't an infinite ledger.
- **Same-database transaction:** ideally the idempotency record and the business write commit in **one ACID transaction**, so you can never record COMPLETED without the effect or vice versa. When the effect is in *another* system (a downstream charge API), you're in distributed-transaction territory: pass the same idempotency key downstream (Stripe accepts one!) and/or use the **outbox pattern** ([Event-Driven Architecture](../../05-distributed-systems/event-driven-architecture/README.md)) so effect and record share a commit.

## 4. The consumer-side twin: dedup under at-least-once delivery

Queue consumers face the same problem inverted: the broker redelivers ([Message Queues](../../02-building-blocks/message-queues/README.md)), so handlers must dedup on a **message/event ID** — same unique-constraint pattern, often called an *inbox table* — or be naturally idempotent (absolute writes, upserts). "Exactly-once" end-to-end is at-least-once + idempotent consumption; saying that sentence when queues come up is reliably a strong signal.

## 5. Real-world reference: Stripe

Stripe's `Idempotency-Key` header is the canonical implementation and worth citing precisely: keys are per-API-key scoped, stored ~24h, replay returns the **original response verbatim** (including errors), reuse with a different body is rejected, and keys are honored *even for requests that failed server-side* — the semantics above, shipped as a product. Square, PayPal, and most payment APIs have equivalents; **DynamoDB/many DBs offer conditional writes** (`PutItem` with `attribute_not_exists`) as the same unique-arbiter primitive in NoSQL form.

## 6. Common pitfalls

- Generating the key per HTTP attempt instead of per logical operation — the retry gets a fresh key and duplicates anyway. (The key must be created *before* the first attempt and survive client crashes — persisted client-side if the stakes justify it.)
- Check-then-act without a unique constraint — TOCTOU race; two concurrent retries both execute.
- Returning "duplicate" errors to retries instead of the stored original response — safe but useless; the client needed the payment ID.
- Dedup memory shorter than the retry horizon (queue redeliveries can arrive hours later — align inbox TTL with max redelivery window).
- Believing a broker's "exactly-once" flag removes the need for consumer idempotency across system boundaries.

## 7. 60-Second Interview Answer

> "A timed-out write has three indistinguishable outcomes for the client — never arrived, failed, or succeeded with the response lost — so clients must retry, and the system must make retries harmless: that's idempotency. GETs, PUTs and DELETEs are idempotent by contract; POSTs and relative updates like balance-minus-100 are not, so for anything that creates or moves value I use idempotency keys: the client generates a UUID per logical operation — the retry reuses it — and the server inserts it into a table with a unique constraint as the atomic arbiter. If the insert wins, execute and store the full response against the key; if it conflicts and the original completed, return that stored response verbatim so the retry still gets its payment ID; if it's still in progress, block or 409 rather than execute concurrently. Details that matter: hash the request body to reject same-key-different-payload, scope keys per user with a TTL, and commit the idempotency record and the business write in one transaction — or use the outbox pattern when the effect crosses systems. The same pattern inverted is consumer dedup under at-least-once queue delivery — 'exactly-once' in practice means at-least-once delivery plus idempotent handling. Stripe's Idempotency-Key header is the canonical reference: 24-hour keys, verbatim replay, mismatched-body rejection."

**Related:** [Resilience Patterns](../../07-microservices/resilience-patterns/README.md) · [Message Queues](../../02-building-blocks/message-queues/README.md) · [Saga Pattern](../../05-distributed-systems/saga-pattern/README.md) · [Payment System](../../03-high-level-design/payment-system/README.md) · [Transactions & ACID](../../06-databases-deep-dive/transactions-acid/README.md)
