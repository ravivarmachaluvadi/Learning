/*
 * EXPERIMENT 3 — ATOMICITY (lost updates)
 * =======================================
 *
 * Eight threads each add 1 to a shared counter, one million times each.
 * Expected total: 8,000,000. Watch what you actually get.
 *
 *   java -cp out LostUpdate plain          -> loses millions of updates
 *   java -cp out LostUpdate volatile       -> STILL loses updates (the trap)
 *   java -cp out LostUpdate synchronized   -> exact
 *   java -cp out LostUpdate atomic         -> exact, and faster than synchronized
 *
 * The 'volatile' run is the one worth staring at. volatile fixes VISIBILITY.
 * It does not create ATOMICITY. count++ is three separate machine operations:
 *
 *      read count      ->  register
 *      add 1           ->  register
 *      write register  ->  count
 *
 * Two threads can both read 41, both compute 42, and both write 42.
 * Two increments happened; the counter moved by one. That update is lost.
 * Making the field volatile means each of those three steps reads and writes
 * real memory -- it does nothing to stop another thread from interleaving
 * between step 1 and step 3.
 */
import java.util.concurrent.atomic.AtomicLong;

public class LostUpdate {

    static final int THREADS = 8;
    static final int PER_THREAD = 1_000_000;

    static long plainCount;
    static volatile long volatileCount;
    static long syncCount;
    static final AtomicLong atomicCount = new AtomicLong();

    static final Object lock = new Object();

    public static void main(String[] args) throws Exception {
        final String mode = args.length > 0 ? args[0].toLowerCase() : "plain";

        System.out.println("=== EXPERIMENT 3: ATOMICITY ==========================================");
        System.out.println("mode             : " + mode);
        System.out.println("threads          : " + THREADS + " x " + String.format("%,d", PER_THREAD) + " increments");
        long expected = (long) THREADS * PER_THREAD;
        System.out.println("expected total   : " + String.format("%,d", expected));
        System.out.println();

        Runnable body = switch (mode) {
            case "plain"        -> () -> { for (int i = 0; i < PER_THREAD; i++) plainCount++; };
            case "volatile"     -> () -> { for (int i = 0; i < PER_THREAD; i++) volatileCount++; };
            case "synchronized" -> () -> { for (int i = 0; i < PER_THREAD; i++) { synchronized (lock) { syncCount++; } } };
            case "atomic"       -> () -> { for (int i = 0; i < PER_THREAD; i++) atomicCount.incrementAndGet(); };
            default -> throw new IllegalArgumentException(
                    "mode must be one of: plain | volatile | synchronized | atomic");
        };

        Thread[] threads = new Thread[THREADS];
        for (int i = 0; i < THREADS; i++) threads[i] = new Thread(body, "inc-" + i);

        long start = System.nanoTime();
        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();
        long millis = (System.nanoTime() - start) / 1_000_000;

        long actual = switch (mode) {
            case "plain"        -> plainCount;
            case "volatile"     -> volatileCount;
            case "synchronized" -> syncCount;
            default             -> atomicCount.get();
        };

        long lost = expected - actual;
        System.out.println("  actual total   : " + String.format("%,d", actual));
        System.out.println("  updates lost   : " + String.format("%,d", lost)
                + String.format("   (%.1f%%)", 100.0 * lost / expected));
        System.out.println("  elapsed        : " + millis + " ms");
        System.out.println();

        if (lost > 0) {
            System.out.println("RESULT: *** LOST UPDATES ***  " + String.format("%,d", lost)
                    + " increments vanished.");
            if (mode.equals("volatile")) {
                System.out.println("  volatile made every read and write hit memory -- and changed nothing,");
                System.out.println("  because the bug is not visibility. The bug is that read-modify-write");
                System.out.println("  is three steps and another thread can slip in between them.");
            }
        } else {
            System.out.println("RESULT: exact. Every increment counted.");
        }
        System.out.println("======================================================================");
    }
}
