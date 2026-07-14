# Rate Limiting

> Rate limiting caps how many requests a client (a user, an API key, an IP, or a whole tenant) can make in a given time window, protecting the system from abuse, accidental traffic spikes, and cascading overload — and, in multi-tenant systems, enforcing fair usage across customers who are paying for different service tiers.

---

## 1. The Core Algorithms — Precisely Compared

### Token Bucket
A bucket holds up to `N` tokens, refilled at a steady rate `r` tokens/sec. Each request consumes one token; if the bucket is empty, the request is rejected (or queued).
- **Key property:** allows **bursts** up to the bucket size, as long as the bucket has accumulated tokens (e.g., from a quiet period), while enforcing a steady average rate over time.
- **Most widely used in practice** — this is the default in AWS API Gateway, Stripe's API, and most production rate limiters, precisely because real traffic is naturally bursty and a hard per-second cap is often too punishing for legitimate usage patterns.

### Leaky Bucket
Requests enter a queue (the "bucket") and are processed ("leak out") at a fixed, constant rate, regardless of how bursty the arrival pattern is. If the queue is full, new requests are rejected.
- **Key property:** enforces a perfectly smooth **outflow** rate — unlike token bucket, it does not allow bursts to pass through faster than the fixed rate, only to be buffered up to the queue size.
- **Best for:** protecting a downstream system that genuinely cannot handle any burst at all (e.g., a legacy system with a hard fixed-capacity limit), at the cost of added latency for queued requests during bursts.

### Fixed Window Counter
Count requests in fixed time windows (e.g., "0:00–0:59", "1:00–1:59"); reject once the count exceeds the limit for that window; reset the counter at each window boundary.
- **Key flaw (a classic interview gotcha):** a client can send the full limit at the very end of one window and the full limit again at the very start of the next window, achieving nearly **2x the intended rate** in a short burst straddling the boundary. E.g., a 100 req/min limit could allow 100 requests at 0:59 and another 100 at 1:00 — 200 requests in under 2 seconds.

### Sliding Window Log
Store a timestamp for every individual request in the current window; on each new request, discard timestamps older than the window and count what remains against the limit.
- **Pros:** Perfectly accurate — no boundary-burst problem.
- **Cons:** Memory cost scales with request volume (must store every timestamp), which is expensive at high throughput.

### Sliding Window Counter (the practical compromise)
Combines the fixed window's cheap counting with an approximation of the sliding window's accuracy: it weights the previous window's count proportionally to how much of it overlaps with the current sliding window, avoiding both the boundary-burst flaw of fixed windows and the storage cost of a full log.
- **Formula:** `estimated_count = current_window_count + previous_window_count × overlap_percentage`
- This is the algorithm most production-grade distributed rate limiters actually implement, because it's cheap (just two counters, not a full log) and accurate enough for real-world use.

---

## 2. Distributed Rate Limiting — Why It's Harder Than It Looks

A rate limiter enforcing a limit **per API key across an entire fleet of N application servers** cannot simply keep an in-memory counter on each server — each server would independently allow up to the full limit, letting the effective combined rate scale with fleet size (an availability-vs-consistency trade-off very similar to [CAP Theorem](../../01-foundations/cap-theorem/README.md), applied to counters instead of data).

**The standard solution:** a **centralized, shared counter store** (almost universally Redis, due to its speed and support for atomic increment operations) that every app server checks/updates before processing a request. This introduces a network round trip per request (a latency cost) and makes the shared store itself a critical dependency requiring its own availability plan (see [Availability & Reliability](../../01-foundations/availability-reliability/README.md)) — if Redis is down, does the rate limiter fail open (allow all traffic, risking overload) or fail closed (reject all traffic, risking unnecessary downtime)? **This fail-open-vs-fail-closed decision is a genuine, non-obvious design choice a senior architect must make explicitly**, and the right answer depends on what's actually more dangerous for that specific system — an unprotected surge, or blocking all legitimate traffic.

---

## 3. What to Rate Limit By (the Key Selection Problem)

| Key | Protects against | Weakness |
|---|---|---|
| **IP address** | Anonymous/unauthenticated abuse | NAT'd corporate networks share one IP across many real users; easily spoofed/rotated by a determined attacker |
| **API key / user ID** | Per-account abuse, tier enforcement | Requires authentication to have already happened |
| **Global (per-endpoint, all clients combined)** | Protecting a specific expensive/fragile downstream resource regardless of who's calling | Doesn't distinguish good actors from bad ones; one abusive client can consume the whole global budget |

