# Networking Fundamentals — OSI Model, TCP, and UDP

> **The question this answers, precisely:** every API design decision in this section — REST vs gRPC vs GraphQL, WebSockets vs SSE, even which load balancer type to use — is downstream of a smaller set of facts about how bytes actually move across a network. This file is the "why" underneath every other file in this section: what the OSI model's seven layers actually do, how TCP earns its reliability guarantees (and what that costs), why UDP exists at all, and how HTTP's own evolution (1.1 → 2 → 3) is really a story about which transport-layer trade-offs each version was willing to make. Skipping this and jumping straight to "REST vs gRPC" is exactly the shallow-coverage gap this guide exists to close.

---

## 1. The OSI Model — Seven Layers, What Each One Actually Does

The **OSI (Open Systems Interconnection) model** is a conceptual, seven-layer reference for how network communication is structured. No real system implements all seven layers as cleanly separated code, but the model is the shared vocabulary the entire industry uses to talk about "which layer" a problem lives at — including every "L4 vs L7" conversation in [Load Balancers](../../02-building-blocks/load-balancers/README.md) and [API Gateway](../../02-building-blocks/api-gateway/README.md).

| Layer | Name | What it does | Protocol Data Unit (PDU) | Real examples |
|---|---|---|---|---|
| **7** | Application | The actual protocol your application code speaks | Data / Message | HTTP, gRPC, DNS, SMTP, WebSocket (post-handshake) |
| **6** | Presentation | Data format, encoding, encryption/decryption | Data | TLS/SSL encryption, character encoding (UTF-8), compression (gzip) |
| **5** | Session | Establishes, manages, and tears down a logical session between two hosts | Data | TLS session resumption, API session tokens (conceptually) |
| **4** | Transport | End-to-end delivery between processes, with or without reliability | Segment (TCP) / Datagram (UDP) | TCP, UDP, QUIC |
| **3** | Network | Logical addressing and routing between different networks | Packet | IP (IPv4/IPv6), ICMP, routers |
| **2** | Data Link | Addressing and error-detection on a single physical link (same local network) | Frame | Ethernet, Wi-Fi (802.11), MAC addresses, switches |
| **1** | Physical | Raw bits over a physical medium | Bit | Copper cable, fiber optic, radio waves, voltages |

**Why the layer numbers matter operationally, not just academically:**
- A device is described by the **highest layer it operates at**. A network **switch** is a Layer 2 device (it forwards frames by MAC address, has no concept of IP). A **router** is Layer 3 (it forwards packets by IP address across networks). An **L4 load balancer** (§2, and see [Load Balancers §2](../../02-building-blocks/load-balancers/README.md)) reads TCP/UDP headers only — source/destination IP and port — and cannot see anything above that. An **L7 load balancer** or **API gateway** terminates all the way up to Layer 7, meaning it fully speaks HTTP and can route on URL path, headers, or cookies.
- **Each layer only trusts the interface of the layer directly below it** — this is what lets you swap Wi-Fi for Ethernet (Layer 1/2) without changing anything about how TCP (Layer 4) or HTTP (Layer 7) behaves. This is also precisely why the "layer" of a piece of infrastructure defines what it's *capable of* — a Layer 3/4 firewall can block or allow by IP/port; only a Layer 7 firewall (a WAF) can block by "this HTTP request body contains a SQL injection pattern."
- **Encapsulation:** as data travels down the stack for transmission, each layer wraps the data from the layer above with its own header (and sometimes trailer) — an HTTP request becomes a TCP segment (adds source/dest port, sequence numbers), becomes an IP packet (adds source/dest IP), becomes an Ethernet frame (adds source/dest MAC). The receiving side unwraps in reverse. This nested-envelope structure is why a packet capture (`tcpdump`/Wireshark) shows layered headers stacked on top of each other for every single packet.

**The simpler TCP/IP model**, which is what real-world networking stacks (and most engineers) actually reference day-to-day, collapses OSI's seven layers into four: **Application** (OSI 5-7 combined), **Transport** (OSI 4), **Internet** (OSI 3), **Network Access/Link** (OSI 1-2). When someone says "the transport layer," they mean the same thing in either model — this guide uses OSI layer numbers since that's the vocabulary interviewers and vendor documentation (AWS, GCP load balancer docs, e.g.) use.

---

## 2. Layer 4 (Transport): TCP — Reliability, Mechanically

