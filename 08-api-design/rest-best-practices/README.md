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

- **Method semantics are load-bearing, not style.** `GET` is **safe** (no side effects) — which is what makes it cacheable by [CDNs](../../02-building-blocks/cdn/README.md) and safe to retry blindly. `PUT`/`DELETE` are **idempotent by contract** — retrying them is safe, which plugs directly into [resilience retries](../../07-microservices/resilience-patterns/README.md). `POST` is neither, which is exactly why [idempotency keys](../idempotency/README.md) exist. When you state a method choice in an interview, you're implicitly stating its caching and retry behavior — say so.
- **Nesting shallowly:** `/users/123/orders` reads well; `/users/123/orders/456/items/9/discounts` couples the URL to your object graph — prefer flat top-level resources (`/order-items/9`) once a resource has its own identity.
- **Actions that don't map to CRUD** (cancel, refund, publish): model the action as a sub-resource — `POST /orders/456/cancellation` (or pragmatically `POST /orders/456/cancel`). Everyone hits this; having a considered answer matters more than which convention you pick.

## 2. HTTP Methods In Depth — Every Verb, What It Means, and When to Use It

Method choice isn't stylistic — each verb carries a **contract** about safety, idempotency, and cacheability that clients, proxies, browsers, and caches all rely on. Get this wrong and you break retry logic, prefetching, and caching for everyone downstream.

| Method | Purpose | Safe? (no side effects) | Idempotent? (same result if repeated N times) | Cacheable? | Has a request body? | Typical use |
|---|---|---|---|---|---|---|
| **GET** | Retrieve a resource | Yes | Yes | Yes (the default cacheable method) | No | Fetch `/orders/456`, list `/orders?status=shipped` |
| **HEAD** | Same as GET but returns only headers, no body | Yes | Yes | Yes | No | Check if a resource exists / get its size or `ETag` without downloading it |
| **OPTIONS** | Ask the server what methods/headers are allowed on a resource | Yes | Yes | No | No | Browsers send this automatically as a CORS "preflight" request before a cross-origin POST/PUT with custom headers |
| **POST** | Create a resource, or trigger a non-idempotent action | No | **No** | Only if explicitly marked cacheable (rare) | Yes | `POST /orders` (create), `POST /orders/456/cancel` (action) |
| **PUT** | Fully replace a resource at a known URL (or create it at that exact URL if absent) | No | **Yes** | No | Yes | `PUT /orders/456` with the *entire* order object — sending it twice yields the same end state |
| **PATCH** | Partially update a resource — send only the fields that change | No | Not guaranteed by spec (depends on the patch semantics used — see below) | No | Yes | `PATCH /orders/456 {"status": "shipped"}` |
| **DELETE** | Remove a resource | No | **Yes** | No | Usually no | `DELETE /orders/456` — deleting an already-deleted resource is still "deleted," so it's idempotent even though the second call often returns `404` instead of `204` |
| **TRACE** | Echoes back the exact request received, for diagnostics | Yes | Yes | No | No | Rarely used; frequently disabled outright since it's historically been a vector for cross-site tracing attacks |
| **CONNECT** | Establishes a tunnel to the server, typically for HTTPS through a proxy | N/A | N/A | No | No | Used by proxies to tunnel TLS traffic, not something application code implements directly |

**Why "safe" and "idempotent" are not the same thing, precisely:**
- **Safe** means the method causes no server-side state change at all. Only `GET`, `HEAD`, `OPTIONS`, `TRACE` are safe.
- **Idempotent** means calling it once has the same *end state* as calling it N times — but it can still have a side effect the first time. `DELETE` is idempotent but not safe: the first call changes state, but repeating it doesn't change the state any further.
- `POST` is neither safe nor idempotent by default — two identical `POST /orders` calls typically create two separate orders. This is precisely the gap [idempotency keys](../idempotency/README.md) exist to close: the client attaches a unique key so the server can recognize and de-duplicate a retried `POST`.
- `PATCH`'s idempotency depends on *what kind* of patch you send: `{"status": "shipped"}` is idempotent (setting a field to a fixed value repeatedly is stable), but `{"increment_quantity_by": 1}` is not (repeating it changes state further every time) — a genuinely common interview trap, since people assume PATCH is "just PUT but partial" and it isn't automatically idempotent.

**Why this matters architecturally:** a [load balancer](../../02-building-blocks/load-balancers/README.md) or client retry policy can safely auto-retry `GET`/`PUT`/`DELETE` on a timeout without asking anyone, because idempotency guarantees the retry is harmless — but auto-retrying a bare `POST` on timeout risks double-charging a customer or double-creating a resource, which is exactly why [resilience retry patterns](../../07-microservices/resilience-patterns/README.md) treat POST specially and why idempotency keys matter so much in payment APIs.

