# 05 — Distributed Systems

> Sections 01-04 used terms like "consensus," "leader election," and "eventual consistency" as building blocks you reach for. This section opens those black boxes. This is the material that separates a senior engineer who can *use* a distributed database from a staff/principal architect who can reason about *why* it behaves the way it does under failure — and who could, if truly pushed, sketch the algorithm underneath it on a whiteboard.

## Why this section is weighted heavily in staff+ interviews

Below the "principal" bar, most interviewers are satisfied if you know *that* Raft/Paxos exist and *when* to reach for a consensus-backed system (ZooKeeper, etcd). At the senior-to-staff bar and above, interviewers will frequently ask you to **explain the actual mechanism** — how a leader is elected, why a quorum of `N/2 + 1` is the magic number, what happens during a network partition, how a saga rolls back a distributed transaction — because these mechanisms are exactly what's silently running underneath almost every "building block" in section 02.

## Topics in this section

| Topic | The concrete question it answers |
|---|---|
| [Consensus Algorithms: Raft](consensus-algorithms/raft/README.md) | How do N machines agree on one value/log, and keep agreeing, even as some crash? |
| [Consensus Algorithms: Paxos](consensus-algorithms/paxos/README.md) | What's the original, more general (and more confusing) algorithm Raft was designed to replace pedagogically? |
| [Distributed Transactions](distributed-transactions/README.md) | How do you get ACID-like guarantees across multiple independent databases/services? |
| [Event-Driven Architecture](event-driven-architecture/README.md) | How do you structure a whole system around events instead of direct calls, and what does that buy/cost you? |
| [Saga Pattern](saga-pattern/README.md) | If 2PC is impractical at scale, how do you actually get correctness across services in practice? |
| [CQRS & Event Sourcing](cqrs-event-sourcing/README.md) | How do you separate read and write models, and what does it mean to store state as a log of events instead of a snapshot? |
| [Leader Election](leader-election/README.md) | How does a cluster agree on who's "in charge" right now, and detect when that changes? |

## How these connect back to earlier sections

- [CAP Theorem](../01-foundations/cap-theorem/README.md) and [Consistency Models](../01-foundations/consistency-models/README.md) are the *theory*; this section is the *mechanism* that implements a CP choice in practice.
- [Database Replication](../02-building-blocks/databases/replication/README.md)'s failover discussion is leader election in miniature — this section gives you the actual algorithm.
- [Payment System](../03-high-level-design/payment-system/README.md) and [Hotel Booking](../03-high-level-design/hotel-booking/README.md) both informally reached for "distributed transaction"-shaped problems; this section gives you the Saga pattern to solve them properly, and names why 2PC usually isn't the practical answer.

Previous: [04 — Low-Level Design](../04-low-level-design/README.md) · Next: [06 — Databases Deep Dive](../06-databases-deep-dive/README.md)
