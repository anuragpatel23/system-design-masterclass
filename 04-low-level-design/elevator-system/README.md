# Low-Level Design: Elevator System

> **The core OOP challenge:** modeling elevator movement as an explicit **State machine** (idle / moving-up / moving-down / door-open), combined with a **scheduling algorithm** that decides how to service concurrent, arbitrarily-ordered requests efficiently — this is the LLD question most likely to have a genuinely interesting algorithmic component, not just class-diagram bookkeeping.

---

## 1. Requirements Clarification

- A building with N floors and M elevators.
- A user on any floor can press an up/down call button (an **external request**).
- A user inside an elevator can press a destination floor button (an **internal request**).
- The system should minimize wait time / avoid unnecessary direction reversals (the classic "elevator algorithm" problem).
- (Extension to anticipate) Multiple elevators — which one should service a given request?

---

## 2. Class Design

```
ElevatorController
 ├── manages many ── Elevator
 │                      ├── has a ── ElevatorState (interface — State pattern)
 │                      │              ├── IdleState
 │                      │              ├── MovingUpState
 │                      │              ├── MovingDownState
 │                      │              └── DoorOpenState
 │                      └── has a ── SchedulingStrategy (interface — Strategy pattern,
 │                                     for WITHIN a single elevator's request ordering)
 └── uses ── ElevatorSelectionStrategy (interface — Strategy pattern,
              for WHICH elevator services a new request, in a multi-elevator building)

Request (abstract)
 ├── ExternalRequest (floor + direction, from a hallway call button)
 └── InternalRequest (destination floor, from inside the elevator car)
```

---

## 3. The State Pattern for Elevator Movement

```java
public interface ElevatorState {
    void handle(Elevator elevator);
}

public class IdleState implements ElevatorState {
    @Override
    public void handle(Elevator elevator) {
        if (elevator.hasPendingRequests()) {
            int nextFloor = elevator.getNextTargetFloor();
            elevator.setState(nextFloor > elevator.getCurrentFloor()
                ? new MovingUpState() : new MovingDownState());
        }
        // else: remain idle -- no pending requests, nothing to do
    }
}

public class MovingUpState implements ElevatorState {
    @Override
    public void handle(Elevator elevator) {
        elevator.incrementFloor();
        if (elevator.shouldStopAtCurrentFloor()) {
            elevator.setState(new DoorOpenState());
        }
        // else: remain in MovingUpState, continue ascending on the next tick
    }
}

public class MovingDownState implements ElevatorState {
    @Override
    public void handle(Elevator elevator) {
        elevator.decrementFloor();
        if (elevator.shouldStopAtCurrentFloor()) {
            elevator.setState(new DoorOpenState());
        }
    }
}

public class DoorOpenState implements ElevatorState {
    @Override
    public void handle(Elevator elevator) {
        elevator.removeSatisfiedRequestsAtCurrentFloor();
        elevator.setState(elevator.hasPendingRequests() ? new IdleState() : new IdleState());
        // Returning to IdleState lets the NEXT handle() tick decide the new
        // direction fresh, based on whatever requests remain -- avoids
        // baking a stale direction decision into the door-close transition itself.
    }
}
```

**Why State, not a `switch` on an enum, matters here as a design choice (not just style):** each state's `handle()` method encodes **exactly what's legal from that state** — `DoorOpenState` doesn't have a way to accidentally skip straight to `MovingUpState` without processing pending requests first, because that transition simply isn't a method that exists on it. A shared `switch` statement scattered across the codebase (rather than encapsulated per-state) makes it much easier to accidentally introduce an illegal transition somewhere as the system grows, which is precisely the bug class the State pattern is designed to structurally prevent.

---

## 4. The Elevator Class and Request Queue Management

