# Consensus Algorithms: Raft

> **Precise definition:** consensus is the problem of getting a cluster of machines to agree on a single value (or an ordered sequence of values — a "log") **even when some machines crash, restart, or the network partitions** — such that every non-faulty node eventually agrees on the same answer, and that answer was actually proposed by someone (not invented out of thin air). Raft is the algorithm engineered specifically to make this understandable and implementable correctly — it was published in 2014 explicitly as a more approachable alternative to Paxos, without weakening the guarantees.

---

## 1. Why Consensus Is Hard (Motivate It Before Describing It)

If every node in a cluster could simply "vote" and go with the majority, consensus would be trivial. The actual difficulty comes from **partial failure and asynchrony**: a message can be delayed indefinitely, a node can crash right after sending half its messages, and — critically — **a node that hasn't heard from another node cannot distinguish "that node crashed" from "that node is just slow" from "the network between us is partitioned."** Any correct consensus algorithm has to produce the right answer despite this fundamental ambiguity, which is why naive "just take a majority vote" approaches break under real failure patterns (e.g., two different nodes each believing they're the leader simultaneously — split-brain).

Raft decomposes this hard problem into three independently-understandable sub-problems: **leader election**, **log replication**, and **safety** (ensuring the first two never violate consistency, even across leader changes).

---

## 2. Server States

Every node in a Raft cluster is in exactly one of three states at any time:

- **Follower** — passive; responds to requests from leaders and candidates, never initiates anything itself. This is the default/starting state.
- **Candidate** — a follower that has stopped hearing from a leader and is actively campaigning to become the new leader.
- **Leader** — handles all client requests and log replication; there is **at most one leader per "term"** (defined below), enforced by the election mechanism itself.

```
        times out, starts election
Follower ────────────────────────▶ Candidate
   ▲                                   │  │
   │  discovers current leader          │  │ wins election (majority votes)
   │  or new term                       │  ▼
   └───────────────────────────────  Leader
                                         │
                    discovers a node with a higher term
                    (steps down back to Follower)
```

---

## 3. Terms — Raft's Logical Clock

Time is divided into **terms**, numbered with consecutive integers. Each term begins with an election; if a leader is successfully elected, it remains leader for the rest of that term. Terms act as a **logical clock** allowing servers to detect stale/outdated information: **every message in Raft carries the sender's current term number**, and a server that discovers another server's term is higher than its own **immediately reverts to follower state** (its own term was stale — it may have missed an election, or been partitioned away during one).

