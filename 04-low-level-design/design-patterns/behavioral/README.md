# Behavioral Design Patterns

> Behavioral patterns solve one core problem: **how objects communicate and distribute responsibility for a behavior**, especially when that behavior needs to vary independently of the objects using it. This is the category most heavily used across the LLD systems later in this section — nearly every one of them leans on Strategy, State, or Observer.

---

## 1. Strategy

**Problem it solves:** define a family of interchangeable algorithms, encapsulate each one, and make them swappable at runtime — the direct alternative to a large `if/else` or `switch` block scattered through business logic.

```java
public interface PricingStrategy {
    BigDecimal calculatePrice(ParkingTicket ticket);
}

public class HourlyPricingStrategy implements PricingStrategy {
    public BigDecimal calculatePrice(ParkingTicket ticket) {
        long hours = ticket.getDurationInHours();
        return BigDecimal.valueOf(hours * 2.50);
    }
}

public class FlatRatePricingStrategy implements PricingStrategy {
    public BigDecimal calculatePrice(ParkingTicket ticket) {
        return BigDecimal.valueOf(15.00); // e.g., event-day flat rate
    }
}

// The class USING the strategy doesn't know or care which concrete strategy
// it holds -- it's injected, and can be swapped without touching this class.
public class ParkingSpotBilling {
    private final PricingStrategy pricingStrategy;

    public ParkingSpotBilling(PricingStrategy pricingStrategy) {
        this.pricingStrategy = pricingStrategy;
    }

    public BigDecimal generateBill(ParkingTicket ticket) {
        return pricingStrategy.calculatePrice(ticket);
    }
}
```

**Where this shows up:** [Parking Lot](../../parking-lot/README.md)'s pluggable pricing, and more broadly, any place a business rule needs to vary by configuration/tenant/context without an ever-growing conditional. This is arguably the single most useful pattern for interview LLD questions, precisely because "make X pluggable/configurable" is one of the most common follow-up requirements interviewers add mid-interview.

---

## 2. State

**Problem it solves:** allow an object to alter its behavior when its internal state changes, such that it appears to change its class — replacing a sprawling `if (state == X) ... else if (state == Y) ...` conditional with polymorphism, one class per state.

```java
public interface OrderState {
    OrderState next(Order order);   // returns the NEXT state after a valid transition
    void cancel(Order order);
}

public class PlacedState implements OrderState {
    public OrderState next(Order order) {
        order.notifyRestaurant();
        return new PreparingState();
    }
    public void cancel(Order order) { order.refund(); /* cancellation allowed here */ }
}

public class PreparingState implements OrderState {
    public OrderState next(Order order) {
        order.assignDeliveryRider();
        return new OutForDeliveryState();
    }
    public void cancel(Order order) {
        throw new IllegalStateException("Cannot cancel once preparation has started");
        // THIS is the key value of the State pattern: illegal transitions are
        // enforced structurally, per-state, rather than via a giant shared
        // conditional that's easy to get wrong or forget to update.
    }
}

public class OutForDeliveryState implements OrderState {
    public OrderState next(Order order) { return new DeliveredState(); }
    public void cancel(Order order) { throw new IllegalStateException("Cannot cancel, already out for delivery"); }
}

public class Order {
    private OrderState currentState = new PlacedState();
    public void advance() { this.currentState = currentState.next(this); }
    public void cancel() { currentState.cancel(this); }
}
```

**Where this shows up:** [Elevator System](../../elevator-system/README.md) (idle/moving-up/moving-down/door-open states) and [Food Delivery App](../../food-delivery-app/README.md) (order lifecycle, exactly as shown above) are the canonical State-pattern LLD interview questions — an interviewer explicitly asking "how do you prevent an invalid state transition?" is looking for exactly this structure.

---

## 3. Observer

**Problem it solves:** define a one-to-many dependency so that when one object (the "subject") changes state, all its dependents ("observers") are notified automatically — the OOP-level analogue of the pub/sub messaging pattern from [Message Queues](../../../02-building-blocks/message-queues/README.md), but in-process rather than over a network.

