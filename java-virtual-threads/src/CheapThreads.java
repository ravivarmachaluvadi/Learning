/**
 * DEMO 1 — How cheap is a virtual thread?
 *
 * Starts 100,000 virtual threads that each sleep for 1 second, and times the
 * whole thing. Then does the same with 5,000 PLATFORM (OS) threads for
 * comparison. We use fewer platform threads on purpose: 100,000 OS threads
 * would ask the OS for ~100 GB of stack address space and likely fail.
 *
 * Run:  java src/CheapThreads.java
 */
import java.util.ArrayList;
import java.util.List;

public class CheapThreads {

    public static void main(String[] args) throws Exception {
        System.out.println("JVM sees " + Runtime.getRuntime().availableProcessors() + " CPU cores.\n");

        int virtualCount = 100_000;
        System.out.printf("Starting %,d VIRTUAL threads, each sleeping 1 second...%n", virtualCount);
        long t0 = System.nanoTime();
        List<Thread> vts = new ArrayList<>(virtualCount);
        for (int i = 0; i < virtualCount; i++) {
            vts.add(Thread.ofVirtual().start(CheapThreads::sleepOneSecond));
        }
        for (Thread t : vts) t.join();
        long virtualMs = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf("  -> all %,d finished in %,d ms total.%n", virtualCount, virtualMs);
        System.out.printf("     (If threads ran one at a time this would take ~28 hours. They all%n");
        System.out.printf("      slept AT THE SAME TIME on a handful of OS threads.)%n%n");

        int platformCount = 5_000;
        System.out.printf("Now %,d PLATFORM (OS) threads, each sleeping 1 second...%n", platformCount);
        t0 = System.nanoTime();
        List<Thread> pts = new ArrayList<>(platformCount);
        boolean osRefused = false;
        try {
            for (int i = 0; i < platformCount; i++) {
                pts.add(Thread.ofPlatform().start(CheapThreads::sleepOneSecond));
            }
        } catch (OutOfMemoryError e) {
            osRefused = true;   // pthread_create failed: the OS is out of threads
        }
        for (Thread t : pts) t.join();
        long platformMs = (System.nanoTime() - t0) / 1_000_000;
        if (osRefused) {
            System.out.printf("  -> THE OS REFUSED at thread #%,d: \"unable to create native thread\".%n", pts.size() + 1);
            System.out.printf("     Every platform thread is a real OS thread, and this machine's%n");
            System.out.printf("     per-process limit is ~%,d of them. Meanwhile %,d virtual threads%n", pts.size(), virtualCount);
            System.out.printf("     ran without complaint — they are JVM objects, not OS resources.%n%n");
        } else {
            System.out.printf("  -> all %,d finished in %,d ms total.%n%n", platformCount, platformMs);
        }

        System.out.printf("Creation cost per thread (total time minus the 1s of sleeping):%n");
        System.out.printf("  virtual : %,d threads cost ~%,d ms of overhead  (~%.1f microseconds each)%n",
                virtualCount, virtualMs - 1000, (virtualMs - 1000) * 1000.0 / virtualCount);
        System.out.printf("  platform: %,d threads cost ~%,d ms of overhead  (~%.1f microseconds each)%n",
                pts.size(), platformMs - 1000, (platformMs - 1000) * 1000.0 / Math.max(1, pts.size()));
        System.out.println("\nAnd each platform thread also reserved ~1 MB of stack memory up front;");
        System.out.println("a virtual thread's stack starts at a few hundred BYTES on the heap and grows as needed.");
    }

    static void sleepOneSecond() {
        try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
