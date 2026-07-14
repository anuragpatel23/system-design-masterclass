# API Gateway

> An API Gateway is the single entry point that sits in front of a system's backend services (often many microservices), handling cross-cutting concerns — routing, authentication, rate limiting, request aggregation, protocol translation — so that individual backend services don't each have to reimplement them, and so that external clients see one coherent API surface instead of the internal service topology.

---

## 1. Core Responsibilities

| Responsibility | What it means | Why centralize it |
|---|---|---|
| **Routing** | Directing `/users/*` to the user service, `/orders/*` to the order service, etc. | Clients need one host/domain, not knowledge of internal service addresses |
| **Authentication / Authorization** | Validating tokens (JWT, OAuth) before a request reaches any backend | Avoids reimplementing auth logic in every microservice; a single hardened choke point |
| **Rate Limiting** | Enforcing per-client/per-API-key request quotas (see [Rate Limiting](../rate-limiting/README.md)) | Protects the entire backend fleet from a single abusive or buggy client, at the earliest possible point |
| **Request/Response Transformation** | Adapting protocols (REST ↔ gRPC), reshaping payloads for different client needs | Decouples external API contract from internal service implementation details |
| **Aggregation (API Composition)** | Combining results from multiple backend calls into a single client response | Reduces client-side round trips (crucial for mobile clients on high-latency networks) |
| **Observability** | Centralized logging, metrics, tracing for every request entering the system | One place to instrument instead of N services each needing their own setup |
| **TLS Termination** | Handling HTTPS at the edge so internal traffic can be plaintext (within a trusted network) | Reduces certificate management burden on every internal service |

---

## 2. Concrete Products — Managed vs Self-Hosted

| Product | Category | Distinguishing trait |
|---|---|---|
| **AWS API Gateway** | Managed (cloud) | Deep Lambda integration (common pairing for serverless — see [Serverless](../../07-microservices/serverless/README.md)), usage-plan-based API-key quotas, pay-per-request pricing |
| **Kong** | Self-hosted / managed | Built on NGINX/OpenResty, plugin-architecture (auth, rate limiting, transformation as composable plugins) — see the dedicated [Kong API Gateway](../../11-technologies/kong-api-gateway/README.md) deep dive |
| **Apigee (Google)** | Managed (cloud) | Strong API monetization/analytics features, aimed at enterprises exposing APIs as a product to external partners |
| **Azure API Management** | Managed (cloud) | Integrates with Azure AD for auth, developer portal generation for external API consumers out of the box |
| **Envoy Gateway / Ambassador** | Self-hosted | Built on Envoy proxy (see [Load Balancers §4](../load-balancers/README.md)) — the natural choice when the internal service mesh is also Envoy-based, for a consistent data-plane technology at both the edge and internally |
| **Traefik** | Self-hosted | Auto-discovers routes from container/Kubernetes labels, popular in containerized environments for its low-config operational model |

**The practical decision:** managed cloud gateways (AWS/Apigee/Azure) minimize operational burden and fit naturally if the backend is already on that cloud; self-hosted gateways (Kong/Envoy/Traefik) trade that convenience for portability, deeper customization via plugins, and avoiding cloud-specific lock-in at the edge — the same capex/elasticity-style trade-off discussed for load balancers applies here, one layer up the stack.

---

## 3. The Backend-for-Frontend (BFF) Pattern

A single, generic API Gateway serving both a data-hungry web client and a bandwidth/battery-constrained mobile client often ends up as an awkward compromise for both. The **BFF pattern** introduces a **dedicated gateway layer per client type** (a "web BFF" and a "mobile BFF"), each independently shaped to that client's specific needs (the mobile BFF might aggressively aggregate and trim payloads to minimize round trips and data usage; the web BFF might pass through more raw data since the web client has more bandwidth and can do its own client-side composition).

