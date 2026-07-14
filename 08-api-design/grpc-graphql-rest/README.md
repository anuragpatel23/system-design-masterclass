# gRPC vs GraphQL vs REST (and where SOAP fits historically)

> **The question this answers, precisely:** given the major mature API styles, which one belongs at which boundary of your system, why do real architectures use several at once, and — going deeper than the usual comparison — what does each one actually look like on the wire? The junior answer picks a favorite; the senior answer assigns each to the boundary whose constraints it matches and can explain the underlying serialization/transport mechanics, not just the marketing pitch.

---

## 1. REST, mechanically

Resources over HTTP/1.1+, JSON bodies, HTTP methods and status codes as the contract — the full mechanical treatment (methods, status codes, safe/idempotent/cacheable) lives in [HTTP Fundamentals](../http-fundamentals/README.md) and [REST Best Practices](../rest-best-practices/README.md). Its superpowers are **ubiquity** (every language, tool, proxy, and developer understands it), **cacheability** (GET + URLs plug directly into [CDNs](../../02-building-blocks/cdn/README.md) and HTTP caches via `Cache-Control`/`ETag`), and human-debuggability (curl it, read it — no special tooling required).

**JSON's actual cost:** it's a **text-based, self-describing** format — every object repeats its field names as strings (`{"user_id": 4821, "user_name": "..."}`), which is what makes it human-readable but also why it's larger on the wire and slower to parse than a binary, schema-defined format. This is the concrete mechanical fact behind "REST is less efficient than gRPC" — it's not vague hand-waving, it's repeated field-name strings and text-based number encoding, every single message.

---

## 2. gRPC, mechanically