```java
public interface OrderObserver {
    void onOrderStatusChanged(Order order, OrderStatus newStatus);
}

public class Order {
    private final List<OrderObserver> observers = new ArrayList<>();
    private OrderStatus status;

    public void addObserver(OrderObserver observer) { observers.add(observer); }

    public void updateStatus(OrderStatus newStatus) {
        this.status = newStatus;
        // The Order doesn't know or care WHAT the observers do with this --
        // could be sending a notification, updating an analytics dashboard,
        // logging, etc. -- new observers can be added with ZERO changes here.
        observers.forEach(observer -> observer.onOrderStatusChanged(this, newStatus));
    }
}

public class CustomerNotifierObserver implements OrderObserver {
    public void onOrderStatusChanged(Order order, OrderStatus newStatus) {
        // triggers the Notification System HLD's fan-out for this event
    }
}

public class AnalyticsObserver implements OrderObserver {
    public void onOrderStatusChanged(Order order, OrderStatus newStatus) {
        // records a metrics event, entirely independent of the notifier above
    }
}
```

**Interview-critical connection to make explicit:** this is exactly the in-process design pattern whose distributed, cross-service equivalent is the **pub/sub** messaging pattern from [Message Queues](../../../02-building-blocks/message-queues/README.md#1-messaging-patterns) — recognizing and stating that "Observer is to a single process what pub/sub is to a distributed system" is a strong, connective, senior-level thing to say out loud.

---

## 4. Command

**Problem it solves:** encapsulate a request/action as an object, so it can be queued, logged, undone, or passed around like any other value — decoupling the object that *invokes* an operation from the object that actually *performs* it.

```java
public interface Command {
    void execute();
    void undo();
}

public class MoveCommand implements Command {
    private final ChessBoard board;
    private final Position from, to;
    private Piece capturedPiece; // remembered for undo

    public MoveCommand(ChessBoard board, Position from, Position to) {
        this.board = board; this.from = from; this.to = to;
    }

    @Override
    public void execute() {
        this.capturedPiece = board.getPieceAt(to);  // remember what was there, for undo
        board.movePiece(from, to);
    }

    @Override
    public void undo() {
        board.movePiece(to, from);
        if (capturedPiece != null) {
            board.placePiece(capturedPiece, to); // restore captured piece on undo
        }
    }
}

// A history stack of executed commands gives undo/redo "for free" --
// this is precisely how a chess game's move history and undo feature work.
public class MoveHistory {
    private final Deque<Command> history = new ArrayDeque<>();

    public void executeAndRecord(Command command) {
        command.execute();
        history.push(command);
    }

    public void undoLast() {
        if (!history.isEmpty()) {
            history.pop().undo();
        }
    }
}
```

**Where this shows up:** [Chess Game](../../chess-game/README.md)'s move history/undo feature is the textbook use case — representing each move as a Command object is what makes undo, replay, and move history all fall out of the same simple structure, rather than needing separate bespoke logic for each.

---

## 5. Which Behavioral Pattern, When — the Interview Decision Table

| Situation | Pattern |
|---|---|
| An algorithm/business rule needs to vary and be swappable at runtime (pricing, matching, ranking) | Strategy |
| An object's allowed behaviors/transitions depend on its current lifecycle state | State |
| Multiple, decoupled parts of the system need to react to an event without the source object knowing about them | Observer |
| An action needs to be queued, logged, undone, or treated as a first-class object | Command |

**A genuinely important meta-point for interviews:** **Strategy and State have nearly identical class structures** (an interface, multiple implementations, a context class holding a reference to one of them) — the difference is entirely about **intent**: Strategy is about interchangeable *algorithms* chosen once (often at construction) and generally not expected to change themselves; State is about an object's *behavior changing as a direct result of its own lifecycle*, with the state itself often deciding and returning the next state (as shown above). Being able to articulate this distinction, rather than treating them as the same pattern with two names, is a genuine signal of understanding rather than memorization.

**Related:** [Creational Patterns](../creational/README.md) · [Structural Patterns](../structural/README.md) · [Parking Lot](../../parking-lot/README.md) · [Elevator System](../../elevator-system/README.md) · [Chess Game](../../chess-game/README.md) · [Message Queues](../../../02-building-blocks/message-queues/README.md)
