import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * MiniZooKeeper — ZooKeeper's primitive set from scratch, then the two
 * classic recipes built ON TOP of the primitives (not hardcoded):
 *
 *   PRIMITIVES: znode tree · ephemeral nodes (die with their session)
 *               · sequential nodes (global counters) · one-shot watches
 *   RECIPES:    leader election (ephemeral-sequential + predecessor watch,
 *               herd-effect-free) · distributed lock with FENCING TOKENS
 *
 * Compile & run:  javac MiniZooKeeper.java && java MiniZooKeeper
 */
public class MiniZooKeeper {

    // ================= the primitives =================

    static final class Znode {
        final String path; final String data;
        final boolean ephemeral; final long ownerSession; final long zxid;
        Znode(String path, String data, boolean ephemeral, long ownerSession, long zxid) {
            this.path = path; this.data = data; this.ephemeral = ephemeral;
            this.ownerSession = ownerSession; this.zxid = zxid;
        }
    }

    private final NavigableMap<String, Znode> tree = new ConcurrentSkipListMap<>();
    private final Map<String, List<Consumer<String>>> watches = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> sequenceCounters = new ConcurrentHashMap<>();
    private final AtomicLong zxidGen = new AtomicLong();          // total write order (ZAB's gift)
    private final Set<Long> liveSessions = ConcurrentHashMap.newKeySet();
    private final AtomicLong sessionGen = new AtomicLong();

    public long connect() { long s = sessionGen.incrementAndGet(); liveSessions.add(s); return s; }

    /** Session expiry: every ephemeral node owned by it vanishes (+ watches fire). */
    public void expireSession(long session) {
        liveSessions.remove(session);
        List<String> dead = tree.values().stream()
                .filter(z -> z.ephemeral && z.ownerSession == session).map(z -> z.path).toList();
        dead.forEach(this::delete);
        System.out.println("  [zk] session " + session + " expired -> ephemeral nodes removed: " + dead);
    }

    /** create with flags; sequential appends a zero-padded counter (returns final path). */
    public synchronized String create(String path, String data, long session,
                                      boolean ephemeral, boolean sequential) {
        String finalPath = path;
        if (sequential) {
            long seq = sequenceCounters
                    .computeIfAbsent(path.substring(0, path.lastIndexOf('/')), p -> new AtomicLong())
                    .getAndIncrement();
            finalPath = path + String.format("%010d", seq);
        }
        tree.put(finalPath, new Znode(finalPath, data, ephemeral, session, zxidGen.incrementAndGet()));
        fireWatches(parentOf(finalPath));
        return finalPath;
    }

    public synchronized void delete(String path) {
        if (tree.remove(path) != null) { fireWatches(path); fireWatches(parentOf(path)); }
    }

    public List<String> getChildren(String dir) {
        return tree.keySet().stream()
                .filter(p -> p.startsWith(dir + "/") && p.indexOf('/', dir.length() + 1) == -1)
                .sorted().toList();
    }

    /** One-shot watch (like ZK): fires once on change of the given path, then must be re-set. */
    public void watch(String path, Consumer<String> callback) {
        watches.computeIfAbsent(path, p -> new CopyOnWriteArrayList<>()).add(callback);
    }
    private void fireWatches(String path) {
        List<Consumer<String>> cbs = watches.remove(path);
        if (cbs != null) cbs.forEach(cb -> cb.accept(path));
    }
    private static String parentOf(String path) {
        int i = path.lastIndexOf('/');
        return i <= 0 ? "/" : path.substring(0, i);
    }
    public Znode get(String path) { return tree.get(path); }

    // ================= recipe 1: leader election =================
    /**
     * Each candidate creates an EPHEMERAL SEQUENTIAL node under /election.
     * Lowest sequence = leader. Everyone else watches ONLY its predecessor:
     * when it vanishes, re-check — maybe you're leader now (no herd effect).
     */
    public final class Candidate {
        final String name; final long session; final String myNode;
        Candidate(String name) {
            this.name = name;
            this.session = connect();
            this.myNode = create("/election/candidate-", name, session, true, true);
            checkLeadership();
        }
        void checkLeadership() {
            List<String> children = getChildren("/election");
            if (children.isEmpty()) return;
            if (children.get(0).equals(myNode)) {
                System.out.println("  [election] " + name + " is LEADER (" + myNode + ")");
            } else {
                String predecessor = children.get(children.indexOf(myNode) - 1);
                System.out.println("  [election] " + name + " follows, watching predecessor " + predecessor);
                watch(predecessor, changed -> checkLeadership());   // watch predecessor, NOT the leader
            }
        }
    }

    // ================= recipe 2: distributed lock + fencing =================
    public final class DistributedLock {
        private final long session = connect();
        private String myNode;
        /** Try to acquire; returns a FENCING TOKEN (the zxid) if acquired, else -1. */
        public long tryAcquire(String client) {
            myNode = create("/locks/lock-", client, session, true, true);
            List<String> children = getChildren("/locks");
            if (children.get(0).equals(myNode)) {
                long fencingToken = get(myNode).zxid;
                System.out.println("  [lock] " + client + " ACQUIRED, fencing token=" + fencingToken);
                return fencingToken;
            }
            System.out.println("  [lock] " + client + " waiting behind " + children.get(0));
            return -1;
        }
        public void release() { delete(myNode); }
        public long sessionId() { return session; }
    }

    /** A downstream resource that enforces fencing tokens (rejects zombie writers). */
    static final class FencedStorage {
        long highestTokenSeen = -1;
        void write(String data, long token) {
            if (token < highestTokenSeen)
                System.out.println("  [storage] REJECTED write '" + data + "' (stale token " + token
                        + " < " + highestTokenSeen + ") — zombie ex-lock-holder!");
            else { highestTokenSeen = token; System.out.println("  [storage] accepted '" + data
                    + "' with token " + token); }
        }
    }

    // ================= demo =================
    public static void main(String[] args) throws Exception {
        MiniZooKeeper zk = new MiniZooKeeper();

        System.out.println("== leader election with automatic failover ==");
        Candidate a = zk.new Candidate("node-A");
        Candidate b = zk.new Candidate("node-B");
        Candidate c = zk.new Candidate("node-C");
        System.out.println("  ...node-A (leader) crashes: its session expires...");
        zk.expireSession(a.session);   // A's ephemeral node vanishes -> B's watch fires -> B is leader
        Thread.sleep(50);

        System.out.println("\n== distributed lock + fencing tokens ==");
        FencedStorage storage = new FencedStorage();
        DistributedLock lock1 = zk.new DistributedLock();
        long token1 = lock1.tryAcquire("worker-1");
        storage.write("update-from-worker-1", token1);

        // worker-1 "GC-pauses"; its session expires; the lock self-releases:
        System.out.println("  ...worker-1 GC-pauses so long its session expires...");
        zk.expireSession(lock1.sessionId());
        DistributedLock lock2 = zk.new DistributedLock();
        long token2 = lock2.tryAcquire("worker-2");
        storage.write("update-from-worker-2", token2);

        // the zombie wakes up and tries to write with its OLD token:
        System.out.println("  ...worker-1 wakes up, still believing it holds the lock...");
        storage.write("zombie-update-from-worker-1", token1);   // rejected — fencing saves the day
    }
}
