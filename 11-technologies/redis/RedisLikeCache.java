import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * RedisLikeCache — the core mechanisms of Redis-as-a-cache, from scratch.
 *
 * Implements, in the way Redis actually does it:
 *   1. Per-key TTL with LAZY expiry (checked on access) + ACTIVE expiry
 *      (background sampling loop) — mirroring Redis's dual strategy.
 *   2. APPROXIMATE LRU eviction under a maxKeys budget: sample N random
 *      keys, evict the least-recently-used of the sample (Redis samples 5).
 *   3. Atomic INCR — trivially safe here for the same reason as in Redis:
 *      one writer executes at a time (we use a single global lock to model
 *      Redis's single-threaded command loop).
 *   4. A minimal fire-and-forget PUB/SUB.
 *
 * Compile & run:  javac RedisLikeCache.java && java RedisLikeCache
 */
public class RedisLikeCache {

    /** A stored value + expiry metadata + LRU clock. */
    private static final class Entry {
        Object value;
        long expiresAtMillis;     // 0 = no TTL
        long lastAccessMillis;    // LRU clock
        Entry(Object v, long ttlMillis) {
            this.value = v;
            this.expiresAtMillis = ttlMillis > 0 ? System.currentTimeMillis() + ttlMillis : 0;
            this.lastAccessMillis = System.currentTimeMillis();
        }
        boolean expired() {
            return expiresAtMillis > 0 && System.currentTimeMillis() >= expiresAtMillis;
        }
    }

    private final Map<String, Entry> store = new HashMap<>();
    /** Models Redis's single-threaded command execution: all commands serialize here. */
    private final ReentrantLock commandLoop = new ReentrantLock();
    private final int maxKeys;
    private final int evictionSampleSize;
    private final Random random = new Random();
    private final Map<String, List<Consumer<String>>> subscribers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService activeExpiryTimer =
            Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r); t.setDaemon(true); return t; });

    public RedisLikeCache(int maxKeys, int evictionSampleSize) {
        this.maxKeys = maxKeys;
        this.evictionSampleSize = evictionSampleSize;
        // ACTIVE expiry: like Redis, periodically sample keys and delete expired ones,
        // so memory is reclaimed even for keys nobody reads again.
        activeExpiryTimer.scheduleAtFixedRate(this::activeExpireCycle, 100, 100, TimeUnit.MILLISECONDS);
    }

    // ---------------- Commands ----------------

    /** SET key value [EX seconds] */
    public void set(String key, Object value, long ttlMillis) {
        withCommandLoop(() -> {
            if (!store.containsKey(key) && store.size() >= maxKeys) evictOneLikeRedis();
            store.put(key, new Entry(value, ttlMillis));
            return null;
        });
    }

    /** GET key — lazy expiry happens HERE, exactly as in Redis. */
    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return (T) withCommandLoop(() -> {
            Entry e = store.get(key);
            if (e == null) return null;
            if (e.expired()) { store.remove(key); return null; }   // lazy expiry
            e.lastAccessMillis = System.currentTimeMillis();        // touch LRU clock
            return e.value;
        });
    }

    /** INCR key — atomic because commands serialize (Redis: single thread). */
    public long incr(String key) {
        return (Long) withCommandLoop(() -> {
            Entry e = store.get(key);
            long next = 1;
            if (e != null && !e.expired() && e.value instanceof Long l) next = l + 1;
            long ttlRemaining = (e != null && e.expiresAtMillis > 0)
                    ? Math.max(0, e.expiresAtMillis - System.currentTimeMillis()) : 0;
            set(key, next, ttlRemaining);
            return next;
        });
    }

    public boolean del(String key) { return (Boolean) withCommandLoop(() -> store.remove(key) != null); }
    public long ttlMillis(String key) {
        return (Long) withCommandLoop(() -> {
            Entry e = store.get(key);
            if (e == null || e.expired()) return -2L;             // Redis: -2 = no such key
            if (e.expiresAtMillis == 0) return -1L;               // Redis: -1 = no TTL
            return e.expiresAtMillis - System.currentTimeMillis();
        });
    }
    public int dbSize() { return (Integer) withCommandLoop(store::size); }

    // ---------------- Eviction: approximate LRU, the Redis way ----------------

    /**
     * Redis does NOT keep a perfect LRU list (too much memory/bookkeeping).
     * It samples `maxmemory-samples` random keys (default 5) and evicts the
     * least-recently-used among the sample. Cheap, and nearly as good.
     */
    private void evictOneLikeRedis() {
        List<String> keys = new ArrayList<>(store.keySet());
        if (keys.isEmpty()) return;
        String victim = null;
        long oldest = Long.MAX_VALUE;
        for (int i = 0; i < Math.min(evictionSampleSize, keys.size()); i++) {
            String candidate = keys.get(random.nextInt(keys.size()));
            Entry e = store.get(candidate);
            if (e == null) continue;
            if (e.expired()) { victim = candidate; break; }        // expired = free win
            if (e.lastAccessMillis < oldest) { oldest = e.lastAccessMillis; victim = candidate; }
        }
        if (victim != null) {
            store.remove(victim);
            System.out.println("  [evicted (approx-LRU): " + victim + "]");
        }
    }

    /** Active expiry cycle: sample up to 20 keys, remove the expired. */
    private void activeExpireCycle() {
        withCommandLoop(() -> {
            List<String> keys = new ArrayList<>(store.keySet());
            for (int i = 0; i < Math.min(20, keys.size()); i++) {
                String k = keys.get(random.nextInt(keys.size()));
                Entry e = store.get(k);
                if (e != null && e.expired()) store.remove(k);
            }
            return null;
        });
    }

    // ---------------- Pub/Sub (fire-and-forget, like Redis PUBLISH) ----------------

    public void subscribe(String channel, Consumer<String> handler) {
        subscribers.computeIfAbsent(channel, c -> new CopyOnWriteArrayList<>()).add(handler);
    }
    /** Returns receiver count. Note: no persistence — offline subscribers miss messages. */
    public int publish(String channel, String message) {
        List<Consumer<String>> subs = subscribers.getOrDefault(channel, List.of());
        subs.forEach(s -> s.accept(message));
        return subs.size();
    }

    // ---------------- plumbing ----------------

    private <T> T withCommandLoop(java.util.function.Supplier<T> body) {
        commandLoop.lock();
        try { return body.get(); } finally { commandLoop.unlock(); }
    }

    // ---------------- demo ----------------

    public static void main(String[] args) throws Exception {
        RedisLikeCache redis = new RedisLikeCache(5, 5);

        System.out.println("== TTL + lazy expiry ==");
        redis.set("session:42", "shilpak", 300);
        System.out.println("GET session:42 -> " + redis.get("session:42"));
        System.out.println("TTL -> " + redis.ttlMillis("session:42") + "ms");
        Thread.sleep(350);
        System.out.println("after 350ms, GET -> " + redis.get("session:42") + " (lazy-expired)");

        System.out.println("\n== atomic INCR as a rate-limit counter ==");
        for (int i = 0; i < 3; i++)
            System.out.println("INCR rate:user1 -> " + redis.incr("rate:user1"));

        System.out.println("\n== approximate-LRU eviction at maxKeys=5 ==");
        for (int i = 1; i <= 5; i++) redis.set("k" + i, i, 0);
        redis.get("k1"); redis.get("k2");            // touch k1,k2 (recently used)
        redis.set("k6", 6, 0);                       // forces an eviction
        System.out.println("dbsize -> " + redis.dbSize());

        System.out.println("\n== pub/sub ==");
        redis.subscribe("orders", msg -> System.out.println("  subscriber got: " + msg));
        int n = redis.publish("orders", "order-created:789");
        System.out.println("delivered to " + n + " subscriber(s)");
    }
}
