# Structural Design Patterns

> Structural patterns solve one core problem: **how classes and objects are composed into larger structures, without those structures becoming rigid or forcing awkward inheritance hierarchies.** The recurring theme across all of them: **favor composition over inheritance** wherever variation needs to be combined flexibly.

---

## 1. Adapter

**Problem it solves:** make an existing class's interface compatible with what calling code expects, without modifying the existing class — essential when integrating a third-party library or legacy code whose interface doesn't match your system's expectations.

```java
// The interface OUR system expects, used throughout our payment code.
public interface PaymentProcessor {
    PaymentResult charge(BigDecimal amount, String currency);
}

// A third-party SDK we don't control, with an incompatible interface shape.
public class LegacyPaymentGatewaySDK {
    public LegacyResponse processTransaction(int amountInCents, String currencyCode) { ... }
}

// The Adapter: translates OUR interface calls into the third-party SDK's shape.
public class LegacyPaymentAdapter implements PaymentProcessor {
    private final LegacyPaymentGatewaySDK legacySdk;

    public LegacyPaymentAdapter(LegacyPaymentGatewaySDK legacySdk) {
        this.legacySdk = legacySdk;
    }

    @Override
    public PaymentResult charge(BigDecimal amount, String currency) {
        int amountInCents = amount.movePointRight(2).intValueExact(); // adapt data shape
        LegacyResponse legacyResponse = legacySdk.processTransaction(amountInCents, currency);
        return new PaymentResult(legacyResponse.isOk(), legacyResponse.getRefId()); // adapt response shape
    }
}
```

**Why this matters at senior level:** this is precisely how you'd cleanly support **multiple payment processors** in the [Payment System](../../../03-high-level-design/payment-system/README.md#8-trade-offs--follow-up-questions-to-anticipate) HLD's "failover between processors" requirement — every processor gets its own Adapter implementing the same `PaymentProcessor` interface, so the rest of the codebase never needs to know which concrete processor is in use.

---

## 2. Decorator

**Problem it solves:** attach additional responsibilities to an object **dynamically**, without modifying its class or resorting to a combinatorial explosion of subclasses (e.g., needing `LoggingCachedRateLimitedService`, `CachedRateLimitedService`, `LoggingRateLimitedService`, ... for every combination of optional behaviors).

```java
public interface DataFetcher {
    String fetch(String key);
}

public class DatabaseFetcher implements DataFetcher {
    public String fetch(String key) { /* actual DB query */ return "data-for-" + key; }
}

// Each decorator WRAPS another DataFetcher, adding one behavior, and can be
// stacked in any combination -- no subclass explosion needed.
public class CachingDecorator implements DataFetcher {
    private final DataFetcher delegate;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public CachingDecorator(DataFetcher delegate) { this.delegate = delegate; }

    @Override
    public String fetch(String key) {
        return cache.computeIfAbsent(key, delegate::fetch); // cache-aside, see Caching doc
    }
}

public class LoggingDecorator implements DataFetcher {
    private final DataFetcher delegate;
    public LoggingDecorator(DataFetcher delegate) { this.delegate = delegate; }

    @Override
    public String fetch(String key) {
        log.info("Fetching key: {}", key);
        long start = System.nanoTime();
        String result = delegate.fetch(key);
        log.info("Fetch took {}ms", (System.nanoTime() - start) / 1_000_000);
        return result;
    }
}

// Usage: compose behaviors by wrapping, in whatever order/combination is needed --
// this IS the cache-aside pattern from the Caching building block, expressed as OOP composition.
DataFetcher fetcher = new LoggingDecorator(new CachingDecorator(new DatabaseFetcher()));
```

**Real-world note:** Java's own I/O library (`new BufferedReader(new InputStreamReader(new FileInputStream(...)))`) is the canonical textbook instance of this exact pattern, one most engineers use daily without naming it.

---

## 3. Facade

**Problem it solves:** provide a single, simplified interface over a complex subsystem of many interacting classes, so calling code doesn't need to understand or coordinate all of them directly.

```java
// A complex subsystem with several classes that must be coordinated in the right order.
public class OrderFacade {
    private final InventoryService inventoryService;
    private final PaymentService paymentService;
    private final ShippingService shippingService;
    private final NotificationService notificationService;

    // Calling code (e.g., a controller) calls ONE method, unaware of the
    // multi-step choreography happening underneath.
    public OrderResult placeOrder(OrderRequest request) {
        inventoryService.reserve(request.getItems());
        PaymentResult payment = paymentService.charge(request.getPaymentDetails());
        if (!payment.isSuccessful()) {
            inventoryService.release(request.getItems());
            return OrderResult.failed("Payment declined");
        }
        shippingService.scheduleShipment(request);
        notificationService.notifyOrderConfirmed(request.getCustomerId());
        return OrderResult.success();
    }
}
```

