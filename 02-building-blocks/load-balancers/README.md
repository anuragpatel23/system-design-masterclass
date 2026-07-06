# Load Balancers

> A load balancer distributes incoming requests across a pool of backend servers so no single server is overwhelmed, and so the failure of any one server doesn't take down the whole service. It's usually the very first "building block" traffic hits after DNS resolution, and it's where availability (health checks) and scalability (distribution) physically meet.

---

## 1. Layer 4 vs Layer 7 Load Balancing

### Layer 4 (Transport layer — TCP/UDP)
Routes based on IP address and port only, **without inspecting the actual request content**.

- **Pros:** Extremely fast (no payload parsing), protocol-agnostic (works for any TCP/UDP traffic — HTTP, gRPC, raw databases, game servers).
- **Cons:** Cannot route based on URL path, headers, or cookies — it has no idea a request is even HTTP.
- **Examples:** AWS Network Load Balancer (NLB), raw `LVS` (Linux Virtual Server), HAProxy in TCP mode.

### Layer 7 (Application layer — HTTP/HTTPS/gRPC)
Terminates the connection and inspects the actual request — path, headers, cookies, body — before deciding routing.

- **Pros:** Can route `/api/*` to one service and `/static/*` to another, can do content-based routing, can terminate TLS, can inject/modify headers, can do sticky sessions via cookies.
- **Cons:** Slower than L4 (must parse the request), protocol-specific (must understand HTTP/gRPC).
- **Examples:** AWS Application Load Balancer (ALB), NGINX, HAProxy in HTTP mode, Envoy.

**Senior-level nuance interviewers probe for:** "Use an L7 load balancer" as a blanket answer is a mild red flag if the workload is, say, a raw database connection pool or a gaming server needing UDP — there, L4 is not just adequate but *necessary* since there's no HTTP semantics to route on, and the extra L7 overhead would be pure waste.

---

## 2. Load Balancing Algorithms

| Algorithm | How it works | Best for |
|---|---|---|
| **Round Robin** | Requests cycle through servers in fixed order | Homogeneous servers, roughly equal request cost |
| **Weighted Round Robin** | Like round robin, but servers with more capacity get proportionally more requests | Heterogeneous server sizes (some 2x bigger) |
| **Least Connections** | Routes to the server currently handling the fewest active connections | Requests with variable/long processing time (some take 10ms, some take 10s) |
| **Least Response Time** | Routes to the server with the lowest recent latency + fewest connections | Latency-sensitive workloads, heterogeneous backend performance |
| **IP Hash / Consistent Hashing** | Routes based on a hash of the client IP (or another key) — same client always hits the same server | Need for session affinity without a shared session store; also core to CDN/cache node selection |
| **Random / Power of Two Choices** | Pick 2 random servers, route to the less loaded of the two | Very large server pools where tracking exact state of all servers is expensive |

**Interview-critical distinction:** Round Robin assumes uniform request cost. The moment request processing times vary significantly (e.g., some API calls do a quick cache lookup, others do a heavy report generation), Round Robin can send a burst of "heavy" requests to one server while others sit idle — **Least Connections** is the correct answer in that scenario, and stating this distinction explicitly is a strong signal.

---

## 3. Health Checks — the Availability Half of a Load Balancer's Job

A load balancer is only as good as its ability to detect and route around unhealthy nodes.

- **Active health checks:** the LB itself periodically pings a `/health` endpoint on each backend (e.g., every 5s) and removes non-responding nodes from rotation.
- **Passive health checks:** the LB observes real traffic — if a node starts returning 5xx errors or timing out on actual requests, it's marked unhealthy without a separate probe.
- **Key tunables:** check interval, timeout, unhealthy threshold (how many consecutive failures before removal), healthy threshold (how many consecutive successes before re-admission — avoids "flapping" a node in and out of rotation).

---

## 4. Session Affinity ("Sticky Sessions") — and Why It's a Trap

If application state lives in a specific server's memory (see the anti-pattern in [Scalability](../../01-foundations/scalability/README.md)), the LB can be configured to always route a given client to the same backend using a cookie or IP hash.

**Why senior architects are wary of this:** sticky sessions reintroduce a form of statefulness that undermines the whole point of horizontal scaling — if that one server dies, that user's session is gone (unless there's a fallback), and load distribution becomes uneven (a server with many "sticky" long-lived users can't shed load even if it's overloaded). **The preferred fix is externalizing session state** (Redis, a database) so the LB can be a completely dumb, stateless router — sticky sessions are a legitimate but second-choice tool, reached for only when externalizing state genuinely isn't feasible (e.g., WebSocket connections, which are inherently pinned to one server for their lifetime).

---

## 5. Real-World Example: How Google's Maglev Load Balancer Achieves Consistent Hashing at Massive Scale

Google's **Maglev** (described in their public 2016 NSDI paper) is a software network load balancer that handles a significant portion of Google's incoming traffic, sitting in front of everything from Search to YouTube.

The key architectural decision senior engineers borrow from it:
- Instead of a traditional single "load balancer box" (a potential bottleneck and single point of failure), Maglev runs as **many identical instances behind Equal-Cost Multi-Path (ECMP) routing at the network layer** — so there is no single load balancer at all, just a horizontally scaled fleet of them, each capable of handling any packet.
- To keep TCP connections stable even as backend pools change size (servers added/removed), Maglev uses a **consistent hashing scheme** so that, as much as possible, the same connection keeps landing on the same backend even after the backend pool is resized — minimizing connection resets during scaling events or failures.
- This design lets Google scale the load-balancing layer itself horizontally and disposably, avoiding the classic "load balancer as single point of failure" trap.

**The lesson:** the load balancer itself must be designed with the same scalability/availability principles ([Scalability](../../01-foundations/scalability/README.md), [Availability & Reliability](../../01-foundations/availability-reliability/README.md)) as the services behind it — "just put an NGINX box in front" doesn't scale past a certain point without the LB layer itself becoming the bottleneck or single point of failure.

---

## 6. Spring Boot Example: Client-Side Load Balancing with Spring Cloud LoadBalancer

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

## 7. Common Pitfalls

- Defaulting to sticky sessions instead of externalizing state — treats a symptom (server needs client affinity) instead of the cause (state shouldn't live in server memory).
- Ignoring L4 vs L7 trade-offs and assuming "load balancer" always means an HTTP-aware one — costs unnecessary latency/complexity for non-HTTP workloads.
- Forgetting that the load balancer itself needs to be highly available (DNS round-robin across multiple LB IPs, or a managed/anycast solution) — a single load balancer instance is just a relocated single point of failure.
- Using Round Robin for workloads with highly variable per-request cost, causing uneven load despite "balanced" request counts.

---

## 8. 60-Second Interview Answer

> "A load balancer distributes traffic across a backend pool and removes unhealthy nodes via health checks. L4 balances on IP/port without understanding the protocol — fast, protocol-agnostic; L7 terminates and inspects HTTP so it can route on path or headers, at some latency cost. For algorithm choice, I'd default to round robin for uniform-cost requests, but switch to least-connections when request cost varies a lot, since round robin can pile heavy requests onto one node. I try to avoid sticky sessions by externalizing session state to something like Redis, since sticky sessions reintroduce statefulness that undermines horizontal scalability — I'd only accept them for genuinely stateful protocols like long-lived WebSocket connections."

**Related:** [Scalability](../../01-foundations/scalability/README.md) · [Rate Limiting](../rate-limiting/README.md) · [API Gateway](../api-gateway/README.md) · [Availability & Reliability](../../01-foundations/availability-reliability/README.md)