```java
public class Elevator {
    private final int id;
    private int currentFloor;
    private ElevatorState state;
    private final TreeSet<Integer> upRequests = new TreeSet<>();      // sorted ascending
    private final TreeSet<Integer> downRequests = new TreeSet<>(Comparator.reverseOrder()); // sorted descending

    public Elevator(int id) {
        this.id = id;
        this.currentFloor = 0;
        this.state = new IdleState();
    }

    public void addRequest(int floor) {
        if (floor > currentFloor) upRequests.add(floor);
        else if (floor < currentFloor) downRequests.add(floor);
        // if floor == currentFloor, it's already satisfied -- a common edge case worth handling explicitly
    }

    public boolean hasPendingRequests() {
        return !upRequests.isEmpty() || !downRequests.isEmpty();
    }

    public int getNextTargetFloor() {
        // SCAN-style logic (see Component Deep Dive below): prefer continuing
        // in whichever direction has pending requests, defaulting to "up" first.
        if (!upRequests.isEmpty()) return upRequests.first();
        return downRequests.first();
    }

    public boolean shouldStopAtCurrentFloor() {
        return upRequests.contains(currentFloor) || downRequests.contains(currentFloor);
    }

    public void removeSatisfiedRequestsAtCurrentFloor() {
        upRequests.remove(currentFloor);
        downRequests.remove(currentFloor);
    }

    public void incrementFloor() { currentFloor++; }
    public void decrementFloor() { currentFloor--; }
    public void setState(ElevatorState newState) { this.state = newState; }
    public int getCurrentFloor() { return currentFloor; }
    public void tick() { state.handle(this); } // called periodically by the controller
}
```

**Why `TreeSet` for pending requests:** a `TreeSet` keeps requests **sorted by floor automatically** and gives O(log n) insertion/lookup — this directly supports the **SCAN/elevator algorithm** (below): the elevator should service the *nearest* pending floor in its current direction of travel next, not requests in the arbitrary order they happened to arrive, and a sorted set gives you `.first()` (nearest pending floor) in O(log n) without a manual scan.

---

## 5. Component Deep Dive: The Scheduling Algorithm (SCAN / "Elevator Algorithm")

A naive **FIFO** (first-in-first-out) approach to request servicing — servicing requests in the literal order they arrived — is a classic wrong answer here: if requests arrive for floor 10, then floor 2, then floor 9, FIFO would send the elevator to 10, then all the way down to 2, then back up to 9 — wildly inefficient, needlessly reversing direction multiple times.

**The SCAN algorithm** (the standard, well-known answer, also literally called "the elevator algorithm" in operating-systems disk-scheduling contexts, where it originated for an analogous problem — scheduling disk head movement): the elevator continues moving in its **current direction**, servicing every pending request along the way in that direction, and only reverses direction once there are no more pending requests further in the current direction. This is exactly what `getNextTargetFloor()`'s "prefer continuing in whichever direction has pending requests" logic above implements, backed by the sorted `TreeSet`s.

- **Why this matters as an explicit, named algorithm to raise in an interview:** stating "I'm using SCAN, the same algorithm used for disk-head scheduling, because it minimizes total direction reversals and average wait time compared to naive FIFO" is a strong, precise, well-recognized answer — far stronger than an unnamed ad-hoc "just go in order" scheme that happens to work similarly by accident.
- **A real trade-off worth naming:** pure SCAN can make a request at the very end of a floor range wait a long time if the elevator is currently at the opposite end and has many other requests to service first — variants like **LOOK** (reverse direction as soon as there are no *further* requests in the current direction, rather than always traveling all the way to the physical end of the building) address this, and are worth mentioning as a refinement if pushed for more depth.

---

## 6. Multi-Elevator Selection Strategy

