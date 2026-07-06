# SQL vs NoSQL

> The SQL vs NoSQL "debate" is largely settled at the senior level: it's not a hierarchy (NoSQL isn't "the modern upgrade" to SQL), it's a **fit-for-purpose decision** based on data shape, query patterns, and consistency needs. Most non-trivial real systems use **both**, for different data.

---

## 1. What "SQL" Actually Buys You

Relational databases (Postgres, MySQL, Oracle, SQL Server) give you:

- **ACID transactions** — Atomicity, Consistency, Isolation, Durability — a group of writes either all happen or none do, and concurrent transactions don't corrupt each other's view of the data.
- **A fixed, enforced schema** — every row in a table has the same columns, types, and constraints (NOT NULL, foreign keys, unique constraints) — the database itself rejects invalid data.
- **Joins** — the ability to relate normalized tables together at query time (e.g., `orders JOIN customers JOIN products`), avoiding data duplication.
- **A mature, standardized, extremely expressive query language (SQL)** — ad-hoc analytical queries, aggregations, and complex filtering are all first-class.

**The cost:** vertical scaling is the natural first lever (a single writer-leader for strong consistency); horizontal write-scaling requires sharding, which breaks joins across shards and is a genuinely hard engineering problem (see [Sharding](../sharding/README.md)).

## 2. What "NoSQL" Actually Buys You

"NoSQL" is really an umbrella over several very different data models — conflating them is a common junior mistake:

| NoSQL type | Data model | Examples | Best for |
|---|---|---|---|
| **Key-Value** | Simple key → opaque value | Redis, DynamoDB, Riak | Session storage, caching, simple lookups by ID |
| **Document** | Key → semi-structured document (JSON/BSON) | MongoDB, Couchbase | Data with nested/variable structure per record (e.g., a product catalog where different categories have different attributes) |
| **Wide-Column** | Rows with dynamic, sparse sets of columns, optimized for huge write volume | Cassandra, HBase, Bigtable | Massive-scale time-series/event data, write-heavy workloads |
| **Graph** | Nodes and edges as first-class citizens | Neo4j, Amazon Neptune | Data whose *relationships* are the primary query pattern (social graphs, fraud detection, recommendation engines) |

The common thread across all of them: **relaxed or absent schema enforcement, horizontal scalability designed in from the start (often via built-in sharding/partitioning), and typically weaker consistency guarantees by default** (many are AP systems per [CAP Theorem](../../../01-foundations/cap-theorem/README.md)) in exchange for that scalability.

---

## 3. The Actual Decision Framework (what a staff interview is testing)

| Question | If "yes" → lean SQL | If "yes" → lean NoSQL |
|---|---|---|
| Does the data have multiple entities with well-defined relationships you'll query via joins? | ✅ | |
| Do you need multi-row/multi-table ACID transactions (e.g., debit one account, credit another, atomically)? | ✅ | |
| Is write volume so high that a single-leader relational writer will bottleneck, and horizontal write scaling is a first-order requirement? | | ✅ |
| Does each record's shape vary significantly (e.g., different product types with different attribute sets)? | | ✅ (document) |
| Is the core query pattern "traverse relationships N hops deep" (friends-of-friends, fraud rings)? | | ✅ (graph) |
| Do you need flexible, ad-hoc analytical queries across the dataset? | ✅ | |

**Senior-level nuance:** modern relational databases have eroded much of the traditional gap — Postgres supports a native `JSONB` column type (semi-structured documents inside a relational table), and many NoSQL stores (MongoDB 4.0+, for instance) now support multi-document ACID transactions. The interview signal isn't reciting "NoSQL has no transactions" as a fixed law — it's knowing the **current, specific trade-offs of the actual technology you'd choose**, and being honest that the line has blurred.

---

## 4. Real-World Example: Uber's Migration from Postgres to a Custom MySQL-based Schemaless Layer, and Back

Uber's engineering blog has publicly documented a genuinely instructive back-and-forth: they originally used **Postgres**, migrated core datastores to a custom **MySQL-based "Schemaless"** layer (a sharded, append-only key-value abstraction on top of MySQL) to solve write-scalability and replication issues they were hitting at rapidly growing scale, and later **migrated back to Postgres** for a significant portion of workloads once Postgres's own scalability and tooling caught up and the operational complexity of the custom layer outweighed its benefits.

The senior-level lesson from this case, as Uber's engineers describe it:
- The original move away from vanilla Postgres was driven by concrete, measured problems — specifically, replication and schema-migration pain at their write volume and growth rate — not a generic belief that "NoSQL scales better."
- The move back was driven by the **operational cost of maintaining a custom-built schemaless abstraction layer** outweighing the marginal scalability benefit it once provided, once Postgres itself had matured.

