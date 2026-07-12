# Load Balancers

> A load balancer distributes incoming requests across a pool of backend servers so no single server is overwhelmed, and so the failure of any one server doesn't take down the whole service. It's usually the very first "building block" traffic hits after DNS resolution, and it's where availability (health checks) and scalability (distribution) physically meet.

---

## Table of Contents
1. [What a Load Balancer Actually Does](#1-what-a-load-balancer-actually-does)
2. [Forward Proxy vs Reverse Proxy — Where a LB Fits](#2-forward-proxy-vs-reverse-proxy--where-a-lb-fits)
3. [Layer 4 vs Layer 7 Load Balancing](#3-layer-4-vs-layer-7-load-balancing)
4. [Hardware vs Software vs Cloud (Elastic) Load Balancers](#4-hardware-vs-software-vs-cloud-elastic-load-balancers)
5. [DNS Load Balancing and Global Server Load Balancing (GSLB)](#5-dns-load-balancing-and-global-server-load-balancing-gslb)
6. [Load Balancing Algorithms](#6-load-balancing-algorithms)
7. [Health Checks](#7-health-checks--the-availability-half-of-a-load-balancers-job)
8. [TLS / SSL Handling: Termination, Passthrough, Re-encryption](#8-tls--ssl-handling-termination-passthrough-re-encryption)
9. [Session Affinity ("Sticky Sessions")](#9-session-affinity-sticky-sessions--and-why-its-a-trap)
10. [Connection Draining and Zero-Downtime Deploys](#10-connection-draining-and-zero-downtime-deploys)
11. [High Availability of the Load Balancer Itself](#11-high-availability-of-the-load-balancer-itself)
12. [Decision Framework: Which Load Balancer Do I Actually Pick?](#12-decision-framework-which-load-balancer-do-i-actually-pick)
13. [Real-World Example: Google Maglev](#13-real-world-example-how-googles-maglev-load-balancer-achieves-consistent-hashing-at-massive-scale)
14. [Spring Boot Example: Client-Side Load Balancing](#14-spring-boot-example-client-side-load-balancing-with-spring-cloud-loadbalancer)
15. [Common Pitfalls](#15-common-pitfalls)
16. [60-Second Interview Answer](#16-60-second-interview-answer)

---

## 1. What a Load Balancer Actually Does

At its core a load balancer is doing three jobs simultaneously, and conflating them is where most shallow explanations go wrong:

1. **Distribution** — spreading requests across a pool of backends using some algorithm (round robin, least connections, etc.).
2. **Health management** — continuously deciding which backends are even eligible to receive traffic (health checks, circuit breaking on the LB side).
3. **Abstraction** — giving clients a single stable address (a VIP — Virtual IP — or a DNS name) so that the actual fleet of servers behind it can grow, shrink, be replaced, or fail without any client ever needing to know or care.

That third point is the one people miss: a load balancer isn't just "traffic splitting," it's the thing that lets everything behind it be treated as **disposable, horizontally-scalable, interchangeable capacity**. This is why a load balancer is inseparable from the [scalability](../../01-foundations/scalability/README.md) and [availability](../../01-foundations/availability-reliability/README.md) foundations — it's the mechanical enforcement of "no server is special."

---

## 2. Forward Proxy vs Reverse Proxy — Where a LB Fits

This distinction is asked about constantly and conflated constantly.

- **Forward proxy**: sits in front of **clients**, hiding the client from the server. The server doesn't know who the real client is. Example: a corporate proxy that all employee laptops route outbound traffic through, or a VPN.
- **Reverse proxy**: sits in front of **servers**, hiding the servers from the client. The client doesn't know (or care) which actual server handled the request. Example: NGINX in front of a fleet of app servers.

**A load balancer is a specialized reverse proxy** — every load balancer is a reverse proxy, but not every reverse proxy load-balances (e.g., a reverse proxy that just does TLS termination and forwards to a single backend isn't "balancing" anything). API gateways ([API Gateway](../api-gateway/README.md)) and CDNs ([CDN](../cdn/README.md)) are also reverse-proxy variants with additional responsibilities layered on (auth, caching, rate limiting).

---

## 3. Layer 4 vs Layer 7 Load Balancing

### Layer 4 (Transport layer — TCP/UDP)
Routes based on IP address and port only, **without inspecting the actual request content**. The LB looks at the TCP/UDP packet header, picks a backend, and from that point on just shuffles packets back and forth (or, in DSR designs, doesn't even see the return traffic).

- **Pros:** Extremely fast (no payload parsing, often handled in-kernel or even in NIC hardware), protocol-agnostic (works for any TCP/UDP traffic — HTTP, gRPC, raw databases, game servers, SMTP, custom binary protocols), lower latency, higher raw throughput (millions of packets/sec on modern hardware).
- **Cons:** Cannot route based on URL path, headers, cookies, or request body — it has no idea a request is even HTTP. Can't do content-based routing, can't terminate TLS meaningfully for routing decisions, can't inspect for L7 attacks.
- **Examples:** AWS Network Load Balancer (NLB), raw `LVS` (Linux Virtual Server / IPVS), HAProxy in TCP mode, F5 BIG-IP LTM in L4 mode.
- **Typical use cases:** database connection pooling/proxying, raw TCP microservice traffic inside a cluster, gaming servers (often UDP), IoT device fleets, anything needing extreme throughput with minimal added latency (sub-millisecond).

### Layer 7 (Application layer — HTTP/HTTPS/gRPC/WebSocket)
Terminates the connection and inspects the actual request — path, headers, cookies, body — before deciding routing. The LB essentially acts as an HTTP client to the backend on the client's behalf (proxying, not just packet-forwarding).

- **Pros:** Can route `/api/*` to one service and `/static/*` to another (content-based routing), can do host-based routing (`api.example.com` vs `admin.example.com` on the same IP), can terminate TLS, can inject/modify headers (`X-Forwarded-For`, `X-Request-Id`), can do cookie-based sticky sessions, can do weighted routing for canary/blue-green deploys, can retry idempotent requests transparently, can rewrite URLs, can enforce WAF-style rules.
- **Cons:** Slower than L4 (must parse the request), protocol-specific (must understand HTTP/gRPC/WebSocket semantics), higher CPU cost per connection, adds a hop of latency for TLS termination + re-encryption if re-encrypting to the backend.
- **Examples:** AWS Application Load Balancer (ALB), NGINX, HAProxy in HTTP mode, Envoy, Traefik, F5 BIG-IP in L7/iRules mode.
- **Typical use cases:** web applications and REST/GraphQL/gRPC APIs, microservices needing path-based routing, anything needing TLS termination centrally, blue-green/canary traffic shifting, WebSocket upgrade handling.

**Senior-level nuance interviewers probe for:** "Use an L7 load balancer" as a blanket answer is a mild red flag if the workload is, say, a raw database connection pool or a gaming server needing UDP — there, L4 is not just adequate but *necessary* since there's no HTTP semantics to route on, and the extra L7 overhead would be pure waste. Conversely, defaulting to L4 for a public-facing web app throws away path-based routing, TLS termination, and observability that L7 gives you almost for free. In practice, **many real architectures stack both**: an L4 LB (e.g., NLB) at the very edge for raw throughput and DDoS absorption, fronting a layer of L7 LBs/proxies (e.g., Envoy) that do the smart routing — this is exactly how large-scale platforms like Google and Netflix are built.

---

## 4. Hardware vs Software vs Cloud (Elastic) Load Balancers

This is the axis most masterclasses skip entirely, and it's one of the most common real interview and real-world procurement questions: "do we buy an appliance, run software ourselves, or use the cloud provider's managed offering?"

### 4a. Hardware Load Balancers
Physical (or virtual-appliance) dedicated devices, purpose-built with specialized silicon (ASICs/FPGAs) for packet processing.

- **Examples:** F5 BIG-IP, Citrix ADC (formerly NetScaler), A10 Networks Thunder, Radware Alteon.
- **How they work:** Sit as a physical box (or a VM image of one) in the data center network path, often deployed in active-passive or active-active HA pairs with a floating VIP.
- **Pros:**
  - Extremely high raw throughput and low latency due to hardware-accelerated packet processing (custom ASICs beat general-purpose CPUs for pure packet-pushing).
  - Rich, mature feature sets built over decades: advanced L4–L7 rules (F5 "iRules" scripting), SSL offload chips (dedicated crypto hardware so TLS termination doesn't burn general CPU), DDoS mitigation, WAF, GSLB built in.
  - Vendor support contracts, compliance certifications (useful in heavily regulated industries — banking, healthcare, government) that some auditors specifically ask for by name.
- **Cons:**
  - Very expensive (capex for the box + ongoing support/license fees) — often $50K–$500K+ per pair depending on throughput tier.
  - Not elastic — capacity is fixed by the hardware you bought; scaling up means buying a bigger box or another pair, which takes procurement time (weeks/months), not minutes.
  - Physical/operational overhead: rack space, power, cooling, firmware upgrades, hardware refresh cycles (~5 years).
  - Poor fit for cloud-native, container-based, or highly elastic workloads — they're built around the assumption of a relatively static data center topology.
- **When to actually reach for one:** large regulated enterprises with existing data centers, workloads with very strict, predictable, sustained high throughput, environments where compliance/audit requirements mandate a named appliance vendor, or organizations that already have F5/Citrix expertise and support contracts and aren't cloud-first.

### 4b. Software Load Balancers
Load balancing implemented purely in software, running on commodity servers or VMs (on-prem or in the cloud) — you install and operate it yourself.

- **Examples:** NGINX / NGINX Plus, HAProxy, Envoy Proxy, Traefik, Linux `LVS`/`IPVS`, Caddy.
- **Pros:**
  - Cheap (often open source) — no licensing cost for the base product (NGINX Plus and HAProxy Enterprise are paid tiers with support/extra features, but the OSS cores are free).
  - Runs anywhere: bare metal, VM, container, cloud, edge — full portability, no vendor lock-in to a specific cloud.
  - Highly configurable and scriptable (NGINX config, HAProxy config language, Envoy's xDS API for dynamic config, Lua/WASM extensibility).
  - Can be horizontally scaled like any other software — just run more instances behind DNS or another LB layer (this is literally how Google's Maglev works, see §13).
  - First-class fit for containerized/Kubernetes environments — Envoy in particular is the data plane for most modern service meshes ([Service Mesh](../../07-microservices/service-mesh/README.md)).
- **Cons:**
  - You own the operational burden: patching, scaling, HA setup, monitoring, upgrades — nothing is "managed" for you.
  - Throughput per instance is bounded by the underlying commodity hardware's CPU/network — to match a hardware LB's raw throughput you need many instances plus a way to distribute traffic across *them* (ECMP, DNS, or another LB — see Maglev below).
  - You are responsible for security patching of the software itself.
- **When to actually reach for one:** startups and cloud-native companies, Kubernetes/container environments (Envoy/NGINX Ingress Controllers are the default), any team that wants config-as-code and CI/CD-driven LB changes instead of a vendor UI/ticket process, service-to-service (east-west) traffic inside a data center or cluster.

### 4c. Cloud / Managed ("Elastic") Load Balancers
Load balancing offered as a fully managed cloud service — you don't provision servers or appliances at all; the cloud provider runs, scales, and patches the underlying fleet for you.

- **AWS — Elastic Load Balancing (ELB) family** (this is precisely the "elastic load balancer" naming you were expecting to see covered):
  - **Application Load Balancer (ALB)** — L7, HTTP/HTTPS/WebSocket/gRPC, content-based routing, integrates with AWS WAF, target groups can be EC2, containers (ECS/EKS), or Lambda functions.
  - **Network Load Balancer (NLB)** — L4, TCP/UDP/TLS-passthrough, ultra-high throughput and low latency, supports static IPs and preserves source IP, ideal for extreme performance needs or non-HTTP protocols.
  - **Gateway Load Balancer (GLB)** — L3/L4, designed to insert third-party virtual appliances (firewalls, IDS/IPS) transparently into the traffic path using GENEVE encapsulation — not a traditional "distribute app traffic" LB, but a traffic-steering primitive for security appliances.
  - **Classic Load Balancer (CLB)** — the legacy original ELB, spans both L4 and limited L7, AWS actively steers customers toward ALB/NLB now; mostly seen in older architectures.
- **Azure**: Azure Load Balancer (L4), Application Gateway (L7, includes WAF), Front Door (global, L7, anycast + CDN-like edge routing).
- **GCP**: Cloud Load Balancing — a single anycast-IP-based global service that spans L4 (Network LB) and L7 (Global External HTTP(S) LB), notable because it's genuinely global by default (one IP, backends in multiple regions) rather than regional.
- **Pros:**
  - True elasticity — scales automatically with traffic, no capacity planning or procurement lead time, pay-as-you-go pricing.
  - Zero operational burden — the provider patches, scales, and provides HA across availability zones by default.
  - Deep integration with the rest of the cloud ecosystem (auto scaling groups, container orchestration, IAM, WAF, certificate management via ACM, native health checks tied to the platform).
  - Built-in multi-AZ (and for GCP, multi-region) high availability out of the box — no separate HA design needed.
- **Cons:**
  - Vendor lock-in — configuration and behavior are cloud-specific, migrating to another provider means re-architecting the LB layer.
  - Less low-level control than software LBs (can't always tune obscure kernel/TCP parameters or run custom modules) and less feature depth than top-tier hardware appliances for very specialized needs.
  - Cost can scale unpredictably with traffic volume at very large scale compared to owning hardware outright (opex vs capex trade-off).
- **When to actually reach for one:** the default choice for the overwhelming majority of new, cloud-hosted systems today — anyone building on AWS/Azure/GCP without a specific reason to self-host or use an appliance should start here. It's the right default in system design interviews unless the question specifically probes for on-prem/regulated/hybrid scenarios.

### Summary Comparison

| Dimension | Hardware (F5, Citrix ADC) | Software (NGINX, HAProxy, Envoy) | Cloud/Managed (AWS ALB/NLB, Azure LB, GCP LB) |
|---|---|---|---|
| Elasticity | Poor — fixed capacity per box | Good — scale by adding instances | Excellent — auto-scales transparently |
| Cost model | High capex + support contracts | Low/free (OSS) + your ops time | Pay-as-you-go opex |
| Operational burden | Vendor patches firmware; you rack/manage | You own everything (patch, scale, HA) | Provider manages everything |
| Raw throughput/latency | Very high (ASIC-accelerated) | High, bounded by commodity hardware | High, effectively unbounded (managed fleet) |
| Portability | Locked to physical infra | Runs anywhere (on-prem, cloud, edge) | Locked to that cloud provider |
| Best fit | Regulated on-prem enterprise, extreme sustained throughput | Cloud-native/Kubernetes, cost-sensitive, config-as-code | Default for cloud-hosted systems |

---

## 5. DNS Load Balancing and Global Server Load Balancing (GSLB)

Before any packet even reaches a "load balancer" as most people picture it, DNS is often doing a coarse first layer of balancing:

- **DNS round robin**: a domain name resolves to multiple IPs, and the DNS server hands them out in rotating order to different clients. Crude — no health awareness (a dead IP can still be returned), no weighting, and clients/resolvers cache results so it's a blunt, slow-to-react instrument.
- **GSLB (Global Server Load Balancing)**: DNS-based routing that *is* health- and location-aware — it directs users to the nearest or healthiest **region/data center**, not an individual server. This is the layer that answers "which data center should this user's traffic even go to" before a regional/local load balancer decides "which specific server within that data center." Examples: AWS Route 53 (latency-based, geolocation, and health-check-based routing policies), Azure Traffic Manager, GCP Cloud DNS + Cloud Load Balancing's anycast IP, and dedicated GSLB features on F5/Citrix appliances.
- **Anycast**: a more elegant alternative to classic DNS GSLB — the *same* IP address is announced from multiple physical locations via BGP, and network routing itself sends the client to the topologically nearest location. This is how GCP's global load balancer and most large CDNs work, and it avoids DNS caching staleness entirely since the routing decision happens at the network layer, not the DNS layer.

**Why this matters:** a single regional load balancer (however well designed) cannot make a system globally available — if that entire region goes down, DNS/anycast-level routing is what's needed to redirect users to a surviving region. This is the layer above the "load balancer" most people mean, and conflating the two is a common gap in interview answers about multi-region availability.

---

## 6. Load Balancing Algorithms

| Algorithm | How it works | Best for |
|---|---|---|
| **Round Robin** | Requests cycle through servers in fixed order | Homogeneous servers, roughly equal request cost |
| **Weighted Round Robin** | Like round robin, but servers with more capacity get proportionally more requests | Heterogeneous server sizes (some 2x bigger) |
| **Least Connections** | Routes to the server currently handling the fewest active connections | Requests with variable/long processing time (some take 10ms, some take 10s) |
| **Weighted Least Connections** | Least connections, adjusted for declared server capacity | Heterogeneous servers with variable-cost requests |
| **Least Response Time** | Routes to the server with the lowest recent latency + fewest connections | Latency-sensitive workloads, heterogeneous backend performance |
| **IP Hash / Consistent Hashing** | Routes based on a hash of the client IP (or another key) — same client always hits the same server | Session affinity without a shared session store; also core to CDN/cache node selection |
| **URL Hash** | Hashes the request URL to pick a backend | Cache-friendly routing — same URL always hits the same caching backend, maximizing cache hit rate |
| **Random / Power of Two Choices** | Pick 2 random servers, route to the less loaded of the two | Very large server pools where tracking exact state of all servers is expensive |

**Interview-critical distinction:** Round Robin assumes uniform request cost. The moment request processing times vary significantly (e.g., some API calls do a quick cache lookup, others do a heavy report generation), Round Robin can send a burst of "heavy" requests to one server while others sit idle — **Least Connections** is the correct answer in that scenario, and stating this distinction explicitly is a strong signal.

---

## 7. Health Checks — the Availability Half of a Load Balancer's Job

A load balancer is only as good as its ability to detect and route around unhealthy nodes.

- **Active health checks:** the LB itself periodically pings a `/health` endpoint on each backend (e.g., every 5s) and removes non-responding nodes from rotation.
- **Passive health checks:** the LB observes real traffic — if a node starts returning 5xx errors or timing out on actual requests, it's marked unhealthy without a separate probe.
- **Key tunables:** check interval, timeout, unhealthy threshold (how many consecutive failures before removal), healthy threshold (how many consecutive successes before re-admission — avoids "flapping" a node in and out of rotation).
- **Shallow vs deep health checks:** a shallow check (`GET /health` returns `200 OK` if the process is alive) can lie — the process might be up but unable to reach its database. A deep/dependency-aware check (the `/health` endpoint itself verifies DB connectivity, cache connectivity, etc.) is more accurate but risks a cascading failure: if every instance's DB dependency goes down, a naive deep check marks *every* backend unhealthy simultaneously and the LB has nowhere to route — this is why many production systems use deep checks for **readiness** (should this instance receive new traffic) but shallow checks for **liveness** (should this process be restarted), a distinction borrowed directly from Kubernetes probes.

---

## 8. TLS / SSL Handling: Termination, Passthrough, Re-encryption

- **TLS Termination**: the LB decrypts HTTPS traffic itself (holds the certificate/private key), then forwards plain HTTP to backends over a trusted internal network. Simplifies certificate management (one place to renew/rotate certs) and offloads CPU-expensive crypto work from application servers. This requires L7 awareness in most implementations.
- **TLS Passthrough**: the LB forwards encrypted traffic untouched all the way to the backend, which does its own decryption. Used when end-to-end encryption is a compliance requirement, or when doing pure L4 balancing where the LB has no business inspecting content. The trade-off: the LB can't do content-based (path/header) routing since it never sees the decrypted request.
- **TLS Re-encryption (a.k.a. "SSL bridging")**: the LB terminates TLS from the client, then opens a *new*, separate TLS connection to the backend. Gives you both content-based routing *and* encryption on the internal network segment — the standard choice in zero-trust or regulated environments where "internal network" is not treated as implicitly safe.

---

## 9. Session Affinity ("Sticky Sessions") — and Why It's a Trap

If application state lives in a specific server's memory (see the anti-pattern in [Scalability](../../01-foundations/scalability/README.md)), the LB can be configured to always route a given client to the same backend using a cookie or IP hash.

**Why senior architects are wary of this:** sticky sessions reintroduce a form of statefulness that undermines the whole point of horizontal scaling — if that one server dies, that user's session is gone (unless there's a fallback), and load distribution becomes uneven (a server with many "sticky" long-lived users can't shed load even if it's overloaded). **The preferred fix is externalizing session state** (Redis, a database) so the LB can be a completely dumb, stateless router — sticky sessions are a legitimate but second-choice tool, reached for only when externalizing state genuinely isn't feasible (e.g., WebSocket connections, which are inherently pinned to one server for their lifetime).

---

## 10. Connection Draining and Zero-Downtime Deploys

When a backend is being removed from rotation (deploy, scale-down, unhealthy), an abrupt removal drops in-flight requests. **Connection draining** (a.k.a. "deregistration delay") tells the LB: stop sending *new* requests to this instance immediately, but let its *existing* in-flight requests finish (up to a configurable timeout, e.g. 30–300s) before actually terminating it. This is what makes rolling deploys, blue-green deploys, and autoscaling scale-down events invisible to end users — skipping it is a common cause of "random 502s during deploys" in production incidents.

Related patterns built on top of L7 LBs: **weighted target groups** for canary releases (send 5% of traffic to the new version, watch error rates, ramp up) and **blue-green** cutover (flip 100% of traffic from the old target group to the new one atomically).

---

## 11. High Availability of the Load Balancer Itself

A load balancer that is itself a single point of failure has just relocated the problem, not solved it.

- **Active-Passive pair**: two LB instances share a floating Virtual IP (VIP) via a protocol like VRRP/keepalived; if the active one fails, the passive one takes over the VIP within seconds. Common in hardware/software self-hosted setups.
- **Active-Active with ECMP**: multiple LB instances are all live simultaneously, and upstream routers use Equal-Cost Multi-Path routing to spread traffic across all of them — no passive standby, and losing any one instance just reduces capacity slightly rather than causing a failover event. This is the Maglev approach (§13).
- **Managed cloud LBs handle this for you**: AWS ALB/NLB, Azure LB, and GCP LB are all natively deployed across multiple availability zones (and for GCP, can span multiple regions) with no separate HA configuration required — this is one of the strongest arguments for choosing a managed option.

---

## 12. Decision Framework: Which Load Balancer Do I Actually Pick?

A practical checklist, roughly in the order a real design discussion should walk through it:

1. **Is this L4 or L7 traffic?** Raw TCP/UDP (databases, game servers, arbitrary protocols) → L4. HTTP/gRPC/WebSocket web traffic needing routing intelligence → L7.
2. **Cloud-hosted and no unusual constraint?** → default to the cloud provider's managed offering (ALB/NLB, Azure LB/App Gateway, GCP LB). It's elastic, HA by default, and has the lowest operational cost — this should be the default answer unless something below overrides it.
3. **Kubernetes/container-native environment?** → software LB as an Ingress Controller or service mesh data plane (NGINX Ingress, Envoy/Istio) — needed regardless of cloud, since pod IPs are ephemeral and cloud LBs alone don't understand container-level routing.
4. **On-prem, regulated industry, or existing appliance investment/compliance mandate?** → hardware LB (F5, Citrix ADC), or software LB if the organization is more cloud-native-minded even on-prem.
5. **Global, multi-region availability requirement?** → this is not solved by *any single* LB above — you need DNS-based GSLB or anycast routing (§5) layered on top of regional LBs.
6. **East-west (service-to-service) traffic inside a cluster?** → often best solved by client-side load balancing or a service mesh sidecar (Envoy) rather than routing every internal call through a centralized LB and adding a network hop — see §14 and [Service Mesh](../../07-microservices/service-mesh/README.md).

---

## 13. Real-World Example: How Google's Maglev Load Balancer Achieves Consistent Hashing at Massive Scale

Google's **Maglev** (described in their public 2016 NSDI paper) is a software network load balancer that handles a significant portion of Google's incoming traffic, sitting in front of everything from Search to YouTube.

The key architectural decision senior engineers borrow from it:
- Instead of a traditional single "load balancer box" (a potential bottleneck and single point of failure), Maglev runs as **many identical instances behind Equal-Cost Multi-Path (ECMP) routing at the network layer** — so there is no single load balancer at all, just a horizontally scaled fleet of them, each capable of handling any packet.
- To keep TCP connections stable even as backend pools change size (servers added/removed), Maglev uses a **consistent hashing scheme** so that, as much as possible, the same connection keeps landing on the same backend even after the backend pool is resized — minimizing connection resets during scaling events or failures.
- This design lets Google scale the load-balancing layer itself horizontally and disposably, avoiding the classic "load balancer as single point of failure" trap.

**The lesson:** the load balancer itself must be designed with the same scalability/availability principles ([Scalability](../../01-foundations/scalability/README.md), [Availability & Reliability](../../01-foundations/availability-reliability/README.md)) as the services behind it — "just put an NGINX box in front" doesn't scale past a certain point without the LB layer itself becoming the bottleneck or single point of failure. This is also, structurally, exactly what AWS NLB and GCP's global LB do under the hood — a fleet of software LB nodes fronted by anycast/ECMP, not one giant box.

---

## 14. Spring Boot Example: Client-Side Load Balancing with Spring Cloud LoadBalancer

While infrastructure-level load balancers (NGINX, ALB, Envoy) handle most cases, senior architects should also know **client-side load balancing** — where a calling service itself picks which instance of a downstream service to call, common in service-mesh-free microservice setups.

```java
// build.gradle: org.springframework.cloud:spring-cloud-starter-loadbalancer

@Configuration
public class LoadBalancerConfig {

    // Custom load-balancing strategy: swap the default round-robin for a
    // least-response-time-style strategy when downstream call costs are non-uniform.
    @Bean
    public ServiceInstanceListSupplier discoveryClientServiceInstanceListSupplier(
            ConfigurableApplicationContext context) {
        return ServiceInstanceListSupplier.builder()
            .withDiscoveryClient()          // pulls live instance list from Eureka/Consul
            .withHealthChecks()              // filters out instances failing health checks
            .withCaching()                   // avoids hammering the discovery service
            .build(context);
    }
}
```

```java
@Service
@RequiredArgsConstructor
public class PricingClient {

    private final RestClient.Builder restClientBuilder;

    // "http://pricing-service" is a LOGICAL name resolved by the load balancer
    // to one of N live instances -- the calling code never hardcodes an IP or port.
    @LoadBalanced
    RestClient pricingRestClient() {
        return restClientBuilder.baseUrl("http://pricing-service").build();
    }

    public Price getPrice(String sku) {
        return pricingRestClient()
            .get()
            .uri("/price/{sku}", sku)
            .retrieve()
            .body(Price.class);
        // Under the hood: Spring Cloud LoadBalancer picks a healthy pricing-service
        // instance from the service registry, applying round-robin (default) or a
        // custom strategy, and retries on a different instance on connection failure.
    }
}
```

```yaml
# application.yml
spring:
  cloud:
    loadbalancer:
      health-check:
        interval: 5s          # active health check interval -- matches infra-LB concepts
        path:
          default: /actuator/health
      retry:
        enabled: true         # retry on a DIFFERENT instance if one call fails
```

**Why this matters at senior level:** it shows you understand load balancing isn't confined to a physical box at the network edge — the same algorithmic concepts (round robin, least connections, health checks) recur at the application layer in service-to-service calls, and knowing when to reach for infra-level vs client-side load balancing (client-side avoids an extra network hop, but couples the algorithm choice into every service) is itself a design decision.

---

## 15. Common Pitfalls

- Defaulting to sticky sessions instead of externalizing state — treats a symptom (server needs client affinity) instead of the cause (state shouldn't live in server memory).
- Ignoring L4 vs L7 trade-offs and assuming "load balancer" always means an HTTP-aware one — costs unnecessary latency/complexity for non-HTTP workloads.
- Forgetting that the load balancer itself needs to be highly available (DNS round-robin across multiple LB IPs, or a managed/anycast solution) — a single load balancer instance is just a relocated single point of failure.
- Using Round Robin for workloads with highly variable per-request cost, causing uneven load despite "balanced" request counts.
- Treating "load balancer" as one single-region concept and forgetting that global availability needs DNS/GSLB or anycast on top.
- Using shallow health checks that don't verify real dependency health, or the opposite mistake — deep checks that cause every instance to fail simultaneously during a shared dependency outage.
- Skipping connection draining, causing dropped in-flight requests on every deploy or scale-down event.
- Choosing a hardware appliance for a workload that's actually elastic and bursty, paying for fixed peak capacity you rarely use — or choosing a fully unmanaged software LB for a simple cloud-hosted app when a managed ALB/NLB would have eliminated all the operational overhead.

---

## 16. 60-Second Interview Answer

> "A load balancer distributes traffic across a backend pool and removes unhealthy nodes via health checks. L4 balances on IP/port without understanding the protocol — fast, protocol-agnostic; L7 terminates and inspects HTTP so it can route on path or headers, at some latency cost. On the deployment axis, I'd choose between hardware appliances like F5 for regulated, high-throughput on-prem environments, software LBs like NGINX/Envoy for cloud-native or Kubernetes environments where I want config-as-code and portability, or a managed cloud LB like AWS ALB/NLB by default, since it's elastic and HA out of the box with zero operational burden. For global availability I'd add DNS-based GSLB or anycast on top, since no single regional LB can survive a full regional outage. For algorithm choice, I'd default to round robin for uniform-cost requests, but switch to least-connections when request cost varies a lot. I try to avoid sticky sessions by externalizing session state to something like Redis, accepting them only for genuinely stateful protocols like long-lived WebSocket connections, and I'd make sure connection draining is configured so deploys don't drop in-flight requests."

**Related:** [Scalability](../../01-foundations/scalability/README.md) · [Rate Limiting](../rate-limiting/README.md) · [API Gateway](../api-gateway/README.md) · [Availability & Reliability](../../01-foundations/availability-reliability/README.md) · [CDN](../cdn/README.md) · [Service Mesh](../../07-microservices/service-mesh/README.md)
