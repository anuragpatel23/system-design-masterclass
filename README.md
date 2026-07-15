# System Design Masterclass — The Ultimate Interview Preparation Guide

> A complete, self-contained vault for preparing system design interviews at any product company — from FAANG to high-growth startups — written to the depth expected of **senior / staff / principal** loops, while remaining a from-first-principles resource for anyone leveling up. Every topic is a standalone note, every note ends with a **60-Second Interview Answer** you can drill, and every design decision links back to the principle that justifies it.

---

## The 12 Sections

| # | Section | What it covers | Interview round it maps to |
|---|---------|----------------|----------------------------|
| 01 | [Foundations](01-foundations/README.md) | Scalability, availability math, CAP, consistency models, latency vs throughput | Every round — this is the vocabulary |
| 02 | [Building Blocks](02-building-blocks/README.md) | Load balancers, caching, SQL/NoSQL, sharding, replication, queues, CDN, API gateway, rate limiting | HLD rounds — the Lego pieces |
| 03 | [High-Level Design](03-high-level-design/README.md) | 12 complete designs: URL shortener, Twitter, WhatsApp, YouTube, Uber, payments, and more | The classic "design X" round |
| 04 | [Low-Level Design](04-low-level-design/README.md) | Design patterns (creational/structural/behavioral) + 6 machine-coding problems | LLD / machine-coding rounds |
| 05 | [Distributed Systems](05-distributed-systems/README.md) | Raft, Paxos, 2PC, sagas, CQRS/event sourcing, leader election, event-driven architecture | Staff+ "go one level deeper" follow-ups |
| 06 | [Databases Deep Dive](06-databases-deep-dive/README.md) | B-Trees vs LSM-Trees, indexing, ACID/isolation levels, the database-scaling decision tree | "Why this database?" follow-ups |
| 07 | [Microservices & Architecture Patterns](07-microservices/README.md) | Monolith vs microservices, service discovery, resilience patterns, service mesh, serverless, deployment patterns | Architecture-review and migration questions |
| 08 | [API Design](08-api-design/README.md) | REST best practices & versioning, gRPC vs GraphQL vs REST, WebSockets/SSE, pagination, idempotency | API-design rounds; the "define your API" step of every HLD |
| 09 | [Interview Prep](09-interview-prep/README.md) | The RESHADED framework, capacity-estimation cheat sheet, trade-off cheat sheet, question bank | The meta-skill: how to run the room |
| 10 | [Security & Observability](10-security-observability/README.md) | AuthN/AuthZ (OAuth2, JWT, sessions), security essentials, metrics/logs/traces, SLOs | The "how do you secure/operate it?" follow-ups |
| 11 | [Technology Deep Dives](11-technologies/README.md) | Redis, Kafka, RabbitMQ, Solace, email/SMS, Kong, Nginx, Docker, K8s, AWS, GCP, DevOps, Linux, Elasticsearch, ZooKeeper, Prometheus — each with a from-scratch **Java implementation** + install guide | "Okay, but how does Redis/Kafka *actually* work?" |
| 12 | [Application Servers](12-app-servers/README.md) | Tomcat, JBoss/WildFly, Jetty — architecture, deployment workflow, day-2 operations | Enterprise-stack and "how does your code reach prod" questions |

---

## How the sections build on each other

```
01 Foundations ──────────────► the vocabulary (CAP, consistency, p99, nines)
        │
02 Building Blocks ──────────► the Lego pieces (cache, LB, shard, queue, CDN)
        │
03 High-Level Design ────────► assembling pieces into complete systems
        │
04 Low-Level Design ─────────► zooming into classes, patterns, machine coding
        │
05 Distributed Systems ──────► opening the black boxes (consensus, sagas)
        │
06 Databases Deep Dive ──────► opening the database (storage engines, isolation)
        │
07 Microservices ────────────► how organizations actually structure services
        │
08 API Design ───────────────► the contract layer between all of the above
        │
09 Interview Prep ───────────► the framework + drills to deliver it under pressure
        │
10 Security & Observability ─► the follow-ups that distinguish staff+ candidates
        │
11 Technology Deep Dives ────► the named tools, each rebuilt from scratch in Java
        │
12 Application Servers ──────► how Java code actually reaches and serves production
```

