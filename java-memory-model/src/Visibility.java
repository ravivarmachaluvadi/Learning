/*
 * EXPERIMENT 1 — VISIBILITY
 * =========================
 *
 * Setup: one thread spins in a loop reading a boolean flag. A second thread
 * sets that flag to true. Does the spinning thread ever notice?
 *
 * Common sense says "obviously yes, it's the same variable in the same
 * process". Run it and find out.
 *
 *   java -cp out Visibility plain           -> worker probably NEVER stops
 *   java -cp out Visibility volatile        -> worker stops immediately
 *   java -Xint -cp out Visibility plain     -> worker stops (JIT switched off!)
 *
 * The third run is the entire point. Same source. Same CPU. Same JVM.
 * Correct or broken depending only on whether the optimiser was allowed to run.
 * That is why "it worked on my machine" is not evidence of correctness.
 */
public class Visibility {

    /** Plain field. No volatile, no lock, no atomic. */
    static boolean plainFlag = false;

    /** The same field, but declared volatile. */
    static volatile boolean volatileFlag = false;

    /** Written at the end of the loop so the JIT cannot delete the loop as dead code. */
    static long spinsObserved;

    public static void main(String[] args) throws Exception {
        final boolean useVolatile = args.length > 0 && args[0].equalsIgnoreCase("volatile");
        final String mode = useVolatile ? "volatile" : "plain";

        System.out.println("=== EXPERIMENT 1: VISIBILITY =========================================");
        System.out.println("flag declaration : " + (useVolatile ? "static volatile boolean" : "static boolean  (plain)"));
        System.out.println("jvm mode         : " + System.getProperty("java.vm.info"));
        System.out.println("cpu              : " + System.getProperty("os.arch")
                + ", " + Runtime.getRuntime().availableProcessors() + " logical cores");
        System.out.println();

        Thread worker = new Thread(() -> {
            System.out.println("  [worker] started, spinning while the flag is false...");
            long spins = 0;
            if (useVolatile) {
                while (!volatileFlag) { spins++; }
            } else {
                while (!plainFlag) { spins++; }
            }
            spinsObserved = spins;
            System.out.println("  [worker] flag flipped after " + spins + " spins -> loop exited");
        }, "worker");
        worker.setDaemon(true);   // so the JVM can exit even if this thread is stuck
        worker.start();

        // Give the JIT time to notice this is a hot loop and compile it.
        Thread.sleep(1000);

        System.out.println("  [main]   writing flag = true  (t = 1000 ms)");
        if (useVolatile) {
            volatileFlag = true;
        } else {
            plainFlag = true;
        }

        worker.join(3000);   // wait up to 3 more seconds

        System.out.println();
        if (worker.isAlive()) {
            System.out.println("RESULT: *** VISIBILITY FAILURE ***");
            System.out.println("  The worker is still spinning 3 seconds after the write.");
            System.out.println("  The flag really is true in memory. The worker is not reading memory --");
            System.out.println("  the JIT hoisted the load out of the loop, so it re-reads a CPU register");
            System.out.println("  that was filled once, before the write happened. It will spin forever.");
        } else {
            System.out.println("RESULT: worker terminated. The write was visible after "
                    + spinsObserved + " spins.");
            if (!useVolatile) {
                System.out.println("  NOTE: 'plain' terminated this time. That does NOT mean plain is safe --");
                System.out.println("        it means the optimiser happened not to hoist the load on this run.");
            }
        }
        System.out.println("======================================================================");
    }
}
