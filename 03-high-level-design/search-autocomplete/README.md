# Design Search Autocomplete (Typeahead Suggestions)

> **The one hard problem this really tests:** serving ranked prefix-match suggestions with **extremely tight latency budgets** (sub-100ms, ideally sub-50ms, because it fires on every keystroke) at massive query volume — which forces precomputation of a specialized in-memory data structure (a trie) rather than any kind of live database query per keystroke.

---

## 1. Requirements

### Functional
- As a user types a query prefix, return the top-K most relevant completed suggestions.
- Suggestions should reflect actual popularity/relevance (e.g., trending or frequently-searched completions), not just alphabetical order.
- Suggestions should update over time to reflect changing trends (yesterday's top suggestion for "world cup" shouldn't necessarily still be #1 next year).

### Non-Functional
- **Extremely low latency** — this fires on nearly every keystroke; anything above roughly 100ms feels janky and defeats the purpose of "instant" suggestions.
- **Very high read (query) volume** — every keystroke from every user, which is an order of magnitude more request volume than the underlying full search itself.
- **Ranking/relevance data can be stale by hours** — updating global trending data in real time to the millisecond is not required; a periodic refresh (e.g., every few minutes to hours) is entirely acceptable.

---

## 2. Back-of-Envelope Estimation

- If a search product handles, say, 100,000 full searches/sec, and an average query is ~20 characters typed with suggestions requested on most keystrokes, the **autocomplete request volume can easily be 10-20x the full-search request volume** — this single ratio is the reason autocomplete needs its own dramatically optimized, separate serving path rather than reusing the full search infrastructure directly.
- Given the sub-100ms latency requirement and this request volume, **a live database query per keystroke (even a well-indexed one) is not fast enough at this combined volume/latency requirement** — this pushes the design firmly toward an **in-memory, precomputed data structure** served directly from RAM, not disk-backed storage.

---

## 3. Component Deep Dive: The Trie (Prefix Tree) — the Core Data Structure

A **trie** is a tree where each node represents a single character, and a path from the root to a node spells out a prefix. All words sharing a common prefix share the same path down to the point where they diverge.

```
              (root)
             /   |   \
            c    d    ...
           /|         
          a a         
         /   \        
        t     r       
       /|      \       
      s  ...    ...     
     (cats)
```

- **Why a trie, and not just a database `LIKE 'prefix%'` query?** A trie lookup for a prefix of length `L` is `O(L)` — proportional only to the length of what's typed, completely independent of how many total words/suggestions exist in the entire dataset. A `LIKE 'prefix%'` query, even with an index, generally doesn't scale as cleanly or predictably at this volume/latency combination, and definitely can't live purely in a request-local in-memory structure the way a trie naturally can.
- **Storing ranked suggestions at each node:** rather than just marking "is this a complete word," each trie node (or each node along a prefix path) additionally stores its **top-K most popular completions** for that prefix, precomputed ahead of time — so a query for a given prefix is a **single O(L) traversal followed by an O(1) read of an already-sorted top-K list**, not a traversal-then-sort-on-the-fly operation. This precomputation is what makes read-time latency so predictably low.
- **Memory footprint concern:** storing every possible prefix's own top-K list at every single node can be memory-expensive for very deep/wide vocabularies — a common mitigation is only precomputing/caching top-K lists at nodes above a certain popularity/query-frequency threshold, falling back to a live (still fast, but slightly more expensive) computation for extremely rare, long-tail prefixes that don't justify the memory cost of precomputation.

---

## 4. High-Level Design

```
  ── Offline / Periodic Build Pipeline (runs every few minutes to hours) ──
  Search query logs ──▶ Aggregation job: count query frequency per term,
                          over a recent rolling time window (to reflect
                          CURRENT trends, not all-time-ever popularity)
                                 │
                          Build/rebuild the Trie, annotating each relevant
                          node with its precomputed top-K completions
                                 │
                          Serialize the trie into a compact, loadable format
                                 │
                          Distribute to Autocomplete Service instances
                          (each instance loads the FULL trie into its own
                          local memory -- this is a read-only, precomputed
                          artifact, not something queried remotely per request)

  ── Online / Read Path (must be extremely fast) ──
  Client keystroke ──▶ Autocomplete Service (load balanced across many
                        stateless-from-a-request-perspective instances,
                        each holding an identical in-memory trie copy)
                                 │
                        O(L) trie traversal to the prefix's node
                                 │
                        Return precomputed top-K list directly --
                        no live ranking/sorting computation needed
                                 │
                        Response served entirely from local RAM,
                        no network hop to a database or even to
                        a separate cache service
```

**A crucial architectural point:** because the trie is a read-only, periodically-rebuilt artifact, the best design **loads a full copy of the trie into the local memory of every single Autocomplete Service instance**, rather than centralizing it in one shared remote cache/database that every instance queries over the network. This trades some memory duplication (every instance holds a full copy) for eliminating a network hop entirely from the hottest, most latency-sensitive path in the whole system — a deliberate, explicit choice, and a strong thing to call out unprompted in an interview.

---

## 5. Ranking: Beyond Raw Frequency

Pure query-frequency-based ranking has real weaknesses worth naming: it favors already-popular terms (a rich-get-richer effect that can make legitimately new but rising trends slow to surface) and doesn't personalize per user. A more complete system (worth mentioning, not necessarily fully designing unless pushed) would blend:
- **Recency-weighted frequency** — a rolling/decaying window so that "yesterday's news" naturally fades from top rankings over time without needing an explicit manual reset.
- **Personalization** — boosting a user's own recent/frequent searches specifically for them, layered on top of the shared global trie (often implemented as a small personal override list checked first, falling back to the shared global trie for anything not in the user's own history).
- **Business/editorial boosting** — manually promoting certain suggestions (e.g., for moderation, safety, or business reasons) — a real, pragmatic override mechanism worth mentioning as it commonly exists in production systems.

