/**
 * DEMO 2 — Watch a virtual thread hop between OS threads.
 *
 * A virtual thread prints WHICH OS (carrier) thread it is riding on, before
 * and after each blocking call. You will see:
 *   - many virtual threads sharing the same 2 carriers, and
 *   - a virtual thread WAKING UP ON A DIFFERENT CARRIER than it slept on.
 *
 * Run (2 carriers so the sharing is obvious):
 *   java -Djdk.virtualThreadScheduler.parallelism=2 src/CarrierHopping.java
 */
import java.util.ArrayList;
import java.util.List;

public class CarrierHopping {

    public static void main(String[] args) throws Exception {
        System.out.println("Scheduler restricted to 2 carrier (OS) threads. Starting 6 virtual threads.");
        System.out.println("Each line shows:  virtual-thread-name : what happened : carrier it is mounted on\n");

        List<Thread> threads = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            String name = "vthread-" + i;
            threads.add(Thread.ofVirtual().name(name).start(() -> {
                report("started        ");
                for (int hop = 1; hop <= 2; hop++) {
                    sleep(50);   // blocking call -> unmounts from its carrier
                    report("woke from sleep");
                }
            }));
        }
        for (Thread t : threads) t.join();

        System.out.println("\n6 virtual threads, but only ever 2 distinct carriers (worker-1, worker-2).");
        System.out.println("Notice threads that fell asleep on one worker and woke on the other:");
        System.out.println("the JVM parked them, freed the carrier, and later resumed them on");
        System.out.println("whichever carrier was free. The virtual thread never noticed.");
    }

    /** Thread.currentThread() prints as e.g. "VirtualThread[#29,vthread-3]/runnable@ForkJoinPool-1-worker-2" */
    static void report(String event) {
        String s = Thread.currentThread().toString();
        String carrier = s.substring(s.lastIndexOf('@') + 1);          // ForkJoinPool-1-worker-2
        String name = Thread.currentThread().getName();
        System.out.printf("  %-10s : %s : mounted on %s%n", name, event, carrier);
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
