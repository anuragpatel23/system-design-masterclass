# 07 — Microservices & Architecture Patterns

> Sections 01–06 taught you the physics (foundations, distributed systems, storage engines) and the components (building blocks). This section is about the **organizational and architectural layer above them**: how you split a system into services, how those services find and protect themselves from each other, and how you ship changes to them safely. This is the material behind the extremely common interview arc *"you've designed it as a monolith — now your company has 40 teams; what changes?"* — and behind the reverse trap, *"why did you jump straight to microservices?"*

## Topics in this section

| Topic | The concrete question it answers |
|---|---|
| [Monolith vs Microservices](monolith-vs-microservices/README.md) | When does splitting actually pay for itself, and what is the true cost of the split? |
| [Service Discovery](service-discovery/README.md) | With services scaling up/down and moving constantly, how does anyone find anyone? |
| [Resilience Patterns](resilience-patterns/README.md) | Circuit breakers, retries with backoff, timeouts, bulkheads — how one slow service is prevented from killing the fleet |
| [Service Mesh & Sidecars](service-mesh/README.md) | How do you get mTLS, retries, and observability on every call without writing it into every service? |
| [Serverless](serverless/README.md) | When are functions-as-a-service the right call, and what are cold starts really costing you? |
| [Deployment Patterns](deployment-patterns/README.md) | Blue-green, canary, rolling, feature flags — how you ship without a maintenance window |

## The single most important idea in this section

**Microservices are an organizational scaling technique that carries a distributed-systems tax.** Every pattern in this section — discovery, circuit breaking, meshes, canary deploys — exists to *pay down* that tax. A candidate who presents microservices as a free performance upgrade has the causality backwards; a candidate who says "we split for team autonomy and independent deployability, and here is the machinery we must now add to keep it reliable" is speaking at the staff level.

## How this connects to the rest of the vault

- The failure modes these patterns defend against are the ones from [Availability & Reliability](../01-foundations/availability-reliability/README.md) — cascading failure, correlated failure, the multiplication of nines across serial dependencies.
- The inter-service communication itself is section [08 — API Design](../08-api-design/README.md); the async alternative is [Event-Driven Architecture](../05-distributed-systems/event-driven-architecture/README.md) and [Message Queues](../02-building-blocks/message-queues/README.md).
- Cross-service data correctness is the [Saga Pattern](../05-distributed-systems/saga-pattern/README.md) — the answer to "you split the order flow across three services; how is it transactional now?"

Previous: [06 — Databases Deep Dive](../06-databases-deep-dive/README.md) · Next: [08 — API Design](../08-api-design/README.md)
