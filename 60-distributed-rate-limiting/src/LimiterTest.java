import java.util.Random;

/**
 * Tests that double as executable documentation.
 *
 *   java src/LimiterTest.java
 *
 * Each test is named after a claim made in README.md. Two of them
 * (fixed_window_*) assert a BUG on purpose — the whole story of this lab
 * starts from a limiter that is wrong in a way you can predict and exploit.
 */
public class LimiterTest {

    static int passed = 0, failed = 0;
    static final long SEC = Limiter.MICROS_PER_SEC;
    static final long MS = Limiter.MICROS_PER_MS;

    public static void main(String[] args) {
        System.out.println("Running LimiterTest\n");

        fixed_window_allows_exactly_the_limit_inside_one_window();
        fixed_window_allows_double_the_limit_across_a_boundary();
        sliding_log_never_allows_more_than_the_limit_in_any_window();
        sliding_counter_blocks_the_boundary_burst_that_fixed_window_allows();
        token_bucket_refills_continuously_not_in_steps();
        token_bucket_burst_is_capped_at_capacity();
        leaky_bucket_is_token_bucket_inverted();
        gcra_makes_the_same_decisions_as_token_bucket_in_one_number();
        keys_are_independent();
        cost_weighted_requests_consume_more_quota();
        retry_after_is_honest_for_every_limiter();
        sliding_log_memory_grows_with_traffic_others_do_not();

        System.out.printf("%n%d passed, %d failed%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    // ------------------------------------------------------------------ 1
    static void fixed_window_allows_exactly_the_limit_inside_one_window() {
        var l = new Algorithms.FixedWindow(5, SEC);
        int allowed = 0;
        for (int i = 0; i < 8; i++) if (l.allow("m1", 100 * MS * i % SEC).allowed()) allowed++;
        check("fixed_window_allows_exactly_the_limit_inside_one_window", allowed == 5,
              "allowed=" + allowed + " expected 5");
    }

    // ------------------------------------------------------------------ 2
    // The famous one. Limit is "5 per second". We send 10 inside 2 milliseconds
    // and every single one is allowed, because the counter resets between them.
    static void fixed_window_allows_double_the_limit_across_a_boundary() {
        var l = new Algorithms.FixedWindow(5, SEC);
        int allowed = 0;
        for (int i = 0; i < 5; i++) if (l.allow("m1", 999 * MS).allowed()) allowed++;   // 0.999s
        for (int i = 0; i < 5; i++) if (l.allow("m1", 1000 * MS).allowed()) allowed++;  // 1.000s
        check("fixed_window_allows_double_the_limit_across_a_boundary", allowed == 10,
              "allowed=" + allowed + " expected 10 (the bug)");
    }

    // ------------------------------------------------------------------ 3
    static void sliding_log_never_allows_more_than_the_limit_in_any_window() {
        var l = new Algorithms.SlidingLog(5, SEC);
        int allowed = 0;
        for (int i = 0; i < 5; i++) if (l.allow("m1", 999 * MS).allowed()) allowed++;
        for (int i = 0; i < 5; i++) if (l.allow("m1", 1000 * MS).allowed()) allowed++;
        check("sliding_log_never_allows_more_than_the_limit_in_any_window", allowed == 5,
              "allowed=" + allowed + " expected 5");
    }

    // ------------------------------------------------------------------ 4
    static void sliding_counter_blocks_the_boundary_burst_that_fixed_window_allows() {
        var l = new Algorithms.SlidingCounter(5, SEC);
        int allowed = 0;
        for (int i = 0; i < 5; i++) if (l.allow("m1", 999 * MS).allowed()) allowed++;
        for (int i = 0; i < 5; i++) if (l.allow("m1", 1000 * MS).allowed()) allowed++;
        // estimate at t=1.000s is prev(5) * weight(1.0) + curr(0) = 5 -> already at limit
        check("sliding_counter_blocks_the_boundary_burst_that_fixed_window_allows", allowed == 5,
              "allowed=" + allowed + " expected 5");
    }

    // ------------------------------------------------------------------ 5
    static void token_bucket_refills_continuously_not_in_steps() {
        var l = Algorithms.TokenBucket.perWindow(10, SEC, 10); // 10/s, burst 10
        for (int i = 0; i < 10; i++) l.allow("m1", 0);          // drain the bucket
        boolean deniedImmediately = !l.allow("m1", 0).allowed();
        boolean allowedAfter100ms = l.allow("m1", 100 * MS).allowed(); // exactly 1 token back
        boolean deniedAfter100msTwice = !l.allow("m1", 100 * MS).allowed();
        check("token_bucket_refills_continuously_not_in_steps",
              deniedImmediately && allowedAfter100ms && deniedAfter100msTwice,
              deniedImmediately + "/" + allowedAfter100ms + "/" + deniedAfter100msTwice);
    }

    // ------------------------------------------------------------------ 6
    static void token_bucket_burst_is_capped_at_capacity() {
        var l = Algorithms.TokenBucket.perWindow(10, SEC, 10);
        // Idle for an hour. Tokens must NOT accumulate to 36,000.
        int allowed = 0;
        for (int i = 0; i < 50; i++) if (l.allow("m1", 3600 * SEC).allowed()) allowed++;
        check("token_bucket_burst_is_capped_at_capacity", allowed == 10,
              "allowed=" + allowed + " expected 10 (capacity), not 36000");
    }

    // ------------------------------------------------------------------ 7
    // Same machine, mirrored: tokens-remaining == capacity-minus-water-level.
    static void leaky_bucket_is_token_bucket_inverted() {
        var tb = Algorithms.TokenBucket.perWindow(20, SEC, 20);
        var lb = new Algorithms.LeakyBucketMeter(20, 20.0 / SEC);
        Random rnd = new Random(7);
        long now = 0;
        boolean same = true;
        for (int i = 0; i < 2000; i++) {
            now += rnd.nextInt(120) * MS;
            if (tb.allow("m1", now).allowed() != lb.allow("m1", now).allowed()) { same = false; break; }
        }
        check("leaky_bucket_is_token_bucket_inverted", same, "decisions diverged");
    }

    // ------------------------------------------------------------------ 8
    static void gcra_makes_the_same_decisions_as_token_bucket_in_one_number() {
        var tb = Algorithms.TokenBucket.perWindow(100, SEC, 100);
        var gcra = new Algorithms.Gcra(100, SEC, 100);
        Random rnd = new Random(11);
        long now = 0;
        int divergences = 0;
        for (int i = 0; i < 5000; i++) {
            now += rnd.nextInt(20) * MS;
            if (tb.allow("m1", now).allowed() != gcra.allow("m1", now).allowed()) divergences++;
        }
        boolean cheaper = gcra.stateSizeFor("m1") < tb.stateSizeFor("m1");
        check("gcra_makes_the_same_decisions_as_token_bucket_in_one_number",
              divergences == 0 && cheaper, "divergences=" + divergences + " cheaper=" + cheaper);
    }

    // ------------------------------------------------------------------ 9
    static void keys_are_independent() {
        var l = Algorithms.TokenBucket.perWindow(3, SEC, 3);
        for (int i = 0; i < 3; i++) l.allow("merchant_a", 0);
        boolean aBlocked = !l.allow("merchant_a", 0).allowed();
        boolean bFine = l.allow("merchant_b", 0).allowed();
        check("keys_are_independent", aBlocked && bFine, aBlocked + "/" + bFine);
    }

    // ----------------------------------------------------------------- 10
    static void cost_weighted_requests_consume_more_quota() {
        var l = Algorithms.TokenBucket.perWindow(100, SEC, 100);
        boolean bulkOk = l.allow("m1", 60, 0).allowed();       // one bulk report = 60 units
        boolean secondBulkDenied = !l.allow("m1", 60, 0).allowed();
        boolean smallStillOk = l.allow("m1", 1, 0).allowed();  // a cheap call still fits
        check("cost_weighted_requests_consume_more_quota",
              bulkOk && secondBulkDenied && smallStillOk,
              bulkOk + "/" + secondBulkDenied + "/" + smallStillOk);
    }

    // ----------------------------------------------------------------- 11
    // If we tell a client "retry after 340 ms" and they obey exactly, the retry
    // MUST succeed. A limiter that lies here causes retry storms.
    static void retry_after_is_honest_for_every_limiter() {
        Limiter[] limiters = {
            new Algorithms.FixedWindow(5, SEC),
            new Algorithms.SlidingLog(5, SEC),
            new Algorithms.SlidingCounter(5, SEC),
            Algorithms.TokenBucket.perWindow(5, SEC, 5),
            new Algorithms.LeakyBucketMeter(5, 5.0 / SEC),
            new Algorithms.Gcra(5, SEC, 5),
        };
        StringBuilder bad = new StringBuilder();
        for (Limiter l : limiters) {
            long now = 0;
            for (int i = 0; i < 200; i++) {
                now += 37 * MS;
                Limiter.Decision d = l.allow("m1", now);
                if (!d.allowed()) {
                    long retryAt = now + d.retryAfterMicros();
                    if (!l.allow("m1", retryAt).allowed()) {
                        bad.append(l.name()).append(" lied at t=").append(now).append("; ");
                    }
                    now = retryAt;
                }
            }
        }
        check("retry_after_is_honest_for_every_limiter", bad.isEmpty(), bad.toString());
    }

    // ----------------------------------------------------------------- 12
    static void sliding_log_memory_grows_with_traffic_others_do_not() {
        var log = new Algorithms.SlidingLog(10_000, SEC);
        var bucket = Algorithms.TokenBucket.perWindow(10_000, SEC, 10_000);
        for (int i = 0; i < 5_000; i++) { log.allow("m1", i); bucket.allow("m1", i); }
        long logState = log.stateSizeFor("m1");
        long bucketState = bucket.stateSizeFor("m1");
        check("sliding_log_memory_grows_with_traffic_others_do_not",
              logState == 5_000 && bucketState == 2,
              "log=" + logState + " numbers, bucket=" + bucketState + " numbers");
    }

    static void check(String name, boolean ok, String detail) {
        if (ok) { passed++; System.out.println("PASS  " + name); }
        else { failed++; System.out.println("FAIL  " + name + "  -- " + detail); }
    }
}
