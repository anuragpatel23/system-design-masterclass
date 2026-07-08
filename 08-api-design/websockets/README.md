# WebSockets, Server-Sent Events & Polling — Real-Time Delivery

> **The question this answers, precisely:** HTTP is request-response — the client asks, the server answers. So how does the *server* initiate delivery ("you have a new message", "driver location updated", "stock price changed"), and what does each technique cost at a million connected clients? This decision sits inside every chat, feed, dashboard, and location-tracking design in [section 03](../../03-high-level-design/README.md).

---

## 1. The four techniques, mechanically

**Short polling** — client asks every N seconds. Trivially simple, works everywhere. Costs: average latency = N/2; and at 1M clients polling every 5s you've bought **200,000 QPS of mostly-empty responses** — you're paying full request overhead (headers, auth, routing) to learn "nothing new." Fine for genuinely infrequent updates or as a fallback; wasteful as a primary channel.

**Long polling** — client asks; the server **holds the request open** (e.g., up to 30s) until data arrives or timeout, responds, and the client immediately re-asks. Near-instant delivery with plain HTTP semantics (works through old proxies/firewalls — why it was the pre-WebSocket standard). Costs: a held server connection per client anyway, re-request overhead per message, and thundering-herd re-connects after mass events.

**Server-Sent Events (SSE)** — one long-lived HTTP response the server keeps writing to (`Content-Type: text/event-stream`). **Server→client only.**
- Pros: it's *plain HTTP* — works with normal load balancers, auth, and HTTP/2 multiplexing; browsers' `EventSource` gives **automatic reconnection with `Last-Event-ID`** (resume-after-drop built into the protocol — a genuinely lovely detail); dead simple server-side.
- Cons: unidirectional (client→server goes as ordinary requests — usually fine!); over HTTP/1.1 a per-domain connection limit (~6) bites (fixed by HTTP/2); some corporate proxies buffer streaming responses.

**WebSockets** — client sends an HTTP **Upgrade** handshake; the connection becomes a **persistent, full-duplex, message-framed TCP channel**. Either side sends at any time, with minimal per-message overhead (no headers per message).
- Pros: true bidirectionality with lowest per-message latency/overhead — the correct tool for chat, multiplayer games, collaborative editing, high-frequency telemetry.
- Cons: **you now own a stateful connection layer**, and that's the real cost — see §2.

## 2. The real interview content: what 1M+ persistent connections force on your architecture

- **Stateful servers in a stateless world.** Every design in this vault scales app servers by making them stateless behind a [load balancer](../../02-building-blocks/load-balancers/README.md). A WebSocket breaks that: the connection *lives* on one specific server. Consequences: (1) LB must be L4/connection-aware, and "least connections" beats round-robin; (2) **deploys become disruptive** — restarting a server drops its 500k connections, so you need connection draining and client reconnect-with-jitter (see [Deployment Patterns](../../07-microservices/deployment-patterns/README.md) and [Resilience](../../07-microservices/resilience-patterns/README.md) — a mass reconnect is a self-inflicted thundering herd); (3) capacity math changes — a tuned server holds ~100k–1M mostly-idle connections (memory + FD limits, epoll), so 100M connected users ≈ hundreds of connection servers *before any message throughput*.
- **The routing problem (the key design step):** user A (connected to server 17) messages user B (connected to server 942). Server 17 must find B's server. Standard answer: a **connection registry** (`user_id → server`, in Redis or a [service-discovery](../../07-microservices/service-discovery/README.md)-like store) maintained on connect/disconnect, plus a **pub/sub backplane** ([message queue](../../02-building-blocks/message-queues/README.md) or Redis pub/sub) so the sending server publishes and B's server — subscribed to B's channel or its own server-topic — delivers. This registry+backplane pair is the heart of every chat design ([WhatsApp](../../03-high-level-design/whatsapp/README.md)).
- **Offline/missed messages:** the socket is transport, not storage — durable delivery means persisting messages and replaying on reconnect (sequence numbers / `Last-Event-ID` semantics), never assuming the socket delivered.
- **Heartbeats:** TCP half-open connections lie; ping/pong frames detect dead clients and keep NAT/LB idle timeouts from silently killing connections.

## 3. Decision framework

| Use case | Choice | Why |
|---|---|---|
| Notifications, feed updates, dashboards, live scores, job progress | **SSE** (or long polling as fallback) | Server→client only; plain-HTTP operability; auto-reconnect built in |
| Chat, collaborative editing, games, anything client↔server interactive | **WebSockets** | Full duplex, per-message overhead matters |
| Infrequent updates (minutes+), maximal simplicity, hostile networks | **Short/long polling** | Don't build a connection layer you don't need |
| Service-to-service streaming | **gRPC streaming** ([gRPC](../grpc-graphql-rest/README.md)) | Already schema-first HTTP/2 inside the mesh |
| Mobile push when app is closed | **Platform push (FCM/APNs)** | No socket survives the OS killing the app — a commonly-missed point |

**Senior rule of thumb:** *use the weakest primitive that satisfies the interaction pattern* — SSE where one-way suffices, WebSockets only when duplex is real, and platform push for closed apps. Jumping straight to WebSockets for a dashboard signals not having operated the stateful fleet.

## 4. Real-world reference

**WhatsApp** famously ran ~1–2M+ connections per server (Erlang/FreeBSD tuning) — the existence proof for the per-server connection math. **Twitch chat / Slack** are registry+backplane architectures at scale. **Stock tickers and news feeds** (one-way fan-out) are classic SSE. Each maps to a row of the table above.

## 5. Common pitfalls

- Choosing WebSockets for one-way updates "because real-time" — SSE is operationally an order of magnitude simpler.
- Designing the socket layer but not the **routing** (registry + backplane) — the actual hard part.
- No reconnect story: no jitter (thundering herd), no resume (missed messages), no heartbeats (ghost connections).
- Forgetting deploys: rolling a 200-server connection fleet without draining = a self-inflicted outage every release.
- Assuming the connection = delivery — durable messaging still needs persistence + acks + replay.

## 6. 60-Second Interview Answer

> "Four options in increasing capability: short polling — simple but latency equals the poll interval and a million clients polling every five seconds is two hundred thousand QPS of mostly-empty responses; long polling — hold the request until data arrives, near-real-time over plain HTTP, the classic pre-WebSocket fallback; SSE — one long-lived HTTP stream, server-to-client only, with automatic reconnect and resume via Last-Event-ID, operationally simple because it's still just HTTP; and WebSockets — a full-duplex persistent channel after an HTTP upgrade, lowest per-message overhead, the right tool for chat and anything interactive. The rule is to use the weakest primitive that fits: SSE for one-way feeds and dashboards, WebSockets only when bidirectionality is real, platform push for closed mobile apps. The architectural cost of WebSockets is statefulness: connections pin to servers, so I need least-connection L4 balancing, connection draining plus jittered reconnects for deploys, heartbeats to kill ghosts — and crucially a routing layer: a Redis registry mapping user to connection server plus a pub/sub backplane so a message from a user on server A reaches its recipient on server B. And the socket is transport, not storage — durable delivery means persist, ack, and replay on reconnect."

**Related:** [WhatsApp](../../03-high-level-design/whatsapp/README.md) · [Message Queues](../../02-building-blocks/message-queues/README.md) · [Load Balancers](../../02-building-blocks/load-balancers/README.md) · [Notification System](../../03-high-level-design/notification-system/README.md) · [Resilience Patterns](../../07-microservices/resilience-patterns/README.md)
