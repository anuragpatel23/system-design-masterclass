# Low-Level Design: Parking Lot

> **The core OOP challenge:** modeling a physical hierarchy (lot → floors → spots) cleanly, while keeping **spot assignment** and **pricing** pluggable via the Strategy pattern — because a real interviewer will almost certainly ask "now make pricing different for weekends" or "now prioritize handicapped spots differently" mid-interview, and your class design needs to absorb that without a rewrite.

---

## 1. Requirements Clarification (ask these before designing)

- Multiple vehicle types (motorcycle, car, bus/truck) requiring different spot sizes.
- Multiple floors, each with a fixed number of spots per size category.
- A ticket is issued on entry; payment is calculated and settled on exit.
- Support multiple entry/exit gates.
- (Extension to anticipate) Different pricing schemes (hourly, flat-rate for events).

---

## 2. Class Design

```
ParkingLot
 ├── has many ── Floor
 │                 └── has many ── ParkingSpot (abstract, subtyped by size)
 │                                    ├── MotorcycleSpot
 │                                    ├── CompactSpot
 │                                    └── LargeSpot
 ├── uses ── SpotAssignmentStrategy (interface — Strategy pattern)
 ├── uses ── PricingStrategy (interface — Strategy pattern)
 └── issues ── ParkingTicket

Vehicle (abstract)
 ├── Motorcycle
 ├── Car
 └── Bus
```

**Why `ParkingSpot` is abstract with size-specific subtypes, rather than one class with a `size` enum field:** this is a deliberate, defensible design choice worth stating out loud — if spot-type-specific *behavior* ever diverges (e.g., a `LargeSpot` needing different occupancy-check logic for a bus that might span multiple physical spot markings), subtyping gives you a clean seam for that; a single class with an enum field is perfectly fine too if no such behavioral divergence is expected, and it's fine to say so explicitly and justify picking the simpler option if you'd rather not over-engineer — **the interview signal is being able to argue either choice, not memorizing one as "correct."**

---

## 3. Core Interfaces (the extensibility seams)

```java
public enum VehicleSize { MOTORCYCLE, COMPACT, LARGE }

public abstract class Vehicle {
    private final String licensePlate;
    private final VehicleSize size;

    protected Vehicle(String licensePlate, VehicleSize size) {
        this.licensePlate = licensePlate;
        this.size = size;
    }
    public VehicleSize getSize() { return size; }
    public String getLicensePlate() { return licensePlate; }
}

public class Motorcycle extends Vehicle {
    public Motorcycle(String plate) { super(plate, VehicleSize.MOTORCYCLE); }
}
public class Car extends Vehicle {
    public Car(String plate) { super(plate, VehicleSize.COMPACT); }
}
public class Bus extends Vehicle {
    public Bus(String plate) { super(plate, VehicleSize.LARGE); }
}
```

```java
public abstract class ParkingSpot {
    private final String spotId;
    private final VehicleSize supportedSize;
    private Vehicle currentVehicle; // null if unoccupied

    protected ParkingSpot(String spotId, VehicleSize supportedSize) {
        this.spotId = spotId;
        this.supportedSize = supportedSize;
    }

    public boolean isAvailable() { return currentVehicle == null; }

    public boolean canFit(Vehicle vehicle) {
        return vehicle.getSize() == supportedSize; // could relax to "<=" if larger
                                                     // spots can host smaller vehicles --
                                                     // a real, discussable business rule
    }

    public void assignVehicle(Vehicle vehicle) {
        if (!isAvailable()) throw new IllegalStateException("Spot already occupied: " + spotId);
        this.currentVehicle = vehicle;
    }

    public void vacate() { this.currentVehicle = null; }
    public String getSpotId() { return spotId; }
}

public class MotorcycleSpot extends ParkingSpot {
    public MotorcycleSpot(String id) { super(id, VehicleSize.MOTORCYCLE); }
}
public class CompactSpot extends ParkingSpot {
    public CompactSpot(String id) { super(id, VehicleSize.COMPACT); }
}
public class LargeSpot extends ParkingSpot {
    public LargeSpot(String id) { super(id, VehicleSize.LARGE); }
}
```

### Strategy Interface #1: Spot Assignment

```java
public interface SpotAssignmentStrategy {
    Optional<ParkingSpot> findSpot(Vehicle vehicle, List<Floor> floors);
}

// Default: nearest-to-entrance-first, per floor in order
public class NearestSpotStrategy implements SpotAssignmentStrategy {
    @Override
    public Optional<ParkingSpot> findSpot(Vehicle vehicle, List<Floor> floors) {
        return floors.stream()
            .flatMap(floor -> floor.getSpots().stream())
            .filter(ParkingSpot::isAvailable)
            .filter(spot -> spot.canFit(vehicle))
            .findFirst(); // "first available" == "nearest" if spots are ordered by proximity
    }
}

// Anticipated extension: prioritize spots on lower floors for accessibility --
// swapping this in requires ZERO changes to ParkingLot itself (Open/Closed Principle).
public class AccessibilityPriorityStrategy implements SpotAssignmentStrategy {
    @Override
    public Optional<ParkingSpot> findSpot(Vehicle vehicle, List<Floor> floors) {
        return floors.stream()
            .sorted(Comparator.comparingInt(Floor::getFloorNumber)) // lowest floors first
            .flatMap(floor -> floor.getSpots().stream())
            .filter(ParkingSpot::isAvailable)
            .filter(spot -> spot.canFit(vehicle))
            .findFirst();
    }
}
```