**TCP (Transmission Control Protocol)** is a **connection-oriented, reliable, ordered, byte-stream** protocol. Every one of those four adjectives is a specific engineering guarantee, purchased at a specific cost — and understanding the mechanism behind each is what separates "TCP is reliable" from actually being able to reason about latency and failure modes.

### The Three-Way Handshake (connection establishment)

Before any data flows, client and server synchronize sequence numbers:

```
Client                          Server
  |------ SYN (seq=x) --------->|      "I want to connect, my starting sequence number is x"
  |<--- SYN-ACK (seq=y, ack=x+1)|      "OK, here's mine (y), and I acknowledge yours"
  |------ ACK (ack=y+1) ------->|      "Acknowledged. Connection established."
```

This round trip **costs one full network round-trip time (RTT) before a single byte of application data is sent** — a fact that matters enormously for latency-sensitive systems and is a large part of *why* HTTP/2 (persistent connections, §5) and HTTP/3 (§5, which drops TCP entirely) exist: paying a full RTT (or more, once TLS is layered on top — TLS 1.2 adds another 1-2 RTTs, TLS 1.3 reduces this to effectively 1) for *every new connection* is expensive at scale, and connection reuse or handshake elimination is a direct latency win.

### Reliability: sequence numbers, ACKs, and retransmission

Every byte TCP sends is numbered. The receiver acknowledges (`ACK`) the sequence numbers it has successfully received; if the sender doesn't get an ACK within a calculated timeout, it **retransmits** the unacknowledged data, assuming it was lost. This is the mechanical basis of "TCP guarantees delivery" — it doesn't magically prevent packet loss (that happens at Layer 1-3, on real physical networks, constantly), it **detects loss and repairs it transparently** so the application never has to think about it.

### Ordering: the receive buffer

Packets can arrive out of order (different packets can take different physical routes). TCP's receiver holds out-of-order segments in a buffer and only delivers a contiguous, in-order byte stream to the application — which is also *why* a single lost/delayed packet can stall delivery of everything after it, even if those later bytes already arrived. This is called **head-of-line (HOL) blocking**, and it's the single most important TCP limitation for understanding HTTP/2's multiplexing trade-off (§5) and why HTTP/3 moved away from TCP entirely.

### Flow control: the receive window

The receiver advertises a **window size** — how many unacknowledged bytes it's currently willing to buffer — so a fast sender can't overwhelm a slow receiver. This window is renegotiated continuously as data is read by the receiving application and buffer space frees up.

### Congestion control: protecting the network, not just the receiver

Separately from flow control (protecting the *receiver*), TCP implements **congestion control** to protect the *network* from being overwhelmed by all its senders collectively. The classic algorithm, **TCP Reno/CUBIC** (CUBIC is the modern Linux default): start with a small **congestion window**, grow it (originally exponentially — "slow start," a name that's misleading since it actually ramps up fast), and back off multiplicatively when packet loss is detected (interpreted as a signal of network congestion). This is why a brand-new TCP connection is initially *slower* than a long-lived one that has already grown its window — a fact with real implications for short-lived-connection-heavy architectures, and another reason connection reuse (keep-alive, HTTP/2 multiplexing) is a genuine performance technique, not just a minor optimization.

### Connection teardown

A four-way handshake (`FIN`/`ACK` in each direction, since TCP connections are full-duplex and each direction closes independently), followed by a **`TIME_WAIT`** state on the side that closed first — held for up to 2x the maximum segment lifetime to guarantee any straggling duplicate packets from the old connection are discarded rather than confused with a new connection reusing the same port. High-throughput servers that open/close many short connections can exhaust available ports/file descriptors sitting in `TIME_WAIT` — a genuine, historically common production issue, and one more argument for connection pooling and HTTP keep-alive.

### What TCP costs you, summarized

All of the above — handshake, ACKs, retransmission logic, ordering buffers, window management — is **CPU and memory work on both ends, plus latency from the handshake and from head-of-line blocking on loss.** TCP is the right trade-off when correctness (nothing missing, nothing out of order) matters more than raw speed — which is most application traffic, and why HTTP has always run over it (until HTTP/3, §5).

---

## 3. Layer 4 (Transport): UDP — Simplicity by Design

**UDP (User Datagram Protocol)** is TCP's opposite: **connectionless, unreliable, unordered**. There's no handshake, no acknowledgment, no retransmission, no ordering guarantee, and no congestion control (by default) — a sender just fires datagrams and moves on. The UDP header is a mere 8 bytes (source port, destination port, length, checksum) versus TCP's minimum 20 bytes (and TCP's header grows with options).

