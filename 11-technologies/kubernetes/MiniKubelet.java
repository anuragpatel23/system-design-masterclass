import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MiniKubelet — the heart of Kubernetes from scratch: DESIRED STATE vs
 * ACTUAL STATE plus a RECONCILIATION LOOP that converges them.
 *
 * Implements:
 *   1. Deployments (desired: image + replica count).
 *   2. Pods (actual: running instances with readiness).
 *   3. The control loop: crashed pod -> replaced; scale up/down -> converge;
 *      image change -> ROLLING UPDATE honoring maxSurge / maxUnavailable.
 *   4. A Service that only routes to READY pods of the right version.
 *
 * Watch the output: every action is the loop noticing a diff and fixing it —
 * self-healing and rollouts are literally the same code path.
 *
 * Compile & run:  javac MiniKubelet.java && java MiniKubelet
 */
public class MiniKubelet {

    // ---------------- actual state: pods ----------------
    enum Phase { PENDING, READY, TERMINATING, DEAD }

    static final class Pod {
        static final AtomicInteger SEQ = new AtomicInteger();
        final String name; final String image;
        volatile Phase phase = Phase.PENDING;
        Pod(String deployment, String image) {
            this.name = deployment + "-" + Integer.toHexString(SEQ.incrementAndGet());
            this.image = image;
        }
        public String toString() { return name + "(" + image + "," + phase + ")"; }
    }

    // ---------------- desired state: deployment ----------------
    static final class Deployment {
        volatile String image; volatile int replicas;
        final int maxSurge = 1, maxUnavailable = 0;      // zero-downtime rollout policy
        Deployment(String image, int replicas) { this.image = image; this.replicas = replicas; }
    }

    private final Deployment deployment;
    private final List<Pod> pods = new CopyOnWriteArrayList<>();     // actual state
    private final ScheduledExecutorService loop = Executors.newScheduledThreadPool(2);

    MiniKubelet(Deployment d) { this.deployment = d; }

    // ---------------- THE RECONCILIATION LOOP ----------------
    void reconcile() {
        pods.removeIf(p -> p.phase == Phase.DEAD);                    // garbage-collect

        List<Pod> current = pods.stream().filter(p -> p.phase != Phase.TERMINATING).toList();
        List<Pod> desiredVersion = current.stream().filter(p -> p.image.equals(deployment.image)).toList();
        List<Pod> oldVersion     = current.stream().filter(p -> !p.image.equals(deployment.image)).toList();
        long readyDesired = desiredVersion.stream().filter(p -> p.phase == Phase.READY).count();

        // 1. ROLLING UPDATE: old-version pods exist -> surge new, then drain old.
        if (!oldVersion.isEmpty()) {
            int allowedTotal = deployment.replicas + deployment.maxSurge;
            if (current.size() < allowedTotal && desiredVersion.size() < deployment.replicas) {
                startPod("surge (rolling update)");
            }
            long readyTotal = current.stream().filter(p -> p.phase == Phase.READY).count();
            if (readyDesired > 0 && readyTotal - 1 >= deployment.replicas - deployment.maxUnavailable) {
                Pod victim = oldVersion.get(0);
                victim.phase = Phase.TERMINATING;                      // drain, don't kill
                System.out.println("  [loop] draining old-version pod " + victim.name);
                loop.schedule(() -> victim.phase = Phase.DEAD, 300, TimeUnit.MILLISECONDS);
            }
            return;
        }
        // 2. SELF-HEALING / SCALING: converge replica count.
        if (desiredVersion.size() < deployment.replicas) startPod("replace/scale-up");
        else if (desiredVersion.size() > deployment.replicas) {
            Pod victim = desiredVersion.get(desiredVersion.size() - 1);
            victim.phase = Phase.TERMINATING;
            System.out.println("  [loop] scale-down: draining " + victim.name);
            loop.schedule(() -> victim.phase = Phase.DEAD, 300, TimeUnit.MILLISECONDS);
        }
    }

    private void startPod(String reason) {
        Pod p = new Pod("orders", deployment.image);
        pods.add(p);
        System.out.println("  [loop] " + reason + ": starting " + p.name + " image=" + p.image);
        loop.schedule(() -> {                                          // simulated startup + readiness probe
            p.phase = Phase.READY;
            System.out.println("  [probe] " + p.name + " passed readiness -> routable");
        }, 400, TimeUnit.MILLISECONDS);
    }

    /** The Service view: stable name -> only READY pods (readiness-gated endpoints). */
    List<String> serviceEndpoints() {
        return pods.stream().filter(p -> p.phase == Phase.READY).map(p -> p.name).toList();
    }

    void run() { loop.scheduleAtFixedRate(this::reconcile, 0, 100, TimeUnit.MILLISECONDS); }
    void shutdown() { loop.shutdownNow(); }

    // ---------------- demo ----------------
    public static void main(String[] args) throws Exception {
        Deployment d = new Deployment("orders:v1", 3);
        MiniKubelet cluster = new MiniKubelet(d);
        cluster.run();

        System.out.println("== declare: 3 replicas of orders:v1 ==");
        Thread.sleep(1200);
        System.out.println("  service endpoints: " + cluster.serviceEndpoints());

        System.out.println("\n== chaos: kill a pod (kubectl delete pod) ==");
        Pod victim = cluster.pods.get(0);
        victim.phase = Phase.DEAD;
        System.out.println("  killed " + victim.name + " — watch the loop replace it");
        Thread.sleep(1200);
        System.out.println("  service endpoints: " + cluster.serviceEndpoints());

        System.out.println("\n== rolling update: image -> orders:v2 (maxSurge=1, maxUnavailable=0) ==");
        d.image = "orders:v2";
        Thread.sleep(4000);
        System.out.println("  service endpoints after rollout: " + cluster.serviceEndpoints());
        System.out.println("  all pods: " + cluster.pods);

        System.out.println("\n== scale down to 2 (kubectl scale --replicas=2) ==");
        d.replicas = 2;
        Thread.sleep(1000);
        System.out.println("  service endpoints: " + cluster.serviceEndpoints());

        cluster.shutdown();
        System.out.println("\nEverything above was ONE loop diffing desired vs actual — that's Kubernetes.");
    }
}
