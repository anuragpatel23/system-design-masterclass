# Kong — The API Gateway as a Product

> **Mental model:** Kong is the [API gateway concept](../../02-building-blocks/api-gateway/README.md) shipped as software: an **Nginx/OpenResty-based proxy** with a **plugin pipeline** (auth, rate limiting, transformation, logging) that executes around every request, configured via its own Admin API (or declarative YAML / Kubernetes CRDs). Knowing Kong specifically signals you've *operated* a gateway, not just drawn one — and its entity model (Service/Route/Consumer/Plugin) is a clean vocabulary for gateway design in any interview.

---

## 1. The entity model (learn these four words)

```
Request ──► ROUTE (matches host/path/method: "/orders/*")
                │ attached PLUGINS run: authn → rate-limit → transform → log
                ▼
            SERVICE (upstream definition: http://orders.internal:8080)
                ▼
            UPSTREAM + TARGETS (load-balanced backend instances, health-checked)
```

- **Service** = an upstream API (name + URL + timeouts + retries). **Route** = match rules that map inbound requests to a Service. **Consumer** = an identified caller (API key / JWT subject) — the entity rate limits and ACLs hang off. **Plugin** = middleware attachable **globally, per-Service, per-Route, or per-Consumer** — that scoping hierarchy is Kong's core design.
- **Plugin execution phases** (inherited from OpenResty/Nginx): `access` (before proxying — authn/z, rate limiting live here), `header_filter`/`body_filter` (response rewriting), `log` (async, after response). Understanding "plugins are lifecycle hooks around a proxy" generalizes to every gateway (Apigee policies, Envoy filters, Spring Cloud Gateway filters).
- **Bundled plugins you should be able to name:** `key-auth`, `jwt`, `oauth2`, `rate-limiting` (local or Redis-backed cluster-wide — [the distributed rate-limit problem](../../02-building-blocks/rate-limiting/README.md)), `proxy-cache`, `request-transformer`, `cors`, `prometheus` ([observability](../../10-security-observability/observability/README.md)), `zipkin` (tracing).
- **Deployment modes:** classic (Postgres-backed config), **DB-less** (declarative `kong.yml` — GitOps-friendly), **hybrid** (control plane / data plane split — note the echo of the [service-mesh](../../07-microservices/service-mesh/README.md) architecture), and **Kong Ingress Controller** (gateway configured via [Kubernetes](../kubernetes/README.md) CRDs). Kong = north-south; mesh = east-west — same [disambiguation](../../02-building-blocks/api-gateway/README.md) as always.

## 2. Installation & a complete working setup

```bash
# DB-less Kong in Docker with a declarative config:
cat > kong.yml <<'EOF'
_format_version: "3.0"
services:
  - name: orders
    url: http://host.docker.internal:8080
    routes:
      - name: orders-route
        paths: ["/orders"]
plugins:
  - name: key-auth                 # global: every route needs an API key
  - name: rate-limiting
    config: { minute: 5, policy: local }
consumers:
  - username: shilpak
    keyauth_credentials:
      - key: my-secret-key
EOF

docker run -d --name kong -p 8000:8000 -p 8001:8001 \
  -v $PWD/kong.yml:/kong/kong.yml \
  -e KONG_DATABASE=off -e KONG_DECLARATIVE_CONFIG=/kong/kong.yml \
  kong:latest

curl http://localhost:8000/orders                       # 401 — no key
curl http://localhost:8000/orders -H 'apikey: my-secret-key'   # proxied!
# 6th call within a minute → 429 with X-RateLimit-* headers
```

Port 8000 = proxy (data plane), 8001 = Admin API (control plane) — never expose 8001 publicly.

## 3. The from-scratch implementation

[`MiniGateway.java`](MiniGateway.java) builds Kong's architecture in one file on the JDK's `HttpServer`: **route matching → a plugin chain (API-key auth, token-bucket rate limiting per consumer, request/response header transformation, logging) → reverse proxy to an upstream** — with plugins as an ordered list of interfaces exactly like Kong's phases. Includes a demo upstream, so it runs end-to-end: `401` without a key, `429` past the rate limit, proxied with `X-Consumer-Username` injected otherwise.

## 4. Interview soundbites

- "Kong models the gateway as Service/Route/Consumer/Plugin — plugins are lifecycle hooks scoped globally, per-route, or per-consumer; that scoping is the whole configuration model."
- "Rate limiting at the gateway is per-node by default; cluster-accurate limits need the Redis-backed policy — the standard [distributed rate-limiting trade](../../02-building-blocks/rate-limiting/README.md) of accuracy vs a shared dependency."
- "DB-less declarative config makes the gateway GitOps-managed; hybrid mode splits control and data planes like a mesh."
- "Gateway handles north-south concerns — authn, limits, routing; fine-grained authz stays in services ([BOLA](../../10-security-observability/authentication-authorization/README.md)), and east-west belongs to the mesh."

**Related:** [API Gateway](../../02-building-blocks/api-gateway/README.md) · [Rate Limiting](../../02-building-blocks/rate-limiting/README.md) · [Nginx](../nginx/README.md) · [Service Mesh](../../07-microservices/service-mesh/README.md)
