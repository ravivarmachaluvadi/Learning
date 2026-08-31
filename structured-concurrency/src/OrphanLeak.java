/**
 * DEMO 0 — The world BEFORE structured concurrency: the orphan leak.
 *
 * A method fans out two subtasks with a plain executor. One fails fast.
 * The method throws... but its OTHER subtask keeps running in the
 * background — an orphan no one owns, wasting resources, holding locks,
 * maybe writing to the database of a request that already returned 500.
 *
 * Watch the timestamps: the orphan prints AFTER the method has already
 * failed and returned.
 *
 * Run:  java src/OrphanLeak.java        (no preview needed — this is the old world)
 */
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class OrphanLeak {

    static long t0;
    static void say(String s) { System.out.printf("[%4d ms] %s%n", (System.nanoTime() - t0) / 1_000_000, s); }

    public static void main(String[] args) throws Exception {
        t0 = System.nanoTime();
        ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor();
        try {
            say("handleRequest(): calling fetchProfileAndOrders()...");
            fetchProfileAndOrders(ex);
        } catch (Exception e) {
            say("handleRequest(): FAILED with '" + e.getCause().getMessage() + "' — returning 500 to the client.");
        }
        say("handleRequest(): done. The request is over. Nothing should be running for it.");
        Thread.sleep(600);
        say("main(): exiting.");
        ex.shutdown();
    }

    static void fetchProfileAndOrders(ExecutorService ex) throws Exception {
        Future<String> profile = ex.submit(() -> {          // slow subtask
            Thread.sleep(500);
            say("   >>> ORPHAN: profile fetch finished — but nobody is listening anymore!");
            return "profile";
        });
        Future<String> orders = ex.submit(() -> {           // fails quickly
            Thread.sleep(50);
            throw new RuntimeException("orders service is down");
        });
        orders.get();       // throws after ~50 ms...
        profile.get();      // ...and this line is never reached: nobody waits for or cancels 'profile'
    }
}