---

## 6. Data Model

```
-- The trie itself is an in-memory data structure, not a relational table --
-- but the OFFLINE aggregation step that BUILDS it does use a simple
-- aggregate table/pipeline stage:

query_frequency (term, window_start, window_end, count)
  -- Aggregated periodically from raw search query logs (see Message Queues
  -- for how raw query events might be streamed into this aggregation job)

-- Trie node (conceptual, in-memory representation):
TrieNode {
    Map<Character, TrieNode> children;
    List<String> topKCompletions;   // precomputed, already sorted by score
}
```

---

## 7. API Design

```
GET /api/v1/autocomplete?prefix=cat&limit=10
  Response: { "suggestions": ["cats", "category", "catering", ...] }
  -- Fired on (debounced) every keystroke; response time budget: <100ms,
  -- ideally <50ms, since this directly gates perceived UI responsiveness.
```

**A practical client-side companion worth mentioning:** the client typically **debounces** keystroke-triggered requests (e.g., waiting ~100-150ms after the last keystroke before firing a request) to avoid sending a request for every single transient character while the user is still actively typing quickly — reducing request volume without meaningfully hurting perceived responsiveness.

---

## 8. Trade-offs & Follow-Up Questions to Anticipate

| Follow-up | Strong answer direction |
|---|---|
| "How do you keep the trie fresh without stalling reads during a rebuild?" | Build the new trie version fully offline/in the background, then **atomically swap** the in-memory reference from the old trie to the new one once fully built (a blue-green-style swap) — reads never see a partially-built trie, and there's no read-path downtime during a rebuild. |
| "How does this scale as vocabulary grows very large (many languages, many terms)?" | Shard the trie by first character or language/locale, distributing different shards across different service instances, rather than requiring every instance to hold the entire global vocabulary in memory. |
| "What about typos / fuzzy matching (not just exact prefixes)?" | A related but distinct problem, often handled by a secondary approximate-matching structure (e.g., an edit-distance-tolerant index) layered alongside the trie, or by normalizing/correcting the query before the trie lookup — worth naming as a real extension without fully designing it unless asked. |
| "How would you personalize suggestions per user without a huge memory cost per user?" | Keep the shared global trie as the default and layer a small, per-user override structure (recent searches, capped to a small count) checked first — avoids needing a full personalized trie copy per user, which wouldn't scale memory-wise. |

---

## 9. 60-Second Interview Answer

> "The defining constraint is latency, not data volume — this fires on nearly every keystroke, so I need sub-100ms responses at request volume far higher than the underlying full search itself. That rules out a live database query per keystroke, so I'd precompute a trie offline, periodically rebuilt from recent query logs, with each relevant node annotated with its already-ranked top-K completions — turning each request into an O(prefix length) traversal plus an O(1) read of a precomputed list, no live sorting needed. I'd load a full copy of this trie into the local memory of every autocomplete service instance rather than centralizing it behind a network call, trading some memory duplication for eliminating a network hop from the hottest path in the system. To refresh it without downtime, I'd build the new trie fully in the background and atomically swap the reference once ready, so reads never see a half-built trie."

**Related:** [Latency vs Throughput](../../01-foundations/latency-vs-throughput/README.md) · [Caching](../../02-building-blocks/caching/README.md) · [Load Balancers](../../02-building-blocks/load-balancers/README.md)
