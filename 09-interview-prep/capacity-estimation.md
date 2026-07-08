# Capacity Estimation — Back-of-Envelope Numbers That Drive Design

> **What this is:** the memorized constants and the 5-step method that turn "500M DAU" into "therefore we shard, therefore object storage, therefore cache-first reads." Estimation exists to *earn architectural decisions* — an estimate that doesn't change the design was a waste of interview minutes, and interviewers know it.

---

## 1. Numbers to memorize (the interview constants)

**Latency ladder (Dean/Norvig numbers, rounded for speech):**

| Operation | Latency | Say it as |
|---|---|---|
| L1 cache reference | ~0.5 ns | — |
| Main memory reference | ~100 ns | "RAM is nanoseconds" |
| Read 1MB sequentially from RAM | ~0.25 ms | — |
| SSD random read | ~100 µs | "SSD is ~1000x RAM" |
| Read 1MB sequentially from SSD | ~1 ms | — |
| Spinning disk seek | ~10 ms | "disk seeks are why [random I/O kills B-Tree writes](../06-databases-deep-dive/b-trees-lsm-trees/README.md)" |
| Same-datacenter round trip | ~0.5 ms | "every service hop costs this before any work" |
| Cross-continent round trip (e.g., US↔EU) | ~80–150 ms | "why geo-replication & [CDNs](../02-building-blocks/cdn/README.md) exist" |

**Throughput anchors (order-of-magnitude, per node, tuned):** a relational DB sustains roughly **1–10k writes/sec and 10–50k simple reads/sec**; Redis/memcached **~100k+ ops/sec**; Kafka-class log **hundreds of MB/sec per broker**; a stateless app server **~1–10k req/sec**; one server holds **~100k–1M idle WebSocket connections](../08-api-design/websockets/README.md)**. These are *decision thresholds*, not benchmarks — the moment demand exceeds one node's anchor, you've justified sharding/replication/caching.

**Scale conversions (the arithmetic lubricant):**
- 1 day ≈ 86,400s — **round to 100k** for mental math (and say you're rounding).
- 1M requests/day ≈ **12/sec**. 1B/day ≈ **12k/sec**. (Memorize this pair; everything else is multiplication.)
- Peak ≈ **2–5x average** (state your multiplier).
- Powers of two: 2¹⁰≈10³ (KB), 2²⁰≈10⁶ (MB), 2³⁰≈10⁹ (GB), 2⁴⁰≈10¹² (TB), 2⁵⁰≈10¹⁵ (PB).
- Rules of thumb: char = 1B (ASCII) / up to 4B (UTF-8); UUID = 16B; typical metadata row ≈ 100B–1KB; avg photo ≈ 200KB–2MB; 1 min of 1080p video ≈ 50–100MB raw upload, ~10MB streamed.

## 2. The 5-step method (always the same, always out loud)

**Worked example — "design a photo-sharing app, 500M DAU":**

1. **Traffic.** Assume 10% of DAU post 1 photo/day: 50M uploads/day ≈ **500 uploads/sec avg, ~1.5k/sec peak** (3x). Each user views 20 photos/day: 10B views/day ≈ **100k reads/sec avg, 300k/sec peak**.
2. **Read/write ratio.** 10B : 50M = **200:1 read-heavy** → the architecture is a read-serving problem; writes are almost a side channel. (Contrast: [URL shortener](../03-high-level-design/url-shortener/README.md) ~100:1 reads; [WhatsApp](../03-high-level-design/whatsapp/README.md) ~1:1; a metrics pipeline is write-dominated → [LSM storage](../06-databases-deep-dive/b-trees-lsm-trees/README.md).)
3. **Storage.** 50M photos/day × 1MB avg = **50TB/day ≈ 18PB/year** (×3 replication ≈ 55PB raw) → **object storage (S3-class) for blobs is forced**; the DB holds only metadata: 50M × 500B/day ≈ 25GB/day ≈ **9TB/year** — sharded but tractable.
4. **Bandwidth.** Egress: 100k reads/sec × 200KB (CDN-resized) ≈ **20GB/sec** → a [CDN](../02-building-blocks/cdn/README.md) isn't an optimization, it's load-bearing; origin serves cache misses only.
5. **Cache working set.** 80/20 rule: 20% of today's + yesterday's hot photos dominate reads. Metadata hot set: ~100M items × 500B ≈ **50GB → fits in a small Redis cluster** — which converts your 300k/sec read peak from a database problem into a [cache](../02-building-blocks/caching/README.md) problem.

**Then the sentence that makes it count:** "So: reads dominate 200:1 → CDN + cache-first; 18PB/year → object storage + metadata DB split; 1.5k metadata writes/sec peak → a single Postgres primary survives *today* but I'd [shard by user_id](../02-building-blocks/databases/sharding/README.md) for headroom."

## 3. Delivery discipline

- **Round aggressively and narrate:** "86,400 seconds — call it 100k, so 50M/day is 500/sec." Precision theater wastes minutes; interviewers score the *method*.
- **State assumptions as assumptions** ("assuming 10% posters — sound right?") — it invites correction, which is collaboration, which is scored.
- **Sanity-check against anchors:** "500 writes/sec — one primary handles that; 300k reads/sec — nothing single-node handles that; cache or die."
- **Know when to stop.** Two significant figures, five steps, then *back to design*. Estimation is a means.

## 4. Common pitfalls

- Computing numbers, then designing as if you hadn't ("…so we'll use MongoDB" — why?).
- Forgetting peak vs average (provisioning for average = designed-in brownouts) or replication factor in storage.
- Mixing bits and bytes (network specs are bits: 10Gbps = 1.25GB/sec).
- Estimating storage for the blob *and* putting it in the database — the estimate itself should have forced the object-store split.
- Refusing to assume ("I'd need the real numbers") — proposing defensible assumptions *is* the skill being tested.

**Related:** [Interview Framework](interview-framework.md) · [Latency vs Throughput](../01-foundations/latency-vs-throughput/README.md) · [Caching](../02-building-blocks/caching/README.md) · [Sharding](../02-building-blocks/databases/sharding/README.md) · [CDN](../02-building-blocks/cdn/README.md)
