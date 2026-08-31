import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;

/**
 * Single-node experiments. Everything here is measured, not asserted.
 *
 *   java src/Experiments.java
 *
 * Each experiment answers one question the README asks you to predict first.
 */
public class Experiments {

    static final long MS = Limiter.MICROS_PER_MS;
    static final long SEC = Limiter.MICROS_PER_SEC;

    public static void main(String[] args) {
        e1_worst_case_burst_per_algorithm();
        e2_memory_per_key();
        e3_policing_versus_shaping();
        e4_how_wrong_is_the_sliding_counter();
    }

    // =====================================================================
    // E1 — "The limit is 10 per second." How many can a client ACTUALLY get
    //      through in some one-second stretch? Two clients, same limit.
    // =====================================================================
    static void e1_worst_case_burst_per_algorithm() {
        header("E1", "Worst-case requests admitted in ANY 1-second window (limit = 10/s)");

        record Row(String label, Supplier<Limiter> make) {}
        List<Row> rows = List.of(
            new Row("fixed-window",        () -> new Algorithms.FixedWindow(10, SEC)),
            new Row("sliding-log",         () -> new Algorithms.SlidingLog(10, SEC)),
            new Row("sliding-counter",     () -> new Algorithms.SlidingCounter(10, SEC)),
            new Row("token-bucket b=10",   () -> Algorithms.TokenBucket.perWindow(10, SEC, 10)),
            new Row("token-bucket b=3",    () -> Algorithms.TokenBucket.perWindow(10, SEC, 3)),
            new Row("token-bucket b=1",    () -> Algorithms.TokenBucket.perWindow(10, SEC, 1)),
            new Row("gcra b=10",           () -> new Algorithms.Gcra(10, SEC, 10))
        );

        System.out.printf("%-20s %14s %14s %14s   %s%n",
                "algorithm", "greedy client", "boundary-timed", "WORST CASE", "verdict");
        System.out.printf("%-20s %14s %14s %14s%n",
                "", "(every 10 ms)", "(saves for flip)", "(max of both)");
        System.out.println("-".repeat(88));
        for (Row r : rows) {
            long greedy = maxInAnyWindow(run(r.make.get(), politeTrace()), SEC);
            long timed  = maxInAnyWindow(run(r.make.get(), attackerTrace(10)), SEC);
            long worst  = Math.max(greedy, timed);
            String verdict = worst <= 10 ? "holds the limit"
                           : String.format("%.1fx over", worst / 10.0);
            System.out.printf("%-20s %14d %14d %14d   %s%n", r.label, greedy, timed, worst, verdict);
        }
        System.out.println("""

            Two surprises live in this table.

            (1) Fixed window is beaten only by the boundary-timed client. A
                client that just hammers you never finds the hole; a client
                that read your blog post finds it immediately. Rate limiting
                is a security control, so the attacker column is the real one.

            (2) The token bucket ALSO goes over -- 19 in a second -- and it is
                not a bug. `burst` IS permission to exceed the average rate,
                and burst=limit means permission to double it. Lower the burst
                and the overshoot shrinks with it, at the cost of refusing
                legitimate spikes. There is no setting that gives you both.""");
    }

    /** A client that just tries constantly, every 10 ms, for 60 s. */
    static long[] politeTrace() {
        long[] t = new long[6000];
        for (int i = 0; i < t.length; i++) t[i] = i * 10 * MS;
        return t;
    }

    /**
     * A client that has read your rate-limiter's source. It waits until 1 ms
     * before the window flips, empties the window, then empties the next one.
     */
    static long[] attackerTrace(int limit) {
        List<Long> t = new ArrayList<>();
        for (int sec = 0; sec < 60; sec++) {
            for (int i = 0; i < limit; i++) t.add(sec * SEC + 999 * MS + i);
            for (int i = 0; i < limit; i++) t.add((sec + 1) * SEC + i);
        }
        return t.stream().mapToLong(Long::longValue).toArray();
    }

    static long[] run(Limiter l, long[] arrivals) {
        List<Long> admitted = new ArrayList<>();
        for (long t : arrivals) if (l.allow("m1", t).allowed()) admitted.add(t);
        return admitted.stream().mapToLong(Long::longValue).toArray();
    }

