# Consensus Algorithms: Paxos

> Paxos, published by Leslie Lamport in 1998 (and infamously explained via a fictional ancient Greek legislature, which contributed to its reputation for being harder to understand than it needs to be), is the **original** widely-adopted consensus algorithm. Raft was explicitly designed later as a more approachable alternative to Paxos — but Paxos remains foundational, still underlies real production systems (Google's Chubby lock service, Google Spanner's internal replication), and interviewers sometimes specifically want to hear you distinguish it from Raft rather than treat them as interchangeable.

---

## 1. The Problem Paxos Solves (Single-Decree Paxos)

The original, simplest form of Paxos ("single-decree Paxos") solves consensus on **one single value**, agreed upon once, even with nodes crashing and messages being lost or reordered (though not corrupted — Paxos, like Raft, assumes a "crash-fault," not "Byzantine-fault," model; see the note on Byzantine fault tolerance below). Real systems need to agree on a **sequence** of values (a log/state machine) — this is "Multi-Paxos," essentially running many instances of single-decree Paxos in sequence, one per log slot, with some practical optimizations layered on top (discussed below).

---

## 2. The Two Phases of Single-Decree Paxos

Paxos has three logical roles — **Proposers** (propose values), **Acceptors** (vote on proposals), **Learners** (learn the final agreed value) — though in practice a single node often plays multiple roles simultaneously.

### Phase 1: Prepare / Promise
1. A proposer selects a **proposal number** `n` (must be unique and higher than any it has used before — typically constructed by combining a monotonic counter with the proposer's own ID to guarantee global uniqueness across proposers).
2. It sends `Prepare(n)` to a majority of acceptors.
3. Each acceptor, upon receiving `Prepare(n)`: if `n` is higher than any proposal number it has already promised to, it responds with a **Promise** — "I won't accept any proposal numbered less than `n` anymore" — and, critically, **includes the highest-numbered proposal it has already accepted, if any** (this is the detail that makes Paxos safe across proposer failures/retries, explained below).

### Phase 2: Accept / Accepted
4. If the proposer receives promises from a **majority**, it sends `Accept(n, value)` — where `value` is either its own originally-intended value, **or, if any acceptor's promise reported an already-accepted value, the proposer must adopt that highest-numbered previously-accepted value instead of its own.** This single rule is the crux of Paxos's entire safety guarantee.
5. Each acceptor, upon receiving `Accept(n, value)`: accepts it (and reports so to learners) **unless it has since promised a higher-numbered proposal** in the meantime.
6. Once a majority of acceptors have accepted the same `(n, value)`, that value is **chosen** — permanently, irrevocably — and learners find out via the accept responses (or a subsequent announcement round).

**Why "adopt the highest previously-accepted value" is the safety-critical rule:** imagine a value was already accepted by a majority under an earlier proposal number, but the proposer that achieved that crashed before announcing it to everyone. A **new** proposer, unaware this happened, starts a new round — Paxos's Phase 1 promise mechanism guarantees this new proposer will **learn about the previously-accepted value** (because at least one acceptor in its majority must have been part of the earlier accepting majority, per the same quorum-overlap math used in [Raft](../raft/README.md#5-the-quorum-requirement--why-n2--1-precisely)), and is **required** to propose that same value forward rather than its own — this is precisely what prevents two different values from ever being chosen.

---

## 3. Why Paxos Has a Reputation for Being Hard to Understand

- **The roles (Proposer/Acceptor/Learner) are decoupled from any notion of "leader"** — unlike Raft, which has an explicit, persistent leader role that simplifies reasoning about "who's in charge," Paxos in its pure form allows **any** proposer to start a round at any time, including multiple proposers competing simultaneously. This flexibility is theoretically elegant but operationally messy: competing proposers can **livelock** — repeatedly outbidding each other's proposal numbers without either one ever completing Phase 2 before the other starts a higher-numbered Phase 1 — a real, well-documented liveness problem (not a safety problem — Paxos never produces a *wrong* answer, just potentially no answer for a while).
- **Multi-Paxos (agreeing on a sequence of values, which is what any real system actually needs) is under-specified in Lamport's original paper** — the practical optimizations needed to make it efficient (a stable, semi-permanent leader to avoid repeating Phase 1 for every single log entry, batching, pipelining) are widely known and used but were never formalized as rigorously or clearly as Raft's equivalent mechanisms, leading to many subtly different real-world implementations.
- **Raft's explicit design goal was directly addressing this** — its authors (Ongaro and Ousterhout) have publicly written that Raft's decomposition into leader election / log replication / safety, with a persistent leader by design, was specifically meant to eliminate the ambiguity and livelock-proneness that plain Paxos permits, while providing equivalent safety guarantees.

---

## 4. Paxos vs Raft — the Direct Interview Comparison

| | Paxos | Raft |
|---|---|---|
| **Leader concept** | No persistent leader by design (any proposer can compete anytime) — Multi-Paxos optimizations add a de facto leader, but it's not part of the core algorithm's specification | Explicit, persistent leader elected via a well-defined term-based mechanism, core to the algorithm itself |
| **Understandability** | Widely reported as harder to reason about and implement correctly; many subtly incompatible real-world variants exist | Explicitly designed for understandability; a single canonical description most implementations follow closely |
| **Liveness under competing proposers** | Can livelock (competing proposers repeatedly outbidding each other) without an additional leader-election layer bolted on | Randomized election timeouts specifically engineered to avoid this in the common case |
| **Real-world production use** | Google Chubby, Google Spanner (an evolved variant), many older distributed databases | etcd, Consul, CockroachDB, most newer distributed systems built since ~2014 |

**Senior-level answer if asked "why would you pick one over the other for a new system today":** for a **new** system, Raft's explicit design goal of understandability and its widely-available, well-tested implementations make it the pragmatic default choice — you'd reach for Paxos primarily if integrating with or extending an **existing** system already built on it (Chubby-like systems), or if you needed some specific theoretical property of a Paxos variant not directly offered by standard Raft. This is a fair, evenhanded answer that doesn't dismiss Paxos as obsolete, since it remains in massive production use.

---

## 5. Real-World Example: Google Chubby and Google Spanner

**Chubby**, Google's internal distributed lock service (publicly documented in Google's 2006 OSDI paper), uses Paxos internally to keep its replicas consistent — Chubby itself is then used throughout Google's infrastructure as a building block for **leader election** (see [Leader Election](../../leader-election/README.md)) and distributed locking for other systems, a layering that's worth explicitly noting: Paxos solves consensus at Chubby's replication layer, and Chubby then offers a simpler lock/leader-election API built on top, so most engineers at Google building on Chubby never interact with Paxos directly.

