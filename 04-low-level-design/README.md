# 04 — Low-Level Design (LLD)

> High-level design (section 03) asks "what boxes, and how do they talk over a network?" Low-level design asks a completely different question: **"inside one of those boxes, how is the actual code structured — classes, interfaces, relationships — so it's correct, extensible, and doesn't rot the first time a new requirement shows up?"** This is the "machine coding" round at senior levels — you're expected to produce compileable-quality object-oriented design, not pseudocode.

## What a 15-16 year senior architect is actually judged on here

1. **Correct application of SOLID principles** — not reciting the acronym, but pointing at a specific class in your own design and explaining which principle it satisfies or violates and why that's a deliberate trade-off.
2. **Recognizing which GoF design pattern fits a requirement** — and just as importantly, **not** forcing a pattern where a simpler solution would do (over-engineering is graded down just as hard as under-engineering).
3. **Extensibility under a stated future requirement** — a good interviewer will ask "now add X" mid-interview; your class design should absorb that without a rewrite, because you anticipated the right seams (usually: an interface where variation is expected).
4. **Working code, not diagrams-only** — you should be able to write real, compiling Java for the core classes, not just box-and-line UML.

## How to use this section

Read [Design Patterns](design-patterns/creational/README.md) first ([structural](design-patterns/structural/README.md), [behavioral](design-patterns/behavioral/README.md)) — every LLD system below deliberately uses several of these, and the patterns docs explain *why* each was chosen over alternatives, not just *how* to implement them.

## Systems in this section

| System | Core OOP challenge it tests |
|---|---|
| [Parking Lot](parking-lot/README.md) | Modeling a physical resource hierarchy + pluggable pricing/spot-assignment strategy |
| [LRU Cache](lru-cache/README.md) | Correct O(1) data structure design (hash map + doubly linked list) |
| [Elevator System](elevator-system/README.md) | State machine design + a scheduling algorithm under concurrent requests |
| [Chess Game](chess-game/README.md) | Polymorphism for piece movement rules + move validation/history (Command pattern) |
| [Library Management](library-management/README.md) | Classic entity relationships + reservation/holds workflow |
| [Food Delivery App](food-delivery-app/README.md) | Multi-actor state machine (order/restaurant/rider) spanning several of the other patterns |

Previous: [03 — High-Level Design](../03-high-level-design/README.md) · Next: [05 — Distributed Systems](../05-distributed-systems/README.md)