    /** Max number of admitted requests inside any window of `w` microseconds. */
    static long maxInAnyWindow(long[] admitted, long w) {
        long best = 0;
        int lo = 0;
        for (int hi = 0; hi < admitted.length; hi++) {
            while (admitted[hi] - admitted[lo] >= w) lo++;
            best = Math.max(best, hi - lo + 1);
        }
        return best;
    }

    // =====================================================================
    // E2 — What does each algorithm cost to REMEMBER? This is the number that
    //      decides whether you can rate-limit per user at 10 million users.
    // =====================================================================
    static void e2_memory_per_key() {
        header("E2", "State stored per key, and what that costs at scale");

        int[] rates = {10, 100, 1000};
        System.out.printf("%-18s  %s%n", "algorithm", "numbers stored per key at N req/window");
        System.out.printf("%-18s  %10s %10s %10s   %s%n", "", "10", "100", "1000", "bytes for 10M keys @1000");
        System.out.println("-".repeat(84));

        for (String algo : List.of("fixed-window", "sliding-log", "sliding-counter", "token-bucket", "gcra")) {
            StringBuilder row = new StringBuilder();
            long lastSize = 0;
            for (int r : rates) {
                Limiter l = make(algo, r);
                for (int i = 0; i < r; i++) l.allow("m1", i * (SEC / r));
                lastSize = l.stateSizeFor("m1");
                row.append(String.format("%10d ", lastSize));
            }
            long bytes = lastSize * 8L * 10_000_000L;
            System.out.printf("%-18s  %s  %,18d  (%s)%n", algo, row, bytes, human(bytes));
        }
        System.out.println("""
            
            The sliding log is the only correct-by-construction algorithm here,
            and it is the only one whose memory depends on TRAFFIC rather than
            on the number of keys. That is the trade: perfect accuracy costs you
            a timestamp per request, forever, for every key.""");
    }

    static Limiter make(String algo, int limit) {
        return switch (algo) {
            case "fixed-window" -> new Algorithms.FixedWindow(limit, SEC);
            case "sliding-log" -> new Algorithms.SlidingLog(limit, SEC);
            case "sliding-counter" -> new Algorithms.SlidingCounter(limit, SEC);
            case "token-bucket" -> Algorithms.TokenBucket.perWindow(limit, SEC, limit);
            case "gcra" -> new Algorithms.Gcra(limit, SEC, limit);
            default -> throw new IllegalArgumentException(algo);
        };
    }

    static String human(long bytes) {
        if (bytes > 1L << 30) return String.format("%.1f GB", bytes / (double) (1L << 30));
        return String.format("%.1f MB", bytes / (double) (1L << 20));
    }

    // =====================================================================
    // E3 — 200 clients wake up at the same millisecond (a cron job, a mobile
    //      app push, a retry storm). Policer vs shaper: same limit, very
    //      different thing happens to your database.
    // =====================================================================
    static void e3_policing_versus_shaping() {
        header("E3", "A 200-request thundering herd hits a 50/s limit");

        var bucket = Algorithms.TokenBucket.perWindow(50, SEC, 50);
        int admittedImmediately = 0, rejected = 0;
        for (int i = 0; i < 200; i++) {
            if (bucket.allow("m1", 0).allowed()) admittedImmediately++; else rejected++;
        }
        System.out.printf("token bucket (POLICER, burst 50):%n");
        System.out.printf("   at t=0ms: %d admitted in the same instant, %d rejected outright%n",
                admittedImmediately, rejected);
        System.out.printf("   -> your database sees a %dx spike over the steady rate, and %d users see an error%n",
                admittedImmediately, rejected);

        var queue = new Algorithms.LeakyBucketQueue(200, SEC / 50);
        long lastRelease = 0; int queued = 0, dropped = 0;
        for (int i = 0; i < 200; i++) {
            long r = queue.offer(0);
            if (r < 0) dropped++; else { queued++; lastRelease = Math.max(lastRelease, r); }
        }
        System.out.printf("%nleaky bucket (SHAPER, queue 200):%n");
        System.out.printf("   at t=0ms: %d accepted, %d dropped; last one is forwarded at t=%d ms%n",
                queued, dropped, lastRelease / MS);
        System.out.printf("   -> your database sees a perfectly flat 50/s, and 0 users see an error,%n");
        System.out.printf("      but the unluckiest user waits %.1f seconds for a response%n",
                lastRelease / (double) SEC);
        System.out.println("""
            
            Neither is right. A policer protects the backend and pushes the
            problem onto the client (who must retry). A shaper protects the
            client and pushes the problem into latency and memory (a queue is
            unbounded latency wearing a disguise). Payments APIs police the
            public edge and shape their outbound calls to card networks --
            because the network will ban you for bursting, and you cannot ask
            a card network to retry.""");
    }

