# Availability & Reliability

> **Availability** = the proportion of time a system is operational and able to serve requests correctly.
> **Reliability** = the probability a system performs correctly *over a given period without failure*, given that it was working at the start of that period.

They are related but distinct, and conflating them is a common junior mistake: a system can be **highly available but unreliable** (it's always up, but 1 in 20 requests silently returns wrong data), and a system can be **highly reliable but not available** (it never corrupts data, but it's down for scheduled maintenance every night for 4 hours).

---

## 1. The Math Interviewers Actually Expect

### Availability as a percentage of uptime

```
Availability = Uptime / (Uptime + Downtime)
```

| "Nines" | Availability % | Downtime / year | Downtime / month |
|---|---|---|---|
| 1 nine | 90% | 36.5 days | 73 hours |
| 2 nines | 99% | 3.65 days | 7.3 hours |
| 3 nines | 99.9% | 8.76 hours | 43.8 minutes |
| 4 nines | 99.99% | 52.6 minutes | 4.4 minutes |
| 5 nines | 99.999% | 5.26 minutes | 26 seconds |
| 6 nines | 99.9999% | 31.5 seconds | 2.6 seconds |

**Interview trap:** candidates often claim "we'll build a five-nines system" without realizing 5.26 minutes of downtime *per year, total* means you cannot even take a leisurely deploy window — every release, every failover, every DNS propagation delay eats into that budget. A senior answer ties the target nines to an **error budget** (SRE terminology) that is then spent deliberately on releases vs incidents.

### Availability of components in series vs parallel

**Series** (a request must pass through component A AND component B — e.g., Load Balancer → App Server, no redundancy at either):
```
A_total = A_1 × A_2 × ... × A_n
```
Two components each at 99.9% availability, in series, yield **99.8%** combined — availability *degrades* as you chain more single-point components. This is why microservices architectures with long synchronous call chains are an availability risk: every added service in the critical path multiplies down the combined availability, even if each individual service is excellent.

**Parallel / redundant** (component has a standby — e.g., two independent replicas, either can serve the request):
```
A_total = 1 - [(1 - A_1) × (1 - A_2)]
```
Two components each at 99% availability, run redundantly, yield **99.99%** combined — redundancy *compounds* availability upward. This is the mathematical justification for "always run at least 2 replicas of everything in the critical path," which is close to gospel in senior interviews.

---

## 2. Reliability: MTBF, MTTR, and Why They Matter More Than the Nines

- **MTBF (Mean Time Between Failures):** average time a system runs before it fails.
- **MTTR (Mean Time To Recovery):** average time to detect + repair + restore service after a failure.

```
Availability ≈ MTBF / (MTBF + MTTR)
```

**Senior-level insight:** you can hit high availability numbers two completely different ways — (a) make failures rare (increase MTBF, e.g. better hardware, more testing) or (b) make recovery fast (decrease MTTR, e.g. automated failover, canary rollbacks, circuit breakers). In practice, **MTTR is the cheaper lever.** It's far easier to build automated detection + failover that cuts recovery from 30 minutes to 30 seconds than to make hardware/software fail 60x less often. This is why senior architects prioritize observability (fast detection), automated rollback, and failover mechanisms over simply "writing more robust code."

---

## 3. SLI, SLO, and SLA — the vocabulary that turns "nines" into a contract

These three terms are frequently used interchangeably, which is itself the interview tell that someone hasn't worked with them for real:

- **SLI (Service Level Indicator):** the actual **measured metric** — e.g., "the percentage of requests in the last 5 minutes that completed in under 300ms with a 2xx status." This is a number you compute from real telemetry.
- **SLO (Service Level Objective):** the **internal target** for that SLI — e.g., "99.9% of requests complete in under 300ms, measured over a rolling 30 days." This is the number engineering teams design and operate against.
- **SLA (Service Level Agreement):** an **external, often contractual** commitment to a customer, typically set *looser* than the internal SLO on purpose (e.g., SLO of 99.95%, SLA of 99.9%) — the gap is deliberate slack so that normal operational variance doesn't trigger customer-facing penalty clauses (service credits, refunds) every time the SLO has a slightly rough month.

**The error budget, precisely:** if the SLO is 99.9% over 30 days, the **error budget** is the complementary 0.1% — a concrete, spendable allowance (roughly 43 minutes over 30 days) that the team can consciously spend on risky deploys, planned maintenance, or experiments. When the budget is exhausted, the team's own policy (this is the actual SRE practice, not a suggestion) is to **freeze further risky releases** until the budget replenishes — turning "be more careful" from a vague exhortation into an automatic, metric-driven gate. This is the single most practical piece of SRE theory to be able to state precisely in an interview.

