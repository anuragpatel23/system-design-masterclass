# Pagination Patterns — Offset vs Cursor vs Keyset

> **The question this answers, precisely:** why does `LIMIT 20 OFFSET 1000000` take seconds while page 1 takes milliseconds, why do items duplicate or vanish while a user scrolls a feed, and what contract should a collection API expose instead? Pagination looks like a detail; it's actually a database-mechanics question wearing an API costume — which is exactly why interviewers love it.

---

## 1. Offset pagination — and why it breaks, mechanically

```sql
SELECT * FROM posts ORDER BY created_at DESC LIMIT 20 OFFSET 1000000;
```

- **The performance problem:** an index doesn't know "where row 1,000,000 starts." The database must **walk and discard** 1,000,000 index entries (often fetching rows along the way) to return the 20 after them — cost is **O(offset)**, so page depth is a linear cost multiplier and deep pages are effectively a DoS you built into your own API. (See [Indexing Strategies](../../06-databases-deep-dive/indexing-strategies/README.md) — a B-Tree supports "seek to key" cheaply, "seek to ordinal position N" is not what it does.)
- **The correctness problem (worse, and less known):** offsets are positions in a *live* list. If 5 new posts arrive between a user's page-1 and page-2 requests, everything shifts down: page 2 **re-shows 5 items from page 1** (duplicates) — or, on deletion, **skips items the user never saw**. For any feed, this isn't an edge case; it's continuous.
- **Where offset is fine:** small, mostly-static, human-numbered datasets — admin tables, search-results pages where "jump to page 7" is a real requirement and depth is bounded. State this, don't just condemn offset.

## 2. Cursor (keyset) pagination — the scalable contract

Instead of a position, the client presents **a pointer to the last item it saw**, and the server returns items *after that key*:

```sql
-- cursor encodes: (created_at, id) of the last item seen
SELECT * FROM posts
WHERE (created_at, id) < ('2026-07-01T10:00:00Z', 987654)   -- "seek past the cursor"
ORDER BY created_at DESC, id DESC
LIMIT 20;
```

- **Performance:** the composite index on `(created_at DESC, id DESC)` lets the database **seek directly** to the cursor position — **O(log n) regardless of depth**. Page 50,000 costs the same as page 1. This is "keyset" or "seek" pagination.
- **Correctness:** the cursor anchors to an *item*, not a position — newly inserted rows above it can't shift the window, so no duplicates/skips from concurrent inserts. (Items *deleted at the boundary* are simply skipped past — acceptable in practice.)
- **The tie-breaker detail that separates candidates:** `created_at` alone isn't unique — two posts in the same millisecond make the cursor ambiguous (rows skipped or repeated at page boundaries). Hence the **composite key `(created_at, id)`** with row-value comparison, and `id` in the `ORDER BY`. Naming the tie-breaker unprompted is the depth signal.
- **API shape:** return the cursor **opaque** (base64 of the key tuple, ideally with a version and HMAC): `{"data": [...], "next_cursor": "eyJ0IjoiMjAyNi4uLiJ9", "has_more": true}`. Opacity is a [contract-longevity](../rest-best-practices/README.md) decision — clients that parse your cursor freeze your sort key forever; an opaque token lets you change the underlying key later. Trade-offs to state honestly: no "jump to page N", no cheap total count (`COUNT(*)` over a huge filtered set is its own expensive query — return counts only if the product genuinely needs them, often as estimates), and the cursor is only valid for one specific sort order + filter combination.

## 3. Variants worth naming

- **Time-based pagination** (`?since=...&until=...`): cursoring where the key *is* time — natural for logs/metrics; same tie-breaker caveat.
- **Search-engine pagination:** Elasticsearch forbids deep offsets (`max_result_window` = 10k default) for exactly the O(offset) reason — and provides `search_after` (keyset) and PIT (point-in-time snapshots for stable iteration). Evidence that this isn't an SQL quirk but a fundamental of sorted retrieval.
- **Feed systems:** a Twitter-style home timeline is cursor pagination over a **precomputed feed** ([fan-out](../../03-high-level-design/twitter-feed/README.md)) — the cursor points into a per-user list in Redis/Cassandra, same seek principle, different store. On sharded stores, "next page" may fan out to all shards and merge — see [Sharding](../../02-building-blocks/databases/sharding/README.md).

## 4. Decision framework

| Situation | Choice |
|---|---|
| Infinite scroll, feeds, any large/live collection, any public API | **Cursor/keyset** — performance O(log n), no duplicate/skip anomalies |
| Bounded admin tables; genuine "jump to page N" requirement | **Offset** — with a depth cap (e.g., max 100 pages) |
| Time-series/log retrieval | **Time-based cursor** |
| Client needs total counts | Provide separately/estimated; don't let `COUNT(*)` ride along on every page |

## 5. Real-world reference

**Stripe** (`starting_after=obj_id` — the cursor is literally the last object's ID), **Slack** (`response_metadata.next_cursor`), **GitHub GraphQL** (Relay-style `edges/pageInfo/endCursor`) — every major public API converged on cursors; **Elasticsearch's hard `max_result_window`** is the same physics enforced as config. Convergent evolution is the tell that this is a fundamental, not a preference.

## 6. Common pitfalls

- `OFFSET` on a large, growing table — O(offset) cost plus duplicate/skip anomalies under concurrent writes.
- Cursor on a non-unique sort key without a tie-breaker — boundary rows skip or repeat.
- Transparent cursors clients learn to parse — your sort key is now frozen API.
- Returning `total_count` on every page of a billion-row filtered collection.
- Forgetting the composite index that makes the keyset seek O(log n) — the pattern is index-shaped: no index, no benefit.

## 7. 60-Second Interview Answer

> "Offset pagination breaks in two independent ways at scale. Mechanically, OFFSET N forces the database to walk and discard N index entries before returning anything, so cost is linear in page depth — deep pages are a built-in DoS. And correctness-wise, offsets are positions in a live list, so inserts between requests shift the window and users see duplicates, deletes make them skip items. Cursor — keyset — pagination fixes both: the client returns an opaque token encoding the last item's sort key, and the server does an indexed seek — WHERE (created_at, id) is less than the cursor, ORDER BY both, LIMIT 20 — which is O(log n) at any depth and anchors to an item rather than a position, immune to concurrent inserts. Two details matter: a unique tie-breaker column in the key, because timestamps collide and ambiguous cursors skip or repeat boundary rows; and keeping the cursor opaque, because clients that parse it freeze your sort key into the contract forever. The trade-offs are honest ones — no jump-to-page-N, and total counts become a separate, deliberate query. Stripe, Slack, and GitHub all converged on cursors, and Elasticsearch outright caps offset depth and provides search_after — same physics, enforced as config."

**Related:** [Indexing Strategies](../../06-databases-deep-dive/indexing-strategies/README.md) · [REST Best Practices](../rest-best-practices/README.md) · [Twitter Feed](../../03-high-level-design/twitter-feed/README.md) · [Sharding](../../02-building-blocks/databases/sharding/README.md)