**gRPC** is an RPC framework over **HTTP/2** (see [Networking Fundamentals §5](../networking-fundamentals/README.md) for why HTTP/2's multiplexing matters here) using **Protocol Buffers (protobuf)** as the wire format.

### The `.proto` file — schema-first, code-generated

```protobuf
// pricing.proto
syntax = "proto3";

service PricingService {
  rpc GetPrice (PriceRequest) returns (PriceResponse);         // unary — one request, one response
  rpc StreamPriceUpdates (PriceRequest) returns (stream PriceResponse); // server streaming
}

message PriceRequest {
  string sku = 1;      // the number is a FIELD TAG, not a default value -- see below
}

message PriceResponse {
  string sku = 1;
  int64 price_cents = 2;
  string currency = 3;
}
```

A compiler (`protoc`) generates client and server stubs in every supported language from this **single source of truth** — the schema is enforced at compile time on both ends, not just documented in prose (as REST's OpenAPI spec typically is, optionally and often out of sync with reality).

### Why protobuf is smaller and faster than JSON — the actual mechanism

Each field in a `.proto` message is assigned a small integer **tag** (`= 1`, `= 2`, `= 3` above). On the wire, protobuf encodes each field as `(tag, wire_type)` followed by the value — **field names are never transmitted at all**, only these compact tag numbers, and numbers use variable-length encoding (`varint`) so small numbers take as little as one byte. The result is typically **3-10x smaller** than the equivalent JSON, and because there's no text parsing (no quote-matching, no string-to-number conversion), serialization/deserialization is also substantially cheaper in CPU terms. This is a fixed, well-documented mechanical property, not a vague claim.

### HTTP/2 gives gRPC its other core capabilities

- **Multiplexing** — many concurrent RPC calls share **one** TCP connection, no head-of-line blocking at the HTTP layer (though see [Networking Fundamentals §5](../networking-fundamentals/README.md) for the residual TCP-level HOL blocking this doesn't solve).
- **Native streaming** — because HTTP/2 supports long-lived, bidirectional streams as a first-class primitive, gRPC exposes four call types directly in the schema: **unary** (request/response), **server streaming** (one request, a stream of responses — e.g., live price updates), **client streaming** (a stream of requests, one final response — e.g., uploading chunks), and **bidirectional streaming** (both sides stream independently — e.g., a live chat backend).

### The costs, precisely

- **Browsers cannot speak native gRPC** — browser networking stacks don't expose the raw HTTP/2 trailer-based framing gRPC needs, so browser clients require **gRPC-Web**, a variant that's proxied/translated (typically via Envoy) to real gRPC on the backend — an extra moving part.
- **Payloads aren't human-readable** — you can't `curl` and eyeball a protobuf response; debugging needs `grpcurl` or similar tooling that understands the schema.
- **Standard HTTP caching doesn't apply** — no URL-keyed GET semantics to hang a CDN cache off of.
- **Load balancing needs care** — a long-lived HTTP/2 connection multiplexes many logical RPCs over one TCP connection, which means a naive **L4** load balancer (see [Load Balancers §2](../../02-building-blocks/load-balancers/README.md)) sees only *one* connection and pins *all* of a client's calls to a single backend, defeating even distribution. The fix is **L7/gRPC-aware load balancing** (client-side load balancing, or an L7 proxy like Envoy that understands individual HTTP/2 streams) — a subtlety interviewers specifically enjoy probing for.

---

## 3. GraphQL, mechanically

A query language over a **single endpoint** — the client specifies exactly the shape of data it wants against a typed schema, and the server resolves it.

### Schema, query, and the resolver's N+1 problem — concretely

```graphql
# schema
type Post {
  id: ID!
  title: String!
  author: User!          # this field requires a resolver -- see below
}
type User {
  id: ID!
  name: String!
}
type Query {
  posts: [Post!]!
}
```

```graphql
# client query -- asks for exactly these fields, nothing more
query {
  posts {
    title
    author { name }
  }
}
```

If the naive server implementation resolves `posts` with one query, then resolves **each post's `author` field with its own separate database query**, fetching 20 posts triggers **1 + 20 = 21 database queries** — the classic **N+1 problem**, and it happens *inside the server*, not on the wire like REST's version of over-fetching. The standard fix is a **DataLoader**-style batching layer: instead of each resolver firing its own query immediately, author-ID lookups are collected within a single request tick and issued as **one** batched `WHERE id IN (...)` query. Naming "DataLoader" and "batching" specifically, rather than gesturing at "it can be optimized," is what distinguishes a real answer here.

### What GraphQL actually solves (two distinct real problems)

- **Over-fetching** — a REST `GET /posts` might return 40 fields per post; a mobile client rendering a list view needs 3. GraphQL lets the client ask for exactly `title` and `author.name` and nothing else.
- **Under-fetching / the round-trip problem** — rendering a screen with REST often means one request for the primary resource plus N follow-up requests for related data (a post, then a separate call per author) — GraphQL resolves the whole graph for that screen in **one** round trip, which matters disproportionately on high-latency mobile networks where each additional round trip costs a full RTT.

### What it costs — and the costs are structurally different from REST's, not just "more complex"

- **Query-cost analysis / depth limiting becomes mandatory.** Because the client controls query shape, a malicious or simply naive deeply-nested query (`posts { author { posts { author { posts { ... } } } } }`) can be a genuine denial-of-service vector — [Rate Limiting](../../02-building-blocks/rate-limiting/README.md) has to become **cost-based** (estimate the query's resolver cost before executing it), not simple request-counting.
- **HTTP caching largely breaks.** Nearly everything is a POST to one endpoint with a query body — there's no URL to key a CDN cache on, so caching has to be rebuilt at the application/resolver layer (e.g., per-field caching, persisted queries).
- **Unpredictable load profiles.** A fixed set of REST endpoints has fairly predictable per-endpoint load; an open query language means capacity planning has to account for the *worst* query shape a client is capable of asking for, not just the average one.

---

## 4. SOAP — the historical predecessor worth naming precisely

**SOAP (Simple Object Access Protocol)**, XML-based RPC with a rigid, formally-typed contract (**WSDL** — Web Services Description Language) predates REST's dominance and still persists in enterprise, banking, and government systems built in the 2000s–early 2010s.

- **What it offered that mattered at the time:** built-in, standardized extensions for security (WS-Security), transactions (WS-AtomicTransaction), and reliable messaging (WS-ReliableMessaging) — genuinely valuable in enterprise integration contexts before those concerns had better-known alternatives.
- **Why it lost to REST for general-purpose APIs:** verbose XML envelopes, a steep tooling/complexity curve, tight coupling to WSDL contracts that made evolution brittle, and — critically — REST's simplicity mapped naturally onto the web's own HTTP verbs and caching infrastructure in a way SOAP's transport-agnostic design didn't.
- **Where it's still the right/only answer today:** integrating with legacy enterprise systems (many banking, insurance, and government backends still expose SOAP/WSDL contracts) — knowing it exists and why it persists, rather than treating it as simply "obsolete," is a mark of practical experience rather than only textbook knowledge.

---

## 5. The decision framework: match the style to the boundary

| Boundary | Default choice | Why |
|---|---|---|
| **Public API** (third-party developers) | REST | Ubiquity, tooling, docs, HTTP caching, easy onboarding — the contract-longevity concerns from [REST Best Practices](../rest-best-practices/README.md) dominate |
| **Internal service-to-service** | gRPC | Both ends are yours: schema-first codegen enforces contracts across teams, binary efficiency and multiplexing cut latency/cost on the highest-volume calls, streaming covers push use-cases; browser-unfriendliness is irrelevant |
| **Client-facing aggregation layer** (mobile/web apps composing many services) | GraphQL (as a BFF — backend-for-frontend) | The over/under-fetching problem is exactly the mobile-screen problem; one round trip per screen on high-latency mobile networks is a real win |
| **Real-time push** | Neither — see [WebSockets/SSE](../websockets/README.md) (or gRPC streaming internally) | Different problem entirely |
| **Legacy enterprise integration** | SOAP (if it's already there) | Rewriting a stable, working WSDL-contract integration is rarely worth the cost purely for modernity's sake |

**The composition that describes many real systems (and is a great whiteboard sentence):** *public REST at the edge, GraphQL as the backend-for-frontend aggregation layer for our own apps, gRPC for everything service-to-service behind the [gateway](../../02-building-blocks/api-gateway/README.md).*

---

## 6. Real-world references

- **Google** — gRPC is the open-sourcing of its internal Stubby; essentially all internal Google service traffic is schema-first binary RPC. The lesson: at high internal call volumes, serialization efficiency and generated contracts pay for themselves.
- **GitHub/Shopify** — public GraphQL APIs (with documented **query cost limits** — evidence that cost-based rate limiting isn't optional); **Facebook** built GraphQL for exactly the mobile-screen aggregation problem it was originally designed to solve internally.
- **Stripe/Twilio** — deliberately REST-only public APIs: for third-party longevity and onboarding, boring wins.

---

## 7. Common pitfalls

- Recommending GraphQL "because it's flexible" without naming query-cost control, resolver N+1/DataLoader, and the caching loss — flexibility is the *problem statement*, not the win.
- Recommending gRPC for a public browser-facing API without mentioning gRPC-Web/proxying.
- Missing the gRPC + L4 load-balancing trap (connection pinning) — a specific, testable piece of knowledge, not a vague caveat.
- Treating them as rivals for one slot instead of assigning per-boundary.
- Saying "REST is slow" without being able to explain *why* mechanically — the answer is repeated field-name strings and text parsing in JSON versus tagged binary fields in protobuf, not a vibe.
- Dismissing SOAP as simply obsolete rather than recognizing where it's still the pragmatic answer (legacy enterprise integration).

---

## 8. 60-Second Interview Answer

> "I assign the style per boundary. Public APIs default to REST: ubiquity, human-debuggability, HTTP caching, and contract longevity dominate there, which is why Stripe and Twilio are deliberately REST-only. Internal service-to-service defaults to gRPC: schema-first protobuf gives generated, compiler-enforced contracts across teams; the field-tag binary encoding means field names are never sent over the wire at all, which is a large part of why it's several times smaller and cheaper to parse than JSON; HTTP/2 multiplexes calls over one connection and gives native streaming in four flavors. The trade-offs — browser unfriendliness needing gRPC-Web, and unreadable binary payloads — don't matter when you own both ends, though you do need L7-aware load balancing, because a long-lived HTTP/2 connection pins to one backend under naive L4 balancing. GraphQL earns its place as a backend-for-frontend aggregation layer: it solves mobile over-fetching and the one-round-trip-per-screen problem — but the flexibility transfers cost to the server, so it demands query-cost limits so arbitrary nested queries can't DoS you, DataLoader-style batching for the resolver-level N+1 problem, and rebuilt caching since there's no URL to key an HTTP cache on. Many real architectures are exactly that composition: REST at the public edge, GraphQL BFF for first-party apps, gRPC behind the gateway — with SOAP still showing up at the edges of legacy enterprise integrations that predate all of this."

**Related:** [HTTP Fundamentals](../http-fundamentals/README.md) · [Networking Fundamentals — OSI, TCP, UDP](../networking-fundamentals/README.md) · [REST Best Practices](../rest-best-practices/README.md) · [WebSockets & SSE](../websockets/README.md) · [API Gateway](../../02-building-blocks/api-gateway/README.md) · [Load Balancers](../../02-building-blocks/load-balancers/README.md) · [Rate Limiting](../../02-building-blocks/rate-limiting/README.md)