**Why you'd deliberately choose "unreliable":**
- **Latency-critical, loss-tolerant data:** live video/audio (VoIP, video calls) — a dropped frame that arrives late is *worse* than a dropped frame that's simply skipped, because by the time TCP retransmits it, playback has already moved on. Better to drop it and keep going.
- **Applications that implement their own reliability logic on top:** QUIC (§5) is built on UDP specifically so it can implement TCP-like reliability *without* TCP's head-of-line-blocking behavior baked into the OS kernel — reliability as an application-layer choice, not a transport mandate.
- **Simple query/response with no persistent connection benefit:** **DNS** predominantly uses UDP for its speed and statelessness (a query is one datagram, a response is one datagram — no need for a handshake for that little data; DNS falls back to TCP for large responses like zone transfers).
- **One-to-many delivery:** UDP supports broadcast and multicast (one datagram, many recipients) — TCP's connection-oriented model can't do this natively; used in service discovery protocols, some real-time streaming, and certain gaming architectures.
- **Games and real-time telemetry:** a stale position update for a game character is worse than useless — the game logic wants the *latest* state, not a perfectly ordered history of every intermediate state, so UDP (often with a thin custom reliability layer for the specific fields that do need to arrive, like "player fired a shot") is standard.

### TCP vs UDP — Direct Comparison

| Property | TCP | UDP |
|---|---|---|
| Connection | Connection-oriented (handshake required) | Connectionless (fire and forget) |
| Reliability | Guaranteed delivery via ACK + retransmit | No guarantee — packets can be lost silently |
| Ordering | Guaranteed in-order delivery | No ordering guarantee |
| Speed/overhead | Higher overhead (headers, handshake, ACKs) | Minimal overhead, lowest latency |
| Congestion control | Built-in (CUBIC, etc.) | None by default (app must implement if needed) |
| Header size | 20+ bytes | 8 bytes |
| Head-of-line blocking | Yes (§2) | No |
| Typical uses | HTTP/1.1, HTTP/2, most APIs, file transfer, email | DNS, VoIP, video streaming, gaming, QUIC/HTTP/3 |

**The framing that shows real understanding, not memorization:** TCP and UDP aren't "TCP is better, UDP is for special cases" — they represent a genuine engineering trade-off between **correctness guarantees** and **latency/simplicity**, and the right choice is a direct function of whether *stale-but-fast* or *complete-but-slower* better serves the specific data being sent. This is the exact same trade-off axis that shows up again in [Consistency Models](../../01-foundations/consistency-models/README.md) (strong vs. eventual) and [CAP Theorem](../../01-foundations/cap-theorem/README.md) — "how much correctness am I willing to trade for how much speed/availability" is one of the most recurring questions in this entire vault, and TCP vs. UDP is its most literal, wire-level expression.

---

## 4. Where IP and DNS Fit In

Two Layer-3/Layer-7 concepts worth naming explicitly, since they're assumed knowledge everywhere else in this vault:

- **IP (Internet Protocol, Layer 3)** handles logical addressing and routing *between* networks — it's what gets a packet from your laptop's network to a server's network across the internet's mesh of routers, but it makes **no reliability promise at all** (a router under load is allowed to simply drop a packet) — which is exactly the gap TCP's Layer-4 mechanisms (§2) exist to fill on top of it.
- **DNS (Domain Name System, Layer 7 — despite usually running over UDP at Layer 4)** resolves a human-readable name (`api.example.com`) to an IP address, and is itself the first load-balancing decision point in most systems — see [Load Balancers §7 — DNS-Based Load Balancing & GSLB](../../02-building-blocks/load-balancers/README.md) for how DNS responses themselves are used to route traffic to different regions before any application-level load balancer is even reached.

---

## 5. How HTTP's Evolution Maps Directly to These Transport Trade-offs

This is the payoff of the whole file: HTTP/1.1 → HTTP/2 → HTTP/3 is a chronological story of engineers running into TCP's specific costs (§2) and re-engineering around them, one at a time.

