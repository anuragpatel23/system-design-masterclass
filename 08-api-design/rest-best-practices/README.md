# REST Best Practices & API Versioning

> **The question this answers, precisely:** what separates a professionally designed REST API — one that Stripe or Shopify would ship — from a "JSON over HTTP" endpoint collection, and how do you evolve it for years without breaking a single client you don't control? This is table stakes for API rounds and the "define your API" step of every HLD answer.

---

## 1. Resource-oriented design — the core discipline

REST models the domain as **resources (nouns) addressed by URLs, manipulated by a fixed set of HTTP methods (verbs)**. The discipline is refusing to invent verbs:

```
GET    /users/123/orders        list orders for user 123 (safe, cacheable)
POST   /users/123/orders        create an order (not safe, not idempotent → see Idempotency)
GET    /orders/456              fetch one
PUT    /orders/456              full replace (idempotent)
PATCH  /orders/456              partial update
DELETE /orders/456              delete (idempotent — deleting twice = deleted)

Anti-patterns interviewers listen for:
POST /getUserOrders             verb in URL — RPC wearing REST's clothes
GET  /deleteOrder?id=456        state change via GET — breaks caching, prefetchers DELETE your data
```

- **Method semantics are load-bearing, not style.** `GET` is **safe** (no side effects) — which is what makes it cacheable by [CDNs](../../02-building-blocks/cdn/README.md) and safe to retry blindly. `PUT`/`DELETE` are **idempotent by contract** — retrying them is safe, which plugs directly into [resilience retries](../../07-microservices/resilience-patterns/README.md). `POST` is neither, which is exactly why [idempotency keys](../idempotency/README.md) exist. When you state a method choice in an interview, you're implicitly stating its caching and retry behavior — say so. For the full method-by-method breakdown (safe/idempotent/cacheable for every method including HEAD, OPTIONS, TRACE, CONNECT) and the complete status code catalog, see [HTTP Fundamentals](../http-fundamentals/README.md).
- **Nesting shallowly:** `/users/123/orders` reads well; `/users/123/orders/456/items/9/discounts` couples the URL to your object graph — prefer flat top-level resources (`/order-items/9`) once a resource has its own identity.
- **Actions that don't map to CRUD** (cancel, refund, publish): model the action as a sub-resource — `POST /orders/456/cancellation` (or pragmatically `POST /orders/456/cancel`). Everyone hits this; having a considered answer matters more than which convention you pick.

## 2. The Richardson Maturity Model — how "RESTful" your API actually is

Most APIs called "REST" only partially satisfy REST's original constraints. The **Richardson Maturity Model** gives a precise, four-level vocabulary for exactly how far an API goes, and it's worth knowing because "is this really REST?" is a genuine, answerable question, not a matter of opinion:

- **Level 0 — The Swamp of POX ("Plain Old XML"):** one single endpoint, everything is a POST, the "method" is described inside the body (`{"action": "getUser", "id": 123}`). This is RPC-over-HTTP wearing REST's clothes — HTTP is used purely as a transport tunnel.
- **Level 1 — Resources:** multiple URLs now exist for different resources (`/users/123`, `/orders/456`), but every request might still use the same HTTP method (often POST) regardless of intent.
- **Level 2 — HTTP Verbs:** resources *and* proper use of HTTP methods and status codes (GET to read, POST to create, PUT to update, DELETE to remove, meaningful 2xx/4xx/5xx). **The overwhelming majority of APIs that call themselves "RESTful" — including Stripe's, GitHub's, and most others in production — stop here, and that's a legitimate, deliberate choice, not a failure.**
- **Level 3 — HATEOAS (Hypermedia as the Engine of Application State):** responses include **links to related actions/resources**, so a client discovers what it can do next from the response itself rather than from out-of-band documentation — e.g., an order response includes a `cancel` link only if the order is actually still cancellable:
  ```json
  {
    "id": 456,
    "status": "processing",
    "_links": {
      "self": { "href": "/orders/456" },
      "cancel": { "href": "/orders/456/cancel" }
    }
  }
  ```

**Why Level 3 is rare in practice, and why that's a defensible engineering choice to be able to articulate:** true HATEOAS requires clients to be built as generic hypermedia-following agents rather than hardcoding endpoint URLs — which most client codebases (mobile apps, SPAs) don't do, since they're compiled/deployed with explicit knowledge of the API shape anyway. The payoff — discoverability, and being able to change URLs server-side without breaking clients — rarely outweighs the real cost: extra payload size, extra client complexity, and a smaller ecosystem of tooling built around it. **The senior answer to "should we do HATEOAS" is "what would a client actually gain from discovering links dynamically versus us documenting the API," not "yes, because it's more RESTful"** — maturity level is a description of an API's design, not a scorecard where higher is automatically better.

## 3. Status codes and errors — the contract's error half

Use the code families precisely, because **clients and infrastructure branch on them**: retry logic retries 5xx/429 but not 4xx; gateways alert on 5xx rates. (Full catalog with the 502/503/504 distinction and every 4xx/5xx code explained: [HTTP Fundamentals §2](../http-fundamentals/README.md).)

- `200` OK · `201` Created (with a `Location` header) · `202` Accepted (async — work queued, poll a status URL: the REST face of a [message queue](../../02-building-blocks/message-queues/README.md)) · `204` No Content.
- `400` malformed request · `401` unauthenticated · `403` authenticated but forbidden · `404` not found (also used to hide existence from unauthorized callers) · `409` conflict (version clash, duplicate) · `422` semantically invalid · `429` rate-limited, with `Retry-After` ([rate limiting](../../02-building-blocks/rate-limiting/README.md)).
- `500` bug · `502/504` upstream failure/timeout · `503` overloaded/down, with `Retry-After`.
- **Error bodies are API too:** stable machine-readable code + human message + field-level details + a correlation/request ID for [tracing](../../10-security-observability/observability/README.md). Stripe's error format (`type`, `code`, `message`, `param`) is the canonical example. Returning `200 {"error": ...}` is the anti-pattern that breaks every piece of infrastructure that branches on status codes.