Real production systems typically layer **multiple rate limits simultaneously** — e.g., a per-IP limit at the edge (catches anonymous abuse before auth even happens) combined with a per-API-key limit further in (enforces the customer's actual paid tier) combined with a global per-endpoint limit (protects a specific fragile downstream dependency regardless of source).

### Communicating limits to the client

A well-designed rate limiter tells the client where it stands, so well-behaved clients can self-throttle instead of discovering the limit only by hitting `429`:

- `X-RateLimit-Limit` — the total quota for the current window.
- `X-RateLimit-Remaining` — how many requests are left before the limit is hit.
- `X-RateLimit-Reset` — when the window resets (Unix timestamp or seconds).
- `Retry-After` — sent specifically with a `429` response (see [HTTP Fundamentals](../../08-api-design/http-fundamentals/README.md)), telling the client precisely how long to wait before retrying, so a well-behaved client can back off deterministically instead of guessing or hammering immediately.

### At extreme scale: approximate, sharded rate limiting

A single centralized Redis counter (§2) becomes its own bottleneck and added-latency cost once request volume is high enough — every single request now pays a network round trip to Redis just to be counted. Two standard mitigations trade strict global accuracy for scale:
- **Local-first with periodic sync:** each app server keeps its own local, approximate counter and only periodically (e.g., every few hundred milliseconds) syncs/reconciles with the shared Redis store — meaning the enforced limit can briefly overshoot the true global limit by up to the sync interval's worth of traffic, in exchange for removing Redis from the hot path of every single request.
- **Sharded counters:** instead of one global key per client, split the limit across several Redis keys (or nodes) and give each app server (or shard) a fraction of the total budget — trades perfect global fairness (one server's shard might exhaust while another's is idle) for eliminating a single hot key/node as a bottleneck under very high request volume, similar in spirit to the hot-key sharding trade-offs in [Database Sharding](../databases/sharding/README.md).

**The framing worth stating explicitly:** rate limiting itself sits on a consistency/availability spectrum, just like data does — a perfectly accurate, strongly consistent global counter costs a network round trip and a single coordination point per request, while an approximate, eventually-synced counter trades some precision for lower latency and higher scalability, the same trade-off CAP/PACELC describe for data, applied to a counter instead.

---

## 4. Real-World Example: Stripe's API Rate Limiting

Stripe's publicly documented API design uses a **token bucket** algorithm for its rate limits, explicitly because their API traffic is naturally bursty (a merchant's checkout flow might fire several API calls in quick succession during a single customer transaction, then go quiet) — a strict fixed-rate cap would routinely and unfairly reject legitimate bursts from well-behaved integrations.

Stripe's documentation also describes **different rate limit tiers for different API operations** — read operations (fetching a customer record) are allowed a higher rate than write operations (creating a charge), reflecting that writes are typically more expensive to process and carry higher risk if abused (e.g., a bug in a merchant's retry logic looping on a failed charge creation). This is a direct application of the "layer multiple limits, don't apply one blanket number" principle above, but split **by operation type** rather than by client.

**The lesson:** rate limits shouldn't be a single global number chosen for convenience — they should reflect the actual cost and risk profile of the specific operation being protected, which is exactly why Stripe's writes and reads are limited differently even for the same customer.

---

## 5. Spring Boot Example: Distributed Token Bucket Rate Limiter with Redis (Fail-Open Fallback)

