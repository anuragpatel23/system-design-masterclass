import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * MiniPrometheus — the metrics pipeline end to end, from scratch:
 *
 *   1. An instrumented demo service exposing /metrics in the REAL
 *      Prometheus text format: counter, gauge, histogram (cumulative
 *      'le' buckets — the part people get wrong).
 *   2. A PULL-based scraper storing time series in memory.
 *   3. Query evaluation: rate() over a window and histogram_quantile()
 *      with linear interpolation inside the winning bucket.
 *   4. A burn-rate alert on an SLO error budget.
 *
 * Compile & run:  javac MiniPrometheus.java && java MiniPrometheus
 */
public class MiniPrometheus {

    // ============ 1. instrumentation library + demo service ============

    static final class Metrics {
        final AtomicLong requestsTotal = new AtomicLong();
        final AtomicLong errorsTotal = new AtomicLong();
        final AtomicLong inFlight = new AtomicLong();                       // gauge
        final double[] bucketBounds = {0.05, 0.1, 0.25, 0.5, 1.0};          // seconds
        final AtomicLong[] bucketCounts = new AtomicLong[bucketBounds.length + 1]; // +Inf
        final DoubleAdder latencySum = new DoubleAdder();
        Metrics() { for (int i = 0; i < bucketCounts.length; i++) bucketCounts[i] = new AtomicLong(); }

        void observeRequest(double seconds, boolean error) {
            requestsTotal.incrementAndGet();
            if (error) errorsTotal.incrementAndGet();
            latencySum.add(seconds);
            for (int i = 0; i < bucketBounds.length; i++)
                if (seconds <= bucketBounds[i]) bucketCounts[i].incrementAndGet();
            bucketCounts[bucketBounds.length].incrementAndGet();            // +Inf: CUMULATIVE
        }

        /** The real Prometheus exposition format. Note: buckets are cumulative! */
        String scrape() {
            StringBuilder sb = new StringBuilder();
            sb.append("# TYPE http_requests_total counter\n")
              .append("http_requests_total ").append(requestsTotal.get()).append('\n')
              .append("# TYPE http_errors_total counter\n")
              .append("http_errors_total ").append(errorsTotal.get()).append('\n')
              .append("# TYPE http_in_flight gauge\n")
              .append("http_in_flight ").append(inFlight.get()).append('\n')
              .append("# TYPE http_request_seconds histogram\n");
            long cumulative = 0;
            for (int i = 0; i < bucketBounds.length; i++) {
                cumulative = bucketCounts[i].get();
                sb.append("http_request_seconds_bucket{le=\"").append(bucketBounds[i]).append("\"} ")
                  .append(cumulative).append('\n');
            }
            sb.append("http_request_seconds_bucket{le=\"+Inf\"} ")
              .append(bucketCounts[bucketBounds.length].get()).append('\n')
              .append("http_request_seconds_sum ").append(latencySum.sum()).append('\n')
              .append("http_request_seconds_count ").append(bucketCounts[bucketBounds.length].get()).append('\n');
            return sb.toString();
        }
    }

    // ============ 2. the scraper (pull!) + time-series store ============

    record Sample(long timestampMs, double value) {}
    static final class TimeSeriesDb {
        final Map<String, List<Sample>> series = new ConcurrentHashMap<>();
        void ingest(String metricLine, long ts) {
            if (metricLine.startsWith("#") || metricLine.isBlank()) return;
            int space = metricLine.lastIndexOf(' ');
            String name = metricLine.substring(0, space);
            double value = Double.parseDouble(metricLine.substring(space + 1));
            series.computeIfAbsent(name, n -> new CopyOnWriteArrayList<>()).add(new Sample(ts, value));
        }
        /** rate(): (last - first) / windowSeconds over the trailing window. Counter math. */
        double rate(String name, long windowMs) {
            List<Sample> s = series.getOrDefault(name, List.of());
            long cutoff = System.currentTimeMillis() - windowMs;
            List<Sample> win = s.stream().filter(x -> x.timestampMs() >= cutoff).toList();
            if (win.size() < 2) return 0;
            return (win.get(win.size() - 1).value() - win.get(0).value())
                    / ((win.get(win.size() - 1).timestampMs() - win.get(0).timestampMs()) / 1000.0);
        }
        double latest(String name) {
            List<Sample> s = series.getOrDefault(name, List.of());
            return s.isEmpty() ? 0 : s.get(s.size() - 1).value();
        }
    }

