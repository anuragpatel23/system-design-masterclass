# Monolith vs Microservices

> **The question this answers, precisely:** when does splitting a system into independently deployable services actually pay for itself, what exactly does the split cost, and why do experienced architects say "microservices solve an organizational problem, not a technical one"? This is one of the highest-frequency architecture questions at every level, and one where the *expected* answer inverted around 2020 — blind microservices enthusiasm now reads as junior.

---

## 1. Definitions that survive follow-up questions

- **Monolith:** a single deployable unit — one process (or homogeneous fleet of identical processes) containing all business functionality, typically sharing one database. Note: a monolith can still be *modular* internally (clean package boundaries, enforced interfaces) and can still *scale horizontally* (run 200 identical copies behind a load balancer). Neither modularity nor horizontal scaling requires microservices — a very common interviewer trap.
- **Microservices:** the system is decomposed into services that are (1) **independently deployable**, (2) **independently scalable**, (3) **owning their own data** (no shared database), and (4) communicating only over network APIs or async events. If your "microservices" share a database or must be deployed together, you have a **distributed monolith** — all of the costs, none of the benefits, and interviewers love hearing you name this failure mode.
- **Modular monolith:** the increasingly recommended middle ground — one deployable, but with module boundaries enforced strictly enough (separate schemas, no cross-module imports outside published interfaces) that later extraction into services is a mechanical exercise rather than an archaeology project.

---

## 2. What microservices actually buy you

1. **Independent deployability — the headline benefit.** Team A ships 20 times a day without coordinating with Team B. With 40 teams in one monolith, the release train, merge conflicts, and "who broke the build" coordination overhead grow super-linearly. This is Conway's Law used deliberately: align service boundaries with team boundaries so teams don't block each other.
2. **Independent scaling.** The video-transcoding service needs 500 CPU-heavy nodes; the user-profile service needs 6. In a monolith you scale everything together and pay for the maximum of every component's needs.
3. **Fault isolation (when done right).** A memory leak in the recommendations service takes down recommendations — not checkout. (This only holds if you also apply the [resilience patterns](../resilience-patterns/README.md); otherwise a slow dependency drags callers down with it.)
4. **Independent technology and data-model choices.** The search service can use Elasticsearch and Java; the ML service Python; the ledger Postgres with serializable isolation. Per-service data ownership lets each service pick the storage engine its workload actually wants — connecting directly to [B-Trees vs LSM-Trees](../../06-databases-deep-dive/b-trees-lsm-trees/README.md).
5. **Smaller blast radius per deploy** — a bad deploy affects one service, and rollback is one service's rollback.

---

## 3. What microservices actually cost — the distributed-systems tax

Every in-process function call that becomes a network call inherits **all** of the failure modes of a network:

- **Latency:** an in-process call is ~nanoseconds; a same-datacenter RPC is ~0.5–1ms *before* the callee does any work. A request that fans out through 5 serial service hops has paid ~5ms of pure network tax and multiplied its [tail-latency](../../01-foundations/latency-vs-throughput/README.md) exposure — the p99 of a chain is dominated by the worst hop.
- **Partial failure:** the monolith's call either happens or the whole process is down. A network call can time out, succeed-but-lose-the-response (forcing [idempotency](../../08-api-design/idempotency/README.md)), or fail for one caller and not another. You must now design for *every* call failing independently.
- **Loss of transactions:** in the monolith, "create order + decrement inventory + record payment" is one ACID transaction. Split across three services, it becomes a [saga](../../05-distributed-systems/saga-pattern/README.md) with compensations — an order of magnitude more design effort for the same correctness.
- **Loss of joins:** queries that were one SQL join now require API composition, data duplication via [events](../../05-distributed-systems/event-driven-architecture/README.md), or [CQRS](../../05-distributed-systems/cqrs-event-sourcing/README.md) read models.
- **Operational surface:** you now need [service discovery](../service-discovery/README.md), per-service CI/CD, [distributed tracing](../../10-security-observability/observability/README.md), contract testing, and an on-call structure per service. A rough rule of thumb: microservices need a platform team; if the whole company is 12 engineers, the platform team *is* the company.
- **Debugging:** a stack trace becomes a distributed trace across 8 services; "it's slow" becomes a murder mystery without [tracing](../../10-security-observability/observability/README.md) in place.

---

## 4. The decision framework (what interviewers want to hear)

