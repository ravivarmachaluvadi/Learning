/*
 * TESTS — executable documentation of what the Java Memory Model does and does
 * not promise. Run with:   ./run.sh Tests
 *
 * Every test is named after the scenario it describes, so the output reads as a
 * list of claims about the model.
 *
 * Tests come in two kinds, and the difference matters:
 *
 *   [ASSERT]  a SAFETY property -- the model forbids the bad outcome, so seeing
 *             it even once is a genuine failure. These are real tests.
 *
 *   [OBSERVE] a BROKEN property -- the model ALLOWS the bad outcome but does not
 *             require it. A run where the bug fails to appear proves nothing, so
 *             asserting on it would be a lie. These report what happened instead.
 *
 * That split is the whole lesson of this lab in miniature: you can test that a
 * guarantee holds, but you can never test that a race is absent.
 */
public class Tests {

    static int passed, failed, observed;

    public static void main(String[] args) throws Exception {
        System.out.println("=== JMM TEST SUITE ===================================================");
        System.out.println("cpu: " + System.getProperty("os.arch")
                + " | cores: " + Runtime.getRuntime().availableProcessors()
                + " | jvm: " + System.getProperty("java.vm.info"));
        System.out.println();

        volatile_flag_is_always_visible_to_a_spinning_reader();
        plain_flag_may_never_become_visible_at_all();
        volatile_ints_forbid_the_impossible_00_outcome();
        plain_ints_permit_the_impossible_00_outcome();
        synchronized_increment_loses_nothing();
        atomic_increment_loses_nothing();
        unsynchronized_increment_loses_updates();
        volatile_increment_still_loses_updates();
        volatile_write_publishes_the_plain_writes_before_it();

        System.out.println();
        System.out.println("---------------------------------------------------------------------");
        System.out.printf("  %d assertions passed, %d failed, %d observations recorded%n",
                passed, failed, observed);
        System.out.println("======================================================================");
        if (failed > 0) System.exit(1);
    }

    // =================================================================
    // 1. SAFETY: a volatile write always reaches a spinning reader.
    // =================================================================
    static volatile boolean t1Flag = false;

    static void volatile_flag_is_always_visible_to_a_spinning_reader() throws Exception {
        Thread w = new Thread(() -> { while (!t1Flag) { } });
        w.setDaemon(true);
        w.start();
        Thread.sleep(400);           // let the JIT compile the loop
        t1Flag = true;
        w.join(3000);
        assertTrue("volatile_flag_is_always_visible_to_a_spinning_reader",
                !w.isAlive(),
                "reader exited its loop after the volatile write");
    }

    // =================================================================
    // 2. BROKEN: a plain write is allowed never to arrive.
    // =================================================================
    static boolean t2Flag = false;

    static void plain_flag_may_never_become_visible_at_all() throws Exception {
        Thread w = new Thread(() -> { long n = 0; while (!t2Flag) { n++; } t2Spins = n; });
        w.setDaemon(true);
        w.start();
        Thread.sleep(400);
        t2Flag = true;
        w.join(2500);
        boolean stuck = w.isAlive();
        observe("plain_flag_may_never_become_visible_at_all",
                stuck ? "reader NEVER saw the write -- still spinning after 2.5s (load hoisted out of the loop)"
                      : "reader happened to see the write this time (" + t2Spins + " spins) -- luck, not a guarantee");
        // NOTE: if stuck, that daemon thread spins for the rest of the suite.
        // There is no safe way to stop it -- Thread.stop was removed in Java 20.
    }
    static long t2Spins;

    // =================================================================
    // 3/4. The Dekker test: is r1 == 0 && r2 == 0 possible?
    // =================================================================
    static int x, y, r1, r2;
    static volatile int vx, vy;
    static volatile long tA, tB;

    static void volatile_ints_forbid_the_impossible_00_outcome() throws Exception {
        long impossible = dekker(true, 500_000);
        assertTrue("volatile_ints_forbid_the_impossible_00_outcome",
                impossible == 0,
                "(0,0) occurred " + impossible + " times in 500,000 trials; the model allows 0");
    }

    static void plain_ints_permit_the_impossible_00_outcome() throws Exception {
        long impossible = dekker(false, 500_000);
        observe("plain_ints_permit_the_impossible_00_outcome",
                impossible > 0
                    ? "(0,0) occurred " + String.format("%,d", impossible)
                        + " times in 500,000 trials -- an outcome no interleaving can produce"
                    : "(0,0) did not occur this run; it is still permitted, just not exercised");
    }

