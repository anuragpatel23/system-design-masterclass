# Service Discovery

> **The question this answers, precisely:** in a system where service instances are constantly created, killed, rescheduled, and autoscaled — so IP addresses are meaningless minutes after you learn them — how does service A reliably find a healthy instance of service B? This is the first piece of machinery a microservices answer must include, and interviewers use it to test whether you understand that *the hard part is not the lookup, it's keeping the registry truthful*.

---

## 1. Why this problem exists at all

In the pre-cloud world, services lived at fixed IPs written into config files. In a containerized, autoscaled world ([Kubernetes](https://kubernetes.io), ECS, Nomad), an instance's IP is assigned at schedule time and dies with it — possibly minutes later. Hardcoding addresses is impossible; DNS with long TTLs is too stale. So you need a **service registry**: a live, queryable database of *which instances of which service are currently alive and healthy, and at what address*.

The registry has three jobs, and each is a failure mode in disguise:

1. **Registration** — how instances get added.
2. **Health checking** — how dead instances get removed *quickly but not too eagerly*.
3. **Discovery** — how callers query it without the registry becoming a bottleneck or single point of failure.

---

## 2. Registration: self-registration vs third-party registration

- **Self-registration:** each instance, on startup, writes itself into the registry (e.g., a Spring Boot app registering with **Eureka** or **Consul**), then sends periodic **heartbeats**; missing N heartbeats ⇒ eviction. Simple, but couples every service to the registry client library, and a hung-but-alive process may keep heartbeating while serving nothing.
- **Third-party registration:** the platform registers instances on the service's behalf — this is the Kubernetes model: the kubelet/control plane tracks pod lifecycle and readiness, and the Endpoints/EndpointSlice objects *are* the registry. Services contain zero discovery code. This is why "Kubernetes Services + DNS" has largely absorbed the standalone-registry world.
- **Health checking done right** distinguishes **liveness** ("process is running — restart it if not") from **readiness** ("process can serve traffic *right now* — e.g., caches warmed, DB connections up; remove from rotation if not"). Conflating them causes the classic outage: a service that's momentarily slow gets *restarted* (losing warm state) instead of merely *removed from rotation*, amplifying the incident.

---

## 3. Discovery: client-side vs server-side

```
CLIENT-SIDE DISCOVERY                      SERVER-SIDE DISCOVERY
                                           
Service A ──query──► Registry             Service A ──► Load Balancer / VIP ──► Service B
    │                   │                                     │
    │◄──[B1, B2, B3]────┘                              Registry consulted
    │                                                  by the LB, not by A
    └──pick one (LB logic in client)──► B2
```

- **Client-side discovery** (Netflix Eureka + Ribbon lineage): the caller fetches the healthy-instance list and load-balances *in-process* (round-robin, least-loaded, zone-aware). **Pros:** no extra network hop, per-request routing intelligence (e.g., prefer same-zone instances to cut cross-AZ cost/latency), no LB to scale. **Cons:** discovery/LB logic embedded in every service, in every language — a polyglot org pays it N times, and upgrading the logic means redeploying the fleet.
- **Server-side discovery** (Kubernetes Service/kube-proxy, AWS ALB, or a [service mesh](../service-mesh/README.md) sidecar): callers hit a stable virtual name/IP; infrastructure resolves it to a healthy instance. **Pros:** zero discovery code in services; language-agnostic; centrally upgradable. **Cons:** an extra hop, and the balancing layer itself must be scaled and made highly available.
- **The modern synthesis is the service mesh:** a sidecar proxy next to each instance does client-side-style smart routing, but as infrastructure — you get client-side's intelligence with server-side's decoupling. That's the subject of [Service Mesh & Sidecars](../service-mesh/README.md).

---

## 4. The registry itself: the CP-vs-AP decision hiding inside

The registry is a distributed store, so [CAP](../../01-foundations/cap-theorem/README.md) applies — and the two most famous registries deliberately chose opposite sides:

- **Consul / etcd / ZooKeeper — CP.** Built on [Raft](../../05-distributed-systems/consensus-algorithms/raft/README.md)/ZAB consensus; the registry is always *consistent*, but during a partition the minority side refuses reads/writes. Correct choice when the registry also backs [leader election](../../05-distributed-systems/leader-election/README.md) or distributed locking, where stale answers are dangerous.
- **Eureka — AP, deliberately.** Netflix's insight: for *discovery specifically*, a stale answer beats no answer. If the registry partitions, Eureka keeps serving its last-known instance list — some entries may be dead, but callers have their own retries and [circuit breakers](../resilience-patterns/README.md) to route around corpses. Eureka even has **self-preservation mode**: if too many heartbeats vanish at once, it assumes *the network* (not hundreds of instances simultaneously) failed, and stops evicting — refusing to turn a network blip into a mass de-registration that would empty the registry.
- **Interview gold:** being able to say *"discovery data is a good candidate for AP because staleness is recoverable by the caller's retry, but lock/leader data must be CP because staleness there means two leaders"* demonstrates you can apply CAP per-use-case rather than per-slogan.

**DNS as discovery** deserves one honest sentence: Kubernetes exposes services via DNS names, which is server-side discovery with DNS as the query protocol; classic wide-area DNS alone is a poor registry because TTL caching (clients and resolvers ignoring low TTLs) makes eviction unreliably slow.

---

## 5. Real-world reference: Kubernetes' built-in discovery

A `Service` object gives a stable virtual IP + DNS name (`orders.prod.svc.cluster.local`). The control plane continuously reconciles the set of pods matching the Service's selector *and passing readiness probes* into EndpointSlices; kube-proxy (or a mesh) programs routing so connections to the virtual IP reach a healthy pod. Registration, health, and discovery are all platform-owned — services just make HTTP calls to a name. This is why in a Kubernetes-based interview design you can say "discovery is handled by the platform" *and then demonstrate depth* by mentioning readiness-vs-liveness and what happens during rolling deploys (pods are drained from endpoints *before* termination — the connection-draining detail that prevents 502s during every deploy).

---

## 6. Common pitfalls

- Treating discovery as solved by "just use DNS" without addressing TTL staleness and health eviction.
- Ignoring the registry's own availability — it's on the critical path of *every* call setup; a CP registry that loses quorum can lock up new connections fleet-wide (mitigation: clients cache the last-known-good list and keep using it).
- Conflating liveness and readiness probes — the "restart the slow-but-warming service in a loop" self-inflicted outage.
- Forgetting eviction lag: between an instance dying and its eviction, callers *will* hit a corpse — which is why discovery is inseparable from client [retries and circuit breakers](../resilience-patterns/README.md).
- Describing client-side discovery without its polyglot cost, or server-side without its extra-hop cost — either omission reads as one-sided.

---

## 7. 60-Second Interview Answer

> "Service discovery exists because in an autoscaled, containerized world instance addresses are ephemeral, so you need a live registry of healthy instances plus a way to query it. There are two halves: registration — either instances self-register with heartbeats like Eureka, or the platform registers them like Kubernetes does with readiness-probed EndpointSlices — and lookup, which is either client-side, where the caller fetches the instance list and load-balances in-process for smarter routing at the cost of embedding that logic in every language, or server-side, where callers hit a stable name and infrastructure routes it, which is simpler for services but adds a hop. The subtle design decision is the registry's consistency posture: Eureka is deliberately AP because a stale instance list is survivable — callers retry around corpses — while Consul and etcd are CP via Raft because they also back locks and leader election, where staleness means split-brain. In a modern design I'd default to platform-provided discovery — Kubernetes Services with readiness probes and connection draining — and layer a service mesh sidecar when I need zone-aware routing, mTLS, and retries as infrastructure."

**Related:** [Service Mesh](../service-mesh/README.md) · [Resilience Patterns](../resilience-patterns/README.md) · [CAP Theorem](../../01-foundations/cap-theorem/README.md) · [Leader Election](../../05-distributed-systems/leader-election/README.md) · [Load Balancers](../../02-building-blocks/load-balancers/README.md)