**Distinction from a plain service class (a fair interview question):** the "Facade" label specifically emphasizes that the point is **simplifying access to an existing, more complex subsystem of already-separate classes** for a specific caller's needs — it doesn't necessarily contain new business logic itself, just orchestration. In practice, in a well-layered Spring Boot app, this often looks exactly like a typical `@Service` class, and that's fine — the pattern name matters less than recognizing the "hide complexity behind one entry point" intent.

---

## 4. Composite

**Problem it solves:** treat individual objects and compositions (groups) of objects **uniformly**, through a shared interface — ideal for tree-structured, part-whole hierarchies (file systems, UI component trees, organizational hierarchies).

```java
public interface FileSystemNode {
    long getSize();
}

public class File implements FileSystemNode {
    private final long size;
    public File(long size) { this.size = size; }
    public long getSize() { return size; }
}

// A Directory holds a mix of Files AND other Directories, but exposes the
// SAME getSize() interface -- calling code doesn't need to distinguish them.
public class Directory implements FileSystemNode {
    private final List<FileSystemNode> children = new ArrayList<>();

    public void add(FileSystemNode node) { children.add(node); }

    @Override
    public long getSize() {
        return children.stream().mapToLong(FileSystemNode::getSize).sum(); // recurses naturally
    }
}
```

**Where this shows up in this vault:** the folder hierarchy in [Google Drive](../../../03-high-level-design/google-drive/README.md) (files and folders, where a folder can contain files and other folders, and operations like "compute total size" or "check permissions" should work uniformly across both) is a textbook Composite use case.

---

## 5. Proxy

**Problem it solves:** provide a stand-in/surrogate for another object that controls access to it — adding a layer of indirection for lazy loading, access control, caching, or remote-object representation, **without the calling code needing to know a proxy is involved at all.**

```java
public interface Image {
    void display();
}

public class HighResImage implements Image {
    private final String path;
    public HighResImage(String path) {
        this.path = path;
        loadFromDisk(path); // expensive -- happens immediately in the real object
    }
    public void display() { /* render */ }
}

// A Proxy that defers the expensive load until display() is ACTUALLY called --
// this is "virtual proxy" / lazy-loading, one of several proxy sub-types.
public class LazyImageProxy implements Image {
    private final String path;
    private HighResImage realImage; // null until first actually needed

    public LazyImageProxy(String path) { this.path = path; }

    @Override
    public void display() {
        if (realImage == null) {
            realImage = new HighResImage(path); // expensive load deferred until now
        }
        realImage.display();
    }
}
```

**Distinction from Decorator (a very commonly confused pair in interviews):** both wrap another object behind the same interface, but their **intent** differs — a Decorator's purpose is to **add behavior**, and you typically want the decoration to happen (it's an intentional enhancement, and the caller usually knows composition is at play). A Proxy's purpose is to **control access** to the underlying object (lazy loading, permission checks, remote-call marshalling), and ideally the caller is **unaware** a proxy is even involved — from the caller's perspective, it should be indistinguishable from talking to the real object directly. Spring's own `@Transactional`/`@Cacheable` AOP mechanism (used throughout this vault's Spring Boot examples) works by generating **proxies** around your `@Service` beans at runtime, specifically to intercept calls transparently — a real, load-bearing production use of this exact pattern.

---

## 6. Which Structural Pattern, When — the Interview Decision Table

| Situation | Pattern |
|---|---|
| Two interfaces don't match, and you can't change one of them (usually third-party code) | Adapter |
| You need to add optional behaviors that can combine in different combinations, without a subclass explosion | Decorator |
| A subsystem has many classes, and callers just want one simple entry point | Facade |
| You have a tree/part-whole structure and want uniform treatment of leaves and groups | Composite |
| You need to control/defer/intercept access to an object transparently to the caller | Proxy |

**Related:** [Creational Patterns](../creational/README.md) · [Behavioral Patterns](../behavioral/README.md) · [Google Drive](../../../03-high-level-design/google-drive/README.md) · [Payment System](../../../03-high-level-design/payment-system/README.md)
