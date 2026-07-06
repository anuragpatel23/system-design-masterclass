# 02 — Building Blocks

> The reusable "Lego pieces" that appear in almost every high-level design in section 03. A senior architect doesn't re-derive these from scratch in an interview — they reach for the right one instantly and can defend *why* under follow-up pressure.

## Topics in this section

| # | Topic | Core interview question it answers |
|---|-------|--------------------------------------|
| 1 | [Load Balancers](load-balancers/README.md) | L4 vs L7, algorithms, health checks, sticky sessions |
| 2 | [Caching](caching/README.md) | Cache-aside vs write-through vs write-behind, eviction, invalidation |
| 3 | [Databases: SQL vs NoSQL](databases/sql-vs-nosql/README.md) | When ACID matters vs when it doesn't |
| 4 | [Databases: Sharding](databases/sharding/README.md) | How you actually split a 10TB table across 50 machines |
| 5 | [Databases: Replication](databases/replication/README.md) | Master-slave vs master-master, replication lag, failover |
| 6 | [Message Queues](message-queues/README.md) | Point-to-point vs pub/sub, delivery guarantees, DLQs |
| 7 | [CDN](cdn/README.md) | Push vs pull CDNs, cache invalidation at the edge |
| 8 | [API Gateway](api-gateway/README.md) | Routing, auth, rate limiting, aggregation at the edge |
| 9 | [Rate Limiting](rate-limiting/README.md) | Token bucket vs sliding window, distributed rate limiting |

## How these compose

A single "simple" API request in a production system typically touches **6 of these 9 building blocks** before a response is returned:

```
Client → CDN (static assets) → API Gateway (auth + rate limit)
       → Load Balancer → App Server → Cache (aside) → Database (sharded + replicated)
                                    ↘ Message Queue (async side-effects)
```

Understand each piece in isolation first (this section), then watch them combine in section 03 (`03-high-level-design`).

Previous: [01 — Foundations](../01-foundations/README.md)
