# gRPC vs GraphQL vs REST

> **The question this answers, precisely:** given three mature API styles, which one belongs at which boundary of your system — and why do real architectures use all three at once? The junior answer picks a favorite; the senior answer assigns each to the boundary whose constraints it matches.

---

## 1. The three, mechanically

**REST** (see [REST Best Practices](../rest-best-practices/README.md)): resources over HTTP/1.1+, JSON bodies, HTTP methods and status codes as the contract. Its superpowers are **ubiquity** (every language, tool, proxy, and developer understands it), **cacheability** (GET + URLs plug directly into [CDNs](../../02-building-blocks/cdn/README.md) and HTTP caches), and human-debuggability (curl and read it).

**gRPC**: RPC framework over **HTTP/2** with **Protocol Buffers** as the wire format.
- **Protobuf is binary and schema-first:** a `.proto` file defines messages/services; the compiler generates client and server stubs in every language. Messages are field-number-tagged binary — typically **3–10x smaller than JSON** and much cheaper to serialize/parse (no field-name text, no parsing ambiguity).
- **HTTP/2 gives it multiplexing** (many concurrent calls over one TCP connection — no head-of-line blocking at the HTTP layer), header compression, and **native streaming**: unary, server-streaming, client-streaming, and bidirectional streaming are first-class call types.
- **The costs:** browsers can't speak native gRPC (needs gRPC-Web + a proxy layer), payloads aren't human-readable, and the binary/HTTP2 nature means standard HTTP caching and much familiar middleware doesn't apply. Load balancing needs care: long-lived HTTP/2 connections defeat naive L4 balancing (all requests pin to one backend), so you need L7/gRPC-aware balancing — a [load balancer](../../02-building-blocks/load-balancers/README.md) subtlety interviewers enjoy.

**GraphQL**: a query language over a single endpoint — the client specifies **exactly the shape of data it wants**, the server resolves it against a typed schema.
- **Solves two real client problems:** **over-fetching** (mobile client needs 3 fields, REST returns 40) and **under-fetching / the N+1 request problem** (render a screen = 1 request for the post + N requests for each author — GraphQL fetches the whole graph in one round trip). Strongly typed schema + introspection give excellent tooling.
- **The costs are server-side, and they're substantial:** every client can now write an arbitrarily expensive query, so you need **query-cost analysis / depth limiting** (a malicious or naive deeply-nested query is a DoS vector — see [Rate Limiting](../../02-building-blocks/rate-limiting/README.md), which must become cost-based, not request-based); the **N+1 problem reappears server-side** in resolvers (each post's author resolved with its own DB query — mitigated by **DataLoader**-style batching, a term worth naming); and HTTP caching largely breaks (POSTs to one endpoint — you rebuild caching at the application layer). Also: with great flexibility comes unpredictable load profiles — capacity planning is harder than for a fixed set of REST endpoints.

## 2. The decision framework: match the style to the boundary

| Boundary | Default choice | Why |
|---|---|---|
| **Public API** (third-party developers) | REST | Ubiquity, tooling, docs, HTTP caching, easy onboarding — the contract-longevity concerns from [REST Best Practices](../rest-best-practices/README.md) dominate |
| **Internal service-to-service** | gRPC | Both ends are yours: schema-first codegen enforces contracts across teams, binary efficiency and multiplexing cut latency/cost on the highest-volume calls, streaming covers push use-cases; browser-unfriendliness is irrelevant |
| **Client-facing aggregation layer** (mobile/web apps composing many services) | GraphQL (as a BFF) | The over/under-fetching problem is exactly the mobile-screen problem; one round trip per screen on high-latency mobile networks is a real win |
| **Real-time push** | Neither — see [WebSockets/SSE](../websockets/README.md) (or gRPC streaming internally) | Different problem |

**The composition that describes many real systems (and is a great whiteboard sentence):** *public REST at the edge, GraphQL as the backend-for-frontend aggregation layer for our own apps, gRPC for everything service-to-service behind the [gateway](../../02-building-blocks/api-gateway/README.md).*

## 3. Real-world references

- **Google** — gRPC is the open-sourcing of its internal Stubby; essentially all internal Google service traffic is schema-first binary RPC. The lesson: at high internal call volumes, serialization efficiency and generated contracts pay for themselves.
- **GitHub/Shopify** — public GraphQL APIs (with documented **query cost limits** — evidence that cost-based rate limiting isn't optional); **Facebook** built GraphQL for exactly the mobile-screen aggregation problem.
- **Stripe/Twilio** — deliberately REST-only public APIs: for third-party longevity and onboarding, boring wins.

## 4. Common pitfalls

- Recommending GraphQL "because it's flexible" without naming query-cost control, resolver N+1/DataLoader, and the caching loss — flexibility is the *problem statement*, not the win.
- Recommending gRPC for a public browser-facing API without mentioning gRPC-Web/proxying.
- Missing the gRPC + L4 load-balancing trap (connection pinning).
- Treating them as rivals for one slot instead of assigning per-boundary.
- Saying "REST is slow" — JSON-over-HTTP/1.1 vs protobuf-over-HTTP/2 differences matter at high internal volumes; they rarely dominate a public API's latency budget, where a database query dwarfs serialization.

## 5. 60-Second Interview Answer

> "I assign the style per boundary. Public APIs default to REST: ubiquity, human-debuggability, HTTP caching, and contract longevity dominate there, which is why Stripe and Twilio are deliberately REST-only. Internal service-to-service defaults to gRPC: schema-first protobuf gives generated, compiler-enforced contracts across teams, binary encoding is several times smaller and cheaper than JSON, HTTP/2 multiplexes calls over one connection, and streaming is native — the trade-offs, browser unfriendliness and unreadable payloads, don't matter when you own both ends, though you do need L7-aware load balancing because long-lived HTTP/2 connections pin to backends under naive L4 balancing. GraphQL earns its place as a backend-for-frontend aggregation layer: it solves mobile over-fetching and the one-round-trip-per-screen problem — but the flexibility transfers cost to the server, so it demands query-cost limits so arbitrary nested queries can't DoS you, DataLoader-style batching for resolver N+1, and rebuilt caching since HTTP caching breaks. Many real architectures are exactly that composition: REST at the public edge, GraphQL BFF for first-party apps, gRPC behind the gateway."

**Related:** [REST Best Practices](../rest-best-practices/README.md) · [WebSockets & SSE](../websockets/README.md) · [API Gateway](../../02-building-blocks/api-gateway/README.md) · [Load Balancers](../../02-building-blocks/load-balancers/README.md) · [Rate Limiting](../../02-building-blocks/rate-limiting/README.md)