**The lesson:** the SQL/NoSQL decision is not permanent or ideological — it should be revisited as both your workload and the available technology evolve, and "we chose X five years ago" is not a reason to keep choosing it if the original constraints have changed.

---

## 5. Spring Boot Example: Using Both in the Same Application (Polyglot Persistence)

A realistic e-commerce order service: **order data** (needs ACID, relationships, financial correctness) lives in Postgres; **product catalog** (highly variable attribute sets per category, read-heavy) lives in MongoDB.

```java
// Order: strongly relational, needs ACID across order + order_items + payment -- SQL.
@Entity
@Table(name = "orders")
public class Order {
    @Id @GeneratedValue
    private Long id;
    private String customerId;
    @Enumerated(EnumType.STRING)
    private OrderStatus status;
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL)
    private List<OrderItem> items;
}

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;

    // Multi-table ACID transaction: order creation and payment record must succeed
    // or fail TOGETHER -- this is exactly the case where SQL's transactional
    // guarantees earn their keep over a NoSQL alternative.
    @Transactional
    public Order placeOrder(OrderRequest request) {
        Order order = orderRepository.save(Order.from(request));
        Payment payment = paymentRepository.save(Payment.pending(order.getId(), request.getAmount()));
        if (!chargePayment(payment)) {
            throw new PaymentFailedException(); // rolls back BOTH inserts atomically
        }
        order.setStatus(OrderStatus.CONFIRMED);
        return orderRepository.save(order);
    }
}
```

```java
// Product catalog: wildly variable attributes per category (a "laptop" has RAM/CPU,
// a "t-shirt" has size/color) -- rigid relational columns would mean a sparse,
// awkward schema or endless ALTER TABLEs. A schemaless document model fits naturally.
@Document(collection = "products")
public class Product {
    @Id
    private String sku;
    private String category;
    private Map<String, Object> attributes; // free-form per category, no schema migration needed
}

@Service
@RequiredArgsConstructor
public class ProductCatalogService {

    private final ProductMongoRepository productRepository; // Spring Data MongoDB

    public Product addProduct(String sku, String category, Map<String, Object> attrs) {
        // No ALTER TABLE needed when a new category introduces new attribute keys --
        // this flexibility is precisely NoSQL's core value proposition here.
        return productRepository.save(new Product(sku, category, attrs));
    }
}
```

```yaml
# application.yml -- polyglot persistence: two datasource types, chosen per bounded context
spring:
  datasource:
    url: jdbc:postgresql://orders-db.internal:5432/orders   # relational, for Order/Payment
  data:
    mongodb:
      uri: mongodb://catalog-db.internal:27017/catalog       # document store, for Product
```

**Why this matters at senior level:** demonstrating **polyglot persistence within a single bounded application** — not "SQL vs NoSQL" as a global, once-and-for-all architectural choice — is exactly the nuance that separates a senior answer from a junior one.

---

## 6. Common Pitfalls

- Treating "NoSQL" as one technology rather than four distinct data models (key-value, document, wide-column, graph) with very different strengths.
- Choosing NoSQL purely because "it scales better" without a concrete scaling bottleneck identified — modern relational databases handle far more scale than this myth assumes, especially with read replicas.
- Forgetting that giving up joins (common in most NoSQL stores) pushes relationship-handling logic into the application layer — this is a real engineering cost, not a free lunch.
- Assuming schema flexibility is purely a benefit — an unenforced schema means data-quality bugs are caught at read time (or never), instead of being rejected at write time by the database itself.

---

## 7. 60-Second Interview Answer

> "SQL gives you ACID transactions, enforced schema, and joins, at the cost of write-scaling requiring sharding, which is hard. NoSQL is really four different data models — key-value, document, wide-column, graph — each trading schema enforcement and joins for horizontal scalability and a data model suited to a specific access pattern. I'd choose based on concrete needs: relational for data with real cross-entity transactional requirements like orders and payments, document stores for data with highly variable structure like a product catalog, wide-column for massive write-heavy event data, graph when the core query is relationship traversal. In practice, most real systems use more than one — polyglot persistence per bounded context, not one database technology for the whole system."

**Related:** [CAP Theorem](../../../01-foundations/cap-theorem/README.md) · [Sharding](../sharding/README.md) · [Replication](../replication/README.md) · [Transactions & ACID](../../../06-databases-deep-dive/transactions-acid/README.md)