**Why this matters for split-brain prevention:** if a former leader was partitioned away and a new leader was elected in a higher term during the partition, the old leader (still believing it's leader, in a now-stale term) will, upon any contact with the rest of the cluster, see the higher term number and immediately step down — this is the core mechanism preventing two leaders from both believing they're in charge simultaneously once connectivity is restored.

---

## 4. Leader Election — the Mechanism, Precisely

1. A follower's **election timeout** expires (a randomized duration, typically 150-300ms, re-randomized each time) without hearing a heartbeat from a leader.
2. It transitions to candidate, **increments its term**, votes for itself, and sends `RequestVote` RPCs to every other node in parallel.
3. Each recipient votes for **at most one candidate per term** (first-come-first-served) — and only if the candidate's log is **at least as up-to-date** as its own (this is the safety-critical detail explained below).
4. If the candidate receives votes from a **majority** (`⌊N/2⌋ + 1`) of the cluster, it becomes leader for that term and immediately starts sending heartbeats to establish authority and suppress other election timeouts.
5. If no candidate achieves a majority (e.g., a split vote among multiple simultaneous candidates), the term ends without a leader, and a new election starts after each candidate's (independently randomized) timeout expires again.

**Why the election timeout is randomized (a frequently-asked "why" question):** if every follower had the exact same fixed timeout, multiple followers would time out and become candidates **simultaneously**, splitting the vote repeatedly in a way that could recur indefinitely. Randomizing the timeout means, with high probability, **one** follower times out first, starts its election, and wins before others even become candidates — a simple, elegant mechanism that avoids needing any more complex synchronization to break ties.

---

## 5. The Quorum Requirement — Why `N/2 + 1`, Precisely

A **quorum** (majority) is required for both winning an election and committing a log entry, and this single number is the mathematical foundation of Raft's entire safety guarantee:

> **Any two quorums, drawn from the same set of N nodes, must overlap in at least one node.**

With `N` total nodes, a quorum of `⌊N/2⌋ + 1` guarantees this: if `2 × (⌊N/2⌋ + 1) > N` (which is always true), any two such quorums cannot be disjoint. This is precisely why a **new leader is guaranteed to have seen every log entry that was committed by any prior leader** — the new leader's election quorum necessarily overlaps with the quorum that committed any previously-committed entry, so at least one voter in the new election already had that entry and (per the log-up-to-date check in step 3 above) the new leader's log must be at least that current.

**The direct, practical consequence for cluster sizing (a very commonly asked follow-up):** a 5-node cluster tolerates **2** node failures and still has a quorum (3 of 5); a 3-node cluster tolerates only **1** failure (2 of 3). Adding a 4th node to a 3-node cluster **doesn't improve fault tolerance at all** — it still only tolerates 1 failure (needing 3 of 4 for quorum), while adding cost and slightly more replication latency. **This is why production Raft/Paxos-based clusters (etcd, ZooKeeper, Consul) are almost always sized at odd numbers (3, 5, 7) — an even-sized cluster spends resources without buying additional fault tolerance**, and stating this specific insight unprompted is a strong senior-level signal.

---

## 6. Log Replication

Once elected, the leader is the **only** node that accepts client write requests. For each write:

1. The leader appends the entry to its own log (uncommitted).
2. The leader sends `AppendEntries` RPCs to all followers in parallel, containing the new entry.
3. Once a **majority** of nodes (including the leader) have durably stored the entry, the leader considers it **committed** and applies it to its own state machine, returning success to the client.
4. The leader includes the latest commit index in subsequent `AppendEntries` (or heartbeat) messages, so followers learn which entries are safe to apply to their own state machines too.

**This is exactly the [semi-synchronous replication](../../../02-building-blocks/databases/replication/README.md#2-synchronous-vs-asynchronous-replication) pattern from the Building Blocks section, generalized:** the leader doesn't wait for *all* replicas (that would sacrifice availability for a single slow/dead follower), and doesn't act as if a single local write is sufficient (that would sacrifice durability) — it waits for a quorum, exactly matching the "wait for at least one/some replicas" middle ground described there, but formalized with the majority-quorum safety proof behind it.

**Handling log inconsistencies after a leader change:** a new leader's log might not perfectly match every follower's log (some followers might be missing entries, or have extra uncommitted entries from a former leader that never got a majority). Raft resolves this by having the new leader **force followers' logs to match its own** — followers are made to delete conflicting entries and receive the leader's version, guaranteed safe specifically because the new leader's log is provably at least as up-to-date as any log that achieved a majority (per the quorum-overlap proof above).

---

## 7. Real-World Example: etcd's Use of Raft as Kubernetes' Source of Truth

**etcd**, a distributed key-value store built directly on the Raft algorithm (its designers have publicly stated this was a primary motivation for choosing Raft over Paxos — implementability and operational understandability), is the datastore underlying **Kubernetes' entire cluster state** — every Deployment, Pod spec, Service, ConfigMap, and Secret in a Kubernetes cluster is a key in etcd, replicated via Raft across (typically) 3 or 5 etcd nodes.

- This is a direct, massive-scale, production instance of the CP choice from [CAP Theorem](../../../01-foundations/cap-theorem/README.md): if etcd's Raft cluster cannot achieve quorum (e.g., during a severe network partition or the loss of more than a minority of nodes), **the Kubernetes control plane's ability to accept new state changes stops entirely** — no new deployments, no scheduling decisions — rather than risk two different etcd leaders disagreeing about cluster state, which could cause catastrophic scheduling conflicts (e.g., double-scheduling the same resource).
- The choice of typically 3 or 5 etcd nodes (never an even number) is a direct, practical application of the quorum-sizing insight from Section 5 above, and etcd's own operational documentation explicitly recommends against even-sized clusters for exactly this reason.

**The lesson:** Raft isn't an academic curiosity — it is, quite literally, the mechanism that decides whether one of the most widely-deployed pieces of infrastructure in the industry can safely make any decision at all at a given moment.

---

## 8. Spring Boot / Java Example: Modeling the Core RequestVote Logic

While you would never hand-roll Raft for production (use etcd, ZooKeeper, or a battle-tested Raft library), being able to sketch the **core decision logic** of `RequestVote` handling demonstrates genuine understanding rather than pattern-recitation — this is a legitimate thing to be asked to whiteboard/live-code in a staff-level interview.

```java
public class RaftNode {

    private volatile NodeRole role = NodeRole.FOLLOWER;
    private volatile long currentTerm = 0;
    private volatile String votedFor = null;      // candidateId voted for in currentTerm, or null
    private final List<LogEntry> log = new ArrayList<>();

    // The core safety-critical decision: should I grant my vote to this candidate?
    public synchronized RequestVoteResponse handleRequestVote(RequestVoteRequest request) {

        // Rule 1: reject any request from a stale (older) term outright.
        if (request.getTerm() < currentTerm) {
            return new RequestVoteResponse(currentTerm, false);
        }

        // Rule 2: if the candidate's term is NEWER than ours, we're stale --
        // step down to follower and update our own term before proceeding
        // (this is the split-brain-prevention mechanism from Section 3).
        if (request.getTerm() > currentTerm) {
            this.currentTerm = request.getTerm();
            this.role = NodeRole.FOLLOWER;
            this.votedFor = null; // fresh term -- haven't voted yet
        }

        boolean alreadyVotedForSomeoneElse =
            votedFor != null && !votedFor.equals(request.getCandidateId());

        boolean candidateLogIsAtLeastAsUpToDate =
            isLogAtLeastAsUpToDate(request.getLastLogTerm(), request.getLastLogIndex());

        // Rule 3: grant the vote ONLY if we haven't already voted this term
        // for someone else, AND the candidate's log is sufficiently caught up --
        // this second condition is exactly what guarantees a new leader can
        // never "forget" a previously committed entry.
        if (!alreadyVotedForSomeoneElse && candidateLogIsAtLeastAsUpToDate) {
            this.votedFor = request.getCandidateId();
            resetElectionTimeout(); // granting a vote also counts as "hearing from a peer"
            return new RequestVoteResponse(currentTerm, true);
        }

        return new RequestVoteResponse(currentTerm, false);
    }

    private boolean isLogAtLeastAsUpToDate(long candidateLastLogTerm, long candidateLastLogIndex) {
        LogEntry myLastEntry = log.isEmpty() ? null : log.get(log.size() - 1);
        long myLastTerm = myLastEntry == null ? 0 : myLastEntry.getTerm();
        long myLastIndex = log.size();

        // Compare by TERM first, then by INDEX -- a log with a higher term in
        // its last entry is considered more up-to-date regardless of length,
        // since a higher term means it was written under a MORE RECENT leader.
        if (candidateLastLogTerm != myLastTerm) {
            return candidateLastLogTerm > myLastTerm;
        }
        return candidateLastLogIndex >= myLastIndex;
    }
}
```

**Why this matters at senior level:** the two-part "term first, then index" comparison in `isLogAtLeastAsUpToDate` is exactly the kind of easy-to-get-subtly-wrong detail a staff interviewer probes for — a candidate who says "just compare log length" without the term-first comparison has missed the actual safety mechanism, since a longer log from an old, abandoned leader's term must **not** be allowed to win over a shorter log written under a more recent, legitimate leader.

---

## 9. Common Pitfalls

- Treating "consensus" and "leader election" as the same thing — leader election is one **sub-problem** consensus algorithms solve; log replication and the safety proof around it are equally essential and often the part interviewers actually want explained.
- Assuming a longer log is always "more up-to-date" — the term-first comparison above is the actual rule, and getting this backwards breaks the safety guarantee.
- Sizing a cluster with an even number of nodes, not realizing it buys zero additional fault tolerance over one fewer node while adding replication cost.
- Confusing Raft's guarantee ("every non-faulty node eventually agrees") with linearizability of reads without qualification — a naive read directly from a follower can return stale data (since replication is asynchronous relative to the follower catching up); Raft-based systems typically route strongly-consistent reads through the leader, or use a "read index" mechanism, precisely to avoid this.

---

## 10. 60-Second Interview Answer

> "Raft solves consensus by decomposing it into leader election, log replication, and a safety proof tying them together. Time is divided into terms, and every message carries a term number so a stale former leader immediately steps down the moment it contacts a node with a higher term — that's the core split-brain prevention mechanism. Leader election uses randomized timeouts specifically to avoid repeated split votes, and a candidate needs a majority quorum to win. That same majority-quorum requirement is what guarantees safety during log replication: any two quorums out of N nodes must overlap by at least one node, which guarantees a newly-elected leader has already seen every entry any previous leader ever got committed, since its election quorum necessarily overlaps with whatever quorum committed that entry. That overlap math is also exactly why production clusters are sized at odd numbers like 3 or 5 — an even-sized cluster doesn't buy any additional fault tolerance over one fewer node."

**Related:** [Paxos](../paxos/README.md) · [Leader Election](../../leader-election/README.md) · [Database Replication](../../../02-building-blocks/databases/replication/README.md) · [CAP Theorem](../../../01-foundations/cap-theorem/README.md)
