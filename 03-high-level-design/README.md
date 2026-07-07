# 03 — High-Level Design

> This is where sections 01 (Foundations) and 02 (Building Blocks) get assembled into complete, defensible system architectures. Every design below follows the **RESHADED** framework from [09-interview-prep](../09-interview-prep/interview-framework.md) and is written to the depth expected of a **15–16 year senior/staff/principal architect** interview loop — not a bootcamp-level "draw some boxes" answer.

## How to actually use these documents

A common failure mode: memorizing a design verbatim and reciting it regardless of what the interviewer actually asked. **Don't do that.** Every real HLD interview is steered by the interviewer's follow-ups, and what they're really scoring is:

1. Did you **clarify scope** before designing (a "URL shortener" for a startup vs. one at Bitly's scale are different systems)?
2. Did you **quantify** before architecting (back-of-envelope math drives real decisions: do you need a cache? how many shards? what replication factor?)
3. Did you **justify every box** in your diagram against a requirement, rather than drawing a generic "microservices + Kafka + Redis + Postgres" diagram for every problem regardless of fit?
4. Did you **surface trade-offs out loud**, including the ones that go against your own design?
5. Could you **go deeper on demand** into any single component (data model, a specific algorithm, a failure mode) when pushed?

Read a design once end-to-end. Then close the document and try to **re-derive the estimation math and the core data model from memory** — that's the part that actually gets grilled in an interview, far more than the box-and-arrow diagram.

## Systems in this section

| System | The one hard problem it's really testing |
|---|---|
| [URL Shortener](url-shortener/README.md) | Unique ID generation at scale + read-heavy caching |
| [Twitter Feed](twitter-feed/README.md) | Fan-out-on-write vs fan-out-on-read, celebrity problem |
| [WhatsApp](whatsapp/README.md) | Real-time delivery, message ordering, offline delivery guarantees |
| [YouTube](youtube/README.md) | Video storage/transcoding pipeline + global CDN delivery |
| [Uber](uber/README.md) | Geospatial indexing + real-time matching under a two-sided marketplace |
| [Netflix](netflix/README.md) | Adaptive streaming, global content distribution, personalization at scale |
| [Google Drive](google-drive/README.md) | Chunked file storage, sync/versioning, conflict resolution |
| [Search Autocomplete](search-autocomplete/README.md) | Trie-based prefix search under extreme low-latency requirements |
| [Notification System](notification-system/README.md) | Multi-channel fan-out, delivery guarantees, and user preference management |
| [Payment System](payment-system/README.md) | Idempotency, exactly-once money movement, double-entry ledgers |
| [Hotel Booking](hotel-booking/README.md) | Inventory contention, overselling prevention, distributed locking |

Previous: [02 — Building Blocks](../02-building-blocks/README.md)