| Signal | Points toward |
|---|---|
| Small team (< ~20-30 engineers), product still finding fit, requirements churning | **Monolith / modular monolith** — the split's coordination benefit doesn't exist yet, but its tax is paid immediately |
| Many teams blocking each other on releases; deploy train is the bottleneck | **Microservices** — this is the actual problem they solve |
| One component's scaling needs are wildly different (10-100x) from the rest | Extract **that component** — you don't need 50 services to fix one hotspot |
| A component needs a different availability/consistency posture (e.g., payments needs strict consistency, feed doesn't) | Extract along that boundary |
| Domain boundaries still unclear | **Wait.** The most expensive microservices mistake is drawing service boundaries wrong — a wrong in-process module boundary is a refactor; a wrong service boundary is a data migration plus API deprecation across teams |

**The migration answer (frequently asked as "how would you split an existing monolith?"):** the **strangler-fig pattern** — put a routing layer ([API gateway](../../02-building-blocks/api-gateway/README.md)) in front of the monolith, extract one capability at a time behind it (starting with the least entangled, highest-value one), route its traffic to the new service, repeat. Never big-bang rewrite. Data is split by first giving the module a private schema *inside* the shared DB, then moving that schema out — decoupling logically before physically.

---

## 5. Real-world references (use these as evidence)

- **Amazon (early 2000s)** — the canonical pro-split story: monolith deploy coordination was throttling the company; the mandate that all teams expose functionality only via service interfaces is the founding legend of microservices *and* of AWS. The driver was **team autonomy**, not performance.
- **Netflix** — split for independent scaling and fault isolation across hundreds of services; also had to *invent* much of the resilience tooling (Hystrix — see [Resilience Patterns](../resilience-patterns/README.md)) to make it survivable, which is the honest half of the story.
- **Amazon Prime Video (2023)** — the counter-story every current interviewer knows: the video-monitoring team publicly moved a workload *from* serverless microservices *back to* a monolith and cut costs ~90%, because the inter-service data shuffling dominated the actual work. **The lesson is not "monoliths won"** — it's that decomposition granularity is a per-workload decision, and chatty, data-heavy paths belong in one process.
- **Shopify & Stack Overflow** — deliberate, publicly documented modular monoliths at very large scale, proving the monolith ceiling is far higher than the hype suggested.

---

## 6. Common pitfalls

- Claiming microservices are needed "for scale" — horizontal scaling of a stateless monolith behind a load balancer handles enormous scale; the honest driver is organizational.
- Proposing microservices that share one database — that's a distributed monolith; name it and avoid it.
- Splitting by technical layer ("UI service, business-logic service, data service") instead of by business capability — layer-splits maximize inter-service chatter because every request crosses every layer.
- Never mentioning the migration path — jumping from "monolith today" to "microservices tomorrow" without the strangler-fig story signals you've never done it.
- Forgetting that cross-service consistency now requires [sagas](../../05-distributed-systems/saga-pattern/README.md)/[outbox patterns](../../05-distributed-systems/event-driven-architecture/README.md) — interviewers deliberately probe "so how does the order stay consistent with inventory now?"

---

## 7. 60-Second Interview Answer

> "Microservices primarily solve an organizational problem — independent deployability so that many teams can ship without coordinating — plus independent scaling and fault isolation as secondary wins. The cost is the distributed-systems tax: every in-process call becomes a network call with latency, partial failure, and no shared transactions, so cross-service workflows need sagas and idempotency, and you need discovery, tracing, and a platform team just to operate it. So my default is a modular monolith while the team is small and boundaries are still settling, extracting services only when a specific pressure justifies it — teams blocking each other on deploys, one component with wildly different scaling needs, or a component needing a different consistency posture. When I do split, I use the strangler-fig pattern behind a gateway rather than a big-bang rewrite, and I split by business capability, never by technical layer. Amazon's split was driven by exactly this team-autonomy pressure — and the Prime Video 2023 case going back to a monolith shows granularity is a per-workload judgment, not an ideology."

**Related:** [Resilience Patterns](../resilience-patterns/README.md) · [Saga Pattern](../../05-distributed-systems/saga-pattern/README.md) · [API Gateway](../../02-building-blocks/api-gateway/README.md) · [Event-Driven Architecture](../../05-distributed-systems/event-driven-architecture/README.md)
