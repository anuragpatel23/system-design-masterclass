# CDN (Content Delivery Network)

> A CDN is a geographically distributed network of proxy servers ("edge nodes" or "Points of Presence") that cache content physically close to end users, reducing latency (less distance for data to travel) and offloading traffic from the origin server. It's the caching pattern from [Caching](../caching/README.md) applied specifically at the geographic/network-topology layer.

---

## 1. Why Physical Distance Actually Matters (the physics, briefly)

Light in fiber travels at roughly two-thirds the speed of light in a vacuum — about 200,000 km/s. A round trip from New York to Singapore (roughly 15,000 km one-way) has a theoretical minimum latency around **150ms**, before any processing time at all, purely from the speed of light in fiber. No amount of server optimization removes this floor — the only fix is **not making the trip in the first place**, which is precisely what a CDN's geographic distribution achieves: serving the response from an edge node in or near Singapore instead of round-tripping to a single origin server in Virginia.

---

## 2. Push vs Pull CDNs

### Pull CDN (Origin Pull) — the more common model today
Edge nodes fetch content from the origin **on the first request** (cache miss) and cache it for subsequent requests, until it expires or is invalidated.
- **Pros:** Simple to set up — you don't need to proactively upload content to every edge location; the CDN handles population automatically as demand appears.
- **Cons:** The very first request from any given region pays full origin latency (a "cold" edge cache) — this is why CDNs pre-position content for anticipated high-demand events when possible.

### Push CDN
Content is proactively uploaded to all (or many) edge nodes **ahead of time**, before any user requests it.
- **Pros:** No cold-cache penalty — content is available at the edge immediately.
- **Cons:** Requires knowing in advance what to push and where; wastes storage/bandwidth pushing content to regions that may never request it; more operational overhead to manage.

**When each is used:** pull CDNs dominate general web/API traffic (unpredictable, long-tail content). Push CDNs (or a pull CDN with pre-warming) are used for **known, high-demand events** — e.g., a major software release, a scheduled live-streamed event, or a new game patch — where the traffic surge is predictable and cold-cache latency for millions of simultaneous first-requesters would be unacceptable.

---

## 3. What CDNs Cache (and What They Can't)

- **Static assets** (images, CSS, JS, video segments, downloadable files) — the classic, easy case: content is identical for every user and changes rarely.
- **Dynamic content acceleration:** modern CDNs (Cloudflare, Akamai, CloudFront) also optimize *dynamic*, per-user content — not by caching the actual response (which varies per user), but by terminating the TLS handshake and TCP connection at the nearby edge node, then using an optimized, persistent backbone connection from the edge back to the origin — cutting the "expensive" part of the round trip (initial connection setup, especially over lossy/high-latency public internet paths) even for uncacheable responses.
- **What genuinely can't be CDN-cached:** truly unique, per-request, real-time data (a live stock ticker's exact current price, a specific user's private account balance) — though even here, the CDN's edge-termination trick above still helps latency, just not via caching.

---

## 4. Cache Invalidation at the Edge

