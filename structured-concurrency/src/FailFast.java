/**
 * DEMO 2 — One child fails: everyone is called home.
 *
 * The profile call would take 5 SECONDS. The orders call fails after 50 ms.
 * With the default joiner, the scope immediately CANCELS the slow sibling
 * (interrupts its virtual thread), join() reports the failure, and the whole
 * thing is over in ~60 ms — not 5 seconds, and with nothing left running.
 *
 * Run:  java --enable-preview --source 25 src/FailFast.java
 */
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.FailedException;
import java.util.concurrent.StructuredTaskScope.Subtask;

public class FailFast {

    public static void main(String[] args) throws InterruptedException {
        long t0 = System.nanoTime();
        Subtask<String> profile = null;
        try (var scope = StructuredTaskScope.<String>open()) {
            profile = scope.fork(() -> {
                try {
                    Thread.sleep(5000);                          // "5-second profile call"
                    return "profile-data";
                } catch (InterruptedException e) {
                    System.out.println("   profile subtask: I was interrupted — my sibling must have failed. Cleaning up.");
                    throw e;
                }
            });
            scope.fork(() -> {
                Thread.sleep(50);
                throw new RuntimeException("orders service is down");
            });
            scope.join();                                        // throws FailedException
            System.out.println("unreachable: " + profile.get());
        } catch (FailedException e) {
            long ms = (System.nanoTime() - t0) / 1_000_000;
            System.out.printf("%njoin() threw FailedException, cause: \"%s\"%n", e.getCause().getMessage());
            System.out.printf("Elapsed: %d ms — the 5-second sibling was cancelled, not awaited.%n", ms);
            System.out.println("Slow subtask's final state: " + profile.state());   // UNAVAILABLE (cancelled)
        }
        System.out.println("After the scope: guaranteed nothing is still running on our behalf.");
    }
}
