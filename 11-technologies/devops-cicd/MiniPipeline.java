import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * MiniPipeline — a CI/CD engine from scratch:
 *
 *   1. STAGE DAG: stages with dependencies, executed in order, fail-fast.
 *   2. IMMUTABLE ARTIFACTS: the image built once from a commit SHA is the
 *      exact thing promoted through every environment.
 *   3. PROMOTION with AUTOMATED CANARY ANALYSIS: compare canary error rate
 *      to baseline; pass -> promote, fail -> auto-rollback.
 *   4. GITOPS RECONCILER: a desired-state map (the "git repo") converged
 *      against a running-state map (the "cluster").
 *
 * Compile & run:  javac MiniPipeline.java && java MiniPipeline
 */
public class MiniPipeline {

    // ---------------- 1. the stage DAG ----------------
    record Stage(String name, List<String> needs, Supplier<Boolean> run) {}

    static boolean executePipeline(List<Stage> stages) {
        Set<String> done = new HashSet<>();
        System.out.println("== pipeline start ==");
        for (Stage s : stages) {                                   // stages listed in topo order
            if (!done.containsAll(s.needs())) {
                System.out.println("  SKIP " + s.name() + " (dependency failed)");
                continue;
            }
            System.out.printf("  RUN  %-22s", s.name());
            boolean ok = s.run().equals(Boolean.TRUE);
            System.out.println(ok ? "✔" : "✘  — FAIL FAST");
            if (!ok) return false;
            done.add(s.name());
        }
        return true;
    }

    // ---------------- 2. immutable artifacts ----------------
    static final Map<String, String> registry = new LinkedHashMap<>();   // sha -> "image digest"

    static String buildImage(String gitSha) {
        String digest = "sha256:" + Integer.toHexString(("img" + gitSha).hashCode());
        registry.put(gitSha, digest);
        return digest;
    }

    // ---------------- 3. environments + canary analysis ----------------
    static final Map<String, String> environments = new LinkedHashMap<>(); // env -> running digest

    /** Simulated golden-signal comparison: canary error rate vs baseline. */
    static boolean canaryAnalysis(String digest, double injectedErrorRate) {
        double baseline = 0.01;
        double canary = injectedErrorRate + ThreadLocalRandom.current().nextDouble(0.005);
        System.out.printf("      canary analysis: baseline=%.3f canary=%.3f -> %s%n",
                baseline, canary, canary <= baseline * 2 ? "PASS" : "FAIL");
        return canary <= baseline * 2;
    }

    static boolean promote(String env, String digest, double canaryErrorRate) {
        if (env.equals("prod")) {
            String previous = environments.get("prod");
            environments.put("prod-canary(5%)", digest);
            System.out.println("    canary 5% of prod traffic on " + digest);
            if (!canaryAnalysis(digest, canaryErrorRate)) {
                environments.remove("prod-canary(5%)");
                System.out.println("    AUTO-ROLLBACK: prod stays on " + previous);
                return false;
            }
            environments.remove("prod-canary(5%)");
        }
        environments.put(env, digest);
        System.out.println("    " + env + " now running " + digest);
        return true;
    }

    // ---------------- 4. GitOps reconciler ----------------
    static final Map<String, String> gitDesired = new LinkedHashMap<>();  // "repo": app -> digest
    static final Map<String, String> cluster = new LinkedHashMap<>();     // "cluster": app -> digest

    static void reconcile() {
        gitDesired.forEach((app, desired) -> {
            String actual = cluster.get(app);
            if (!desired.equals(actual)) {
                System.out.printf("  [gitops] drift on %s: cluster=%s git=%s -> converging to git%n",
                        app, actual, desired);
                cluster.put(app, desired);
            }
        });
    }

    // ---------------- demo: the whole push-to-prod story ----------------
    public static void main(String[] args) {
        String sha = "a1b2c3d";
        final String[] digest = new String[1];

        boolean green = executePipeline(List.of(
                new Stage("checkout",        List.of(),                    () -> true),
                new Stage("unit-tests",      List.of("checkout"),          () -> true),
                new Stage("static-analysis", List.of("checkout"),          () -> true),
                new Stage("build-image",     List.of("unit-tests", "static-analysis"),
                        () -> { digest[0] = buildImage(sha); return true; }),
                new Stage("integration",     List.of("build-image"),       () -> true)));

        if (!green) return;
        System.out.println("  artifact: " + sha + " -> " + digest[0] + " (immutable; promoted, never rebuilt)");

        System.out.println("\n== promotion: same digest through every environment ==");
        promote("staging", digest[0], 0.0);
        boolean shipped = promote("prod", digest[0], 0.0);
        System.out.println("  deploy " + (shipped ? "succeeded" : "rolled back"));

        System.out.println("\n== a bad release: canary catches it, prod untouched ==");
        String badDigest = buildImage("deadbee");
        promote("staging", badDigest, 0.0);
        promote("prod", badDigest, 0.15);                        // 15% error rate in canary
        System.out.println("  prod still running: " + environments.get("prod"));

        System.out.println("\n== GitOps: merge a PR (update git) and let the reconciler deploy ==");
        cluster.put("orders", digest[0]);
        gitDesired.put("orders", digest[0]);
        reconcile();                                              // in sync — nothing happens
        gitDesired.put("orders", "sha256:newrelease");            // the "merged PR"
        reconcile();                                              // reconciler converges cluster
        System.out.println("  rollback = git revert:");
        gitDesired.put("orders", digest[0]);
        reconcile();
    }
}
