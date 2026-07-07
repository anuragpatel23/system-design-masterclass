# Design WhatsApp (Real-Time Messaging)

> **The one hard problem this really tests:** maintaining real-time, ordered, exactly-once-feeling message delivery over unreliable mobile networks, across online and offline recipients, at a scale of billions of messages per day — with end-to-end encryption layered on top without breaking any of it.

---

## 1. Requirements

### Functional
- 1:1 messaging and group messaging (up to some max group size, e.g., 1024).
- Delivery status: sent (✓), delivered (✓✓), read (✓✓ blue).
- Messages delivered even if the recipient is offline when sent (queued for later delivery).
- Media messages (images, video, voice notes).
- End-to-end encryption (mention it; deep protocol design is usually out of scope unless explicitly asked).
- Online/last-seen presence indicators.

### Non-Functional
- **Real-time delivery** — sub-second latency when both parties are online.
- **At-least-once delivery guarantee** — a message must never be silently lost, even across app crashes, network drops, or server failures; duplicates are acceptable if the client de-duplicates (see [Message Queues](../../02-building-blocks/message-queues/README.md#2-delivery-guarantees--the-precise-definitions)).
- **Ordering** — messages within a single conversation must arrive in the order they were sent (per-conversation ordering, not necessarily global ordering).
- **Massive concurrent connection count** — billions of devices need a persistent connection open at any given moment, which is a distinct scaling problem from typical stateless HTTP request/response scaling.

---

## 2. Back-of-Envelope Estimation

- Assume 2 billion monthly active users, ~50 billion messages/day sent → ~580,000 messages/sec average, bursty far higher at peak (e.g., New Year's Eve message spikes, publicly documented by WhatsApp engineering as multi-hour sustained peaks several times normal volume).
- Concurrent open connections: assume ~500 million devices connected at any given moment globally (accounting for time zones) — **this is the number that shapes the architecture more than raw message throughput does**, because it determines how many persistent (not request/response) connections the serving fleet must hold open simultaneously.
- Each connection consumes server-side memory/file-descriptor resources even when idle — a traditional one-thread-per-connection server model (common in older Java servlet containers) would need an enormous number of OS threads just to hold idle connections open, which is why this system fundamentally requires an **event-driven, non-blocking I/O architecture** (Erlang/OTP historically, per WhatsApp's well-known engineering history, or Netty-based async I/O in a JVM context) rather than a traditional thread-per-request model.

---

## 3. High-Level Design

```
   Device A (sender) ──persistent connection──▶ Connection/Gateway Server (holds
                                                   the open socket, does presence
                                                   tracking, routes messages)
                                                        │
                                              is Device B currently
                                              connected to SOME gateway
                                              server? (lookup in a
                                              distributed "session/
                                              presence" store)
                                                        │
                          ┌─────────────────────────────┴─────────────────────────┐
                          │ YES (Device B online)                                    │ NO (Device B offline)
                          ▼                                                          ▼
              Route message directly to the                            Persist message in an
              specific gateway server holding                          offline message queue /
              Device B's connection (often via                         store, keyed by recipient
              a message broker like Kafka/                             ID, with a durable store
              a custom pub/sub layer, since                            (see Data Model)
              the sending gateway server and
              receiving gateway server are
              usually DIFFERENT machines)
                          │                                                          │
                          ▼                                                          ▼
              Push message to Device B over                            When Device B reconnects,
              its open connection; on ack,                             gateway server queries the
              mark delivered (✓✓) and notify                           offline store and delivers
              sender                                                   all pending messages, in order
```

---

## 4. Component Deep Dive: The Connection/Gateway Layer

This is the component that makes this system architecturally distinct from a typical request/response web service.

- **Why not plain HTTP polling?** Polling (repeatedly asking "any new messages?") wastes enormous resources at this scale and adds latency proportional to the poll interval. A **persistent connection** (historically raw TCP with a custom lightweight protocol at WhatsApp; more commonly WebSockets or gRPC bidirectional streaming in modern designs) lets the server **push** a message to the client the instant it arrives, with no polling delay.
- **Stateful gateway servers, by necessity:** unlike a stateless HTTP app server (see [Scalability](../../01-foundations/scalability/README.md)), a connection gateway server **must** hold state — specifically, which device is connected to which specific machine, because that's literally where the open socket lives. This is one of the few legitimate cases where "the server holds state in memory" is not an anti-pattern but an inherent property of the problem — you can't externalize an open TCP/WebSocket connection to Redis.
- **The routing implication:** because Device A's message might need to reach Device B, who is connected to a *different* gateway server, the gateway fleet needs an internal routing mechanism — either a **shared, low-latency presence/session directory** (which gateway server is each user currently connected to) combined with direct server-to-server message routing, or an internal pub/sub layer (each gateway server subscribes to a channel per user connected to it, and messages are published to the recipient's channel regardless of which gateway server produced them).
- **Horizontal scaling of a stateful layer:** since each gateway server holds a specific, non-transferable set of live connections, scaling this layer horizontally means adding more gateway servers, each independently holding a fraction of the total connection count — this is horizontal scaling of a **partitioned stateful** system (partitioned by which connections happen to land on which server), a different pattern from the usual "any stateless replica can serve any request."

---

## 5. Message Ordering and Delivery Guarantees

- **Per-conversation ordering:** each message is assigned a **monotonically increasing sequence number scoped to the conversation** (not a global sequence, which would require unnecessary global coordination — see [Scalability](../../01-foundations/scalability/README.md#3-scalability-patterns-the-toolbox) and the [Message Queues](../../02-building-blocks/message-queues/README.md#4-ordering-guarantees) partitioned-ordering principle). The recipient's client can then detect gaps or out-of-order arrival and reorder/request retransmission locally.
- **At-least-once delivery with client-side deduplication:** every message carries a unique client-generated message ID. If a network blip causes the server or client to be unsure whether a message was received, it's safe to resend — the receiving client deduplicates by message ID before displaying it, exactly matching the [at-least-once + idempotent consumer](../../02-building-blocks/message-queues/README.md#2-delivery-guarantees--the-precise-definitions) pattern from the Message Queues building block, here applied at the client rather than a server-side consumer.
- **The three checkmark states are a client-visible representation of a delivery pipeline:** sent (✓, server has accepted and durably stored the message) → delivered (✓✓, recipient device has acknowledged receipt) → read (✓✓ blue, recipient has opened/viewed it) — each transition is a distinct acknowledgment event flowing back to the sender, itself delivered through the same persistent-connection infrastructure.

---

## 6. Data Model

```sql
-- Messages: a message belongs to a conversation, ordered by a per-conversation sequence.
-- At WhatsApp's actual scale this would be a wide-column/NoSQL store (see SQL vs NoSQL),
-- sharded by conversation_id, but the logical shape is shown relationally for clarity.
CREATE TABLE messages (
    conversation_id   BIGINT NOT NULL,
    sequence_number   BIGINT NOT NULL,   -- monotonic PER conversation, not global
    sender_id         BIGINT NOT NULL,
    content           BLOB NOT NULL,      -- encrypted payload; server never sees plaintext (E2E)
    sent_at           TIMESTAMP NOT NULL,
    PRIMARY KEY (conversation_id, sequence_number)
);

-- Offline message queue: pending messages for a currently-disconnected recipient.
-- Deleted once delivered and acknowledged -- this table should stay small in steady
-- state (most users are online most of the time relative to message volume),
-- but must handle the "phone off for days" tail case without unbounded growth
-- (a max retention / expiry policy is a reasonable, commonly-asked follow-up).
CREATE TABLE offline_messages (
    recipient_id      BIGINT NOT NULL,
    message_id        BIGINT NOT NULL,
    conversation_id   BIGINT NOT NULL,
    enqueued_at       TIMESTAMP NOT NULL,
    PRIMARY KEY (recipient_id, message_id)
);

-- Presence/session directory: which gateway server (if any) holds this user's live connection.
-- This is a small, extremely hot, low-latency-critical table/store -- typically Redis,
-- not a relational table, given the read/write volume and the "just need the current value" shape.
-- presence:{user_id} -> { gateway_server_id, connected_at, last_seen }
```

---

## 7. API / Protocol Design

Unlike a typical REST API, this system is dominated by a **persistent, bidirectional protocol** rather than discrete request/response calls:

```
Client → Server (over persistent connection):
  SEND_MESSAGE { conversationId, clientMessageId, ciphertext, sequenceHint }
  ACK_DELIVERED { messageId }
  ACK_READ { messageId }

Server → Client (pushed, not polled):
  NEW_MESSAGE { conversationId, messageId, sequenceNumber, ciphertext, senderId }
  DELIVERY_RECEIPT { messageId, status: DELIVERED | READ }
  PRESENCE_UPDATE { userId, status: ONLINE | OFFLINE, lastSeen }
```

A REST-style HTTP API still exists alongside this for non-real-time concerns (account management, media upload URLs, group metadata changes) — it's specifically the message-delivery path that needs the persistent-connection model.

---

## 8. Trade-offs & Follow-Up Questions to Anticipate

| Follow-up | Strong answer direction |
|---|---|
| "How do you scale group messages to 1000 members?" | Fan-out on send to each online member's gateway connection (similar in spirit to [Twitter's fan-out-on-write](../twitter-feed/README.md#3-the-core-trade-off-fan-out-on-write-vs-fan-out-on-read), but at a much smaller, bounded fan-out factor than a celebrity's followers, so the celebrity-problem hybrid usually isn't needed here — 1024 is small enough for direct fan-out). |
| "What happens if a gateway server crashes?" | All connections held by that server drop; clients detect the disconnect and reconnect (often to a different server via a load balancer), then resume from their last known sequence number per conversation to catch up on any messages sent during the gap — this is why per-conversation sequence numbers exist, not just for ordering but for gap detection/recovery. |
| "How do you handle multi-device (same account on phone + web + desktop)?" | Fan out delivery to all of a user's currently-connected devices, and track delivery/read receipts per-device or reconcile them, since "delivered" now means "delivered to at least one active device" rather than a single connection. |
| "How does end-to-end encryption change the server's role?" | The server becomes purely a durable, ordered relay of opaque ciphertext — it cannot inspect content, which also means server-side features requiring content visibility (like content-based spam filtering) must be redesigned or pushed to the client/metadata layer. |

---

## 9. 60-Second Interview Answer

> "The defining challenge here isn't the message volume itself — it's holding hundreds of millions of persistent connections open simultaneously, which requires a stateful, event-driven gateway layer rather than typical stateless request/response app servers, since an open socket physically lives on one specific machine and can't be externalized to a shared cache. I'd assign each conversation a monotonic, per-conversation sequence number rather than a global one, giving ordering and gap-detection without unnecessary global coordination. Delivery is at-least-once with client-side deduplication by message ID, since exactly-once delivery over unreliable mobile networks isn't realistically achievable, matching the same pattern used in reliable message queues. For offline recipients, messages are durably queued and delivered on reconnect, using the same sequence numbers to let the client detect and fill any gap."

**Related:** [Message Queues](../../02-building-blocks/message-queues/README.md) · [Scalability](../../01-foundations/scalability/README.md) · [Consistency Models](../../01-foundations/consistency-models/README.md) · [WebSockets](../../08-api-design/websockets/README.md)
