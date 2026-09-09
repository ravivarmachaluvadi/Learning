/*
 * EXPERIMENT 2 — REORDERING (the "impossible" outcome)
 * ====================================================
 *
 * Two shared variables, x and y, both starting at 0.
 * Two threads run at the same time:
 *
 *      Thread A            Thread B
 *      --------            --------
 *      x = 1;              y = 1;
 *      r1 = y;             r2 = x;
 *
 * Now reason about it the way everyone naturally does -- assume the four
 * statements get shuffled together in SOME order, but each thread's two
 * statements stay in the order written. Enumerate every interleaving:
 *
 *      A fully first        -> r1 = 0, r2 = 1
 *      B fully first        -> r1 = 1, r2 = 0
 *      interleaved          -> r1 = 1, r2 = 1
 *
 * There is no interleaving that produces r1 = 0 AND r2 = 0. For both threads
 * to read 0, both threads must have read BEFORE the other wrote -- but each
 * thread wrote before it read. It is a contradiction.
 *
 * This experiment counts how often (0,0) happens anyway.
 *
 *   java -cp out Reordering plain       -> (0,0) does occur
 *   java -cp out Reordering volatile    -> (0,0) must never occur
 *   java -Dtrials=5000000 -cp out Reordering plain
 *
 * Why does it happen? Your CPU core does not write to memory directly. It
 * writes into a private "store buffer" and moves on immediately, so it does
 * not have to wait ~100 cycles for the cache line. The store of x=1 is sitting
 * in A's store buffer, invisible to B, while A is already reading y.
 * Symmetrically for B. Both read the old value: 0 and 0.
 */
public class Reordering {

    /** Number of independent trials. Override with -Dtrials=N */
    static final int TRIALS = Integer.getInteger("trials", 1_000_000);

    // ---- the shared variables under test: plain, no synchronisation ----
    static int x, y;

    // ---- the same variables, declared volatile ----
    static volatile int vx, vy;

    // ---- where each thread parks the value it read ----
    static int r1, r2;

    /*
     * A two-thread barrier. Each thread publishes a monotonically increasing
     * "ticket" and spins until its partner reaches the same ticket.
     *
     * These two fields are volatile, which is what makes the harness itself
     * correct: thread B writes r2 (a plain field) and then writes ticketB
     * (volatile). Thread A reads ticketB (volatile) and then reads r2. That
     * volatile write -> volatile read pair is exactly the "happens-before"
     * handshake described in the README -- it drags the plain write of r2
     * along with it. The harness is a working example of the fix.
     */
    static volatile long ticketA, ticketB;

    public static void main(String[] args) throws Exception {
        final boolean useVolatile = args.length > 0 && args[0].equalsIgnoreCase("volatile");

        System.out.println("=== EXPERIMENT 2: REORDERING =========================================");
        System.out.println("variables x,y    : " + (useVolatile ? "volatile int" : "plain int"));
        System.out.println("trials           : " + String.format("%,d", TRIALS));
        System.out.println("cpu              : " + System.getProperty("os.arch")
                + ", " + Runtime.getRuntime().availableProcessors() + " logical cores");
        System.out.println("jvm mode         : " + System.getProperty("java.vm.info"));
        System.out.println();

        // outcomes[r1 * 2 + r2]
        final long[] outcomes = new long[4];

        Thread a = new Thread(() -> {
            long t = 0;
            for (int i = 0; i < TRIALS; i++) {
                // ---- barrier: both threads at the starting line ----
                ticketA = ++t;
                while (ticketB < t) { Thread.onSpinWait(); }

                // ---- the measurement ----
                if (useVolatile) { vx = 1; r1 = vy; }
                else             {  x = 1; r1 =  y; }

                // ---- barrier: both threads done ----
                ticketA = ++t;
                while (ticketB < t) { Thread.onSpinWait(); }

                // ---- thread A alone records the outcome and resets ----
                outcomes[r1 * 2 + r2]++;
                if (useVolatile) { vx = 0; vy = 0; } else { x = 0; y = 0; }
                r1 = 0;
                r2 = 0;
            }
        }, "A");

        Thread b = new Thread(() -> {
            long t = 0;
            for (int i = 0; i < TRIALS; i++) {
                ticketB = ++t;
                while (ticketA < t) { Thread.onSpinWait(); }

                if (useVolatile) { vy = 1; r2 = vx; }
                else             {  y = 1; r2 =  x; }

                ticketB = ++t;
                while (ticketA < t) { Thread.onSpinWait(); }
            }
        }, "B");

        long start = System.nanoTime();
        a.start();
        b.start();
        a.join();
        b.join();
        long millis = (System.nanoTime() - start) / 1_000_000;

        long impossible = outcomes[0];

        System.out.println("  r1  r2      count          meaning");
        System.out.println("  --  --   ------------      -------");
        System.out.printf("   1   1   %,12d      both threads saw the other's write (interleaved)%n", outcomes[3]);
        System.out.printf("   1   0   %,12d      thread B ran completely first%n", outcomes[2]);
        System.out.printf("   0   1   %,12d      thread A ran completely first%n", outcomes[1]);
        System.out.printf("   0   0   %,12d      <-- NOT POSSIBLE under sequential consistency%n", impossible);
        System.out.println();
        System.out.println("  elapsed: " + millis + " ms");
        System.out.println();

        if (impossible > 0) {
            System.out.println("RESULT: *** REORDERING OBSERVED ***");
            System.out.printf("  The 'impossible' outcome happened %,d times out of %,d trials (1 in %,d).%n",
                    impossible, TRIALS, TRIALS / Math.max(1, impossible));
            System.out.println("  Neither thread executed its two lines out of order in any way you could");
            System.out.println("  see with a debugger. The store simply had not reached memory yet.");
        } else {
            System.out.println("RESULT: the (0,0) outcome did not occur in this run.");
            if (useVolatile) {
                System.out.println("  Expected: volatile forbids it. This is the guarantee, not luck.");
            } else {
                System.out.println("  With plain variables this is luck, not a guarantee. Raise the trial");
                System.out.println("  count (-Dtrials=20000000) and it will show up.");
            }
        }
        System.out.println("======================================================================");
    }
}
