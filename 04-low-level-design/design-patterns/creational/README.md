# Creational Design Patterns

> Creational patterns solve one core problem: **how objects get created, without the calling code being tightly coupled to concrete classes.** In an interview, the signal isn't "do you know the pattern name" — it's "can you recognize when object creation logic is about to leak into every corner of the codebase, and fix it with the right amount of indirection, not more than needed."

---

## 1. Singleton

**Problem it solves:** ensure a class has exactly one instance, globally accessible (e.g., a configuration manager, a connection pool, a logging service).

**When to actually use it:** genuinely global, stateless-or-carefully-synchronized resources where multiple instances would be wasteful or incorrect (a thread pool, a cache manager). **When to avoid it:** as a lazy substitute for proper dependency injection — Singletons make unit testing harder (global mutable state is difficult to mock/reset between tests) and hide a class's real dependencies. In a Spring Boot context, **`@Component`/`@Service` beans are already effectively singletons by default**, managed by the container — reaching for a manual Singleton pattern in a Spring application is usually a sign you should just use the framework's own DI-managed singleton scope instead.

```java
public class ConnectionPoolManager {
    // volatile ensures visibility of the fully-constructed instance across threads
    // (prevents a subtle bug where another thread sees a partially-constructed object
    // due to instruction reordering -- the "double-checked locking" hazard).
    private static volatile ConnectionPoolManager instance;
    private final List<Connection> pool;

    private ConnectionPoolManager() {
        this.pool = initializePool();
    }

    public static ConnectionPoolManager getInstance() {
        if (instance == null) {                         // first check, no lock (fast path)
            synchronized (ConnectionPoolManager.class) {
                if (instance == null) {                  // second check, WITH lock
                    instance = new ConnectionPoolManager();
                }
            }
        }
        return instance;
    }
}
```

**Interview-critical detail:** the double-checked locking pattern above requires the `volatile` keyword to be correct under the Java Memory Model — omitting it is a classic subtle bug that a senior interviewer will specifically probe for.

---

## 2. Factory Method

**Problem it solves:** delegate the decision of *which concrete class to instantiate* to subclasses (or a dedicated factory), so calling code depends only on an abstract type, never a concrete one.

```java
public interface NotificationSender {
    void send(String recipient, String message);
}

public class EmailSender implements NotificationSender {
    public void send(String recipient, String message) { /* SMTP logic */ }
}

public class SmsSender implements NotificationSender {
    public void send(String recipient, String message) { /* Twilio-like logic */ }
}

// The Factory: calling code never says "new EmailSender()" directly --
// it asks the factory for "whatever sender this channel needs."
public class NotificationSenderFactory {
    public static NotificationSender create(String channel) {
        return switch (channel) {
            case "EMAIL" -> new EmailSender();
            case "SMS" -> new SmsSender();
            default -> throw new IllegalArgumentException("Unknown channel: " + channel);
        };
    }
}
```

**Why this matters for extensibility:** adding a `PushNotificationSender` later means adding one new class and one new `case` branch — every piece of calling code that already depends on the `NotificationSender` interface (see [Notification System](../../../03-high-level-design/notification-system/README.md)) needs zero changes. This is the Open/Closed Principle (open for extension, closed for modification) made concrete.

---

## 3. Abstract Factory

**Problem it solves:** create **families of related objects** together, ensuring the objects within a family are always compatible with each other (e.g., a UI toolkit that must produce a matching Button + Checkbox + ScrollBar for either "Windows style" or "Mac style" — never a mismatched mix).

```java
public interface UIComponentFactory {
    Button createButton();
    Checkbox createCheckbox();
}

public class MacUIFactory implements UIComponentFactory {
    public Button createButton() { return new MacButton(); }
    public Checkbox createCheckbox() { return new MacCheckbox(); }
}

public class WindowsUIFactory implements UIComponentFactory {
    public Button createButton() { return new WindowsButton(); }
    public Checkbox createCheckbox() { return new WindowsCheckbox(); }
}

// Calling code picks ONE factory once, then every component it creates
// through that factory is guaranteed to be from the same consistent family.
UIComponentFactory factory = os.equals("mac") ? new MacUIFactory() : new WindowsUIFactory();
Button button = factory.createButton();
Checkbox checkbox = factory.createCheckbox();
```