**Google Spanner**, Google's globally-distributed relational database, uses **Paxos groups** to replicate each shard of data across multiple datacenters/regions — combined with Spanner's famous **TrueTime** API (a globally synchronized clock with bounded uncertainty, using GPS and atomic clocks) to achieve external consistency (a stronger guarantee than plain linearizability) for globally-distributed transactions. This is a genuinely advanced, staff+-level detail worth having in your back pocket: Spanner's ability to offer strong consistency at global scale rests on **combining** a consensus algorithm (Paxos, for replication safety) with a specialized clock synchronization mechanism (TrueTime, for globally-ordered transaction timestamps) — neither alone would be sufficient.

---

## 6. Java Example: Sketching the Core Prepare/Accept Logic

As with Raft, this would never be hand-rolled for production — but sketching the acceptor's core decision logic demonstrates the safety mechanism concretely.

```java
public class PaxosAcceptor {

    private long highestPromisedProposalNumber = -1;
    private Long acceptedProposalNumber = null;
    private Object acceptedValue = null;

    // Phase 1: respond to a Prepare request.
    public synchronized PrepareResponse handlePrepare(long proposalNumber) {
        if (proposalNumber <= highestPromisedProposalNumber) {
            return PrepareResponse.reject(highestPromisedProposalNumber); // stale proposer, tell it the current high-water mark
        }

        this.highestPromisedProposalNumber = proposalNumber;

        // CRITICAL: report back any value we've already accepted, so the
        // proposer can adopt it instead of its own -- this is the safety
        // mechanism described in Section 2 above.
        return PrepareResponse.promise(acceptedProposalNumber, acceptedValue);
    }

    // Phase 2: respond to an Accept request.
    public synchronized AcceptResponse handleAccept(long proposalNumber, Object value) {
        if (proposalNumber < highestPromisedProposalNumber) {
            return AcceptResponse.reject(highestPromisedProposalNumber); // we've since promised a higher number
        }

        this.highestPromisedProposalNumber = proposalNumber;
        this.acceptedProposalNumber = proposalNumber;
        this.acceptedValue = value;
        return AcceptResponse.accepted(proposalNumber, value);
    }
}
```