    // =====================================================================
    // E4 — The sliding-window counter is an approximation. Approximations are
    //      fine when you know the size and the DIRECTION of the error.
    // =====================================================================
    static void e4_how_wrong_is_the_sliding_counter() {
        header("E4", "Sliding counter vs the exact sliding log (limit = 100/s)");
        System.out.println("""
            Method note: you cannot compare these limiters request-by-request.
            The moment their decisions differ their internal state differs, and
            every later comparison is measuring drift, not error. So each one is
            run independently on the same arrivals, and judged on the two things
            that actually matter: did it ever break the promise (max admitted in
            any true 1-second window), and how much quota did it waste.
            """);

        System.out.printf("%-26s %10s %10s %10s %10s %10s%n",
                "traffic shape", "log max", "cnt max", "fixed max", "log total", "cnt total");
        System.out.println("-".repeat(82));
        compare("uniform (every 5 ms)", uniformTrace());
        compare("edge-burn, then steady", edgeThenSteadyTrace());
        compare("bursty at window starts", startLoadedTrace());
        compare("random poisson-ish", randomTrace());
        System.out.println("""

            `log max` is 100 in every row by construction -- that is what exact
            means. Read `cnt max` against it: where it exceeds 100 the counter
            let through requests it promised it would not, and the amount is the
            error you are buying. Read `cnt total` against `log total` for the
            other half of the bill: quota the counter refused to spend.

            The error has a direction, not just a size. The counter assumes the
            previous window's traffic was spread evenly. Load the END of a
            window and it under-counts, so it over-admits. Load the START and it
            over-counts, so it refuses requests it should have allowed.
            Cloudflare published their version of this number: ~0.003% of
            requests handled wrongly, which they accepted -- because the
            alternative was a timestamp per request, forever (see E2).""");
    }

    static void compare(String label, long[] arrivals) {
        var log = new Algorithms.SlidingLog(100, SEC);
        var counter = new Algorithms.SlidingCounter(100, SEC);
        var fixed = new Algorithms.FixedWindow(100, SEC);
        long[] logAdmitted = run(log, arrivals);
        long[] cntAdmitted = run(counter, arrivals);
        long[] fixAdmitted = run(fixed, arrivals);
        System.out.printf("%-26s %10d %10d %10d %10d %10d%n", label,
                maxInAnyWindow(logAdmitted, SEC),
                maxInAnyWindow(cntAdmitted, SEC),
                maxInAnyWindow(fixAdmitted, SEC),
                logAdmitted.length, cntAdmitted.length);
    }

    static long[] uniformTrace() {
        long[] t = new long[12000];
        for (int i = 0; i < t.length; i++) t[i] = i * 5 * MS;
        return t;
    }

    /**
     * The trace built specifically to break the sliding-window counter. Burn the
     * whole window's quota in its last millisecond, then keep trying steadily
     * through the next window. The counter believes those 100 requests were
     * spread evenly across the previous second and discounts them linearly --
     * but in reality every one of them is still inside the true sliding window.
     */
    static long[] edgeThenSteadyTrace() {
        List<Long> t = new ArrayList<>();
        for (long ms = 0; ms < 60_000; ms++) {
            if (ms % 1000 == 999) { for (int i = 0; i < 120; i++) t.add(ms * MS + i); }
            else if (ms % 5 == 0) t.add(ms * MS);
        }
        return t.stream().mapToLong(Long::longValue).toArray();
    }

    static long[] startLoadedTrace() { // all traffic in the first 100 ms of each second
        List<Long> t = new ArrayList<>();
        for (int s = 0; s < 60; s++) for (int i = 0; i < 200; i++) t.add(s * SEC + i * 500);
        return t.stream().mapToLong(Long::longValue).toArray();
    }

    static long[] randomTrace() {
        Random rnd = new Random(42);
        long[] t = new long[12000];
        long now = 0;
        for (int i = 0; i < t.length; i++) { now += rnd.nextInt(10) * MS; t[i] = now; }
        return t;
    }

    static void header(String id, String title) {
        System.out.println("\n" + "=".repeat(78));
        System.out.println(id + " -- " + title);
        System.out.println("=".repeat(78));
    }
}
