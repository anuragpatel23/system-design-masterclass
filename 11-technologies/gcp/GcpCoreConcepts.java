import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * GcpCoreConcepts — the two genuinely distinctive GCP mechanisms, from scratch:
 *
 *   1. TRUETIME COMMIT-WAIT (Spanner): every clock has bounded uncertainty
 *      [now-ε, now+ε]. Spanner assigns a commit timestamp and WAITS until
 *      the timestamp is guaranteed past, everywhere, before acknowledging —
 *      making global timestamp ordering safe. The demo shows the ordering
 *      anomaly that appears when you skip the wait.
 *
 *   2. COLUMNAR STORAGE (BigQuery): analytics scans read one column of
 *      millions of rows. A row store must read every field of every row;
 *      a column store reads just the needed column. Measured side by side.
 *
 * Compile & run:  javac GcpCoreConcepts.java && java GcpCoreConcepts
 */
public class GcpCoreConcepts {

    // ================= 1. TrueTime + commit wait =================

    /** A clock that admits its own uncertainty: true time is in [earliest, latest]. */
    static final class TrueTime {
        final long epsilonMillis;                 // uncertainty bound (GPS+atomic clocks make this ~1-7ms)
        TrueTime(long epsilonMillis) { this.epsilonMillis = epsilonMillis; }
        long nowEarliest() { return System.currentTimeMillis() - epsilonMillis; }
        long nowLatest()   { return System.currentTimeMillis() + epsilonMillis; }
    }

    static final class SpannerNode {
        final String name; final TrueTime tt; final long clockSkewMillis;   // this node's real skew
        SpannerNode(String name, TrueTime tt, long skew) { this.name = name; this.tt = tt; this.clockSkewMillis = skew; }

        /** Commit WITH commit-wait: timestamp is guaranteed past before we ack. */
        long commitWithWait(String txn) throws InterruptedException {
            long ts = System.currentTimeMillis() + clockSkewMillis;         // this node's (skewed) clock
            // Wait until even the earliest possible true time exceeds ts:
            while (tt.nowEarliest() < ts) Thread.sleep(1);
            System.out.printf("  [%s] %s committed at ts=%d (after commit-wait)%n", name, txn, ts);
            return ts;
        }
        /** Commit WITHOUT the wait — what goes wrong with skewed clocks. */
        long commitNoWait(String txn) {
            long ts = System.currentTimeMillis() + clockSkewMillis;
            System.out.printf("  [%s] %s committed at ts=%d (NO wait)%n", name, txn, ts);
            return ts;
        }
    }

    // ================= 2. Row store vs column store =================

    static final class RowStore {
        final long[][] rows;          // each row: {userId, age, revenue, country, ...}
        RowStore(int n, int width) {
            rows = new long[n][width];
            for (long[] r : rows) for (int c = 0; c < width; c++) r[c] = ThreadLocalRandom.current().nextLong(100);
        }
        /** SUM(col) must touch EVERY field of EVERY row (row-major layout). */
        long sumColumn(int col) {
            long sum = 0, touched = 0;
            for (long[] r : rows) { for (long f : r) touched += (f & 1); sum += r[col]; } // simulate full-row read
            return sum + (touched * 0);
        }
    }

    static final class ColumnStore {
        final long[][] columns;       // columns[c] = all values of column c, contiguous
        ColumnStore(RowStore src) {
            int n = src.rows.length, width = src.rows[0].length;
            columns = new long[width][n];
            for (int r = 0; r < n; r++) for (int c = 0; c < width; c++) columns[c][r] = src.rows[r][c];
        }
        /** SUM(col) reads exactly one contiguous array — nothing else. */
        long sumColumn(int col) {
            long sum = 0;
            for (long v : columns[col]) sum += v;
            return sum;
        }
    }

    // ================= demo =================
    public static void main(String[] args) throws Exception {
        System.out.println("== 1. why commit-wait exists ==");
        TrueTime tt = new TrueTime(5);
        SpannerNode fastClock = new SpannerNode("tokyo (clock +8ms)", tt, +8);
        SpannerNode slowClock = new SpannerNode("iowa  (clock -8ms)", tt, -8);

        System.out.println(" WITHOUT commit-wait (causally second txn can get an EARLIER timestamp):");
        long t1 = fastClock.commitNoWait("txn-A (debit)");
        Thread.sleep(2);                                   // txn-B happens strictly AFTER txn-A
        long t2 = slowClock.commitNoWait("txn-B (credit, causally after A)");
        System.out.println("  ordering by timestamp says B before A? " + (t2 < t1)
                + "   <-- anomaly: external consistency violated");

        System.out.println(" WITH commit-wait (Spanner):");
        long w1 = fastClock.commitWithWait("txn-A (debit)");
        long w2 = slowClock.commitWithWait("txn-B (credit)");
        System.out.println("  ordering preserved? " + (w2 > w1)
                + "   <-- the wait costs ms; buys global timestamp ordering");

        System.out.println("\n== 2. row store vs column store: SUM(revenue) over 2M rows x 8 cols ==");
        RowStore rowStore = new RowStore(2_000_000, 8);
        ColumnStore colStore = new ColumnStore(rowStore);

        long start = System.nanoTime();
        long s1 = rowStore.sumColumn(2);
        long rowMs = (System.nanoTime() - start) / 1_000_000;

        start = System.nanoTime();
        long s2 = colStore.sumColumn(2);
        long colMs = (System.nanoTime() - start) / 1_000_000;

        System.out.printf("  row store:    %d ms (reads all 8 columns of every row)%n", rowMs);
        System.out.printf("  column store: %d ms (reads 1 contiguous column)%n", colMs);
        System.out.println("  same answer? " + (s1 == s2)
                + " — this ratio is why BigQuery bills per column scanned, and why"
                + "\n    analytics belongs in columnar storage, not your OLTP database.");
    }
}