### Durability vs. Availability — a frequently conflated, frequently tested distinction

- **Availability** answers "can I access the system right now."
- **Durability** answers "once data is written, will it still exist later, even if nothing is currently trying to read it."

A storage system can be **highly durable but temporarily unavailable** — data is safe on disk across multiple replicas, but a network partition means you can't reach it *right now*. AWS S3's own published figures make this distinction concrete: S3 advertises **99.999999999% (eleven nines) durability** for objects, but a **separate, much lower 99.99% availability** SLA — because durability is about surviving media failure across redundant copies, while availability is about the request path being reachable at this instant. Conflating the two ("S3 has eleven nines of uptime") is a specific, common, and easily-caught interview mistake.

---

## 4. Disaster Recovery: RTO and RPO

Beyond day-to-day availability engineering (circuit breakers, health checks, redundancy), **disaster recovery** plans for the low-probability, high-impact event — a full region loss, a catastrophic data corruption, a ransomware event — and it's scoped with two numbers:

- **RTO (Recovery Time Objective):** how long the system is allowed to be *down* before it must be restored — "we must be back online within 4 hours."
- **RPO (Recovery Point Objective):** how much *data* the organization can afford to lose, measured as time — "we can afford to lose up to 15 minutes of writes" — which directly dictates backup/replication frequency (a nightly backup gives a 24-hour RPO; continuous synchronous replication approaches a near-zero RPO, at a real latency and cost premium).

| DR strategy | Typical RTO | Typical RPO | Cost |
|---|---|---|---|
| **Backup & restore** | Hours to days | Hours (since last backup) | Cheapest |
| **Pilot light** (minimal standby infra, scaled up on failover) | Tens of minutes | Minutes | Low-moderate |
| **Warm standby** (scaled-down but running replica) | Minutes | Seconds to minutes | Moderate-high |
| **Active-active (multi-region)** | Near-zero (automatic) | Near-zero | Highest |

**Why this belongs in a system design answer, not just an ops runbook:** "how would you design this for disaster recovery" is really asking you to pick a point on this table and justify it against the business's actual tolerance for downtime and data loss — a bank's ledger has a near-zero RPO requirement; a social media "likes" counter can tolerate losing a few minutes of writes far more cheaply.

---

## 5. Techniques to Improve Availability

| Technique | What it does | Trade-off |
|---|---|---|
| **Redundancy / replication** | Eliminate single points of failure | Cost, consistency complexity |
| **Failover (active-passive / active-active)** | Automatically reroute traffic when a node dies | Failover detection delay, split-brain risk |
| **Health checks + auto-healing** | Remove unhealthy nodes from rotation automatically | False positives can remove healthy nodes |
| **Circuit breakers** | Stop cascading failures when a downstream dependency is unhealthy | Requests fail fast instead of degrading gracefully unless a fallback exists |
| **Graceful degradation** | Serve a reduced but functional experience instead of a hard failure | Requires designing "what's optional" in advance |
| **Multi-AZ / multi-region deployment** | Survive datacenter or regional outages | Cross-region replication latency & cost |
| **Chaos engineering** | Proactively find weaknesses before they cause real outages | Requires mature ops culture, can itself cause incidents if uncontrolled |

---

## 6. Real-World Example: AWS's Multi-AZ / Multi-Region Availability Model

AWS structures its infrastructure into **Regions** (fully independent geographic areas) made of multiple **Availability Zones (AZs)** — physically separate datacenters with independent power, cooling, and networking, connected by low-latency links.

The pattern senior architects borrow from this:
- Services like RDS Multi-AZ run a **synchronously replicated standby** in a different AZ. If the primary AZ fails (power outage, network partition, natural disaster), AWS automatically promotes the standby and repoints the DNS endpoint — typically within **60–120 seconds** — trading a small MTTR hit for surviving a full datacenter failure.
- For higher-stakes systems, architects go further and deploy **active-active across regions**, so that even a full AWS region outage (which has happened — e.g., major US-EAST-1 incidents affecting large swaths of the internet) doesn't take the system down, at the cost of handling cross-region data consistency explicitly.
- The core lesson: availability isn't purchased once — it's purchased **incrementally**, at each layer (single instance → Multi-AZ → multi-region), each increment cutting into a different class of failure (process crash → AZ failure → region failure) at increasing cost.

---

## 7. Spring Boot Example: Circuit Breakers + Health Checks to Cut MTTR

