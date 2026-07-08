# Question Bank — The Classics, Mapped to This Vault

> **What this is:** the questions that actually get asked, each with (a) the vault design that answers it, (b) what the interviewer is *really* testing, and (c) the follow-up they're saving for minute 30. Use it as a practice menu: one timed rep per row, per the [practice method](README.md).

---

## Tier 1 — the canonical eight (asked everywhere, prepare all)

| Question | Vault answer | Really testing | The saved follow-up |
|---|---|---|---|
| URL shortener | [url-shortener](../03-high-level-design/url-shortener/README.md) | Estimation, key generation, read-heavy caching — the "can you do the basics cleanly" screen | Custom aliases; analytics at 100:1 read ratio; 301 vs 302 and caching |
| Twitter/news feed | [twitter-feed](../03-high-level-design/twitter-feed/README.md) | Fan-out on write vs read — the read/write trade-off in its purest form | The celebrity problem; feed ranking injection |
| Chat (WhatsApp/Slack) | [whatsapp](../03-high-level-design/whatsapp/README.md) | [Stateful connections](../08-api-design/websockets/README.md), routing, delivery guarantees, ordering | Group fan-out; multi-device sync; E2E encryption vs server features |
| Video platform (YouTube/Netflix) | [youtube](../03-high-level-design/youtube/README.md), [netflix](../03-high-level-design/netflix/README.md) | Blob pipeline: upload → transcode ([queue](../02-building-blocks/message-queues/README.md)) → [CDN](../02-building-blocks/cdn/README.md); adaptive bitrate | Thundering herd on a viral video; regional pre-positioning |
| Ride-hailing (Uber) | [uber](../03-high-level-design/uber/README.md) | Geo-indexing, high-frequency writes, matching under movement | Surge pricing consistency; driver location write volume |
| Rate limiter | [rate-limiting](../02-building-blocks/rate-limiting/README.md) | Algorithms (token bucket vs windows) + distributed state | Race conditions on the counter; [fail open or closed?](../07-microservices/resilience-patterns/README.md) |
| Notification system | [notification-system](../03-high-level-design/notification-system/README.md) | Multi-channel fan-out, dedup, rate control, retries | Exactly-once ("why can't you?") → [idempotency](../08-api-design/idempotency/README.md) |
| Payment system | [payment-system](../03-high-level-design/payment-system/README.md) | Correctness under retries: [idempotency](../08-api-design/idempotency/README.md), [ledgers, sagas](../05-distributed-systems/saga-pattern/README.md) | Reconciliation; double-entry; what if the PSP times out? |

## Tier 2 — frequent, often as the "second question"

| Question | Vault answer | Really testing |
|---|---|---|
| Search autocomplete | [search-autocomplete](../03-high-level-design/search-autocomplete/README.md) | Tries/precomputation, extreme read QPS, p99 discipline |
| Cloud file storage (Drive/Dropbox) | [google-drive](../03-high-level-design/google-drive/README.md) | Chunking, dedup, sync + conflict resolution |
| Hotel/ticket booking | [hotel-booking](../03-high-level-design/hotel-booking/README.md) | Contention on scarce inventory — [isolation](../06-databases-deep-dive/transactions-acid/README.md), holds, oversell policy |
| Distributed cache | [caching](../02-building-blocks/caching/README.md) + [consistent hashing](../02-building-blocks/databases/sharding/README.md) | Eviction, invalidation, hot keys, resharding |
| Distributed message queue | [message-queues](../02-building-blocks/message-queues/README.md) + [replication](../02-building-blocks/databases/replication/README.md) | Log storage, delivery guarantees, ordering, consumer groups |
| Web crawler | [scalability](../01-foundations/scalability/README.md) + [queues](../02-building-blocks/message-queues/README.md) | Frontier management, politeness, dedup at billions-scale |
| Leaderboard / top-K | [caching](../02-building-blocks/caching/README.md) (sorted sets) + [streams](../05-distributed-systems/event-driven-architecture/README.md) | Real-time aggregation windows, approximate vs exact |
| ID generator (Snowflake) | [sharding](../02-building-blocks/databases/sharding/README.md) + [url-shortener](../03-high-level-design/url-shortener/README.md) | Uniqueness without coordination; clock skew; k-sortability |

## Tier 3 — the deep-dive interrogations (no diagram; pure mechanism)

"Explain how Raft survives a partition" → [raft](../05-distributed-systems/consensus-algorithms/raft/README.md) · "Walk me through what happens when a replica falls behind" → [replication](../02-building-blocks/databases/replication/README.md) · "Why is 2PC rarely used between services?" → [distributed-transactions](../05-distributed-systems/distributed-transactions/README.md) · "Your p99 tripled but average is flat — what happened?" → [latency-vs-throughput](../01-foundations/latency-vs-throughput/README.md) · "Design the schema and indexes for X" → [indexing](../06-databases-deep-dive/indexing-strategies/README.md) · "How would you migrate this monolith?" → [monolith-vs-microservices](../07-microservices/monolith-vs-microservices/README.md) · "How do you deploy 50 services safely?" → [deployment-patterns](../07-microservices/deployment-patterns/README.md) · "Secure this API" → [authn/z](../10-security-observability/authentication-authorization/README.md).

## Reading the question type (what "design X" means at each company flavor)

- **Product companies (Meta/Google/Amazon-style product rounds):** user-facing feature scope, emphasis on requirements discipline and evolution — expect PM-flavored follow-ups ("now add editing to messages").
- **Infra-flavored rounds:** "design a rate limiter / KV store / queue" — mechanism depth over breadth; sections [05](../05-distributed-systems/README.md)/[06](../06-databases-deep-dive/README.md) carry these.
- **Payments/fintech:** correctness > scale, always — [idempotency](../08-api-design/idempotency/README.md), [ledgers](../03-high-level-design/payment-system/README.md), [isolation levels](../06-databases-deep-dive/transactions-acid/README.md), reconciliation. Saying "eventual consistency" carelessly here is the fastest way to fail.
- **Senior/staff loops anywhere:** the question is a doorway; the score is in the follow-ups — which is why every note in this vault leads with *the question it answers* and ends with the 60-second drill.

**Related:** [Interview Framework](interview-framework.md) · [Capacity Estimation](capacity-estimation.md) · [Trade-off Cheat Sheet](tradeoff-cheatsheet.md)
