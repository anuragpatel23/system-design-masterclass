# HTTP Methods & Status Codes — The Full Contract

> **The question this answers, precisely:** [REST Best Practices](../rest-best-practices/README.md) tells you *how* to design a resource-oriented API well; this file is the reference for the underlying HTTP vocabulary itself — every method (not just the four everyone names), what **safe**, **idempotent**, and **cacheable** actually mean and why they're load-bearing properties, the full status code catalog organized by class, and the headers that carry metadata alongside them. This is the file to point to when someone asks "wait, what's the difference between PUT and PATCH" or "when do I use 409 vs 422" and needs a precise, not hand-wavy, answer.

---

## 1. The HTTP Methods, One by One

Every HTTP method carries three formally-defined properties that clients, caches, proxies, and load balancers all rely on — getting these right isn't pedantry, it's what makes retries, caching, and prefetching *safe* to automate.

- **Safe** — the method must not change server state. A safe method can be called by anything (a browser prefetcher, a search engine crawler, a monitoring probe) without fear of side effects.
- **Idempotent** — calling it once or N times produces the **same server state** as calling it once (the *response* can differ, but the effect doesn't compound). This is what makes blind retries safe — see [Idempotency](../idempotency/README.md) and [Resilience Patterns](../../07-microservices/resilience-patterns/README.md).
- **Cacheable** — the response is permitted to be stored and reused for a subsequent identical request, subject to caching headers.

| Method | Safe? | Idempotent? | Cacheable? | Purpose |
|---|---|---|---|---|
| **GET** | Yes | Yes | Yes | Retrieve a representation of a resource. No body in the request (by convention — servers may ignore one if present). |
| **HEAD** | Yes | Yes | Yes | Identical to GET but returns **only headers, no body** — used to check existence, size (`Content-Length`), or freshness (`Last-Modified`/`ETag`) without transferring the payload. |
| **OPTIONS** | Yes | Yes | No | Asks the server what methods/headers are allowed on a resource — the mechanism behind **CORS preflight requests**, where a browser sends an OPTIONS request before a cross-origin POST/PUT to confirm the server permits it. |
| **TRACE** | Yes | Yes | No | Echoes back the received request for diagnostic loop-back testing. Rarely used in practice; often disabled in production (historically involved in cross-site tracing attacks). |
| **POST** | No | **No** | Only if explicitly marked cacheable via headers (rare) | Create a new resource, or trigger a non-idempotent action (submit an order, send an email). Calling it twice creates two of whatever it created — this is precisely why [idempotency keys](../idempotency/README.md) exist as an application-level fix for a method with no built-in retry safety. |
| **PUT** | No | **Yes** | No | **Full replacement** of a resource at a known URL — the entire resource representation is sent, and the server replaces whatever was there. Idempotent because sending the exact same PUT twice leaves the resource in the exact same final state. |
| **PATCH** | No | Not guaranteed (depends on the patch format) | No | **Partial update** — send only the fields that change. Whether it's idempotent depends on *how* the patch is expressed: `{"status": "shipped"}` is idempotent (same result every time); `{"increment_quantity_by": 1}` is **not** (applying it twice doubles the effect) — this distinction is a very common interview follow-up. |
| **DELETE** | No | **Yes** | No | Remove a resource. Idempotent because deleting an already-deleted resource still leaves it deleted — the *response code* may differ (200 the first time, 404 the second), but the *state* doesn't change. |
| **CONNECT** | No | No | No | Establishes a tunnel to a server, most commonly used by HTTP proxies to relay TLS traffic (the mechanism behind how forward proxies handle HTTPS without decrypting it) — not something application APIs implement directly, but worth knowing exists. |

### Why PUT vs. POST is a genuinely common point of confusion, precisely resolved

- **`POST /orders`** — "create an order," server assigns the ID, calling it twice creates two orders. Not idempotent.
- **`PUT /orders/456`** — "the order at this exact URL should now look exactly like this," client specifies the ID, calling it twice leaves exactly one order in exactly that state. Idempotent.

If the client doesn't know or control the resource's final identity ahead of time, it must be POST. If the client is asserting a complete, specific state at a known address, PUT is correct and gets you free retry safety.

### Why GET must never have side effects

A GET request can be triggered by things that are not your application's client at all: browser link-prefetching, a search engine crawler, a corporate proxy warming a cache, a monitoring tool polling for health. `GET /orders/456/cancel` (an all-too-common anti-pattern) means any of those actors can silently cancel an order. The fix is always to model the state change as a POST/PUT/PATCH/DELETE against a resource, never a GET with side effects — see [REST Best Practices §1](../rest-best-practices/README.md) for the resource-modeling version of this rule.

---

## 2. HTTP Status Codes — The Complete Working Catalog

Status codes aren't decoration — **clients and infrastructure branch on the numeric class**, so using the right one is what makes an API's error handling automatable rather than requiring a human to read prose. Retry logic (see [Resilience Patterns](../../07-microservices/resilience-patterns/README.md)) typically retries 5xx and 429, never 4xx (except 408); gateways alert on 5xx rate; browsers cache based on 2xx/3xx codes and specific headers.

### 1xx — Informational (rare in application code, but real)
| Code | Meaning |
|---|---|
| **100 Continue** | Server has received request headers and the client should proceed to send the body — used to avoid sending a large body the server would reject based on headers alone |
| **101 Switching Protocols** | Server agrees to switch protocols in response to an `Upgrade` header — this is exactly how a **WebSocket connection is established** (see [WebSockets §1](../websockets/README.md)): an HTTP GET with `Upgrade: websocket` gets a 101 response, and the connection becomes a raw WebSocket channel from that point on |

### 2xx — Success
| Code | Meaning | Typical use |
|---|---|---|
| **200 OK** | Generic success | GET, successful PUT/PATCH with a response body |
| **201 Created** | A new resource was created | Successful POST — should include a `Location` header pointing to the new resource |
| **202 Accepted** | Request accepted but processing is asynchronous | The work was queued (see [Message Queues](../../02-building-blocks/message-queues/README.md)); response typically includes a status-check URL for polling |
| **204 No Content** | Success, but no body to return | Successful DELETE, or a PUT that doesn't need to echo the resource back |

### 3xx — Redirection
| Code | Meaning |
|---|---|
| **301 Moved Permanently** | Resource has a new permanent URL — clients and search engines should update references (this is exactly what a [URL shortener](../../03-high-level-design/url-shortener/README.md) does *not* want to use for its redirects, since permanent caching would bypass click-tracking on every subsequent visit — 302 is the correct choice there) |
| **302 Found** | Temporary redirect — resource is temporarily at a different URL, don't update bookmarks/caches permanently |
| **304 Not Modified** | Sent in response to a **conditional GET** (`If-None-Match`/`If-Modified-Since`) when the resource hasn't changed — client should use its cached copy. This is the core mechanism behind efficient HTTP caching validation ([Caching](../../02-building-blocks/caching/README.md)) |
| **307/308 Temporary/Permanent Redirect** | Like 302/301, but explicitly guarantee the method and body are preserved on the redirected request (302/301 technically allow clients to switch to GET — a historical quirk 307/308 exist to fix) |

### 4xx — Client Error
| Code | Meaning | When to use it precisely |
|---|---|---|
| **400 Bad Request** | Malformed syntax — the server literally can't parse the request (broken JSON, wrong content-type) |
| **401 Unauthorized** | **Unauthenticated** — despite the name, this means "who are you?", not "you're not allowed." No valid credentials were presented at all |
| **403 Forbidden** | **Authenticated but not authorized** — the server knows who you are and is refusing anyway. The precise distinction from 401 is one of the most common interview clarifications: 401 = prove your identity; 403 = your identity is known and denied |
| **404 Not Found** | Resource doesn't exist — also deliberately used to **hide existence** from unauthorized callers (returning 403 on someone else's private resource leaks that it exists at all; some APIs deliberately return 404 instead for privacy) |
| **405 Method Not Allowed** | The resource exists but doesn't support this HTTP method (e.g., DELETE on a read-only resource) — should include an `Allow` header listing valid methods |
| **406 Not Acceptable** | Server can't produce a response matching the client's `Accept` header (requested a content type the server doesn't support) |
| **408 Request Timeout** | Server gave up waiting for the client to finish sending the request — notably, this is one 4xx code that's often safe to retry, since it reflects a transient network issue, not a bad request |
| **409 Conflict** | The request conflicts with the current state of the resource — classic case: two concurrent edits (optimistic-locking version mismatch via `ETag`/`If-Match`, see [REST Best Practices §4](../rest-best-practices/README.md)), or creating a resource that already exists with a uniqueness constraint |
| **410 Gone** | Resource existed once but was deliberately, permanently removed (distinct from 404's "never existed or unknown") — rarely used but precise when it applies |
| **422 Unprocessable Entity** | Request is syntactically valid (parses fine, unlike 400) but **semantically invalid** — a well-formed JSON body that fails business validation (e.g., an email field that isn't a valid email, a negative quantity) |
| **429 Too Many Requests** | Rate limit exceeded — should include a `Retry-After` header telling the client when to try again (see [Rate Limiting](../../02-building-blocks/rate-limiting/README.md)) |

### 5xx — Server Error
| Code | Meaning |
|---|---|
| **500 Internal Server Error** | Generic catch-all — an unhandled exception/bug on the server. Should never be a client's fault |
| **501 Not Implemented** | Server doesn't support the functionality required (e.g., an unrecognized method) |
| **502 Bad Gateway** | A server acting as a gateway/proxy (like a [load balancer](../../02-building-blocks/load-balancers/README.md) or [API gateway](../../02-building-blocks/api-gateway/README.md)) got an invalid response from an upstream server it forwarded the request to |
| **503 Service Unavailable** | Server is temporarily overloaded or down for maintenance — should include `Retry-After`. This is what a load balancer returns when **no healthy targets** are available in its pool (see [Load Balancers §9](../../02-building-blocks/load-balancers/README.md)) |
| **504 Gateway Timeout** | A gateway/proxy didn't get a response from the upstream server **within its own timeout window** — distinct from 502 (upstream responded, but with garbage) and 503 (no upstream available at all) |

### The 502 / 503 / 504 distinction, precisely (a very common follow-up question)

- **502** — an upstream *did* respond, but the response was invalid/malformed from the proxy's point of view.
- **503** — the proxy/load balancer has **no healthy backend to send the request to at all** (all targets failed health checks, or the service is explicitly in maintenance mode).
- **504** — a backend was reached and a request was forwarded, but it **never responded within the timeout** — the backend might be alive but too slow, deadlocked, or stuck.

Knowing this distinction lets you diagnose *where* in a request's path something broke just from the status code alone — a genuinely practical, frequently-tested piece of knowledge.

---

## 3. Headers Worth Knowing Precisely

| Header | Direction | Purpose |
|---|---|---|
| `Content-Type` | Both | Media type of the body (`application/json`, `application/x-protobuf`, `multipart/form-data`) |
| `Accept` | Request | What media types the client can handle in the response |
| `Authorization` | Request | Credentials — `Bearer <token>` for OAuth2/JWT, `Basic <base64>` for basic auth |
| `Cache-Control` | Both | Caching directives (`no-store`, `max-age=3600`, `private`/`public`) — the primary mechanism [CDNs](../../02-building-blocks/cdn/README.md) and browsers use to decide what to cache and for how long |
| `ETag` / `If-Match` / `If-None-Match` | Both | Resource version fingerprint for optimistic concurrency control (`If-Match`) and cache validation (`If-None-Match` → 304, §2) |
| `Location` | Response | Points to a newly created resource (with 201) or a redirect target (with 3xx) |
| `Retry-After` | Response | How long a client should wait before retrying — accompanies 429 and 503 |
| `X-Forwarded-For` | Request (added by proxies) | Preserves the original client IP as a request passes through proxies/load balancers that terminate the TCP connection (see [Load Balancers §10](../../02-building-blocks/load-balancers/README.md)) |
| `Idempotency-Key` | Request (application-defined) | Client-generated unique key so a retried POST is recognized as a duplicate, not a new operation (see [Idempotency](../idempotency/README.md)) |

---

## 4. Common Pitfalls

- Returning `200 OK` with an error described in the body (`{"success": false}`) — breaks every piece of infrastructure that branches on the status code, from retry logic to monitoring dashboards.
- Using `401` when the real problem is authorization, not authentication — confuses clients about whether re-authenticating would even help.
- Treating PATCH as automatically idempotent — it depends entirely on whether the patch document describes an absolute state or a relative delta.
- Using GET for state-changing operations "because it's simpler to test in a browser" — breaks safety, breaks caching, and can be triggered by prefetchers/crawlers with real side effects.
- Not distinguishing 502/503/504 in logs and dashboards — collapsing them into "5xx" throws away exactly the information needed to tell "upstream is broken" from "upstream is unreachable" from "upstream is just slow."

---

## 5. 60-Second Interview Answer

> "HTTP methods carry three formal properties that infrastructure relies on: safe, meaning no side effects, which is what lets GET be cached and prefetched; idempotent, meaning repeating the call doesn't change the outcome, which is what makes PUT and DELETE safe to retry blindly; and cacheable. POST is neither safe nor idempotent, which is exactly why idempotency keys exist as an application-level fix. PUT is a full replacement at a known URL and is idempotent by definition; PATCH is a partial update whose idempotency depends on whether the patch describes an absolute value or a relative delta — 'set status to shipped' is idempotent, 'increment by one' isn't. For status codes, I treat the class as the contract: 2xx success, 3xx redirect, 4xx means the client should not blindly retry — with 429 being the one exception, since it comes with Retry-After — and 5xx means the server should be retried, typically with backoff. The three gateway error codes are worth being precise about: 502 means an upstream responded but with garbage, 503 means there's no healthy upstream to route to at all, and 504 means a request was forwarded but the upstream never responded in time — that distinction alone tells you where in the request path to start debugging."

**Related:** [REST Best Practices & Versioning](../rest-best-practices/README.md) · [Idempotency](../idempotency/README.md) · [Networking Fundamentals — OSI, TCP, UDP](../networking-fundamentals/README.md) · [Rate Limiting](../../02-building-blocks/rate-limiting/README.md) · [Resilience Patterns](../../07-microservices/resilience-patterns/README.md)