---

## Suggested study plans

**4-week plan (interview in a month, ~1.5h/day):**

| Week | Focus |
|---|---|
| 1 | All of 01 + 02. Drill the 60-second answers daily. |
| 2 | Six designs from 03 (URL shortener, Twitter, WhatsApp, YouTube, Uber, payment system) + 09's RESHADED framework. Practice each design out loud with a timer. |
| 3 | 05 + 06 + remaining designs from 03. |
| 4 | 07 + 08 + 10, then 09's question bank. Full mock interviews on alternate days. |

**1-week crash plan (interview next week):**

Day 1: 01 (all) + 09 interview-framework. Day 2–3: 03 — the four designs most likely for your target company. Day 4: 02 caching + sharding + queues + rate limiting. Day 5: 06 database-scaling + 05 saga pattern. Day 6: 09 capacity-estimation + tradeoff-cheatsheet. Day 7: mock interviews only.

**LLD-focused plan (machine-coding round):** section 04 end to end, one problem per day, writing real code — then 08 for API-shape questions.

---

## How to use each note

Every topic note follows the same structure, on purpose:

1. **The precise question it answers** — so you know when to reach for it in an interview.
2. **The mechanism, from first principles** — not just what, but *why*, with ASCII diagrams and worked numbers.
3. **Trade-offs stated as trade-offs** — every choice names what it costs, because "X is better" is a junior answer and "X buys you A at the cost of B, which is right here because C" is a senior one.
4. **Real-world reference** — a named production system that made this choice, so your answer carries evidence.
5. **Common pitfalls** — the specific wrong statements interviewers are listening for.
6. **60-Second Interview Answer** — a drilled, complete paragraph you can deliver verbatim under pressure.

**The drilling method:** read the note top to bottom once. Re-read tracing every diagram. Then cover everything except the title and reconstruct the 60-second answer from memory. Repeat any note you can't reconstruct after two attempts.

---

## What "senior depth" means in practice (the bar this vault targets)

- **Numbers, not adjectives.** "High QPS" is junior; "~1.2M reads/sec at p99 < 50ms, so a single Postgres primary is out and we're choosing between a cache-fronted cluster and Cassandra" is senior. Section 09's capacity-estimation note gives you the numbers to memorize.
- **Mechanisms, not names.** Naming Kafka is junior; explaining that its partition-per-consumer model gives ordered, at-least-once delivery within a partition — and what that forces on your idempotency design — is senior. Sections 05, 06, and 08 exist for exactly this.
- **Trade-offs, not features.** Every real decision sacrifices something. If your design has no stated costs, the interviewer assumes you can't see them.
- **Failure modes, unprompted.** Staff+ candidates volunteer what happens when the cache dies, the region partitions, or the queue backs up — before being asked. Sections 01, 05, 07, and 10 arm you for this.

---

## Quick topic finder (interview panic index)

| If asked about… | Go to |
|---|---|
| "Design a system that…" | [09 RESHADED framework](09-interview-prep/interview-framework.md) first, then the closest match in [03](03-high-level-design/README.md) |
| "How would you scale this database?" | [Database Scaling](06-databases-deep-dive/database-scaling/README.md) → [Sharding](02-building-blocks/databases/sharding/README.md) → [Replication](02-building-blocks/databases/replication/README.md) |
| "How do services find/talk to each other?" | [Service Discovery](07-microservices/service-discovery/README.md) → [gRPC vs REST](08-api-design/grpc-graphql-rest/README.md) |
| "What if this call fails?" | [Resilience Patterns](07-microservices/resilience-patterns/README.md) → [Saga Pattern](05-distributed-systems/saga-pattern/README.md) → [Idempotency](08-api-design/idempotency/README.md) |
| "Estimate the storage/QPS" | [Capacity Estimation](09-interview-prep/capacity-estimation.md) |
| "How do you secure it?" | [AuthN/AuthZ](10-security-