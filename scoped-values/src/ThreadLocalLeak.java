/**
 * DEMO 3 — The disease ScopedValue cures: the ThreadLocal leak.
 *
 * Classic servlet setup: a small pool of threads serves many requests.
 * Request A stores the logged-in user in a ThreadLocal and FORGETS to
 * remove() it (the finally block someone deleted in a refactor). Request B
 * later runs on the same pooled thread... and sees Alice's identity.
 *
 * This is not hypothetical — "stale ThreadLocal on a pooled thread" is a
 * recurring real-world security-bug class.
 *
 * Run:  java src/ThreadLocalLeak.java
 */
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ThreadLocalLeak {

    static final ThreadLocal<String> CURRENT_USER = new ThreadLocal<>();

    public static void main(String[] args) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(1);   // one thread: the reuse is guaranteed

        pool.submit(() -> {
            CURRENT_USER.set("alice");
            System.out.println("[request A] set CURRENT_USER=alice on " + Thread.currentThread().getName());
            System.out.println("[request A] ...does its work, returns. Oops: nobody called remove().");
            // CURRENT_USER.remove();   <-- the line a refactor deleted
        }).get();

        pool.submit(() -> {
            String user = CURRENT_USER.get();      // fresh request, same pooled thread
            System.out.println("[request B] on " + Thread.currentThread().getName()
                    + " — who am I? CURRENT_USER=" + user);
            if (user != null)
                System.out.println("[request B] I never logged in as " + user + "! Request A's identity LEAKED into me.");
        }).get();
        pool.shutdown();

        System.out.println("""

            Why ScopedValue cannot have this bug, *structurally*:
              - there is no set() to call from anywhere — a value exists only via
                ScopedValue.where(...).run(block)
              - the binding dies when the block exits, on every path (return, throw)
              - so "stale value still bound after the request" is not a mistake you
                can make; the API has no spelling for it.
            (There is also no remove() to forget — nothing to clean is the cure.)""");
    }
}
