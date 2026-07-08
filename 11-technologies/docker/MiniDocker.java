import java.security.MessageDigest;
import java.util.*;

/**
 * MiniDocker — Docker's IMAGE/LAYER model from scratch (the half of Docker
 * you can build without Linux namespaces):
 *
 *   1. LAYERS are content-addressed filesystem diffs (here: maps of
 *      path -> content, identified by SHA-256 of their content).
 *   2. An IMAGE is an ordered stack of layer digests.
 *   3. BUILD CACHING: an instruction whose inputs are unchanged reuses the
 *      cached layer — and one change invalidates everything after it.
 *      (Run the demo: the rebuild shows CACHED vs REBUILT per step.)
 *   4. A CONTAINER = image layers (read-only) + one writable COW layer.
 *      The union view resolves a path from the topmost layer that has it.
 *   5. A REGISTRY maps mutable TAGS and immutable DIGESTS to images.
 *
 * Compile & run:  javac MiniDocker.java && java MiniDocker
 */
public class MiniDocker {

    // ---------------- layers: content-addressed diffs ----------------
    static final class Layer {
        final String digest;
        final Map<String, String> files;      // path -> content (a filesystem diff)
        Layer(Map<String, String> files) {
            this.files = Map.copyOf(files);
            this.digest = sha256(files.toString()).substring(0, 12);
        }
    }

    /** The layer store: digest -> layer. Shared across all images (dedup!). */
    static final Map<String, Layer> layerStore = new HashMap<>();

    static Layer storeLayer(Map<String, String> files) {
        Layer l = new Layer(files);
        layerStore.putIfAbsent(l.digest, l);   // identical content = same layer, stored once
        return layerStore.get(l.digest);
    }

    // ---------------- images: an ordered stack of layers ----------------
    record Image(List<String> layerDigests, String imageDigest) {}

    // ---------------- the builder with layer caching ----------------
    /** A build instruction: a name + the files it produces (its "diff"). */
    record Instruction(String description, Map<String, String> producedFiles) {}

    static final Map<String, String> buildCache = new HashMap<>(); // cacheKey -> layer digest

    /**
     * Builds an image from instructions with Docker's caching rule:
     * cacheKey(step N) = hash(parent layer chain + this instruction's content).
     * One changed instruction invalidates itself AND all later steps.
     */
    static Image build(List<Instruction> instructions) {
        List<String> layers = new ArrayList<>();
        String chain = "";
        for (Instruction ins : instructions) {
            String cacheKey = sha256(chain + "|" + ins.description() + "|" + ins.producedFiles());
            String digest = buildCache.get(cacheKey);
            if (digest != null) {
                System.out.printf("  CACHED   %-38s -> layer %s%n", ins.description(), digest);
            } else {
                Layer l = storeLayer(ins.producedFiles());
                digest = l.digest;
                buildCache.put(cacheKey, digest);
                System.out.printf("  REBUILT  %-38s -> layer %s%n", ins.description(), digest);
            }
            layers.add(digest);
            chain = sha256(chain + digest);     // later steps depend on everything before
        }
        return new Image(List.copyOf(layers), sha256(layers.toString()).substring(0, 12));
    }

    // ---------------- containers: union view + copy-on-write ----------------
    static final class Container {
        final Image image;
        final Map<String, String> writableLayer = new HashMap<>();   // dies with the container
        Container(Image image) { this.image = image; }

        /** Union filesystem read: topmost layer that has the path wins. */
        String read(String path) {
            if (writableLayer.containsKey(path)) return writableLayer.get(path);
            List<String> digests = image.layerDigests();
            for (int i = digests.size() - 1; i >= 0; i--) {          // top -> bottom
                Layer l = layerStore.get(digests.get(i));
                if (l.files.containsKey(path)) return l.files.get(path);
            }
            return null;
        }
        /** Writes NEVER touch image layers — copy-on-write into the container layer. */
        void write(String path, String content) { writableLayer.put(path, content); }
    }

    // ---------------- registry: tags are mutable, digests are not ----------------
    static final class Registry {
        final Map<String, Image> byTag = new HashMap<>();
        final Map<String, Image> byDigest = new HashMap<>();
        void push(String tag, Image img) {
            byTag.put(tag, img);                       // tag can be repointed later!
            byDigest.put(img.imageDigest(), img);      // digest is forever
        }
        Image pull(String ref) { return byTag.getOrDefault(ref, byDigest.get(ref)); }
    }

    static String sha256(String s) {
        try {
            StringBuilder sb = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(s.getBytes()))
                sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    // ---------------- demo ----------------
    public static void main(String[] args) {
        System.out.println("== build #1 (cold cache) ==");
        List<Instruction> dockerfileV1 = List.of(
                new Instruction("FROM temurin:17-jre-alpine", Map.of("/usr/bin/java", "jre-17-binary")),
                new Instruction("COPY pom.xml + mvn go-offline", Map.of("/root/.m2/deps", "spring-boot,jackson")),
                new Instruction("COPY src && mvn package", Map.of("/app/app.jar", "app-v1-bytecode")),
                new Instruction("ENTRYPOINT java -jar app.jar", Map.of("/entrypoint", "java -jar /app/app.jar")));
        Image v1 = build(dockerfileV1);
        System.out.println("  image digest: " + v1.imageDigest());

        System.out.println("\n== build #2: only the CODE changed (pom.xml identical) ==");
        List<Instruction> dockerfileV2 = List.of(
                dockerfileV1.get(0),
                dockerfileV1.get(1),                                            // same deps
                new Instruction("COPY src && mvn package", Map.of("/app/app.jar", "app-v2-bytecode")),
                dockerfileV1.get(3));
        Image v2 = build(dockerfileV2);
        System.out.println("  -> base + deps were CACHE HITS; only code layers rebuilt.");
        System.out.println("  -> this is exactly why Dockerfiles copy pom.xml before src/.");

        System.out.println("\n== containers: shared read-only layers + private COW layer ==");
        Container c1 = new Container(v2);
        Container c2 = new Container(v2);
        System.out.println("  c1 reads /app/app.jar -> " + c1.read("/app/app.jar"));
        c1.write("/tmp/scratch", "c1-local-state");
        System.out.println("  c1 wrote /tmp/scratch; c2 sees it? " + (c2.read("/tmp/scratch") != null)
                + "  (writable layers are private + ephemeral)");

        System.out.println("\n== registry: mutable tag vs immutable digest ==");
        Registry registry = new Registry();
        registry.push("myapp:latest", v1);
        String v1Digest = v1.imageDigest();
        registry.push("myapp:latest", v2);                     // tag repointed!
        System.out.println("  'myapp:latest' now -> " + registry.pull("myapp:latest").imageDigest()
                + " (moved). Digest " + v1Digest + " still -> "
                + registry.pull(v1Digest).imageDigest() + " (immutable — pin digests in prod).");
    }
}