**Senior-level nuance:** BFFs are a legitimate pattern, but they add operational surface (more services to deploy/monitor) — a smaller team building one client type generally doesn't need this pattern yet; it earns its cost once you have genuinely divergent, high-value client types with meaningfully different data/latency needs.

---

## 4. API Gateway vs Service Mesh — a Common Point of Confusion

- **API Gateway:** handles **north-south traffic** — requests entering the system from *outside* (external clients, third-party integrations).
- **Service Mesh** (e.g., Istio, Linkerd): handles **east-west traffic** — communication *between* internal services, typically via sidecar proxies, providing similar concerns (routing, retries, mTLS, observability) but for internal service-to-service calls rather than external ingress.

They solve structurally similar problems (routing, security, observability) but at different traffic boundaries, and mature architectures often use **both** — an API Gateway at the edge, and a service mesh internally, each solving cross-cutting concerns at its respective layer without needing every individual service to reimplement them.

---

## 5. Real-World Example: Netflix's Zuul (and Its Evolution) as the Edge Gateway for a Massive Microservices Fleet

Netflix's engineering blog has extensively documented **Zuul**, the edge gateway sitting in front of their microservices architecture (which, at various points, has comprised many hundreds of independently deployed services):

- Zuul's core job is handling every request from every Netflix client device (TVs, phones, browsers, game consoles) and routing it to the correct one of Netflix's many backend microservices — without any client needing to know that internal topology, or needing to change when Netflix restructures services behind the scenes.
- Netflix's public writing describes using Zuul's **filter architecture** to implement cross-cutting concerns — authentication, dynamic routing, load shedding under stress, and A/B testing/canary routing — as pluggable filters applied uniformly at the edge, rather than duplicating this logic across hundreds of individual services.
- A specifically senior-relevant detail from Netflix's public engineering writing: the gateway is also where they apply **adaptive load shedding** — proactively rejecting or degrading a fraction of incoming requests during periods of extreme backend stress, protecting the overall fleet's availability at the cost of some individual requests failing fast — directly connecting the gateway to the [Availability & Reliability](../../01-foundations/availability-reliability/README.md) principle of graceful degradation, applied at the earliest possible point in the request path.

**The lesson:** an API Gateway isn't just a routing convenience — at scale, it becomes the most strategically valuable place in the entire system to implement protective mechanisms (rate limiting, load shedding, circuit breaking) precisely because it's the one place *every* request passes through before touching any backend capacity.

---

## 6. Spring Boot Example: A Gateway with Routing, Auth, and Response Aggregation using Spring Cloud Gateway

```java
// build.gradle: spring-cloud-starter-gateway

@Configuration
public class GatewayRoutingConfig {

    @Bean
    public RouteLocator customRoutes(RouteLocatorBuilder builder) {
        return builder.routes()
            // Simple routing: path prefix determines which backend service handles it.
            .route("user-service", r -> r.path("/api/users/**")
                .filters(f -> f.stripPrefix(1)
                               .circuitBreaker(c -> c.setName("userServiceCB")
                                                      .setFallbackUri("forward:/fallback/users")))
                .uri("lb://user-service")) // "lb://" -> load-balanced across service instances

            .route("order-service", r -> r.path("/api/orders/**")
                .filters(f -> f.stripPrefix(1)
                               .requestRateLimiter(rl -> rl
                                   .setRateLimiter(redisRateLimiter())
                                   .setKeyResolver(apiKeyResolver())))
                .uri("lb://order-service"))
            .build();
    }

    @Bean
    public RedisRateLimiter redisRateLimiter() {
        // 10 requests/sec sustained, burst up to 20 -- enforced at the EDGE,
        // before any backend service capacity is consumed. See Rate Limiting doc.
        return new RedisRateLimiter(10, 20);
    }

    @Bean
    public KeyResolver apiKeyResolver() {
        return exchange -> Mono.just(
            exchange.getRequest().getHeaders().getFirst("X-API-Key"));
    }
}
```

