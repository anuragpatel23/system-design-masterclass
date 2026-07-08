# The Trade-off Cheat Sheet — One Page Before You Walk In

> **What this is:** the recurring trade-off axes behind nearly every system design decision, each with the one-sentence framing that scores. Re-read the morning of the interview. The meta-rule first: **a design decision stated without its cost is not a decision, it's a preference** — the scoring sentence shape is always *"X buys us A at the cost of B, which is right here because C."*

---

## The core axes

| Trade-off | The one-liner | Deep dive |
|---|---|---|
| **Consistency vs Availability** (under partition) | "Per data type, not per system: the ledger is CP, the last-seen timestamp is AP." | [CAP](../01-foundations/cap-theorem/README.md), [Consistency Models](../01-foundations/consistency-models/README.md) |
| **Latency vs Consistency** (no partition needed) | "Even healthy systems pay latency for coordination — sync replication and quorums cost round trips on every write." | [Consistency Models](../01-foundations/consistency-models/README.md), [Replication](../02-building-blocks/databases/replication/README.md) |
| **Latency vs Throughput** | "Batching raises throughput and p99 together; and I optimize p99, not average, because averages hide the users who leave." | [Latency vs Throughput](../01-foundations/latency-vs-throughput/README.md) |
| **Read-optimized vs Write-optimized** | "Precompute on write (fan-out, materialized views, B-Trees) or compute on read (fan-in, LSM) — pick per access pattern." | [Twitter fan-out](../03-high-level-design/twitter-feed/README.md), [B-Tree vs LSM](../06-databases-deep-dive/b-trees-lsm-trees/README.md) |
| **Freshness vs Efficiency** (caching) | "Every cache is a bet that staleness costs less than recomputation — the TTL is the price tag on that bet." | [Caching](../02-building-blocks/caching/README.md), [CDN](../02-building-blocks/cdn/README.md) |
| **Sync vs Async** | "Synchronous is simple and coupled; async decouples availability and absorbs bursts at the cost of eventual results, ordering questions, and duplicate delivery — which then forces idempotency." | [Message Queues](../02-building-blocks/message-queues/README.md), [Idempotency](../08-api-design/idempotency/README.md) |
| **Normalization vs Denormalization** | "Normalize for write integrity, denormalize for read speed; at scale you usually denormalize and pay with async consistency maintenance." | [SQL vs NoSQL](../02-building-blocks/databases/sql-vs-nosql/README.md), [CQRS](../05-distributed-systems/cqrs-event-sourcing/README.md) |
| **Strong guarantees vs Scale-out simplicity** (2PC vs Saga) | "2PC buys atomicity and pays with blocking on a coordinator; sagas buy availability and pay with compensation logic and intermediate states being visible." | [Distributed Transactions](../05-distributed-systems/distributed-transactions/README.md), [Saga](../05-distributed-systems/saga-pattern/README.md) |
| **Monolith vs Microservices** | "An organizational trade: independent deployability, bought with the distributed-systems tax." | [Monolith vs Microservices](../07-microservices/monolith-vs-microservices/README.md) |
| **Build-time vs Run-time flexibility** | "Feature flags and config buy instant control and pay with combinatorial state and flag debt." | [Deployment Patterns](../07-microservices/deployment-patterns/README.md) |
| **Cost vs Everything** | "Every nine of availability and every millisecond of p99 has a dollar figure; naming the budget is a senior move." | [Availability & Reliability](../01-foundations/availability-reliability/README.md), [Serverless](../07-microservices/serverless/README.md) |

## The recurring "pick two-ish" specials

- **Exactly-once delivery doesn't exist across system boundaries** — at-least-once + [idempotent consumption](../08-api-design/idempotency/README.md) is how "effectively once" is manufactured.
- **The registry/coordination CP-vs-AP split:** locks and leader election must be CP; [service discovery](../07-microservices/service-discovery/README.md) is better AP — same infrastructure category, opposite correct answers.
- **Hot-key/celebrity problems:** any "partition by X" scheme inherits X's skew — the follow-up is always "what about Justin Bieber?" ([Sharding](../02-building-blocks/databases/sharding/README.md), [Twitter](../03-high-level-design/twitter-feed/README.md)).
- **Retries make things worse before they make things better** — amplification vs recovery; budgets and jitter are the dial ([Resilience](../07-microservices/resilience-patterns/README.md)).
- **Every queue is a debt instrument:** it absorbs bursts by *borrowing time* — if sustained input > output, the backlog is the invoice ([backpressure](../02-building-blocks/message-queues/README.md)).

## Sentence templates that score

- Choosing: "Given [requirement number], I'll take X — it costs us B, acceptable because C."
- Hedging honestly: "Both work; X wins if [condition], Y wins if [other condition]. Given our assumption of Z, X."
- Deferring: "That's a real problem; flagging it and handling it in the deep-dive so we keep momentum."
- Self-critique (closing): "The weakest point of this design is X — at 10x scale I'd revisit it with Y."

**Related:** [Interview Framework](interview-framework.md) · [Capacity Estimation](capacity-estimation.md) · [Question Bank](question-bank.md)
