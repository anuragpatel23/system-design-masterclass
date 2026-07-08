# The RESHADED Framework — Running a System Design Round

> **What this is:** a minute-by-minute structure for any "design X" question in a 45–60 minute round. Every design in [03 — High-Level Design](../03-high-level-design/README.md) follows this skeleton, so drilling those designs *is* drilling this framework. RESHADED: **R**equirements, **E**stimation, **S**torage/data model, **H**igh-level design, **A**PI, **D**etailed design (deep dives), **E**valuation, **D**istinctive features/wrap-up. (The exact letters matter less than the phase discipline — several equivalent frameworks exist; what's scored is that you *have* one.)

---

## Phase 0 — Before you draw anything (minute 0–2)

Restate the problem in one sentence and confirm it. "Design WhatsApp" can mean 1:1 chat, groups, calls, statuses — the interviewer usually has a subset in mind and *deliberately leaves it ambiguous to see if you scope*. Jumping straight to boxes is the single most common failure in these rounds.

## Phase 1 — Requirements (minutes 2–8)

- **Functional:** 3–5 core features, spoken as user actions ("send 1:1 message, see delivery/read status, sync across devices"). Explicitly park the rest: "I'll treat group chat and calls as out of scope unless you'd like them in."
- **Non-functional — as numbers and postures, not adjectives:** scale ("500M DAU, ~100B messages/day?" — propose numbers and let the interviewer correct), latency targets (p99, not average — [Latency vs Throughput](../01-foundations/latency-vs-throughput/README.md)), availability target ([how many nines, and what each costs](../01-foundations/availability-reliability/README.md)), consistency needs per data type ([Consistency Models](../01-foundations/consistency-models/README.md) — "message order within a chat must be consistent; last-seen timestamps can be eventual"), durability ("can we ever lose a message? no").
- The per-data-type consistency sentence is a reliable senior signal — systems don't have one consistency requirement, their *data types* do.

## Phase 2 — Estimation (minutes 8–13)

Run the [capacity-estimation method](capacity-estimation.md): DAU → QPS (with peak multiplier) → read/write ratio → storage/year → bandwidth → cache working set. **End with implications, or it was theater:** "60k writes/sec rules out a single primary — we shard; 5PB/year means object storage for media with DB metadata only; 1:100 write:read ratio says cache-heavy read path."

## Phase 3 — API + data model (minutes 13–18)

- Sketch 3–5 core endpoints with request/response shapes ([REST Best Practices](../08-api-design/rest-best-practices/README.md)) — or state why this boundary is [WebSocket/gRPC](../08-api-design/websockets/README.md) instead.
- Core entities, their key fields, and **the access patterns** — because access patterns choose the database ([SQL vs NoSQL](../02-building-blocks/databases/sql-vs-nosql/README.md)), the [shard key](../02-building-blocks/databases/sharding/README.md), and the [indexes](../06-databases-deep-dive/indexing-strategies/README.md). Say the causality out loud: "we always read messages by (chat_id, time range), so chat_id is the partition key."

## Phase 4 — High-level design (minutes 18–28)

Draw the block diagram: client → [CDN](../02-building-blocks/cdn/README.md)/[gateway](../02-building-blocks/api-gateway/README.md) → [LB](../02-building-blocks/load-balancers/README.md) → services → [cache](../02-building-blocks/caching/README.md) → DB (+ [queue](../02-building-blocks/message-queues/README.md) for async paths). **Walk one request end-to-end through the diagram, narrating** — a diagram you don't walk is just decoration. Justify each component with one trade-off sentence as you place it.

## Phase 5 — Deep dives (minutes 28–40)

The interviewer picks a component ("how exactly does the fan-out work?") or you offer: "the interesting problems here are X and Y — which would you like?" This is where sections [05](../05-distributed-systems/README.md)–[08](../08-api-design/README.md) get spent. Standard deep-dive triggers and where they're covered: hot keys/celebrities ([Twitter fan-out](../03-high-level-design/twitter-feed/README.md)), exactly-once/dedup ([Idempotency](../08-api-design/idempotency/README.md)), cross-service consistency ([Sagas](../05-distributed-systems/saga-pattern/README.md)), cache invalidation ([Caching](../02-building-blocks/caching/README.md)), "what if this node dies" ([Replication](../02-building-blocks/databases/replication/README.md), [Leader Election](../05-distributed-systems/leader-election/README.md)).

## Phase 6 — Evaluation & wrap (minutes 40–45)

Attack your own design before the interviewer does: single points of failure, the bottleneck at 10x scale, the consistency corner you punted on, the [failure modes](../07-microservices/resilience-patterns/README.md) of the riskiest dependency, [how you'd observe it in production](../10-security-observability/observability/README.md). Then: "given more time I'd address A and B." Self-critique is scored as seniority, not weakness.

---

## Running the room (the meta-skills)

- **Narrate transitions:** "that's requirements — let me spend two minutes on numbers before the architecture." The interviewer should never wonder where you are.
- **Check in at phase boundaries,** not every sentence: "want the data model deeper, or shall I scale it?"
- **When corrected, incorporate visibly** — "good point, that breaks under X, let me adjust" — the rubric scores collaboration; defensiveness is a strong negative.
- **When you don't know:** reason from principles out loud ("I haven't operated Cassandra, but as an LSM store I'd expect write-heavy workloads to favor it — [here's why](../06-databases-deep-dive/b-trees-lsm-trees/README.md)"). Honest reasoning beats confident fiction every time.
- **Time discipline:** if minute 25 arrives and you have no diagram, cut scope loudly and draw. An unfinished elegant design scores below a finished adequate one.

## The failure modes that actually fail people

1. No scoping — designing all of WhatsApp including calls, for a 45-minute slot.
2. Estimation ritual with no consequences — numbers computed, then ignored.
3. Component name-dropping — "we'll use Kafka" with no *why this, what it costs* ([the whole point](../02-building-blocks/README.md)).
4. One consistency/availability posture for the whole system instead of per-data-type.
5. Silence while thinking — the interviewer can only score what they hear.
6. Fighting the hint. When the interviewer nudges ("what about the celebrity case?"), that's the rubric telling you where the points are.

**Related:** [Capacity Estimation](capacity-estimation.md) · [Trade-off Cheat Sheet](tradeoff-cheatsheet.md) · [Question Bank](question-bank.md) · [High-Level Designs](../03-high-level-design/README.md)
