/**
 * DEMO 3 — The whole point: blocking tasks at scale.
 *
 * 600 tasks each "call a slow service" (sleep 50 ms). Two strategies:
 *   A) classic thread pool with 8 platform threads   (the pre-Loom answer)
 *   B) one virtual thread per task                   (the Loom answer)
 *
 * Prediction for A: only 8 tasks can wait at once -> 600/8 waves * 50 ms = ~3.75 s.
 * Prediction for B: all 600 wait at the same time  -> ~50 ms plus small overhead.
 *
 * Run:  java src/ThroughputRace.java [tasks] [blockMillis]
 */
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ThroughputRace {

    public static void main(String[] args) throws Exception {
        int tasks = args.length > 0 ? Integer.parseInt(args[0]) : 600;
        int blockMs = args.length > 1 ? Integer.parseInt(args[1]) : 50;
        int poolSize = 8;

        System.out.printf("%,d tasks; each blocks for %d ms (a fake network call).%n%n", tasks, blockMs);

        System.out.printf("A) Fixed pool of %d platform threads:%n", poolSize);
        System.out.printf("   predicted ~= tasks/pool * block = %,d ms%n", (long) Math.ceil(tasks / (double) poolSize) * blockMs);
        long a = run(Executors.newFixedThreadPool(poolSize), tasks, blockMs);
        System.out.printf("   measured  = %,d ms%n%n", a);

        System.out.println("B) One virtual thread per task:");
        System.out.printf("   predicted ~= block = %d ms (everyone waits simultaneously)%n", blockMs);
        long b = run(Executors.newVirtualThreadPerTaskExecutor(), tasks, blockMs);
        System.out.printf("   measured  = %,d ms%n%n", b);

        System.out.printf("Virtual threads finished %.0fx faster — not by running code faster,%n", a / (double) b);
        System.out.println("but by letting every task WAIT at the same time.");
    }

    static long run(ExecutorService executor, int tasks, int blockMs) throws Exception {
        long t0 = System.nanoTime();
        try (ExecutorService ex = executor) {           // close() waits for all tasks
            for (int i = 0; i < tasks; i++) {
                ex.submit(() -> {
                    try { Thread.sleep(blockMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                });
            }
        }
        return (System.nanoTime() - t0) / 1_000_000;
    }
}
