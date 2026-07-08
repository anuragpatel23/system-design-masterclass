# Event-Driven Architecture (EDA)

> **The architectural shift, precisely stated:** instead of Service A directly calling Service B's API (a synchronous, temporally-coupled request), Service A publishes a fact — "an event" — describing something that already happened, and any number of other services independently subscribe to and react to that fact, without Service A knowing or caring who they are, how many there are, or whether they're currently even running. This is the [pub/sub pattern](../../02-building-blocks/message-queues/README.md#1-messaging-patterns) from Message Queues elevated to a whole-system architectural philosophy, not just a single building block.

---

## 1. Events vs Commands — a Distinction Worth Getting Precise

- **A command** is a request for something to happen, directed at a specific recipient, with an expectation of a specific outcome (e.g., `ChargeCustomer`, sent specifically to the Payment Service, expecting it to actually attempt a charge). Commands are imperative and typically synchronous or at least directed.
- **An event** is a statement of fact about something that **already happened**, broadcast without a specific intended recipient (e.g., `OrderPlaced`, `PaymentCompleted`) — any number of services (zero, one, or many) may care about it, and the publisher has no expectation about what, if anything, happens in response.

**Why this distinction matters architecturally:** a system built around commands has the sender's code explicitly naming and depending on the receiver (`orderService.calls(paymentService)`), which is exactly the tight coupling EDA exists to eliminate. A system built around events lets new subscribers be added **with zero changes to the publisher** — this is precisely the [Observer pattern](../../04-low-level-design/design-patterns/behavioral/README.md#3-observer) from Low-Level Design, generalized from a single process to an entire distributed system, and it's worth explicitly naming that parallel in an interview.

---

## 2. Core Benefits (and Why Each One Is True, Not Just Asserted)

- **Loose coupling / independent deployability:** because a publisher has no compile-time or runtime dependency on its subscribers, new consumers of an existing event stream can be built, deployed, and iterated on entirely independently — a new "fraud detection" service can start consuming `OrderPlaced` events tomorrow without a single line of the Order Service changing, deploying, or even being aware it happened.
- **Natural scalability and resilience via decoupling in time:** a slow or temporarily-down subscriber doesn't block or fail the publisher's own operation — this is the same decoupling-in-time benefit central to [Message Queues](../../02-building-blocks/message-queues/README.md) and repeatedly leaned on throughout this vault's HLD designs (the [Notification System](../../03-high-level-design/notification-system/README.md)'s triggering services never wait on actual delivery; [Twitter's](../../03-high-level-design/twitter-feed/README.md) fan-out worker is asynchronous specifically so a slow follower-cache write never slows down the act of tweeting).
- **A natural audit trail / historical record:** a stream of "what happened" events is, by construction, a chronological record of everything that occurred in the system — this observation is precisely what motivates [Event Sourcing](../cqrs-event-sourcing/README.md), which takes this idea to its logical conclusion by making the event log itself the *source of truth*, not just a byproduct.

---

## 3. Core Costs (Stated Honestly — a Balanced Answer Matters Here)

- **Harder to trace a single business operation end-to-end.** In a synchronous call chain, a distributed trace naturally follows the request. In an event-driven system, "what happens when an order is placed" is scattered across every independent subscriber's own code — exactly the same "logic scattered across services" drawback named for choreographed sagas in [Saga Pattern](../saga-pattern/README.md#2-two-implementation-styles-choreography-vs-orchestration). Mitigation: correlation IDs propagated through every event, and dedicated distributed tracing infrastructure — but this is real, added operational complexity, not free.
- **Eventual consistency becomes pervasive, not localized.** Every consumer processing an event asynchronously means every piece of derived state in the system can be momentarily stale relative to the originating event — this is fine for many things (see [Consistency Models](../../01-foundations/consistency-models/README.md)) but is a real design constraint that has to be consciously accounted for everywhere, not just in the one or two places a team might expect it.
- **Debugging "why didn't X happen" is harder.** In a direct call, a failure surfaces immediately in the caller's response. In an event-driven system, a subscriber silently failing to process an event (without a proper [dead letter queue](../../02-building-blocks/message-queues/README.md#3-dead-letter-queues-dlq) and alerting) can go unnoticed for a long time — the publisher has no idea and isn't informed.
- **Schema evolution across independently-deployed services is a genuine, ongoing challenge.** If the `OrderPlaced` event's shape needs to change, every independent subscriber consuming it needs to handle both old and new shapes during a rollout window, since publisher and subscribers deploy independently and can't be assumed to update in lockstep.

**A senior-level answer should proactively name at least two or three of these costs unprompted** — EDA is genuinely powerful but is not a free upgrade over direct calls, and interviewers specifically listen for whether a candidate presents it as a trade-off or as an unambiguous best practice (the latter is a mild red flag).

---

## 4. Event Schema Design and Evolution

A well-designed event should generally carry **enough information for a subscriber to act without needing to call back to the publisher** (avoiding a "chatty" round trip that partially reintroduces the coupling EDA is meant to eliminate) — but not so much that every event becomes an unwieldy dump of the entire originating entity's state.

```json
// A reasonably-designed event: enough context to act, without over-including
// unrelated fields a typical subscriber wouldn't need.
{
  "eventType": "OrderPlaced",
  "eventId": "evt_8f3a...",          // unique, for idempotent consumer dedup (see Message Queues)
  "occurredAt": "2026-07-07T10:15:00Z",
  "orderId": "ord_123",
  "customerId": "cust_456",
  "items": [ { "sku": "widget-1", "quantity": 2 } ],
  "totalAmountCents": 5000
}
```

**Schema evolution rule of thumb (a very commonly asked follow-up):** favor **additive, backward-compatible changes** — adding new optional fields is safe, since existing subscribers simply ignore fields they don't recognize; removing or renaming a field, or changing a field's type or meaning, is a breaking change requiring careful, coordinated versioning (e.g., publishing both `OrderPlacedV1` and `OrderPlacedV2` during a migration window, with subscribers migrating on their own independent schedule, then deprecating V1 only once all known subscribers have confirmed migration) — a direct parallel to [API versioning concerns](../../08-api-design/rest-best-practices/README.md) applied to event schemas instead of REST endpoints.

---

## 5. Real-World Example: LinkedIn's Kafka-Centric Architecture (Revisited from a Whole-System Lens)

The [Message Queues](../../02-building-blocks/message-queues/README.md#5-real-world-example-linkedins-kafka--built-to-decouple-hundreds-of-independent-consumers-from-a-firehose-of-events) building block doc covered Kafka's origin at LinkedIn as a solution to an `O(n²)` point-to-point integration mesh. Viewed at the **architectural**, not just the messaging-technology, level: LinkedIn's broader shift was toward treating the **event stream itself as a central nervous system for the whole company's data flow** — an architectural philosophy sometimes described (including by Kafka's own creators in public talks and writing) as making the event log a first-class citizen on par with (or arguably more central than) any individual service's own database, since so many independent systems' behavior is ultimately driven by reacting to the same shared stream of "what happened" facts.

**The lesson, at the architectural level:** EDA isn't just "use a message queue sometimes" — taken to its natural conclusion, it can reshape how an entire organization's systems are built, with services increasingly defined by *what events they consume and produce* rather than *what APIs they expose to be called* — a genuinely different mental model worth being able to articulate, not just a technology choice.

---

## 6. Spring Boot Example: An Event-Driven Order Flow with Correlation IDs for Traceability

```java
// build.gradle: spring-kafka

public record OrderPlacedEvent(
    String eventId,
    String correlationId,   // propagated end-to-end for tracing across independent consumers
    String orderId,
    String customerId,
    List<OrderItem> items,
    Instant occurredAt
) {}

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final KafkaTemplate<String, OrderPlacedEvent> kafkaTemplate;

    @Transactional
    public Order placeOrder(OrderRequest request) {
        Order order = orderRepository.save(Order.from(request));

        String correlationId = MDC.get("correlationId"); // propagated from the inbound HTTP request
        OrderPlacedEvent event = new OrderPlacedEvent(
            UUID.randomUUID().toString(), correlationId, order.getId(),
            order.getCustomerId(), order.getItems(), Instant.now());

        // Publish AFTER the local commit -- see the "outbox pattern" note below
        // for why publishing before/during the DB transaction is a subtle trap.
        kafkaTemplate.send("order-events", order.getId(), event);
        return order;
    }
}

// An entirely independent, separately-deployed service, unaware of and
// unreferenced by OrderService -- this is the whole point of EDA.
@Service
@RequiredArgsConstructor
@Slf4j
public class FraudDetectionConsumer {

    @KafkaListener(topics = "order-events", groupId = "fraud-detection")
    public void onOrderPlaced(OrderPlacedEvent event) {
        MDC.put("correlationId", event.correlationId()); // trace continuity across services
        try {
            log.info("Evaluating order {} for fraud signals", event.orderId());
            // fraud-scoring logic here, entirely independent of OrderService's own code/deploys
        } finally {
            MDC.clear();
        }
    }
}
```

**The subtle trap worth naming (the "dual write" problem):** the code above writes to the database (`orderRepository.save`) and then separately publishes to Kafka (`kafkaTemplate.send`) — these are **two independent operations against two independent systems**, and if the process crashes between them (after the DB commit, before the Kafka publish succeeds), the order exists but **no event was ever published**, silently breaking every downstream consumer's expectation that `OrderPlaced` fires for every order. The robust, production-grade fix is the **Transactional Outbox pattern**: write the event to an "outbox" table in the **same local database transaction** as the order itself (making it atomic with the business write, since it's now just another row in the same database), and have a separate, reliable process (a change-data-capture connector, or a polling publisher) read from that outbox table and publish to Kafka, retrying until it succeeds — this converts "publish to Kafka" from a fragile, transaction-external side effect into an eventually-guaranteed, at-least-once-delivered outcome, at the cost of a small publishing delay and the added outbox-table/relay infrastructure. **Naming the dual-write problem and the Transactional Outbox pattern as its fix, unprompted, is a strong senior-level signal** in any event-driven design discussion.

---

## 7. Common Pitfalls

- Presenting EDA as a strictly-superior replacement for synchronous calls, without naming its real costs (traceability, pervasive eventual consistency, schema evolution complexity) — a balanced answer is expected at senior level.
- The "dual write" problem — publishing an event as a step separate from the local database transaction that produced the underlying change, without the Transactional Outbox pattern (or equivalent) to make the two atomic.
- Overloading events with too much or too little information — too little forces subscribers into chatty callback patterns that reintroduce coupling; too much couples every subscriber to the publisher's full internal data model, making the publisher's internal refactoring harder.
- Treating "event" and "command" as interchangeable terminology — the distinction (broadcast fact vs directed request) has real architectural consequences for coupling and error handling.

---

## 8. 60-Second Interview Answer

> "Event-driven architecture replaces direct service-to-service calls with publishing facts about what already happened, which any number of independent subscribers can react to without the publisher knowing or caring who they are — it's the Observer pattern generalized from a single process to a whole distributed system. The real benefits are loose coupling and independent deployability of new consumers, and decoupling in time so a slow or down subscriber never blocks the publisher. But I'd name the real costs too: tracing a single business operation end-to-end gets harder since the logic is scattered across independent consumers, eventual consistency becomes pervasive rather than localized, and event schema evolution needs careful, additive-by-default versioning since publishers and subscribers deploy independently. The trap I'd specifically design around is the dual-write problem — writing to a database and separately publishing an event are two independent operations, and a crash between them silently drops the event — so I'd use the Transactional Outbox pattern, writing the event to an outbox table in the same local transaction as the business change, with a separate relay process publishing from that outbox reliably."

**Related:** [Message Queues](../../02-building-blocks/message-queues/README.md) · [Saga Pattern](../saga-pattern/README.md) · [CQRS & Event Sourcing](../cqrs-event-sourcing/README.md) · [Design Patterns: Behavioral (Observer)](../../04-low-level-design/design-patterns/behavioral/README.md#3-observer)