## 3. HTTP Status Codes — the Full Picture

Status codes are grouped into five classes, and **the class alone tells a client or piece of infrastructure how to react** without even parsing the body.

### 1xx — Informational (rare in application code)
- `100 Continue` — server has received request headers and the client should proceed to send the body (used with `Expect: 100-continue` for large uploads).
- `101 Switching Protocols` — used in the WebSocket handshake to upgrade an HTTP connection to `ws://` ([WebSockets](../websockets/README.md)).

### 2xx — Success
- `200 OK` — generic success, body contains the result.
- `201 Created` — a new resource was created; should include a `Location` header pointing to it.
- `202 Accepted` — request accepted but processing is asynchronous (work handed to a queue) — the REST-facing signature of a [message queue](../../02-building-blocks/message-queues/README.md)-backed operation, typically paired with a status URL to poll.
- `204 No Content` — success, but there's deliberately nothing to return (common for `DELETE`).
- `206 Partial Content` — response is a byte-range slice of the resource, used for resumable downloads and video streaming (`Range` header).

### 3xx — Redirection
- `301 Moved Permanently` — resource has a new permanent URL.
- `302 Found` — temporary redirect.
- `304 Not Modified` — sent in response to a conditional `GET` (`If-None-Match`/`If-Modified-Since`) when the client's cached copy is still valid — the HTTP mechanism that makes caching actually save bandwidth, not just save a database hit.
- `307 Temporary Redirect` / `308 Permanent Redirect` — like 302/301 but explicitly guarantee the method and body are preserved on the redirected request.

### 4xx — Client Error (the caller did something wrong; don't blindly retry)
- `400 Bad Request` — malformed syntax (broken JSON, wrong types).
- `401 Unauthorized` — actually means *unauthenticated* — no valid credentials presented at all.
- `403 Forbidden` — authenticated, but not permitted to perform this action on this resource.
- `404 Not Found` — resource doesn't exist; also deliberately used to hide the *existence* of a resource from a caller who isn't authorized to know about it (returning 403 instead of 404 can leak information).
- `405 Method Not Allowed` — the resource exists but doesn't support the HTTP method used.
- `406 Not Acceptable` — server can't produce a response matching the `Accept` header.
- `409 Conflict` — the request conflicts with current state (version mismatch, duplicate unique key, concurrent edits).
- `410 Gone` — resource used to exist and is permanently removed (stronger than 404).
- `413 Payload Too Large` — request body exceeds the server's accepted size.
- `415 Unsupported Media Type` — the `Content-Type` sent isn't one the server can parse.
- `422 Unprocessable Entity` — syntactically valid request that fails semantic/business-rule validation (e.g., well-formed JSON, but `end_date` before `start_date`).
- `429 Too Many Requests` — caller has been rate-limited; pair with a `Retry-After` header ([rate limiting](../../02-building-blocks/rate-limiting/README.md)).

### 5xx — Server Error (the server's fault; safe to retry in many cases, with backoff)
- `500 Internal Server Error` — an unhandled exception/bug; the generic catch-all.
- `501 Not Implemented` — server doesn't support the functionality required.
- `502 Bad Gateway` — this server, acting as a gateway/proxy, got an invalid response from upstream.
- `503 Service Unavailable` — server temporarily overloaded or down for maintenance; pair with `Retry-After`.
- `504 Gateway Timeout` — this server, acting as a gateway/proxy, didn't get a timely response from upstream — distinct from 502 (invalid response) vs 504 (no response in time), worth stating precisely since they map to different root causes (upstream bug vs upstream slowness).

**Why the class-level distinction is load-bearing:** generic retry middleware, [resilience patterns](../../07-microservices/resilience-patterns/README.md) like circuit breakers, and [load balancer](../../02-building-blocks/load-balancers/README.md) health checks all branch on the *class* first — "retry 5xx and 429, never blindly retry other 4xx" is closer to a universal rule than a case-by-case judgment call, which is exactly why returning `200 {"error": "..."}` is so damaging: it silently defeats every layer of infrastructure built on this convention.

- **Error bodies are API too:** stable machine-readable code + human message + field-level details + a correlation/request ID for [tracing](../../10-security-observability/observability/README.md). Stripe's error format (`type`, `code`, `message`, `param`) is the canonical example.

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

**Related:** [Idempotency](../idempotency/README.md) · [Pagination Patterns](../pagination-patterns/README.md) · [gRPC vs GraphQL vs REST](../grpc-graphql-rest/README.md) · [API Gateway](../../02-building-blocks/api-gateway/README.md) · [Security Essentials](../../10-security-observability/security-essentials/README.md)
