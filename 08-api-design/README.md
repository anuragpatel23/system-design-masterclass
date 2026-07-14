# 08 — API Design

> Every system in section 03 has a step where you say "and the API looks like this" — and at strong product companies (Stripe, Twilio, Shopify especially) API design is its **own interview round**, because the API is the one part of the system you can never refactor freely: clients you don't control depend on every detail you shipped. This section covers designing that contract layer: resource-oriented REST done properly, the protocol decision (REST vs gRPC vs GraphQL), real-time delivery (WebSockets/SSE/polling), pagination that doesn't fall over at scale, and idempotency — the single most-probed API topic in payment-adjacent interviews.

## Topics in this section

| Topic | The concrete question it answers |
|---|---|
| [Networking Fundamentals — OSI, TCP, UDP](networking-fundamentals/README.md) | What actually happens below HTTP — the seven OSI layers, how TCP earns reliability, why UDP exists, and how HTTP/1.1→2→3 is a running argument with TCP's costs? |
| [HTTP Fundamentals — Methods & Status Codes](http-fundamentals/README.md) | Every HTTP method's safe/idempotent/cacheable properties, and the full status code catalog including the 502 vs 503 vs 504 distinction? |
| [REST Best Practices & Versioning](rest-best-practices/README.md) | What does a professionally designed REST API actually look like — resources, methods, status codes, errors, versioning, compatibility, and how "RESTful" is it really (Richardson Maturity Model)? |
| [gRPC vs GraphQL vs REST](grpc-graphql-rest/README.md) | Which protocol for which boundary — public API, internal service-to-service, client-facing aggregation — and what does each look like on the wire? |
| [WebSockets, SSE & Polling](websockets/README.md) | How do you push data to clients in real time, what does the WebSocket handshake/frame protocol actually look like, and at what infrastructure cost? |
| [Pagination Patterns](pagination-patterns/README.md) | Why does `OFFSET 1000000` kill your database, and what do you do instead? |
| [Idempotency](idempotency/README.md) | How do you make "retry safely" true for operations that move money? |

## The through-line

An API is a **promise you can't take back**. Internal code gets refactored; a published API gets *versioned, deprecated, and supported for years*. Every topic here is a consequence of that: versioning and compatibility rules exist because you can't break clients; idempotency exists because networks force clients to retry; cursor pagination exists because the naive contract ("give me page 5000") makes a promise a database can't keep. Framing API decisions as *contract-longevity decisions* is the senior posture for this entire section.

## How this connects to the rest of the vault

- The API definition step of the [RESHADED framework](../09-interview-prep/interview-framework.md) is where this section plugs into every HLD answer in [03](../03-high-level-design/README.md).
- [API Gateway](../02-building-blocks/api-gateway/README.md) is where these contracts get enforced (auth, [rate limiting](../02-building-blocks/rate-limiting/README.md), routing); [WhatsApp](../03-high-level-design/whatsapp/README.md) and [Twitter Feed](../03-high-level-design/twitter-feed/README.md) consume the WebSockets and pagination notes directly.
- Idempotency is the API-layer half of the reliability story whose infrastructure half is [Resilience Patterns](../07-microservices/resilience-patterns/README.md) and [Message Queues](../02-building-blocks/message-queues/README.md)' at-least-once delivery.

Previous: [07 — Microservices](../07-microservices/README.md) · Next: [09 — Interview Prep](../09-interview-prep/README.md)