### Strategy Interface #2: Pricing

```java
public interface PricingStrategy {
    BigDecimal calculateFee(ParkingTicket ticket);
}

public class HourlyPricingStrategy implements PricingStrategy {
    private final Map<VehicleSize, BigDecimal> ratePerHour = Map.of(
        VehicleSize.MOTORCYCLE, new BigDecimal("1.50"),
        VehicleSize.COMPACT, new BigDecimal("2.50"),
        VehicleSize.LARGE, new BigDecimal("5.00")
    );

    @Override
    public BigDecimal calculateFee(ParkingTicket ticket) {
        long hours = Math.max(1, Duration.between(ticket.getEntryTime(), Instant.now()).toHours());
        BigDecimal rate = ratePerHour.get(ticket.getVehicle().getSize());
        return rate.multiply(BigDecimal.valueOf(hours));
    }
}

// Anticipated extension: flat event-day rate, again a drop-in replacement.
public class FlatRatePricingStrategy implements PricingStrategy {
    private final BigDecimal flatRate;
    public FlatRatePricingStrategy(BigDecimal flatRate) { this.flatRate = flatRate; }

    @Override
    public BigDecimal calculateFee(ParkingTicket ticket) {
        return flatRate;
    }
}
```

---

## 4. The Orchestrating Class

```java
public class ParkingLot {
    private final List<Floor> floors;
    private final SpotAssignmentStrategy assignmentStrategy;
    private final PricingStrategy pricingStrategy;
    private final Map<String, ParkingTicket> activeTickets = new ConcurrentHashMap<>();

    public ParkingLot(List<Floor> floors, SpotAssignmentStrategy assignmentStrategy,
                       PricingStrategy pricingStrategy) {
        this.floors = floors;
        this.assignmentStrategy = assignmentStrategy;
        this.pricingStrategy = pricingStrategy;
    }

    public ParkingTicket parkVehicle(Vehicle vehicle) {
        ParkingSpot spot = assignmentStrategy.findSpot(vehicle, floors)
            .orElseThrow(() -> new NoAvailableSpotException(vehicle.getSize()));

        spot.assignVehicle(vehicle);
        ParkingTicket ticket = new ParkingTicket(vehicle, spot, Instant.now());
        activeTickets.put(ticket.getTicketId(), ticket);
        return ticket;
    }

    public BigDecimal processExit(String ticketId) {
        ParkingTicket ticket = Optional.ofNullable(activeTickets.remove(ticketId))
            .orElseThrow(() -> new InvalidTicketException(ticketId));

        BigDecimal fee = pricingStrategy.calculateFee(ticket);
        ticket.getSpot().vacate(); // free the spot for the NEXT vehicle
        return fee;
    }
}
```

**Why `activeTickets` is a `ConcurrentHashMap`:** multiple entry/exit gates operate concurrently in a real parking lot — this is a genuine, real concurrency concern worth naming unprompted, and a plain `HashMap` here would be a real, exploitable race condition bug under concurrent gate operations.

---

## 5. Extensibility Walkthrough (what an interviewer will actually probe)

| Mid-interview follow-up | How this design absorbs it |
|---|---|
| "Now add support for reserved/VIP spots." | Add a `ReservedSpot` subtype of `ParkingSpot` with an `assignedOwnerId` field, and a new `SpotAssignmentStrategy` implementation that checks reservation ownership before falling back to the general pool — no changes to `ParkingLot` itself. |
| "Now make pricing different on weekends." | A new `PricingStrategy` implementation checking `DayOfWeek`, swapped in via constructor injection — zero changes elsewhere. |
| "How would you handle the lot being full?" | Already handled: `parkVehicle` throws `NoAvailableSpotException`; calling code (a controller, in a real deployed system) decides how to surface that (reject entry, redirect to another lot). |
| "How would this work with multiple physical entry gates operating at once?" | The `ConcurrentHashMap` and the fact that `assignVehicle`/`vacate` mutate spot state directly (not via a separate synchronized service) means each gate's `parkVehicle` call independently finds and claims an available spot — though a genuinely rigorous answer should flag that `findSpot` + `assignVehicle` as two separate steps has a **race condition** if two gates could pick the same spot simultaneously, which would need a lock or an atomic compare-and-set on the spot's state to fully close (a great callback to the same class of problem as [Hotel Booking's](../../03-high-level-design/hotel-booking/README.md#4-component-deep-dive-preventing-overbooking-the-actual-hard-problem) overbooking prevention, just at LLD scale instead of a distributed database). |

---

## 6. 60-Second Interview Answer

> "I'd model the physical hierarchy directly — a ParkingLot containing Floors containing ParkingSpots, with spot subtypes per vehicle size. The two places I'd expect requirements to change are how a spot gets assigned and how the fee gets calculated, so I'd make both pluggable via the Strategy pattern — a SpotAssignmentStrategy and a PricingStrategy interface, injected into the ParkingLot rather than hardcoded, so adding a new pricing scheme or a new assignment priority rule never touches the core class. I'd also flag a real concurrency concern: with multiple entry gates, finding an available spot and claiming it are two separate steps, which is a genuine race condition if not made atomic — the same class of bug as overbooking in a distributed booking system, just at object level here."

**Related:** [Design Patterns: Behavioral (Strategy)](../design-patterns/behavioral/README.md#1-strategy) · [Hotel Booking](../../03-high-level-design/hotel-booking/README.md) · [Elevator System](../elevator-system/README.md)
