# Design a Twitter-like News Feed

> **The one hard problem this really tests:** the fan-out trade-off between computing a feed at write time vs read time — and the "celebrity problem" that breaks the naive version of both approaches at real-world scale.

---

## 1. Requirements

### Functional
- Users can post short text messages ("tweets"), optionally with media.
- Users can follow other users (asymmetric — following isn't mutual, unlike Facebook friends).
- A user's home timeline shows tweets from everyone they follow, roughly reverse-chronological (real systems also rank/rerank, but that's a separate ML-ranking concern — mention it, don't design it here unless asked).
- Users can like/retweet/reply.

### Non-Functional
- **Extreme read:write skew** — most users check their timeline far more often than they post.
- **Low latency timeline reads** (opening the app should feel instant).
- **Eventual consistency is acceptable** — it's fine if a tweet takes a few seconds to appear in a follower's feed; it is not fine if the whole system is unavailable.
- **Massive fan-out for high-follower accounts** — some accounts have 100M+ followers; a single tweet from such an account is a huge structural outlier that must not be allowed to degrade the system for everyone else.

---

## 2. Back-of-Envelope Estimation

- Assume 300M daily active users, average posting 2 tweets/day → ~600M tweets/day → ~7,000 writes/sec average, bursty to much higher during major global events.
- Assume each user checks their timeline ~10 times/day, each timeline read fetching ~20 tweets → **timeline reads vastly exceed tweet writes** — a read:write ratio easily in the thousands:1 once you count that each of 300M users is independently reading, not just the 600M raw tweet-writes.
- Average follower count might be ~200, but the distribution is **extremely long-tailed** — a small number of accounts have 100M+ followers. This long tail, not the average, is what actually shapes the architecture.

**Why the estimation matters:** the average follower count suggests fan-out-on-write is cheap (writing to ~200 followers' cached timelines per tweet is trivial). The **tail** (100M-follower accounts) is what breaks that assumption — this is exactly why the real design needs a hybrid approach, not a single strategy applied uniformly.

---

## 3. The Core Trade-off: Fan-Out-on-Write vs Fan-Out-on-Read

### Fan-Out-on-Write (Push Model)
When a user tweets, immediately push the tweet ID into a **precomputed timeline cache** (e.g., Redis, keyed per follower) for every one of their followers.

```
User tweets → Tweet stored in DB → for each follower F:
    push tweet_id onto F's cached timeline (Redis list, capped to recent N)
```
- **Read is then trivially cheap:** reading a timeline is just reading one pre-built cached list — no aggregation needed at read time.
- **Write is expensive and proportional to follower count** — for a normal user with ~200 followers, this is 200 cheap writes, entirely reasonable. For a celebrity with 100M followers, this is **100 million writes triggered by a single tweet**, which would take an unacceptably long time to complete and would hammer the caching layer — this is **the celebrity problem**.

### Fan-Out-on-Read (Pull Model)
Don't precompute anything. When a user opens their timeline, query the tweets of everyone they follow **at read time** and merge/sort them on the fly.
- **Write is trivially cheap** — a tweet is just inserted once, regardless of follower count.
- **Read is expensive** — for a user following 200 accounts, the read must fan out to (up to) 200 sources and merge-sort them, on every single timeline load, for every single user, all the time. Given that reads vastly outnumber writes (per the estimation above), this inverts the cost to the far more frequent operation — a bad trade for typical usage.

### The Hybrid (What Twitter Actually Does)

- **For the vast majority of users** (normal follower counts): use fan-out-on-write. Tweet, and immediately push into each follower's precomputed timeline cache. Reads stay cheap, which matters because reads are by far the dominant operation.
- **For celebrity/high-follower accounts:** *don't* fan out on write at all. Instead, at read time, merge the celebrity's tweets (fetched directly, since there are few celebrities and their tweets are easy to fetch on demand) into the follower's otherwise-precomputed timeline.
- **Determining "celebrity" status:** a simple follower-count threshold (e.g., accounts above some N followers, perhaps 1M+, are flagged as fan-out-on-read exceptions) — this is a pragmatic engineering heuristic, not a deep algorithm, and it's fine to state it exactly that plainly in an interview.

**This hybrid is the single most important thing to say in this interview.** A candidate who proposes only pure fan-out-on-write or only pure fan-out-on-read, without recognizing the celebrity problem and the hybrid resolution, has not fully solved the core problem this question is designed to test.

---

## 4. High-Level Design

```
  User tweets ──▶ Tweet Service ──▶ Tweet DB (sharded by tweet_id / user_id)
                       │
                       ├── is author a "celebrity" (follower count > threshold)?
                       │
              ┌────────┴─────────┐
              │ NO                │ YES
              ▼                   ▼
     Fan-out Worker          Skip fan-out; tweet is
     (async, via a           simply persisted; will be
     message queue) pushes   merged in at READ time
     tweet_id into every     for followers' timelines
     follower's Redis
     timeline cache

──────────────────────────────────────────────────────

  User opens timeline ──▶ Timeline Service
                                │
                    ┌───────────┴────────────┐
                    │ fetch precomputed        │ fetch celebrity-followed-accounts'
                    │ timeline cache (Redis)   │ recent tweets directly (small set,
                    │ (already has non-        │ cheap to query on demand)
                    │ celebrity followees'     │
                    │ tweets pushed in)        │
                    └───────────┬────────────┘
                                │
                       Merge + sort by timestamp
                                │
                       Hydrate tweet content
                       (Redis/CDN for media, DB for text)
                                │
                          Return timeline
```

---

## 5. Component Deep Dive: The Fan-Out Worker Must Be Asynchronous

Fanning out a single tweet to even 200 followers synchronously, in the request path of the "post tweet" API call, would add unacceptable latency to posting. The correct design: the "post tweet" API call **only writes the tweet to the database and immediately returns success to the user** — the fan-out to followers' caches happens **asynchronously**, via a message queue (see [Message Queues](../../02-building-blocks/message-queues/README.md)), decoupled from the user-facing write path.

This directly explains why a tweet doesn't always instantly appear in every follower's timeline the moment it's posted — a few hundred milliseconds to a couple of seconds of propagation delay through the fan-out queue is an accepted eventual-consistency trade-off (see [Consistency Models](../../01-foundations/consistency-models/README.md)) in exchange for the post-tweet action itself staying fast, always available, and not scaling in cost with follower count on the synchronous path.

---

## 6. Data Model

```sql
-- Tweets: sharded by tweet_id (a Snowflake-style ID encoding creation time + shard info)
CREATE TABLE tweets (
    tweet_id     BIGINT PRIMARY KEY,   -- Snowflake ID: encodes timestamp, naturally sortable
    author_id    BIGINT NOT NULL,
    content      VARCHAR(280),
    media_url    TEXT NULL,
    created_at   TIMESTAMP NOT NULL
);
CREATE INDEX idx_author_created ON tweets (author_id, created_at DESC); -- for celebrity on-demand fetch

-- Follow graph: a classic many-to-many, needs to answer BOTH
-- "who does X follow" (for fan-out-on-read/celebrity merge) AND
-- "who follows X" (for fan-out-on-write) efficiently -- often a separate
-- specialized graph store (see SQL vs NoSQL) rather than a plain relational join table at this scale.
CREATE TABLE follows (
    follower_id  BIGINT NOT NULL,
    followee_id  BIGINT NOT NULL,
    created_at   TIMESTAMP NOT NULL,
    PRIMARY KEY (follower_id, followee_id)
);
CREATE INDEX idx_followee ON follows (followee_id); -- "who follows X" for fan-out-on-write
```

**Timeline cache (Redis):** `timeline:{user_id}` → a capped-size sorted list/ZSET of recent `tweet_id`s (score = timestamp or Snowflake ID itself, since it's naturally time-sortable), typically capped to the most recent ~800 tweet IDs per user — old enough tweets simply fall off the precomputed cache and would require a (rare) fallback query to reconstruct further back in history.

---

## 7. API Design

```
POST /api/v1/tweets
  Request:  { "content": "...", "mediaUrl": "optional" }
  Response: { "tweetId": "...", "createdAt": "..." }
  -- Returns immediately after DB write; fan-out happens asynchronously.

GET /api/v1/timeline?cursor={tweetId}&limit=20
  Response: { "tweets": [...], "nextCursor": "..." }
  -- Cursor-based pagination (using tweet_id, which is naturally time-ordered via Snowflake)
  -- is strongly preferred over offset-based pagination here: see Pagination Patterns doc.
```

---

## 8. Trade-offs & Follow-Up Questions to Anticipate

| Follow-up | Strong answer direction |
|---|---|
| "What threshold defines a 'celebrity' for fan-out-on-read?" | A pragmatic, tunable follower-count threshold (e.g., >1M), possibly combined with monitoring actual fan-out latency/cost per account and dynamically flagging outliers, rather than a purely static, one-time-configured number. |
| "What if a user follows both normal accounts and celebrities?" | Exactly the hybrid merge described above — precomputed cache for normal followees, on-demand fetch-and-merge for celebrity followees, combined at read time. |
| "How do you handle a user who just followed someone new — do they see historical tweets?" | Typically no immediate backfill into the precomputed cache (too expensive per-follow-action); either show only new tweets going forward, or lazily backfill a bounded number of recent tweets from the newly followed account on next read. |
| "How would ranking (not just chronological) change this?" | Ranking is usually a separate scoring/ML step applied to the *candidate set* the fan-out architecture produces — the fan-out problem (getting the right candidate tweets cheaply) is largely orthogonal to ranking (choosing which of those candidates to show first), and it's fine to explicitly scope ranking out unless asked to go deeper. |

---

## 9. 60-Second Interview Answer

> "The core tension is fan-out-on-write, which makes reads cheap by precomputing each follower's timeline but makes writes proportional to follower count, versus fan-out-on-read, which makes writes cheap but pushes expensive aggregation onto every read — and since timeline reads vastly outnumber tweets, pure fan-out-on-read is generally the wrong default. The real answer is a hybrid: fan-out-on-write for typical accounts, since a few hundred follower-cache writes per tweet is cheap and keeps reads fast, but skip fan-out entirely for celebrity accounts above a follower-count threshold, since fanning out to 100 million followers synchronously — or even asynchronously at that volume — isn't practical; instead merge celebrity tweets in at read time from a small, easily-queried set of high-follower accounts. Fan-out itself has to be asynchronous via a queue, decoupled from the tweet-posting request, so posting stays fast and available regardless of the author's follower count."

**Related:** [Message Queues](../../02-building-blocks/message-queues/README.md) · [Caching](../../02-building-blocks/caching/README.md) · [Consistency Models](../../01-foundations/consistency-models/README.md) · [Pagination Patterns](../../08-api-design/pagination-patterns/README.md)