    // ============ 3. histogram_quantile with interpolation ============
    /**
     * Given cumulative bucket counts, find the bucket where the target rank
     * falls and linearly interpolate inside it — Prometheus's actual algorithm.
     */
    static double histogramQuantile(double q, double[] bounds, long[] cumulativeCounts) {
        long total = cumulativeCounts[cumulativeCounts.length - 1];
        if (total == 0) return Double.NaN;
        double rank = q * total;
        long prevCount = 0; double prevBound = 0;
        for (int i = 0; i < bounds.length; i++) {
            if (cumulativeCounts[i] >= rank) {
                long inBucket = cumulativeCounts[i] - prevCount;
                double fraction = inBucket == 0 ? 0 : (rank - prevCount) / inBucket;
                return prevBound + (bounds[i] - prevBound) * fraction;   // interpolate
            }
            prevCount = cumulativeCounts[i]; prevBound = bounds[i];
        }
        return bounds[bounds.length - 1];   // fell in +Inf: report the largest finite bound
    }

    // ============ demo ============
    public static void main(String[] args) throws Exception {
        Metrics metrics = new Metrics();

        // The instrumented service with /metrics
        HttpServer app = HttpServer.create(new InetSocketAddress(8081), 0);
        app.createContext("/metrics", ex -> {
            byte[] b = metrics.scrape().getBytes();
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        app.start();

        // Traffic generator: mostly fast, some slow, growing error rate
        ScheduledExecutorService traffic = Executors.newSingleThreadScheduledExecutor();
        AtomicInteger tick = new AtomicInteger();
        traffic.scheduleAtFixedRate(() -> {
            Random r = ThreadLocalRandom.current();
            for (int i = 0; i < 20; i++) {
                double latency = r.nextInt(100) < 90 ? 0.02 + r.nextDouble() * 0.1
                                                     : 0.4 + r.nextDouble() * 0.8;   // slow tail
                boolean error = r.nextInt(100) < (tick.get() > 15 ? 12 : 1);         // errors ramp up
                metrics.observeRequest(latency, error);
            }
            tick.incrementAndGet();
        }, 0, 100, TimeUnit.MILLISECONDS);

        // The scraper: PULL /metrics every 500ms
        TimeSeriesDb tsdb = new TimeSeriesDb();
        HttpClient http = HttpClient.newHttpClient();
        ScheduledExecutorService scraper = Executors.newSingleThreadScheduledExecutor();
        scraper.scheduleAtFixedRate(() -> {
            try {
                String body = http.send(HttpRequest.newBuilder()
                                .uri(URI.create("http://localhost:8081/metrics")).build(),
                        HttpResponse.BodyHandlers.ofString()).body();
                long now = System.currentTimeMillis();
                body.lines().forEach(line -> tsdb.ingest(line, now));
            } catch (Exception e) { System.out.println("  [scrape] target DOWN: up=0"); }
        }, 200, 500, TimeUnit.MILLISECONDS);

        // Let it run, then "query" like Grafana would
        Thread.sleep(4000);
        System.out.println("== PromQL-style queries after 4s ==");
        System.out.printf("  traffic:  rate(http_requests_total[3s])   = %.1f req/s%n",
                tsdb.rate("http_requests_total", 3000));
        double errRate = tsdb.rate("http_errors_total", 3000) /
                Math.max(0.001, tsdb.rate("http_requests_total", 3000));
        System.out.printf("  errors:   error ratio                     = %.2f%%%n", errRate * 100);

        long[] cumulative = new long[metrics.bucketBounds.length + 1];
        for (int i = 0; i < cumulative.length - 1; i++)
            cumulative[i] = (long) tsdb.latest("http_request_seconds_bucket{le=\"" + metrics.bucketBounds[i] + "\"}");
        cumulative[cumulative.length - 1] = (long) tsdb.latest("http_request_seconds_bucket{le=\"+Inf\"}");
        System.out.printf("  latency:  histogram_quantile(0.5)          = %.0f ms%n",
                histogramQuantile(0.5, metrics.bucketBounds, cumulative) * 1000);
        System.out.printf("  latency:  histogram_quantile(0.99)         = %.0f ms   <- from BUCKETS, not averages%n",
                histogramQuantile(0.99, metrics.bucketBounds, cumulative) * 1000);

        // Burn-rate alert: 99.9% SLO -> budget 0.001; fast-burn = 14.4x
        Thread.sleep(2000);   // error ramp has kicked in by now
        double burnErrRate = tsdb.rate("http_errors_total", 2000) /
                Math.max(0.001, tsdb.rate("http_requests_total", 2000));
        boolean fire = burnErrRate > 14.4 * 0.001;
        System.out.printf("%n== burn-rate alert (SLO 99.9%%) ==%n  error ratio %.2f%% vs threshold %.2f%% -> %s%n",
                burnErrRate * 100, 1.44, fire ? "PAGE! budget exhausting in hours" : "ok");

        traffic.shutdownNow(); scraper.shutdownNow(); app.stop(0);
    }
}