| Version | Transport | Key mechanism | What problem it fixed |
|---|---|---|---|
| **HTTP/1.0** | TCP | New TCP connection per request | Simple, but pays a full handshake (§2) for every single request |
| **HTTP/1.1** | TCP | **Persistent connections** (`Connection: keep-alive`) + pipelining (rarely used in practice) | Reuses one TCP connection for multiple sequential requests — saves repeated handshakes, but requests on one connection are still processed strictly one-at-a-time from the client's perspective in practice, so browsers historically opened **~6 parallel connections per domain** to compensate — itself a workaround with costs (6x the handshake/slow-start overhead, server-side connection pressure) |
| **HTTP/2** | TCP | **Multiplexing** — many concurrent request/response streams over **one** TCP connection, plus header compression (HPACK) | Eliminates the "6 parallel connections" workaround and its overhead — but because it's still TCP, a single lost packet blocks **all** multiplexed streams until retransmitted (TCP head-of-line blocking, §2, now amplified since more logical streams share the one blocked connection) |
| **HTTP/3** | **QUIC over UDP** | Reimplements reliability, ordering, and congestion control **at the application layer over UDP**, with **independent per-stream loss recovery** | A lost packet only blocks the *one stream* it belonged to, not all multiplexed streams — solves HTTP/2's residual head-of-line blocking by discarding TCP's kernel-level, connection-wide ordering guarantee and replacing it with a smarter, stream-aware one. Also folds the TLS handshake into the same round trip as the transport handshake, cutting connection setup to as little as one RTT (zero on resumption) |

**Why this belongs in an API design guide, not just a networking one:** choosing gRPC (which requires HTTP/2 — see [gRPC vs GraphQL vs REST](../grpc-graphql-rest/README.md)) is implicitly choosing multiplexing and its head-of-line-blocking trade-off; a WebSocket ([WebSockets](../websockets/README.md)) is a single long-lived TCP connection with all of TCP's ordering guarantees and costs; and knowing *why* HTTP/3 exists is knowing *exactly* what problem QUIC was built to solve, rather than reciting "it's faster."

---

## 6. Common Pitfalls

- Saying "TCP is reliable, UDP is unreliable" as if that settles which to use — the real question is whether the *specific data* benefits more from completeness or from freshness/speed.
- Confusing "Layer 4 load balancer" with "doesn't do TLS" — an L4 load balancer can still do TCP passthrough of TLS traffic; it just can't inspect the *decrypted* HTTP content, which is the actual distinction (see [Load Balancers §10](../../02-building-blocks/load-balancers/README.md)).
- Assuming HTTP/2's multiplexing eliminates all head-of-line blocking — it eliminates the *application-level* (one-request-at-a-time-per-connection) blocking of HTTP/1.1, but a single dropped TCP packet still stalls every multiplexed stream, which is exactly what HTTP/3/QUIC was built to fix.
- Not knowing that TLS adds its own round trips on top of TCP's handshake — a common gap when reasoning about "why does the first request feel slow."
- Treating OSI layers as something real systems implement cleanly in separate modules — it's a reference model for reasoning and vocabulary, not a literal software architecture.

---

## 7. 60-Second Interview Answer

> "The OSI model gives us shared vocabulary for 'which layer' a piece of infrastructure operates at — a switch is Layer 2, a router is Layer 3, and the L4-vs-L7 load balancer distinction is really 'does it stop at IP/port, or does it fully terminate and understand HTTP.' At the transport layer, TCP buys reliability, ordering, and congestion control through a three-way handshake, sequence numbers and ACKs with retransmission on loss, and a sliding window — all of which costs a round trip up front and creates head-of-line blocking, where one lost packet stalls everything behind it until it's retransmitted. UDP strips all of that away — no handshake, no guarantee, minimal 8-byte header — which is exactly why it's the right choice for latency-sensitive, loss-tolerant traffic like video calls, gaming, and DNS, where a stale retransmitted packet is worse than a dropped one. HTTP's own evolution is a direct response to TCP's costs: HTTP/1.1 added persistent connections to avoid repeated handshakes, HTTP/2 multiplexed many requests over one TCP connection to avoid the six-parallel-connections workaround, but because it's still TCP, one lost packet still blocks every multiplexed stream — which is exactly the problem HTTP/3 solves by moving to QUIC over UDP, reimplementing reliability at the application layer with independent per-stream loss recovery so a lost packet only stalls its own stream."

**Related:** [HTTP Methods & Status Codes](../http-fundamentals/README.md) · [gRPC vs GraphQL vs REST](../grpc-graphql-rest/README.md) · [WebSockets, SSE & Polling](../websockets/README.md) · [Load Balancers](../../02-building-blocks/load-balancers/README.md) · [CAP Theorem](../../01-foundations/cap-theorem/README.md) · [Consistency Models](../../01-foundations/consistency-models/README.md)
