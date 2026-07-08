# ZooKeeper — Coordination as a Service

> **Mental model:** ZooKeeper is a small, replicated, strongly consistent (**CP**) filesystem-like store whose primitives — **znodes, ephemeral nodes, sequential nodes, and watches** — compose into every distributed coordination pattern: [leader election](../../05-distributed-systems/leader-election/README.md), distributed locks, service registries, config distribution, and group membership. Kafka (pre-KRaft), HBase, Hadoop, and SolrCloud all outsourced their hardest problem to it. Understanding *the four primitives* means you can derive the recipes instead of memorizing them.

---

## 1. The four primitives

- **Znodes:** a tree of small data nodes (`/services/orders/instance-1`, max ~1MB — coordination metadata, never application data).
- **Ephemeral znodes:** die automatically **when the creating client's session ends** (missed heartbeats ⇒ session expiry ⇒ node vanishes). This is the failure-detection primitive: liveness becomes *existence of a node*.
- **Sequential znodes:** creation appends a monotonically increasing counter (`lock-0000000042`) — a cluster-wide ordering primitive.
- **Watches:** one-shot notifications on znode change/deletion — the event mechanism that turns polling into push.

**Consistency machinery:** an ensemble (3 or 5 nodes) elects a leader; all **writes** funnel through it via **ZAB** (atomic broadcast, [Raft's](../../05-distributed-systems/consensus-algorithms/raft/README.md) sibling) and commit on **quorum (N/2+1)** — 5 nodes tolerate 2 failures; minority partitions refuse service (**CP in [CAP](../../01-foundations/cap-theorem/README.md)** — correct for locks, where a stale answer means two leaders). Reads are served locally by any node (fast, possibly slightly stale; `sync` forces freshness). Writes are totally ordered by **zxid** — the global sequence everything else builds on.

## 2. The recipes (derive them from the primitives)

- **Leader election:** everyone creates an **ephemeral sequential** node under `/election/`; lowest sequence = leader. Everyone else watches **only the node just before theirs** — when the leader dies, its ephemeral node vanishes, and *only the next-in-line* is notified. Watching the predecessor (not the leader) avoids the **herd effect** — the detail that separates a real answer from a recitation.
- **Distributed lock:** identical recipe under `/locks/` — lowest sequence holds the lock; release = delete (or session death ⇒ **no orphaned locks**, the advantage over [Redis SETNX locks](../redis/README.md); the trade: ZK's lower write throughput and operational weight).
- **Service registry:** register = create ephemeral node with your address; crash = automatic deregistration; consumers watch the parent dir ([service discovery](../../07-microservices/service-discovery/README.md), CP flavor).
- **Config distribution / feature flags:** config in a znode; all instances watch it — near-instant fan-out of changes.
- **Fencing tokens (the subtle one):** a lock holder paused by GC may act *after* losing the lock. The znode's **zxid/sequence is a fencing token**: downstream systems reject writes carrying an older token than the newest seen — the standard answer to "what if the old leader wakes up?"

**Modern context:** etcd (Raft, gRPC, Kubernetes' store) and Consul serve the same role; Kafka's KRaft replaced ZK by internalizing consensus. The primitives-and-recipes knowledge transfers wholesale.

## 3. Installation

```bash
docker run -d --name zk -p 2181:2181 zookeeper:3.9
docker exec -it zk zkCli.sh                      # the interactive shell
> create /config "v1"
> get /config
> create -e /liveness/instance-1 "10.0.0.5"      # ephemeral: dies with this session
> create -s -e /election/candidate- "me"         # ephemeral sequential
> ls /election
> stat /config                                   # note the zxids
# quit the shell (session ends) — reconnect and: ls /liveness  -> empty!
```

Java client (Maven): `org.apache.curator:curator-recipes` — use Curator, not the raw client; it ships tested recipes (`LeaderLatch`, `InterProcessMutex`, `PathChildrenCache`).

## 4. The from-scratch implementation

[`MiniZooKeeper.java`](MiniZooKeeper.java) implements the primitive set — **znode tree, ephemeral nodes bound to sessions, sequential node counters, one-shot watches** — then builds **leader election with predecessor-watching (herd-effect-free) and a distributed lock with fencing tokens** on top, and demonstrates a session expiry triggering automatic failover. The recipes stop being folklore once you've watched your own ephemeral node vanish.

## 5. Interview soundbites

- "ZooKeeper gives four primitives — znodes, ephemeral, sequential, watches — and every coordination pattern is a composition of them."
- "Election: ephemeral-sequential nodes, lowest wins, watch your *predecessor* — that's what prevents the herd effect."
- "It's CP: minority partitions refuse service, because for locks a stale answer means two leaders — the opposite trade from an AP service registry."
- "ZK locks self-release on session death, and the sequence number doubles as a fencing token against GC-paused zombies."

**Related:** [Leader Election](../../05-distributed-systems/leader-election/README.md) · [Raft](../../05-distributed-systems/consensus-algorithms/raft/README.md) · [CAP](../../01-foundations/cap-theorem/README.md) · [Service Discovery](../../07-microservices/service-discovery/README.md) · [Kafka](../kafka/README.md)