The same invalidation challenge from [Caching](../caching/README.md#4-cache-invalidation--the-two-hard-problems-in-computer-science) applies, amplified by geographic distribution:

- **TTL-based expiry** — simplest, works well for genuinely static or slowly-changing content.
- **Cache-busting via versioned URLs** (e.g., `app.js?v=a1b2c3` or content-hashed filenames like `app.a1b2c3.js`) — avoids invalidation entirely by making a *new* URL for new content, so the old cached URL is simply never requested again. This is the dominant strategy for frontend build assets.
- **Explicit purge/invalidation API calls** — telling the CDN provider to actively evict a specific path from all edge nodes globally; slower (can take seconds to minutes to propagate to every PoP) and often rate-limited/costed by providers, so it's reserved for genuinely necessary cases (a legal takedown, a critical bug in a cached asset) rather than routine updates.

---

## 5. Real-World Example: Akamai's Edge Network During Major Live-Streamed Events

Akamai, one of the oldest and largest CDN providers, has publicly described the engineering behind delivering massive live-streaming events (major sporting events with tens of millions of concurrent global viewers) without origin servers being overwhelmed:

- Rather than every one of tens of millions of viewers' video-chunk requests reaching Akamai's (or the broadcaster's) origin infrastructure, the overwhelming majority are served from **edge caches distributed across thousands of network locations** close to each viewer, with only genuine cache misses (or the very first chunk requests from a region) reaching further upstream.
- For predictable large-scale events, CDN providers work with content owners to **pre-position ("pre-warm") anticipated content** at edge locations expected to see high demand, converting what would otherwise be a pull-CDN cold-start problem at massive simultaneous scale into a push-CDN-like guarantee of edge availability from the first request.
- This tiered structure (edge → regional cache → origin) means the origin only ever needs to handle a small fraction of total global request volume — often described as a **multi-hundred-to-one or greater reduction** in effective origin load compared to raw viewer count, which is the entire economic and technical point of a CDN.

**The lesson:** CDNs aren't just "a cache far away" — they're a hierarchical caching topology (edge → regional → origin) specifically engineered to make origin load nearly independent of total end-user traffic volume, which is the real reason they're a core building block for any internet-scale system, not just a latency optimization.

---

## 6. Spring Boot Example: Setting Correct Cache-Control Headers So a CDN Knows What/How Long to Cache

A CDN can only cache correctly if the origin application tells it how — this is entirely driven by standard HTTP caching headers, which a Spring Boot application controls directly.

```java
@RestController
@RequiredArgsConstructor
public class ProductImageController {

    private final ProductImageService productImageService;

    // Immutable, content-hashed asset URL (e.g., /images/products/abc123.a1b2c3.jpg)
    // -- the filename itself changes if the content changes, so this response
    // can be cached FOREVER by the CDN and every downstream browser cache.
    @GetMapping("/images/products/{hashedFilename}")
    public ResponseEntity<byte[]> getProductImage(@PathVariable String hashedFilename) {
        byte[] imageBytes = productImageService.loadImage(hashedFilename);
        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable())
            .contentType(MediaType.IMAGE_JPEG)
            .body(imageBytes);
    }

    // Mutable, frequently-changing pricing data -- cacheable, but only briefly,
    // and the CDN must revalidate with the origin rather than serve indefinitely stale data.
    @GetMapping("/api/products/{sku}/price")
    public ResponseEntity<PriceResponse> getPrice(@PathVariable String sku) {
        PriceResponse price = productImageService.getCurrentPrice(sku);
        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(30, TimeUnit.SECONDS).cachePublic())
            .eTag(price.computeETag()) // lets the CDN send a cheap "304 Not Modified" on revalidation
            .body(price);
    }

    // Per-user, private data -- MUST NOT be cached by a shared CDN, only (optionally)
    // by the individual user's own browser, or not at all for sensitive data.
    @GetMapping("/api/account/balance")
    public ResponseEntity<BalanceResponse> getBalance(@AuthenticationPrincipal User user) {
        BalanceResponse balance = productImageService.getBalance(user.getId());
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore()) // explicit: never cache this, anywhere
            .body(balance);
    }
}
```

```
# Illustrative CDN (e.g., CloudFront/Cloudflare) behavior driven purely by the headers above:
#
#   /images/products/abc123.a1b2c3.jpg  -> Cache-Control: public, max-age=31536000, immutable
#                                          CDN serves from edge for a year, never re-checks origin
#
#   /api/products/{sku}/price           -> Cache-Control: public, max-age=30
#                                          CDN serves from edge for 30s, then revalidates via ETag
#                                          (a cheap 304 response if unchanged, avoiding a full re-fetch)
#
#   /api/account/balance                -> Cache-Control: no-store
#                                          CDN forwards EVERY request straight to origin, never caches
```

**Why this matters at senior level:** this shows that "put a CDN in front of it" is not itself a complete answer — the origin application must be explicitly designed with correct, differentiated cache-control semantics per endpoint, distinguishing immutable assets, short-lived-but-cacheable data, and genuinely private/sensitive data that must never be cached by a shared intermediary.

---

## 7. Common Pitfalls

- Applying one blanket cache policy to an entire application instead of differentiating by content type/sensitivity (immutable static assets vs short-TTL dynamic data vs never-cache private data).
- Forgetting that a **shared** CDN cache (`Cache-Control: public`) is fundamentally different from a private browser cache (`Cache-Control: private`) — accidentally marking user-specific data as `public` is a serious data-leak risk (User A's cached response being served to User B from a shared edge node).
- Relying on CDN purge/invalidation as a routine deployment step — it's slow and often rate-limited; versioned/content-hashed URLs (cache-busting) should be the default strategy for anything frequently updated.
- Ignoring the "dynamic content acceleration" capability of modern CDNs — assuming a CDN only helps static assets and missing the latency benefit available even for uncacheable, per-user API responses via edge-terminated connections.

---

## 8. 60-Second Interview Answer

> "A CDN caches content at edge locations physically close to users, cutting the latency floor imposed by the speed of light in fiber — a request that would round-trip across a continent instead gets served from a nearby edge node. Pull CDNs populate on first request and are the default; push CDNs, or pre-warmed pull CDNs, are used for predictable high-demand events to avoid a simultaneous cold-cache stampede. I'd drive caching behavior entirely through explicit Cache-Control headers per endpoint — long, immutable caching for content-hashed static assets, short TTLs with ETag revalidation for frequently-changing but shareable data, and no-store for private, per-user data, since a shared edge cache serving one user's private response to another user is a serious security bug, not just a performance miss."

**Related:** [Caching](../caching/README.md) · [Latency vs Throughput](../../01-foundations/latency-vs-throughput/README.md) · [API Gateway](../api-gateway/README.md)
