import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.*;

/**
 * LinuxToolbox — the core shell idioms re-implemented in Java, so each
 * pipeline stage stops being folklore:
 *
 *   grep            -> filter(line matches pattern)
 *   sort|uniq -c    -> groupingBy + counting
 *   sort -rn|head   -> sorted(desc) + limit
 *   tail -f         -> poll the file for appended bytes
 *   awk '$9>=500'   -> split columns, filter, aggregate (p99!)
 *
 * Plus: a ProcessBuilder runner — how Java services actually shell out
 * (deploy hooks, health checks) with timeouts and captured output.
 *
 * Compile & run:  javac LinuxToolbox.java && java LinuxToolbox
 */
public class LinuxToolbox {

    // ---------- grep ----------
    static List<String> grep(List<String> lines, String regex) {
        Pattern p = Pattern.compile(regex);
        return lines.stream().filter(l -> p.matcher(l).find()).toList();
    }

    // ---------- sort | uniq -c | sort -rn | head ----------
    static List<Map.Entry<String, Long>> topN(Stream<String> keys, int n) {
        return keys.collect(Collectors.groupingBy(k -> k, Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(n).toList();
    }

    // ---------- awk-style access log analysis ----------
    // log format: METHOD PATH STATUS LATENCY_MS  (columns like awk's $1..$4)
    record LogLine(String method, String path, int status, int latencyMs) {
        static LogLine parse(String raw) {
            String[] f = raw.trim().split("\\s+");
            return new LogLine(f[0], f[1], Integer.parseInt(f[2]), Integer.parseInt(f[3]));
        }
    }

    static void analyzeAccessLog(List<String> raw) {
        List<LogLine> lines = raw.stream().map(LogLine::parse).toList();

        System.out.println("  top endpoints by 5xx count   (awk '$3>=500' | sort | uniq -c | sort -rn):");
        topN(lines.stream().filter(l -> l.status() >= 500).map(LogLine::path), 3)
                .forEach(e -> System.out.printf("    %4d  %s%n", e.getValue(), e.getKey()));

        System.out.println("  p50/p99 latency per endpoint (histogram thinking, not averages!):");
        lines.stream().collect(Collectors.groupingBy(LogLine::path)).forEach((path, ls) -> {
            int[] sorted = ls.stream().mapToInt(LogLine::latencyMs).sorted().toArray();
            System.out.printf("    %-16s p50=%4dms  p99=%4dms  (n=%d)%n", path,
                    sorted[sorted.length / 2], sorted[(int) (sorted.length * 0.99)], sorted.length);
        });
    }

    // ---------- tail -f ----------
    static void tailF(Path file, int iterations) throws Exception {
        long pos = Files.size(file);
        System.out.println("  tail -f " + file + " (watching for appends)...");
        for (int i = 0; i < iterations; i++) {
            Thread.sleep(120);
            long size = Files.size(file);
            if (size > pos) {                                   // new bytes appended
                try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
                    raf.seek(pos);
                    String line;
                    while ((line = raf.readLine()) != null) System.out.println("    >> " + line);
                }
                pos = size;
            }
        }
    }

    // ---------- ProcessBuilder: shelling out properly ----------
    record CommandResult(int exitCode, String stdout, String stderr) {}

    static CommandResult run(long timeoutSec, String... command) throws Exception {
        Process p = new ProcessBuilder(command).start();
        String out, err;
        try (var o = p.getInputStream(); var e = p.getErrorStream()) {
            out = new String(o.readAllBytes());
            err = new String(e.readAllBytes());
        }
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {         // ALWAYS bound the wait
            p.destroyForcibly();
            return new CommandResult(-1, out, "TIMEOUT after " + timeoutSec + "s");
        }
        return new CommandResult(p.exitValue(), out, err);
    }

    // ---------- demo ----------
    public static void main(String[] args) throws Exception {
        List<String> appLog = List.of(
                "2026-07-08 10:00:01 INFO  startup complete",
                "2026-07-08 10:00:05 ERROR db timeout on /api/orders trace=abc123",
                "2026-07-08 10:00:06 ERROR db timeout on /api/orders trace=def456",
                "2026-07-08 10:00:07 WARN  slow query 1200ms",
                "2026-07-08 10:00:09 ERROR redis connection refused trace=ghi789");

        System.out.println("== grep ERROR app.log ==");
        grep(appLog, "ERROR").forEach(l -> System.out.println("  " + l));

        System.out.println("\n== top error types (sort|uniq -c|sort -rn) ==");
        topN(grep(appLog, "ERROR").stream().map(l -> l.replaceAll(".*ERROR\\s+(\\w+ \\w+).*", "$1")), 5)
                .forEach(e -> System.out.printf("  %4d  %s%n", e.getValue(), e.getKey()));

        System.out.println("\n== access log analysis ==");
        List<String> accessLog = new ArrayList<>();
        Random r = new Random(42);
        for (int i = 0; i < 1000; i++) {
            String path = List.of("/api/orders", "/api/users", "/health").get(r.nextInt(3));
            int status = r.nextInt(100) < (path.equals("/api/orders") ? 8 : 1) ? 503 : 200;
            int latency = path.equals("/api/orders") && r.nextInt(100) < 5 ? 900 + r.nextInt(600)
                                                                           : 20 + r.nextInt(60);
            accessLog.add("GET " + path + " " + status + " " + latency);
        }
        analyzeAccessLog(accessLog);

        System.out.println("\n== tail -f on a live file ==");
        Path tmp = Files.createTempFile("app", ".log");
        Files.writeString(tmp, "old line\n");
        Thread writer = new Thread(() -> {
            try {
                Thread.sleep(200);
                Files.writeString(tmp, "NEW: request completed in 45ms\n", StandardOpenOption.APPEND);
                Thread.sleep(200);
                Files.writeString(tmp, "NEW: ERROR something broke\n", StandardOpenOption.APPEND);
            } catch (Exception ignored) {}
        });
        writer.start();
        tailF(tmp, 5);
        writer.join();
        Files.delete(tmp);

        System.out.println("\n== ProcessBuilder: run real commands with a timeout ==");
        String os = System.getProperty("os.name").toLowerCase();
        CommandResult res = os.contains("win")
                ? run(5, "cmd", "/c", "echo hello from the shell")
                : run(5, "sh", "-c", "echo hello from the shell && uname -a");
        System.out.println("  exit=" + res.exitCode() + " stdout: " + res.stdout().trim());
    }
}
