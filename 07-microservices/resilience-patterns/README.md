# Resilience Patterns — Timeouts, Retries, Circuit Breakers, Bulkheads

> **The question this answers, precisely:** how do you stop one slow or failing service from taking down every service that calls it — the **cascading failure** that is the single most common way large microservice systems actually die? Interviewers probe this with "what happens when service B gets slow?" and expect a layered, mechanical answer, not the word "Hystrix."

---

## 1. The enemy: cascading failure, mechanically

The killer is not a *down* dependency — it's a **slow** one. Suppose service A calls B, and B's latency jumps from 20ms to 30s (GC storm, hot shard, whatever):

1. A's threads/connections block waiting on B. A has a finite pool (say 200 threads).
2. At even modest traffic, all 200 threads are soon parked waiting on B. A is now unable to serve *anything* — including endpoints that don't touch B at all.
3. A's callers now see A as slow. Their pools fill. The failure **propagates up the call graph**, and retries multiply the traffic onto the already-struggling B ("retry storm"), guaranteeing it never recovers.

Every pattern below attacks a specific step of that chain. The senior framing: **resilience patterns convert unbounded waiting into fast, bounded failure, and convert failure amplification into failure containment.**

---

## 2. Timeouts — the non-negotiable baseline

- **Every network call gets an explicit timeout.** Library defaults are often infinite or absurd (e.g., 30s+); an unbounded call is a leaked thread waiting to happen. Timeouts convert "hang" into "fast, handleable error" — attacking step 1 directly.
- **Set them from the callee's observed p99, not from hope** (e.g., callee p99 = 80ms ⇒ timeout ≈ 200–300ms). Too tight: you fail healthy-but-slow requests and *cause* retries. Too loose: pools still drain during incidents.
- **Timeout budgets:** in a chain A→B→C, A's timeout must exceed B's timeout for its call to C plus B's own work, or A gives up while B is still trying — work is done, response is thrown away, and the client retries anyway. Sophisticated systems propagate a **deadline** header downstream ("this request has 140ms left"), so every hop can quit early instead of doing doomed work.

## 3. Retries — powerful, and the most dangerous pattern here

- **Only retry idempotent operations** (or make them idempotent with [idempotency keys](../../08-api-design/idempotency/README.md)) — a retried "charge card" without one is a double charge.
- **Only retry transient errors** (timeouts, 503s, connection resets) — retrying a 400 is pure waste; retrying during a real outage is fuel on the fire.
- **Exponential backoff with jitter, always.** Backoff (100ms, 200ms, 400ms…) spaces attempts out; **jitter** (randomizing each delay) prevents thousands of clients whose calls failed *at the same moment* from retrying in synchronized waves that re-spike the dependency exactly when it's trying to recover. "Backoff with jitter" as a single phrase is an interviewer keyword.
- **Cap total attempts (2–3) and use a retry budget** (e.g., retries may be at most 10% of requests fleet-wide): a fixed per-request retry count of 3 across a 5-hop chain can amplify one user request into 3⁵ = 243 downstream attempts during a full outage. Naming **retry amplification** unprompted is a staff-level signal.

## 4. Circuit Breakers — stop calling what's already dying

A circuit breaker wraps calls to a dependency and tracks the recent failure rate:

```
        failure rate over window > threshold (e.g. >50% of last 20 calls)
CLOSED ────────────────────────────────────────────────────────► OPEN
(calls flow;                                             (calls fail IMMEDIATELY,
 failures counted)                                        no network attempt at all)
    ▲                                                            │
    │  probe succeeds                                            │ after cooldown (e.g. 30s)
    │                                                            ▼
    └───────────────────────── HALF-OPEN ◄───────────────────────┘
                    (let a few probe requests through;
                     success ⇒ CLOSED, failure ⇒ OPEN again)
```