```java
// A global filter applied to EVERY request -- centralized auth, exactly the
// cross-cutting-concern principle this building block exists to enable.
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    private final JwtValidator jwtValidator;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (path.startsWith("/api/public/")) {
            return chain.filter(exchange); // public endpoints skip auth
        }

        String token = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (token == null || !jwtValidator.isValid(token)) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
            // Rejected HERE -- the user-service and order-service backends
            // never even see an unauthenticated request; they don't need to
            // reimplement this check themselves.
        }
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return -1; // run before routing filters
    }
}
```

```java
// Response aggregation (API composition): combine two backend calls into
// one client-facing response, saving a mobile client a second round trip.
@RestController
@RequiredArgsConstructor
public class OrderSummaryAggregationController {

    private final WebClient.Builder webClientBuilder;

    @GetMapping("/api/order-summary/{orderId}")
    public Mono<OrderSummaryResponse> getOrderSummary(@PathVariable String orderId) {
        Mono<Order> orderMono = webClientBuilder.build().get()
            .uri("lb://order-service/orders/{id}", orderId).retrieve().bodyToMono(Order.class);

        Mono<ShippingStatus> shippingMono = webClientBuilder.build().get()
            .uri("lb://shipping-service/status/{id}", orderId).retrieve().bodyToMono(ShippingStatus.class);

        // Fan out to two backend services in PARALLEL, then combine --
        // one client request instead of two, and total latency is max(), not sum().
        return Mono.zip(orderMono, shippingMono)
            .map(tuple -> new OrderSummaryResponse(tuple.getT1(), tuple.getT2()));
    }
}
```

```yaml
# application.yml
spring:
  cloud:
    gateway:
      default-filters:
        - name: CircuitBreaker
          args:
            name: defaultCB
            fallbackUri: forward:/fallback/generic
      httpclient:
        connect-timeout: 2000
        response-timeout: 5s
```

**Why this matters at senior level:** it demonstrates the gateway hosting exactly the cross-cutting concerns table above — auth as a global filter, rate limiting per-route, circuit breaking for downstream failures, and response aggregation — as a single coherent implementation, not scattered reimplementations across every backend service.

---

## 7. Common Pitfalls

- Turning the API Gateway into a "smart" gateway with real business logic (beyond composition/aggregation) — this recreates a monolith at the edge and couples the gateway's deploy cycle to business logic changes that shouldn't need it.
- Making the gateway itself a single point of failure by under-provisioning or under-scaling it — since 100% of traffic passes through it, it needs to be at least as horizontally scaled and highly available as the busiest individual backend service (see [Load Balancers](../load-balancers/README.md) for how the gateway's own availability is typically achieved).
- Confusing API Gateway concerns (north-south, edge) with Service Mesh concerns (east-west, internal) and trying to force one tool to do both jobs adequately.
- Not applying load shedding/rate limiting at the gateway, missing the opportunity to protect the entire backend fleet at the cheapest possible point (rejecting a request at the edge is far cheaper than rejecting it after it's already consumed backend capacity).

---

## 8. 60-Second Interview Answer

> "An API Gateway is the single entry point handling cross-cutting concerns — routing, auth, rate limiting, response aggregation, observability — so individual microservices don't each reimplement them, and external clients see one coherent surface instead of the internal service topology. It's distinct from a service mesh, which handles the same kinds of concerns but for internal, service-to-service traffic rather than external ingress — mature systems often use both. I'd be careful not to let the gateway accumulate real business logic, keeping it focused on composition and cross-cutting concerns, and I'd make sure it's scaled and made highly available at least as aggressively as the busiest backend service, since literally all traffic passes through it."

**Related:** [Rate Limiting](../rate-limiting/README.md) · [Load Balancers](../load-balancers/README.md) · [REST Best Practices](../../08-api-design/rest-best-practices/README.md) · [Availability & Reliability](../../01-foundations/availability-reliability/README.md)
