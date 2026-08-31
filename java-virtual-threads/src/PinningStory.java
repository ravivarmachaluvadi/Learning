/**
 * DEMO 4 — Pinning: the famous gotcha, and how JDK 24 fixed it.
 *
 * "Pinning" = a virtual thread blocks but CANNOT give up its carrier OS
 * thread, so the carrier is stuck doing nothing. Before JDK 24, blocking
 * inside a `synchronized` block pinned the carrier. JEP 491 (JDK 24) fixed
 * that.
 *
 * The experiment: restrict the scheduler to ONE carrier, then start 8
 * virtual threads that each sleep 200 ms INSIDE a synchronized block
 * (each on its own lock — no real contention).
 *
 *   - If synchronized pins  (JDK 21–23): the single carrier is held hostage
 *     through each sleep -> the sleeps run one after another -> ~1600 ms.
 *   - If it does not pin    (JDK 24+):   each sleeper unmounts, the carrier
 *     serves the next thread -> all sleep together -> ~200 ms.
 *
 * Run:  java -Djdk.virtualThreadScheduler.parallelism=1 src/PinningStory.java
 */
import java.util.ArrayList;
import java.util.List;

public class PinningStory {

    public static void main(String[] args) throws Exception {
        System.out.println("Java " + Runtime.version() + ", scheduler restricted to 1 carrier thread.");
        System.out.println("8 virtual threads, each sleeping 200 ms inside its own synchronized block.\n");

        long t0 = System.nanoTime();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Object myOwnLock = new Object();
            threads.add(Thread.ofVirtual().start(() -> {
                synchronized (myOwnLock) {                       // held while blocking!
                    try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
            }));
        }
        for (Thread t : threads) t.join();
        long ms = (System.nanoTime() - t0) / 1_000_000;

        System.out.printf("Elapsed: %,d ms%n%n", ms);
        if (ms < 800) {
            System.out.println("VERDICT: ~200 ms — the sleeps overlapped. Blocking inside synchronized");
            System.out.println("did NOT pin the carrier. You are on JDK 24+ with JEP 491.");
            System.out.println("On JDK 21 this exact program takes ~1,600 ms (8 x 200 ms, serialized).");
        } else {
            System.out.println("VERDICT: the sleeps ran one at a time — every sleeper PINNED the only");
            System.out.println("carrier. This is the pre-JDK-24 behavior (JEP 491 not present).");
        }
        System.out.println("\nStill pinning today: blocking while inside a native (JNI/FFM) call,");
        System.out.println("or inside a class initializer. JFR event `jdk.VirtualThreadPinned` reports it.");
    }
}
