import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.*;
import java.util.concurrent.*;

/**
 * AwsCoreConcepts — the *mechanisms* behind three AWS staples, from scratch:
 *
 *   1. S3-style object store with PRESIGNED URLS: HMAC-signed, expiring
 *      links that grant access without credentials — the pattern that takes
 *      upload/download traffic off your servers.
 *   2. DynamoDB-style PARTITIONED KV with a hot-partition detector — why
 *      partition-key design is the whole game.
 *   3. MULTI-AZ FAILOVER: a health-checked primary/standby pair — what
 *      "RDS Multi-AZ" actually does for you.
 *
 * Compile & run:  javac AwsCoreConcepts.java && java AwsCoreConcepts
 */
public class AwsCoreConcepts {

    // ======= 1. S3-style object store + presigned URLs =======
    static final class ObjectStore {
        private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
        private final byte[] secretKey = "aws-secret-key-demo".getBytes();

        public void put(String bucketKey, byte[] data) { objects.put(bucketKey, data); }

        /** Generate a presigned GET URL: key + expiry, signed with HMAC. */
        public String presign(String bucketKey, long validForMillis) {
            long expires = System.currentTimeMillis() + validForMillis;
            String payload = bucketKey + "|" + expires;
            return "/get/" + bucketKey + "?expires=" + expires + "&sig=" + hmac(payload);
        }

        /** "Anonymous" fetch using only the URL — validates signature + expiry. */
        public byte[] fetchWithUrl(String url) {
            String key = url.substring(5, url.indexOf('?'));
            long expires = Long.parseLong(url.replaceAll(".*expires=(\\d+).*", "$1"));
            String sig = url.replaceAll(".*sig=([\\w+/=]+).*", "$1");
            if (System.currentTimeMillis() > expires)
                throw new SecurityException("presigned URL expired");
            if (!hmac(key + "|" + expires).equals(sig))
                throw new SecurityException("signature mismatch (tampered URL)");
            return objects.get(key);
        }

        private String hmac(String payload) {
            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(secretKey, "HmacSHA256"));
                return Base64.getUrlEncoder().encodeToString(mac.doFinal(payload.getBytes()));
            } catch (Exception e) { throw new RuntimeException(e); }
        }
    }

    // ======= 2. DynamoDB-style partitioned KV + hot-partition detection =======
    static final class PartitionedKV {
        private final int partitions;
        private final List<Map<String, String>> data = new ArrayList<>();
        private final long[] accessCount;

        PartitionedKV(int partitions) {
            this.partitions = partitions;
            this.accessCount = new long[partitions];
            for (int i = 0; i < partitions; i++) data.add(new ConcurrentHashMap<>());
        }
        private int partitionOf(String pk) { return Math.floorMod(pk.hashCode(), partitions); }

        public void put(String partitionKey, String sortKey, String value) {
            int p = partitionOf(partitionKey);
            accessCount[p]++;
            data.get(p).put(partitionKey + "#" + sortKey, value);
        }
        public String get(String partitionKey, String sortKey) {
            int p = partitionOf(partitionKey);
            accessCount[p]++;
            return data.get(p).get(partitionKey + "#" + sortKey);
        }
        /** Hot partition = one partition absorbing a disproportionate share of traffic. */
        public void report() {
            long total = Arrays.stream(accessCount).sum();
            for (int i = 0; i < partitions; i++) {
                double pct = 100.0 * accessCount[i] / Math.max(1, total);
                System.out.printf("  partition %d: %5.1f%% of traffic %s%n", i, pct,
                        pct > 50 ? "  <-- HOT PARTITION! throttling in real DynamoDB" : "");
            }
        }
    }

    // ======= 3. Multi-AZ failover =======
    static final class MultiAzDatabase {
        volatile String primaryAz = "us-east-1a";
        volatile String standbyAz = "us-east-1b";
        volatile boolean primaryHealthy = true;

        /** Every write goes to the primary and is synchronously replicated to standby. */
        public String write(String data) {
            if (!primaryHealthy) failover();
            return "written to " + primaryAz + " (sync-replicated to " + standbyAz + ")";
        }
        public void azOutage(String az) {
            if (az.equals(primaryAz)) primaryHealthy = false;
            System.out.println("  !! AZ " + az + " is DOWN");
        }
        private void failover() {
            System.out.println("  [failover] promoting standby " + standbyAz + " to primary (~60-120s in real RDS)");
            String old = primaryAz;
            primaryAz = standbyAz; standbyAz = old;
            primaryHealthy = true;
        }
    }

    // ======= demo =======
    public static void main(String[] args) throws Exception {
        System.out.println("== 1. presigned URLs ==");
        ObjectStore s3 = new ObjectStore();
        s3.put("uploads/report.pdf", "PDF-BYTES".getBytes());
        String url = s3.presign("uploads/report.pdf", 500);
        System.out.println("  presigned: " + url.substring(0, 60) + "...");
        System.out.println("  fetch with URL only: " + new String(s3.fetchWithUrl(url)));
        try { s3.fetchWithUrl(url.replace("report", "salary")); }
        catch (SecurityException e) { System.out.println("  tampered URL rejected: " + e.getMessage()); }
        Thread.sleep(600);
        try { s3.fetchWithUrl(url); }
        catch (SecurityException e) { System.out.println("  after expiry: " + e.getMessage()); }

        System.out.println("\n== 2. hot partitions: good key vs bad key ==");
        PartitionedKV good = new PartitionedKV(4);
        for (int i = 0; i < 1000; i++) good.put("user-" + i, "profile", "data");   // key = user id
        System.out.println("  partition key = user_id (high cardinality):");
        good.report();
        PartitionedKV bad = new PartitionedKV(4);
        for (int i = 0; i < 1000; i++) bad.put("2026-07-08", "event-" + i, "data"); // key = today's date!
        System.out.println("  partition key = date (everything today hits ONE partition):");
        bad.report();

        System.out.println("\n== 3. multi-AZ failover ==");
        MultiAzDatabase rds = new MultiAzDatabase();
        System.out.println("  " + rds.write("order-1"));
        rds.azOutage("us-east-1a");
        System.out.println("  " + rds.write("order-2") + "   <- app retries, lands on new primary");
    }
}
