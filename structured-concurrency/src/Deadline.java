/**
 * DEMO 4 — A deadline for the whole family.
 *
 * The scope gets a 150 ms timeout. The profile call (100 ms) makes it;
 * the orders call (400 ms) does not. At the deadline the scope cancels
 * everything still running and join() throws TimeoutException.
 * One timeout covers the whole tree — no per-call bookkeeping.
 *
 * Run:  java --enable-preview --source 25 src/Deadline.java
 */
import java.time.Duration;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Subtask;
import java.util.concurrent.StructuredTaskScope.TimeoutException;

public class Deadline {

    public static void main(String[] args) throws InterruptedException {
        long t0 = System.nanoTime();
        try (var scope = StructuredTaskScope.open(
                StructuredTaskScope.Joiner.<String>awaitAllSuccessfulOrThrow(),
                cfg -> cfg.withName("page-load").withTimeout(Duration.ofMillis(150)))) {

            Subtask<String> profile = scope.fork(() -> { Thread.sleep(100); return "profile-data"; });
            Subtask<String> orders  = scope.fork(() -> { Thread.sleep(400); return "orders-data"; });

            scope.join();
            System.out.println("unreachable when the deadline fires first");
        } catch (TimeoutException e) {
            System.out.printf("TimeoutException after %d ms — the scope's deadline fired.%n",
                    (System.nanoTime() - t0) / 1_000_000);
            System.out.println("Everything still running was cancelled; the fast subtask's result is simply discarded.");
        }
    }
}