```java
// build.gradle: spring-boot-starter-data-redis

@Component
@RequiredArgsConstructor
@Slf4j
public class TokenBucketRateLimiter {

    private final StringRedisTemplate redisTemplate;
    private static final String LUA_SCRIPT = """
        local key = KEYS[1]
        local capacity = tonumber(ARGV[1])
        local refill_rate = tonumber(ARGV[2])
        local now = tonumber(ARGV[3])

        local bucket = redis.call('HMGET', key, 'tokens', 'last_refill')
        local tokens = tonumber(bucket[1]) or capacity
        local last_refill = tonumber(bucket[2]) or now

        -- Refill based on elapsed time since last check -- this is what makes
        -- token bucket allow bursts: tokens accumulate during idle periods.
        local elapsed = math.max(0, now - last_refill)
        tokens = math.min(capacity, tokens + (elapsed * refill_rate))

        if tokens >= 1 then
            tokens = tokens - 1
            redis.call('HMSET', key, 'tokens', tokens, 'last_refill', now)
            redis.call('EXPIRE', key, 3600)
            return 1  -- request allowed
        else
            redis.call('HMSET', key, 'tokens', tokens, 'last_refill', now)
            redis.call('EXPIRE', key, 3600)
            return 0  -- request denied
        end
        """;

    private final RedisScript<Long> script = RedisScript.of(LUA_SCRIPT, Long.class);

    // Atomicity via a single Lua script executed server-side in Redis is CRITICAL --
    // without it, a check-then-decrement done as two separate round trips from the
    // app server would race across concurrent requests / concurrent app instances.
    public boolean tryConsume(String apiKey, int capacity, double refillPerSecond) {
        try {
            Long result = redisTemplate.execute(script,
                List.of("rate-limit:" + apiKey),
                String.valueOf(capacity),
                String.valueOf(refillPerSecond),
                String.valueOf(Instant.now().getEpochSecond()));
            return result != null && result == 1L;
        } catch (Exception e) {
            // Explicit fail-open decision: if Redis itself is unavailable, we choose
            // to ALLOW traffic through rather than block all legitimate requests --
            // appropriate here because an unavailable rate limiter is a lesser risk
            // than an outage-causing false block of every API client. A payments
            // system protecting a fragile downstream might reasonably choose fail-CLOSED instead.
            log.error("Rate limiter backend unavailable, failing open", e);
            return true;
        }
    }
}
```

```java
@Component
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private final TokenBucketRateLimiter rateLimiter;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain chain) throws ServletException, IOException {
        String apiKey = request.getHeader("X-API-Key");

        // Layered limits: a cheap per-IP check even before we know the API key,
        // PLUS a per-key check reflecting the customer's actual paid tier --
        // mirrors the Stripe-style "layer multiple limits" principle above.
        boolean allowed = rateLimiter.tryConsume(apiKey, 100, 10.0); // 100 burst, 10/sec sustained

        if (!allowed) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", "1");
            response.getWriter().write("{\"error\":\"rate_limit_exceeded\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
```

**Why this matters at senior level:** the Lua-script atomicity detail and the explicit fail-open-vs-fail-closed decision are exactly the depth staff interviews probe for — a naive "check counter, then increment counter" as two separate Redis calls is a classic race-condition bug under concurrent load, and failing to state a deliberate fail-open/fail-closed choice signals the trade-off wasn't consciously considered.

---

## 6. Common Pitfalls

- Using fixed window counters without acknowledging the boundary-burst flaw — a strong candidate proactively raises this even if the interviewer's question doesn't prompt for algorithm comparison.
- Implementing distributed rate limiting as two separate round trips (read counter, then write incremented counter) instead of an atomic operation (Lua script, or Redis's native `INCR`/`EXPIRE` combination) — a race condition under concurrent requests from multiple app server instances.
- Not deciding, explicitly and in advance, what happens when the rate-limiting backend itself is unavailable — silently defaulting to one behavior (usually fail-open, by accident of "the try-catch just returns true") without recognizing it as a real design decision with real trade-offs.
- Rate limiting by a single dimension (e.g., only IP, or only API key) when layering multiple limits at different points in the request path (edge/IP, gateway/API key, service/global) provides much better protection with the same building blocks.

---

## 7. 60-Second Interview Answer

> "Rate limiting caps request rate per client to protect the system from abuse and overload. Token bucket is the most commonly used algorithm in practice because it allows natural bursts while enforcing a steady average rate, unlike leaky bucket, which smooths output to a perfectly constant rate at the cost of added latency for bursts. Fixed window counters have a real flaw — a client can send double the intended limit right across a window boundary — so I'd use a sliding window counter or token bucket instead. For distributed enforcement across many app servers, I'd centralize the counter in Redis using an atomic Lua script to avoid race conditions, and I'd explicitly decide, rather than default into, whether the limiter fails open or fails closed if Redis itself becomes unavailable — that's a real trade-off, not an implementation detail."

**Related:** [API Gateway](../api-gateway/README.md) · [Load Balancers](../load-balancers/README.md) · [Latency vs Throughput](../../01-foundations/latency-vs-throughput/README.md) · [CAP Theorem](../../01-foundations/cap-theorem/README.md)