**Distinction from Factory Method (a common interview mix-up):** Factory Method creates **one** product via a method (often overridden per subclass); Abstract Factory creates a **family of related products** via an object that bundles multiple factory methods together, guaranteeing the family's internal consistency.

---

## 4. Builder

**Problem it solves:** construct a complex object with many optional parameters step by step, avoiding a constructor with an unwieldy number of parameters (a "telescoping constructor" anti-pattern) and avoiding invalid intermediate states.

```java
public class HttpRequest {
    private final String url;
    private final String method;
    private final Map<String, String> headers;
    private final String body;

    private HttpRequest(Builder builder) {
        this.url = builder.url;
        this.method = builder.method;
        this.headers = builder.headers;
        this.body = builder.body;
    }

    public static class Builder {
        private final String url;              // required -- passed to Builder's constructor
        private String method = "GET";          // sensible default
        private final Map<String, String> headers = new HashMap<>();
        private String body;

        public Builder(String url) {
            this.url = url; // enforce the one truly required field at construction time
        }

        public Builder method(String method) { this.method = method; return this; }
        public Builder header(String key, String value) { this.headers.put(key, value); return this; }
        public Builder body(String body) { this.body = body; return this; }

        public HttpRequest build() {
            if (method.equals("POST") && body == null) {
                throw new IllegalStateException("POST requests require a body"); // validate before construction
            }
            return new HttpRequest(this);
        }
    }
}

// Usage -- fluent, readable, and every intermediate step is still a valid Builder,
// never a half-constructed HttpRequest:
HttpRequest request = new HttpRequest.Builder("https://api.example.com/orders")
    .method("POST")
    .header("Content-Type", "application/json")
    .body("{\"item\":\"widget\"}")
    .build();
```

**Real-world note:** Java's `Stream.builder()`, `StringBuilder`, and Spring's `RestClient.Builder` (used throughout this vault's HLD Spring Boot examples) are all real production instances of this exact pattern.

---

## 5. Prototype

**Problem it solves:** create new objects by **cloning an existing, pre-configured instance**, rather than constructing from scratch — useful when object construction is expensive (e.g., involves a database read or complex computation) and you need many similar-but-slightly-varied copies.

```java
public class GameCharacterTemplate implements Cloneable {
    private String characterClass;
    private int baseHealth;
    private List<String> defaultInventory;

    @Override
    public GameCharacterTemplate clone() {
        try {
            GameCharacterTemplate copy = (GameCharacterTemplate) super.clone();
            // CRITICAL: deep-copy mutable fields, or the clone shares the SAME
            // list reference as the original -- a classic shallow-clone bug.
            copy.defaultInventory = new ArrayList<>(this.defaultInventory);
            return copy;
        } catch (CloneNotSupportedException e) {
            throw new AssertionError(e); // Cloneable was implemented, this can't happen
        }
    }
}

// Usage: clone a pre-configured "Warrior" template instead of rebuilding
// its default stats/inventory from scratch every time a player picks that class.
GameCharacterTemplate warriorTemplate = loadTemplateFromConfig("warrior");
GameCharacterTemplate playerCharacter = warriorTemplate.clone();
```

**Interview-critical detail:** the shallow-vs-deep clone distinction above is the single most commonly probed follow-up for this pattern — Java's default `Object.clone()` performs a **shallow copy**, meaning mutable reference fields (lists, maps, nested objects) are shared between the original and the clone unless explicitly deep-copied, which is a subtle, real bug source.

---

## 6. Which Creational Pattern, When — the Interview Decision Table

| Situation | Pattern |
|---|---|
| Exactly one instance must exist globally | Singleton (or, in Spring, just use a default-scoped `@Bean`) |
| Calling code needs a new object of a type chosen at runtime, without knowing the concrete class | Factory Method |
| Need to create a whole **family** of related objects that must stay mutually consistent | Abstract Factory |
| An object has many optional constructor parameters, or must be validated before it's considered "complete" | Builder |
| Object construction is expensive, and you need many similar variants of a pre-configured base | Prototype |

**Related:** [Structural Patterns](../structural/README.md) · [Behavioral Patterns](../behavioral/README.md) · [Notification System](../../../03-high-level-design/notification-system/README.md)