The following demonstrates the two cheapest, highest-leverage availability levers a senior architect reaches for in an app tier: **fast failure detection** (health checks feeding the load balancer) and **fast isolation of a failing dependency** (circuit breaker, via Resilience4j) so one bad downstream service doesn't cascade into a full outage.

```java
// build.gradle / pom.xml dependency: io.github.resilience4j:resilience4j-spring-boot3

@RestController
@RequiredArgsConstructor
public class RecommendationController {

    private final RestClient inventoryServiceClient;

    // If the inventory service is unhealthy, fail fast after a threshold of errors
    // instead of letting every request hang and exhaust this service's own thread pool.
    @CircuitBreaker(name = "inventoryService", fallbackMethod = "fallbackRecommendations")
    @TimeLimiter(name = "inventoryService")
    @GetMapping("/recommendations/{userId}")
    public CompletableFuture<List<Product>> getRecommendations(@PathVariable String userId) {
        return CompletableFuture.supplyAsync(() ->
            inventoryServiceClient.get()
                .uri("/inventory/live-stock?user={id}", userId)
                .retrieve()
                .body(new ParameterizedTypeReference<List<Product>>() {})
        );
    }

    // Graceful degradation: serve cached/static recommendations instead of a 500
    public CompletableFuture<List<Product>> fallbackRecommendations(String userId, Throwable t) {
        return CompletableFuture.completedFuture(staticFallbackCatalog());
    }

    private List<Product> staticFallbackCatalog() {
        return List.of(new Product("bestseller-1"), new Product("bestseller-2"));
    }
}
```

```yaml
# application.yml — circuit breaker tuning: this IS the availability design decision
resilience4j:
  circuitbreaker:
    instances:
      inventoryService:
        failure-rate-threshold: 50          # trip open after 50% of calls fail
        sliding-window-size: 20             # over the last 20 calls
        wait-duration-in-open-state: 10s    # stay open (fail fast) for 10s before retrying
        permitted-number-of-calls-in-half-open-state: 5
  timelimiter:
    instances:
      inventoryService:
        timeout-duration: 2s                # a hung dependency must not hang this service

# Kubernetes readiness/liveness probes -- this is the health-check half of MTTR reduction
# readinessProbe removes the pod from the Service's load-balanced endpoints if it can't serve traffic
# livenessProbe restarts the pod if it's deadlocked -- both cut MTTR from "someone notices" to seconds
```

```yaml
# Spring Boot Actuator exposing the health endpoint the k8s probes hit
management:
  endpoint:
    health:
      probes:
        enabled: true
      show-details: always
  health:
    livenessState:
      enabled: true
    readinessState:
      enabled: true
```

**Why this matters at senior level:** the interviewer isn't testing whether you know the Resilience4j annotation names. They're testing whether you understand that **availability is engineered at the failure-handling layer, not hoped for at the happy-path layer** — and that a fallback response (even a degraded one) is almost always better for overall system availability than a fast, correct failure.

---

## 8. Common Pitfalls

- **Chasing nines without an error budget.** "We need 99.99%" is meaningless without translating it into "we can have 52 minutes of downtime this year — how do we want to spend it (deploys, incidents, maintenance)?"
- **Treating availability and consistency as the same problem.** A system that returns *stale* data instead of an error is available but not strictly consistent — this is a deliberate trade-off (see [CAP Theorem](../cap-theorem/README.md)), not an accident.
- **No graceful degradation plan.** Circuit breakers without a sane fallback just convert "slow failure" into "fast failure" — better for the caller's own resource exhaustion, but still a failure from the end user's perspective unless a fallback path exists.
- **Ignoring correlated failures.** Redundancy math (`1 - (1-A)(1-A)`) assumes independent failures. Two replicas in the *same* AZ, on the *same* power circuit, running the *same* buggy software version are not independent — this is why "multi-AZ" and "canary/staggered deploys" both exist as distinct mitigations.

---

## 9. 60-Second Interview Answer

> "Availability is uptime as a fraction of total time; reliability is the probability of failure-free operation over an interval — a system can be available but wrong, or reliable but frequently offline for maintenance. I'd model availability with `MTBF / (MTBF + MTTR)`, and I'd focus more engineering effort on cutting MTTR — automated health checks, circuit breakers, fast failover — than on chasing a lower MTBF, since detection and recovery speed is usually the cheaper lever. I'd also translate any 'nines' target into an explicit error budget so the team knows how much downtime is available to spend on deploys versus held in reserve for incidents."

**Related:** [CAP Theorem](../cap-theorem/README.md) · [Load Balancers](../../02-building-blocks/load-balancers/README.md) · [Database Replication](../../02-building-blocks/databases/replication/README.md)