```java
public class PaxosProposer {

    public Object propose(Object intendedValue, List<PaxosAcceptor> acceptors) {
        long proposalNumber = generateUniqueProposalNumber();

        // Phase 1
        List<PrepareResponse> promises = broadcastPrepare(proposalNumber, acceptors);
        if (!hasMajority(promises, acceptors.size())) {
            return retryWithHigherProposalNumber(intendedValue, acceptors); // simplified -- real systems back off
        }

        // CRUCIAL rule: if ANY acceptor already accepted a value under a
        // prior proposal, we MUST propose that value instead of our own --
        // find the highest-numbered previously-accepted value among the promises.
        Object valueToPropose = promises.stream()
            .filter(PrepareResponse::hasAcceptedValue)
            .max(Comparator.comparingLong(PrepareResponse::getAcceptedProposalNumber))
            .map(PrepareResponse::getAcceptedValue)
            .orElse(intendedValue); // no one had accepted anything yet -- safe to use our own value

        // Phase 2
        List<AcceptResponse> accepts = broadcastAccept(proposalNumber, valueToPropose, acceptors);
        if (hasMajority(accepts, acceptors.size())) {
            return valueToPropose; // CHOSEN -- permanently and irrevocably
        }
        return retryWithHigherProposalNumber(intendedValue, acceptors);
    }
}
```

**Why the `valueToPropose` selection logic is the single most important line in this whole sketch:** a candidate who writes `propose(proposalNumber, intendedValue)` in Phase 2 without this override — i.e., always proposing their own original value regardless of what Phase 1 revealed — has implemented an algorithm that **can violate safety** (two different values chosen by two different majorities), which is precisely the bug this rule exists to prevent. This is exactly the kind of implementation-level detail that separates "knows the word Paxos" from "understands why Paxos is correct."

---

## 7. A Note on Byzantine Fault Tolerance (a Common, Worthwhile Tangent)

Both Raft and Paxos assume a **crash-fault** model: a faulty node simply stops responding (or responds truthfully but late) — it never lies or sends corrupted/malicious messages. Tolerating nodes that can behave **arbitrarily or maliciously** requires a fundamentally different (and more expensive) class of algorithm — **Byzantine Fault Tolerant (BFT)** consensus, such as PBFT (Practical Byzantine Fault Tolerance) — which requires a larger quorum (`2f + 1` out of `3f + 1` nodes to tolerate `f` Byzantine failures, versus Raft/Paxos's simpler `f + 1` out of `2f + 1` for crash failures) and more message rounds. **This is exactly why blockchain/cryptocurrency consensus mechanisms are a fundamentally different (and more expensive) problem than typical distributed-database consensus** — they must tolerate genuinely adversarial, not just crash-faulty, participants, since anyone can join a public blockchain network. Mentioning this distinction, if a conversation drifts toward blockchain, is a strong way to show you understand *why* that space needs different algorithms entirely, not just "a bigger Raft."

---

## 8. Common Pitfalls

- Treating Paxos and Raft as interchangeable synonyms for "consensus" — they have real, describable differences (explicit leader vs no persistent leader, understandability, liveness characteristics under contention).
- Forgetting the "adopt the previously-accepted value" rule in Phase 2 — this is the actual safety mechanism, not an optional nuance.
- Conflating crash-fault consensus (Raft/Paxos) with Byzantine fault tolerance (PBFT, blockchain consensus) — these solve different problems with different guarantees and different costs.
- Assuming Multi-Paxos is fully specified by Lamport's original paper — in practice it requires additional, widely-known-but-informally-specified optimizations (a stable leader, batching) that Raft formalizes far more explicitly.

---

## 9. 60-Second Interview Answer

> "Paxos solves consensus with Proposers, Acceptors, and Learners across two phases — Prepare/Promise, then Accept/Accepted — and its safety hinges on one rule: if Phase 1 reveals that some acceptor already accepted a value under an earlier proposal, the current proposer must adopt that value instead of its own, which is what guarantees only one value can ever be chosen even with competing, retrying proposers. Unlike Raft, plain Paxos has no persistent leader by design — any proposer can start a round anytime — which is more general but can livelock under contending proposers and is part of why Paxos has a reputation for being harder to implement correctly; Raft was explicitly designed later to fix that by baking a term-based leader election directly into the algorithm. Both are crash-fault-tolerant, not Byzantine-fault-tolerant — they assume nodes fail by stopping, not by lying, which is why blockchain-style consensus needs an entirely different, more expensive class of algorithm to tolerate genuinely adversarial participants."

**Related:** [Raft](../raft/README.md) · [Leader Election](../../leader-election/README.md) · [CAP Theorem](../../../01-foundations/cap-theorem/README.md) · [Distributed Transactions](../../distributed-transactions/README.md)