    /** Runs the two-thread store/load test and returns how often (0,0) was seen. */
    static long dekker(boolean useVolatile, int trials) throws Exception {
        final long[] zeroZero = new long[1];
        tA = 0; tB = 0;

        Thread a = new Thread(() -> {
            long t = 0;
            for (int i = 0; i < trials; i++) {
                tA = ++t; while (tB < t) Thread.onSpinWait();
                if (useVolatile) { vx = 1; r1 = vy; } else { x = 1; r1 = y; }
                tA = ++t; while (tB < t) Thread.onSpinWait();
                if (r1 == 0 && r2 == 0) zeroZero[0]++;
                if (useVolatile) { vx = 0; vy = 0; } else { x = 0; y = 0; }
                r1 = 0; r2 = 0;
            }
        });
        Thread b = new Thread(() -> {
            long t = 0;
            for (int i = 0; i < trials; i++) {
                tB = ++t; while (tA < t) Thread.onSpinWait();
                if (useVolatile) { vy = 1; r2 = vx; } else { y = 1; r2 = x; }
                tB = ++t; while (tA < t) Thread.onSpinWait();
            }
        });
        a.start(); b.start(); a.join(); b.join();
        return zeroZero[0];
    }

    // =================================================================
    // 5-8. Atomicity of read-modify-write.
    // =================================================================
    static final int N = 8, PER = 500_000;
    static final long EXPECTED = (long) N * PER;
    static final Object lock = new Object();
    static long plainC, syncC;
    static volatile long volatileC;
    static final java.util.concurrent.atomic.AtomicLong atomicC =
            new java.util.concurrent.atomic.AtomicLong();

    static void synchronized_increment_loses_nothing() throws Exception {
        syncC = 0;
        race(() -> { for (int i = 0; i < PER; i++) synchronized (lock) { syncC++; } });
        assertTrue("synchronized_increment_loses_nothing",
                syncC == EXPECTED,
                String.format("counted %,d of %,d", syncC, EXPECTED));
    }

    static void atomic_increment_loses_nothing() throws Exception {
        atomicC.set(0);
        race(() -> { for (int i = 0; i < PER; i++) atomicC.incrementAndGet(); });
        assertTrue("atomic_increment_loses_nothing",
                atomicC.get() == EXPECTED,
                String.format("counted %,d of %,d", atomicC.get(), EXPECTED));
    }

    static void unsynchronized_increment_loses_updates() throws Exception {
        plainC = 0;
        race(() -> { for (int i = 0; i < PER; i++) plainC++; });
        observe("unsynchronized_increment_loses_updates",
                String.format("counted %,d of %,d -- %,d increments vanished (%.1f%%)",
                        plainC, EXPECTED, EXPECTED - plainC,
                        100.0 * (EXPECTED - plainC) / EXPECTED));
    }

    static void volatile_increment_still_loses_updates() throws Exception {
        volatileC = 0;
        race(() -> { for (int i = 0; i < PER; i++) volatileC++; });
        observe("volatile_increment_still_loses_updates",
                String.format("counted %,d of %,d -- volatile fixes visibility, not atomicity",
                        volatileC, EXPECTED));
    }

    static void race(Runnable body) throws Exception {
        Thread[] ts = new Thread[N];
        for (int i = 0; i < N; i++) ts[i] = new Thread(body);
        for (Thread t : ts) t.start();
        for (Thread t : ts) t.join();
    }

    // =================================================================
    // 9. SAFETY: the happens-before piggyback rule.
    //    Plain writes made BEFORE a volatile write are visible to any thread
    //    that reads that volatile and sees the new value.
    // =================================================================
    static int p1, p2, p3;               // plain payload
    static volatile boolean ready;       // the gate

    static void volatile_write_publishes_the_plain_writes_before_it() throws Exception {
        final int rounds = 200_000;
        final long[] torn = new long[1];
        tA = 0; tB = 0;

        Thread writer = new Thread(() -> {
            long t = 0;
            for (int i = 1; i <= rounds; i++) {
                tA = ++t; while (tB < t) Thread.onSpinWait();
                p1 = i; p2 = i; p3 = i;     // plain writes
                ready = true;               // volatile write: publishes all three
                tA = ++t; while (tB < t) Thread.onSpinWait();
                ready = false;
            }
        });
        Thread reader = new Thread(() -> {
            long t = 0;
            for (int i = 1; i <= rounds; i++) {
                tB = ++t; while (tA < t) Thread.onSpinWait();
                for (int s = 0; s < 5_000; s++) {
                    if (ready) {                        // volatile read
                        if (p1 != i || p2 != i || p3 != i) torn[0]++;
                        break;
                    }
                    Thread.onSpinWait();
                }
                tB = ++t; while (tA < t) Thread.onSpinWait();
            }
        });
        writer.start(); reader.start(); writer.join(); reader.join();

        assertTrue("volatile_write_publishes_the_plain_writes_before_it",
                torn[0] == 0,
                "reader saw ready==true with stale payload " + torn[0] + " times in "
                        + String.format("%,d", rounds) + " rounds; the model allows 0");
    }

    // =================================================================
    // tiny harness
    // =================================================================
    static void assertTrue(String name, boolean ok, String detail) {
        if (ok) { passed++; System.out.printf("  [ASSERT ] PASS  %s%n            %s%n", name, detail); }
        else    { failed++; System.out.printf("  [ASSERT ] FAIL  %s%n            %s%n", name, detail); }
    }

    static void observe(String name, String detail) {
        observed++;
        System.out.printf("  [OBSERVE]       %s%n            %s%n", name, detail);
    }
}