## 4. Versioning — the part that makes it an architecture question

The instant a third party depends on your API, every change is either **backward-compatible** or **a breaking change requiring a version**.

- **Compatible (ship freely):** adding optional request fields, adding response fields, adding endpoints/enum-values-clients-were-told-to-tolerate. This implies the compatibility rule to name: **clients must ignore unknown response fields** (tolerant reader), and **servers should be conservative in what they send, liberal in what they accept** (Postel's principle, applied with judgment).
- **Breaking (requires versioning):** removing/renaming fields, changing types or meanings, tightening validation, changing error codes clients branch on, changing defaults.
- **Where the version lives:** URL path (`/v2/orders` — explicit, cache-friendly, most common), header (`Accept: application/vnd.api+json;version=2` — "purer," less visible), or **date-based per-account pinning** — Stripe's famous model: each account is pinned to the API version current when it first called; upgrades are opt-in per account; internally, request/response transformers translate every pinned version to/from one canonical current implementation. That model is worth describing because it shows versioning is a *compatibility-management system*, not a URL prefix.
- **Deprecation is part of versioning:** announce with timelines, emit deprecation headers, track who still calls v1 (via gateway analytics), then sunset. "How do you turn off v1?" is a standard follow-up — the answer is data ("we monitor v1 traffic by API key and contact the long tail"), not hope.

## 5. The rest of the professional checklist

- **Pagination** on every collection endpoint from day one — retrofitting it is a breaking change ([Pagination Patterns](../pagination-patterns/README.md)).
- **Filtering/sorting** as query params (`?status=shipped&sort=-created_at`), with an allowlist of filterable fields (each filter is an index commitment — see [Indexing Strategies](../../06-databases-deep-dive/indexing-strategies/README.md)).
- **Rate limits** communicated in headers (`X-RateLimit-Remaining`, `Retry-After`) so clients can behave ([Rate Limiting](../../02-building-blocks/rate-limiting/README.md)).
- **AuthN/AuthZ** on every endpoint — OAuth2/API keys at the [gateway](../../02-building-blocks/api-gateway/README.md), object-level authorization in the service (the #1 real-world API vulnerability — see [Security Essentials](../../10-security-observability/security-essentials/README.md)).
- **Consistent conventions** — one casing (snake or camel), ISO-8601 UTC timestamps, IDs as strings (JavaScript's 53-bit integer limit silently corrupts 64-bit IDs — a real production bug class), money as integer minor units (never floats).
- **Concurrency control** for updates: `ETag` + `If-Match` (optimistic locking over HTTP) so two concurrent editors don't silently overwrite each other — the HTTP face of [transactions](../../06-databases-deep-dive/transactions-acid/README.md)' lost-update problem.

## 6. Real-world reference: Stripe

Stripe's API is the standard answer to "name a well-designed API": strict resource orientation, uniform error envelope, [idempotency keys](../idempotency/README.md) on all POSTs, cursor pagination everywhere, per-account date-based version pinning with request/response transformers, and long deprecation windows. Citing *why* each property matters — retries are safe, errors are machine-handleable, version upgrades are opt-in — turns name-dropping into evidence.

## 7. Common pitfalls

- Verbs in URLs and state changes via GET — the instant "hasn't designed a public API" tells.
- `200` with an error body, or `500` for validation failures — breaks every client's and gateway's branching.
- Treating versioning as "put /v1/ in the path" without a compatibility policy — the version prefix is the *least* interesting part; the additive-change discipline and deprecation process are the substance.
- Floats for money; 64-bit IDs as JSON numbers; local-time timestamps — three classic long-tail production bugs.
- No pagination on a collection that "will be small" — it never stays small.

## 8. 60-Second Interview Answer

> "A professional REST API is resource-oriented — nouns in URLs, the fixed HTTP methods as the only verbs — because method semantics are load-bearing: GET's safety is what makes responses cacheable, PUT and DELETE's idempotency is what makes retries safe, and POST being neither is exactly why idempotency keys exist. Status codes are contract, not decoration — clients and gateways branch on them, so validation is 4xx, server faults are 5xx, rate limits are 429 with Retry-After, and errors carry a stable machine-readable code plus a request ID. The deepest part is evolution: additive changes — new optional fields, new endpoints — ship freely under a tolerant-reader rule, while anything that removes, renames, or re-types is a breaking change requiring a version, a deprecation timeline, and gateway analytics to find who's still on the old one. I'd version in the URL path for visibility, or per-account date pinning at Stripe's scale of contract seriousness — their model of pinning each account to the version it onboarded with, translated internally to one canonical implementation, is the reference. Plus the day-one checklist: cursor pagination on every collection, rate-limit headers, ETags for concurrent updates, money in integer minor units, IDs as strings."

**Related:** [HTTP Fundamentals — Methods & Status Codes](../http-fundamentals/README.md) · [Networking Fundamentals — OSI, TCP, UDP](../networking-fundamentals/README.md) · [Idempotency](../idempotency/README.md) · [Pagination Patterns](../pagination-patterns/README.md) · [gRPC vs GraphQL vs REST](../grpc-graphql-rest/README.md) · [API Gateway](../../02-building-blocks/api-gateway/README.md) · [Security Essentials](../../10-security-observability/security-essentials/README.md)