```java
public interface ElevatorSelectionStrategy {
    Elevator selectElevator(List<Elevator> elevators, ExternalRequest request);
}

// Simplest reasonable default: pick whichever elevator is CLOSEST to the
// requesting floor, among elevators either idle or already moving toward it.
public class NearestElevatorStrategy implements ElevatorSelectionStrategy {
    @Override
    public Elevator selectElevator(List<Elevator> elevators, ExternalRequest request) {
        return elevators.stream()
            .min(Comparator.comparingInt(e -> Math.abs(e.getCurrentFloor() - request.getFloor())))
            .orElseThrow();
    }
}
```

**A stronger, more realistic follow-up answer** (worth raising even if not asked): a genuinely good selection strategy should also weigh **whether the candidate elevator is already moving toward the request's floor and direction** (not just raw current distance) and each elevator's **current queue depth** (an elevator that's closer but already has 8 pending stops queued may serve this new request later than a slightly-farther, currently-idle elevator) — this is precisely the same "don't just pick by one naive metric, consider actual expected completion time" reasoning that recurs in [Load Balancer](../../02-building-blocks/load-balancers/README.md#2-load-balancing-algorithms) algorithm selection (Round Robin vs Least-Connections) — recognizing and stating that parallel explicitly is a strong, connective senior-level observation.

---

## 7. The Controller (Orchestration)

```java
public class ElevatorController {
    private final List<Elevator> elevators;
    private final ElevatorSelectionStrategy selectionStrategy;

    public ElevatorController(List<Elevator> elevators, ElevatorSelectionStrategy selectionStrategy) {
        this.elevators = elevators;
        this.selectionStrategy = selectionStrategy;
    }

    public void handleExternalRequest(ExternalRequest request) {
        Elevator chosen = selectionStrategy.selectElevator(elevators, request);
        chosen.addRequest(request.getFloor());
    }

    public void handleInternalRequest(int elevatorId, InternalRequest request) {
        elevators.get(elevatorId).addRequest(request.getDestinationFloor());
    }

    // Called on a fixed timer/tick (e.g., every simulated "unit of movement time")
    public void tick() {
        elevators.forEach(Elevator::tick);
    }
}
```

---

## 8. Extensibility Walkthrough

| Follow-up | How this design absorbs it |
|---|---|
| "Add a priority/express mode for certain floors (e.g., lobby)." | A new `ElevatorSelectionStrategy` or an added weighting factor in the existing one — no changes to `Elevator`'s own state machine. |
| "What if an elevator breaks down mid-transit?" | Add an `OutOfServiceState` implementing the same `ElevatorState` interface, and have the controller's selection strategy simply filter out elevators in that state — the State pattern's polymorphism absorbs this cleanly. |
| "How do you avoid starvation (a far-away request never getting serviced because closer requests keep arriving)?" | This is exactly the SCAN-vs-LOOK trade-off named above — a good answer proactively raises this and proposes either bounding max wait time with an aging/priority boost for long-waiting requests, or accepting the trade-off explicitly as a known limitation. |

---

## 9. 60-Second Interview Answer

> "I'd model elevator movement as an explicit State machine — idle, moving up, moving down, door open — because each state has fundamentally different legal transitions, and encapsulating that per-state as a class, rather than a shared conditional, structurally prevents illegal transitions as the system grows. For scheduling within one elevator, I'd use the SCAN algorithm — the same one used for disk-head scheduling — where the elevator continues in its current direction servicing all pending requests along the way, only reversing once there's nothing further in that direction, which avoids the wild inefficiency of naive FIFO request ordering. I'd back that with sorted TreeSets of pending floor requests per direction, giving O(log n) lookup of the next nearest floor to service. For a multi-elevator building, I'd pick which elevator services a new request based on more than just current distance — also considering whether a candidate is already heading that way and how many stops it already has queued, the same 'don't pick by one naive metric alone' reasoning that applies to load balancer algorithm selection."

**Related:** [Design Patterns: Behavioral (State)](../design-patterns/behavioral/README.md#2-state) · [Load Balancers](../../02-building-blocks/load-balancers/README.md) · [Parking Lot](../parking-lot/README.md)
