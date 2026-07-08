# Leader Election

> **The problem, precisely stated:** in a cluster of otherwise-equivalent nodes, designate exactly one of them as "the leader" — responsible for some coordinating duty (accepting writes, assigning work, breaking ties) — such that the whole cluster agrees on who it currently is, and the cluster can detect and correct a leader's failure without ever having **two** nodes simultaneously believing they're the leader (split-brain). This document is the practical, tool-level companion to [Raft's](../consensus-algorithms/raft/README.md) leader-election mechanism — Raft describes leader election as it happens *inside* a purpose-built consensus algorithm; this document covers how you'd actually **use** leader election as a building block in a broader system, typically by relying on an existing consensus-backed coordination service rather than reimplementing Raft yourself.

---

## 1. Why You (Almost) Never Implement This From Scratch

Correct leader election, done from first principles, requires solving the exact hard problems [Raft](../consensus-algorithms/raft/README.md) and [Paxos](../consensus-algorithms/paxos/README.md) exist to solve — split-brain prevention under partitions, safe recovery after a coordinator crash, and so on. **The overwhelmingly standard, correct engineering answer is to delegate leader election to an existing, battle-tested, consensus-backed coordination service** — ZooKeeper, etcd, or Consul — rather than hand-rolling it, precisely because getting the edge cases right is exactly as hard as everything covered in the Raft document, and re-deriving that correctly for every service that happens to need a leader would be an enormous, error-prone duplication of effort.

---

## 2. The Common Pattern: Ephemeral Nodes / Leases

Both ZooKeeper and etcd (despite different underlying APIs) support leader election via essentially the same core mechanism: an **ephemeral, time-bound registration** that automatically disappears if the registering node stops actively renewing it (due to a crash, a network partition, or a clean shutdown).

### ZooKeeper's Approach: Ephemeral Sequential Znodes
1. Every candidate node creates an **ephemeral sequential** znode under a shared parent path (e.g., `/election/candidate-`) — ZooKeeper appends a monotonically increasing sequence number to each (`candidate-0000000001`, `candidate-0000000002`, ...).
2. The candidate with the **lowest sequence number** is the leader.
3. Every other candidate watches the znode with the next-lowest sequence number (not the leader directly — this avoids every single candidate needing to be notified of every change, an important scalability detail: watching only your immediate predecessor avoids a "thundering herd" of notifications to all N candidates whenever any one candidate's znode changes).
4. If the current leader's znode disappears (because ZooKeeper detected its session died — no heartbeat/renewal within the session timeout, which is what "ephemeral" means), the candidate watching it is notified, checks if it's now the lowest remaining sequence number, and if so, becomes the new leader.

**Why ephemeral nodes are the crux of correctness here:** the znode's lifetime is tied to the creating client's **active session** with ZooKeeper itself, not to the client explicitly deleting it — so a crashed node (which can no longer renew its session) has its znode automatically removed by ZooKeeper after the session timeout, **without requiring any other node to detect the crash directly**. This sidesteps the fundamental "can't tell crashed from slow" ambiguity described in the [Raft](../consensus-algorithms/raft/README.md#1-why-consensus-is-hard-motivate-it-before-describing-it) document by delegating that detection entirely to ZooKeeper's own session/heartbeat mechanism, which is itself built on a Paxos-like consensus protocol (ZAB — ZooKeeper Atomic Broadcast) internally.

### etcd's Approach: Leases and Compare-and-Swap
1. A candidate acquires a **lease** with a time-to-live (TTL) and attempts to write a specific key (e.g., `/election/leader`) with an atomic **compare-and-swap** ("only write this value if the key doesn't already exist") tied to that lease.
2. Whichever candidate's compare-and-swap succeeds first is the leader; all others fail the write and instead **watch** the key for changes.
3. The leader must periodically **renew its lease** (a heartbeat) to keep the key alive; if it crashes or is partitioned away and stops renewing, the lease **expires**, etcd automatically deletes the key, and the watching candidates are notified, triggering a new election.

Both mechanisms rest on the exact same underlying principle: **leadership is represented as a claim that automatically and reliably expires if its holder stops actively proving it's still alive**, with the actual expiry/detection delegated to the consensus-backed coordination service, not left to ad-hoc, application-level heartbeat logic (which would reintroduce exactly the split-brain risks a real consensus protocol exists to prevent).

---

## 3. The Split-Brain Danger Even With a Correct Election Mechanism (a Frequently Missed Follow-Up)

Even with correct leader election, a subtle danger remains: a leader that has been "elected away" (its lease expired, a new leader was chosen) **might not know this yet** — perhaps it's paused (a long garbage collection pause is the classic real-world cause), or partitioned away from the coordination service, and still believes it's the leader when it resumes/reconnects. If it then performs a leader-only action (e.g., writes to a shared resource) **without first re-verifying it's still actually the leader**, two "leaders" can act simultaneously — the old one, unaware it's been superseded, and the new one.

**The fix: fencing tokens.** Every time leadership changes, the coordination service issues a **monotonically increasing fencing token** along with the new leadership claim. Every downstream resource the leader writes to must be built to **reject any write accompanied by a fencing token lower than the highest one it has already seen.** This means even if a stale former leader, unaware it's been superseded, attempts a write, the resource itself rejects it (because it already saw a higher fencing token from the new, legitimate leader), **rather than relying on the stale leader to somehow realize on its own that it's no longer in charge** — the correctness burden is shifted to the resource being protected, which is a strictly safer place for it to live, since it doesn't depend on the possibly-confused/partitioned former leader behaving correctly.

```
Leader A elected, fencing token = 5
Leader A pauses for a long GC pause...
                                          ...meanwhile, Leader A's lease expires
                                          Leader B is elected, fencing token = 6
                                          Leader B writes to Resource X with token 6
                                          Resource X: "highest token seen so far = 6" -- accepted
Leader A resumes, unaware it's been superseded, tries to write with its OLD token = 5
Resource X: "5 < 6, the highest I've already seen -- REJECTED"
```

**This exact scenario — "what if the old leader doesn't know it's been replaced yet" — is one of the single most common staff-level follow-up questions on this topic, and fencing tokens are the correct, complete answer**, distinct from (and a necessary complement to) the election mechanism itself, which only handles *choosing* a new leader correctly, not *preventing the old one from acting* after being superseded.

---

## 4. Real-World Example: Kubernetes' Own Leader Election for Controller High Availability

Kubernetes itself runs multiple **replicas of its own control-plane controllers** (e.g., the kube-controller-manager, the scheduler) for high availability — but only **one** replica of each should actually be actively performing its duties at any given time (having multiple active schedulers simultaneously making conflicting scheduling decisions would be a serious correctness problem, exactly the class of issue leader election exists to prevent). Kubernetes' publicly documented approach uses **exactly the etcd-lease-based mechanism described above** — since etcd (itself Raft-based, per the [Raft](../consensus-algorithms/raft/README.md#7-real-world-example-etcds-use-of-raft-as-kubernetes-source-of-truth) document's own example) is already the cluster's source of truth, Kubernetes' own control-plane components reuse that same underlying coordination service for their own internal leader election, rather than building or depending on a separate mechanism — **a clean illustration of "don't build your own consensus/leader-election infrastructure when you already depend on a correct one for something else."**

---

## 5. Spring Boot / Java Example: Leader Election via a ZooKeeper Client Library (Apache Curator)

Hand-rolling the raw ZooKeeper ephemeral-sequential-znode protocol correctly (watch management, race conditions in registering vs checking) is genuinely tricky to get exactly right — in practice, you use a well-tested client library like **Apache Curator**, which implements this exact recipe correctly on your behalf.

```java
// build.gradle: org.apache.curator:curator-recipes

@Service
@RequiredArgsConstructor
@Slf4j
public class LeaderElectionService {

    private final CuratorFramework zkClient;
    private LeaderSelector leaderSelector;

    @PostConstruct
    public void startParticipatingInElection() {
        leaderSelector = new LeaderSelector(zkClient, "/election/order-processor", new LeaderSelectorListener() {

            @Override
            public void takeLeadership(CuratorFramework client) throws Exception {
                log.info("This instance is now the LEADER -- starting exclusive duties");
                try {
                    runLeaderOnlyDuties(); // e.g., dispatching scheduled batch jobs exactly once
                } finally {
                    // Curator interprets returning from this method as voluntarily
                    // relinquishing leadership -- so leader-only work belongs INSIDE
                    // this method's lifetime, not fired off asynchronously and
                    // returned from immediately (which would relinquish leadership
                    // while the work is still actually running elsewhere).
                    log.info("Relinquishing leadership");
                }
            }

            @Override
            public void stateChanged(CuratorFramework client, ConnectionState newState) {
                if (newState == ConnectionState.SUSPENDED || newState == ConnectionState.LOST) {
                    // CRITICAL: if our connection to ZooKeeper itself is lost, we can
                    // no longer be CERTAIN we're still the leader (someone else may
                    // have already taken over) -- Curator's contract is that losing
                    // the connection means we must assume leadership may be gone and
                    // stop any leader-only work immediately, rather than optimistically
                    // continuing based on stale local state. This is the practical,
                    // library-level embodiment of the fencing-token safety principle.
                    throw new CancelLeadershipException();
                }
            }
        });

        leaderSelector.autoRequeue(); // automatically re-enter the election after relinquishing
        leaderSelector.start();
    }

    private void runLeaderOnlyDuties() throws InterruptedException {
        while (!Thread.currentThread().isInterrupted()) {
            dispatchScheduledBatchJobs(); // work that must run on EXACTLY one instance
            Thread.sleep(Duration.ofSeconds(30).toMillis());
        }
    }

    @PreDestroy
    public void stopParticipating() {
        leaderSelector.close();
    }
}
```

**Why the `stateChanged` handling matters as much as `takeLeadership` itself:** a candidate who only implements the "become leader, do work" half of this without correctly handling connection loss (by immediately ceasing leader-only work, as shown) has reintroduced exactly the stale-leader risk that fencing tokens exist to prevent, just at the application-code level instead of the resource-protection level — the safest real designs actually want **both**: the application ceases work promptly on connection loss (fast, cooperative response) **and** any critical downstream resource independently enforces fencing tokens (a safety net that doesn't depend on the leader's cooperation at all, for the case where it's slow to notice or fails to react).

---

## 6. Common Pitfalls

- Hand-rolling leader election with custom heartbeat/timeout logic instead of delegating to an existing consensus-backed coordination service — almost always the wrong trade-off given how subtle the correctness edge cases are.
- Believing correct election alone prevents split-brain — it prevents the cluster from *choosing* two leaders, but doesn't stop a stale, superseded former leader from continuing to *act* if it hasn't yet realized it's been replaced; that requires fencing tokens on the protected resource itself.
- Firing off leader-only work asynchronously and returning immediately from a `takeLeadership`-style callback, which many coordination libraries interpret as "leadership voluntarily relinquished" while the actual work is still running.
- Not handling the coordination client's own connection-loss/session-expiry events as a signal to immediately stop assuming leadership, relying only on the "am I still registered" check at the start of each work cycle rather than reacting to connection state changes promptly.

---

## 7. 60-Second Interview Answer

> "I'd never hand-roll leader election — I'd delegate to an existing consensus-backed coordination service like ZooKeeper or etcd, both of which implement the same underlying pattern: leadership is an ephemeral claim, tied to an active session or lease, that's automatically and reliably removed if the holder stops proving it's alive, with the actual failure detection delegated to the coordination service's own Paxos- or Raft-based internals rather than ad-hoc application heartbeat logic. The follow-up I'd raise unprompted is that correct election alone doesn't fully prevent split-brain — a leader that's paused or partitioned might not know it's been superseded and could still try to act. The fix is fencing tokens: every leadership change comes with a monotonically increasing token, and any critical resource the leader writes to rejects writes carrying a token lower than the highest it's already seen, so a stale former leader gets rejected by the resource itself rather than relying on it to realize on its own that it's no longer in charge."

**Related:** [Consensus Algorithms: Raft](../consensus-algorithms/raft/README.md) · [Consensus Algorithms: Paxos](../consensus-algorithms/paxos/README.md) · [Database Replication](../../02-building-blocks/databases/replication/README.md) · [CAP Theorem](../../01-foundations/cap-theorem/README.md)
