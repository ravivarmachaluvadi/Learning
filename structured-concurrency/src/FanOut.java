/**
 * DEMO 1 — The basic shape: fork, join, combine.
 *
 * Fetch a user's profile (80 ms) and their orders (120 ms) concurrently.
 * The scope guarantees: by the time the try-block exits, BOTH subtasks are
 * finished — success, failure, or cancelled. No orphans, ever.
 *
 * Run:  java --enable-preview --source 25 src/FanOut.java
 */
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Subtask;

public class FanOut {

    public static void main(String[] args) throws Exception {
        long t0 = System.nanoTime();

        String page;
        try (var scope = StructuredTaskScope.<String>open()) {   // opens a scope; owner = this thread
            Subtask<String> profile = scope.fork(() -> slowCall("profile-service", 80));
            Subtask<String> orders  = scope.fork(() -> slowCall("orders-service", 120));

            scope.join();                                        // wait for BOTH (or first failure)

            page = "page[" + profile.get() + " + " + orders.get() + "]";
        }   // close(): nothing outlives this line

        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("Result: " + page);
        System.out.printf("Took %d ms — concurrent (max of 80,120), not sequential (80+120=200).%n", ms);
        System.out.println("""

            The shape to remember:
                try (var scope = StructuredTaskScope.open()) {
                    var a = scope.fork(taskA);      // start children
                    var b = scope.fork(taskB);
                    scope.join();                   // wait for all of them
                    use(a.get(), b.get());          // results, only after join
                }                                   // guaranteed: no child outlives this brace
            """);
    }

    static String slowCall(String service, int ms) throws InterruptedException {
        Thread.sleep(ms);                       // stands for an HTTP/DB call
        return service + "-data";
    }
}