- **What it buys, precisely:** while OPEN, callers get an instant failure instead of burning a thread for a full timeout — protecting the *caller's* pools (step 2 of the cascade) — and the dependency gets breathing room to recover instead of being hammered (the retry-storm fix). The half-open state is the recovery probe: cautiously test, don't flood.
- **On failure, degrade rather than die:** return a fallback — cached last-known-good data, a default (empty recommendations row), or a queued deferred operation. *"Netflix shows a non-personalized row rather than an error page"* is the canonical example: the homepage's availability is not held hostage by its least reliable dependency.
- **Lineage:** Netflix **Hystrix** popularized it (thread-pool-per-dependency); modern practice is Resilience4j in-process, or breakers implemented in the [service-mesh sidecar](../service-mesh/README.md) so every service gets them without code changes.

## 5. Bulkheads — partition the blast radius

Named after ship compartments: **partition resources so one dependency's failure can't consume them all.** Give calls to B a dedicated pool of 30 threads/connections; when B hangs, exactly 30 threads are lost, and the other 170 keep serving endpoints that don't need B. Same idea at coarser grains: per-tenant quotas, separate fleets for critical vs batch traffic, cell-based architectures (independent "cells" of the whole stack so an incident hits one cell's users only). Bulkheads attack step 2 — they're what makes "fault *isolation*" literally true.

## 6. Two patterns that complete the picture

- **Load shedding & backpressure:** when overloaded, *reject early and cheaply* (429/503 at the front door, before expensive work) rather than degrading for everyone — a brownout is worse than a controlled shed. Queue-depth caps and [rate limiting](../../02-building-blocks/rate-limiting/README.md) are the mechanisms; "goodput over throughput" is the phrase.
- **Failing open vs failing closed:** when an *auxiliary* dependency (e.g., the rate limiter itself, a feature-flag service) is down, decide deliberately whether requests pass (fail open — availability-biased) or are refused (fail closed — safety-biased; correct for authz). Naming this shows you design failure behavior instead of discovering it in production.

---

## 7. How they layer (the composed answer)

Per call: **deadline-propagated timeout** → bounded **retries with jittered backoff** (idempotent + transient only, budget-capped) → **circuit breaker** around the dependency → **bulkhead-limited** pool → **fallback** on open circuit → **shed load** at the edge when the whole node is stressed. Defense in depth: each layer catches what the previous one lets through.

---

## 8. Common pitfalls

- Retries **without** jitter/budgets — you've built a synchronized battering ram and made outages self-sustaining.
- Circuit breaker with no fallback story — an open breaker still fails the user; the design question is *what do they see instead*.
- Timeouts longer than the caller's own SLA, or uniform 30s defaults everywhere — pools drain long before anyone notices.
- Treating this as library trivia ("use Resilience4j") without the mechanism — interviewers deliberately ask *why* the half-open state exists (answer: to probe recovery without re-flooding).
- Forgetting that retried writes require [idempotency](../../08-api-design/idempotency/README.md) — the follow-up is always "so can that charge happen twice?"

---

## 9. 60-Second Interview Answer

> "The failure mode that kills microservice systems is cascading failure from a slow dependency: callers' thread pools fill up waiting, they go slow themselves, and naive retries multiply traffic onto the struggling service so it never recovers. I defend in layers. Every call has an explicit timeout derived from the callee's p99, ideally with deadline propagation down the chain. Retries are limited to idempotent operations and transient errors, with exponential backoff plus jitter to prevent synchronized retry waves, and a fleet-wide retry budget because per-hop retries compound exponentially across a deep call chain. Around each dependency sits a circuit breaker — closed normally, open when the recent failure rate crosses a threshold so callers fail instantly instead of burning threads, half-open to probe recovery without re-flooding — with a defined fallback like cached or default data, the way Netflix serves a non-personalized row instead of an error page. Bulkheads give each dependency an isolated pool so one hang can't consume every thread, and at the edge I shed load early when overloaded. Timeouts bound the damage, breakers contain it, bulkheads compartmentalize it, and fallbacks hide it from the user."

**Related:** [Availability & Reliability](../../01-foundations/availability-reliability/README.md) · [Rate Limiting](../../02-building-blocks/rate-limiting/README.md) · [Idempotency](../../08-api-design/idempotency/README.md) · [Service Mesh](../service-mesh/README.md)
