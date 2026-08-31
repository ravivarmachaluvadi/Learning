/**
 * DEBUG EXERCISE D2 — the subtask that wouldn't die.
 *
 * Fail-fast scope; one child fails after 50 ms. The other child has
 * "resilient retry logic" someone was proud of. Watch how long the scope
 * takes to exit — then explain which guarantee HELD and which was LOST,
 * and fix the child.
 *
 * Run:  java --enable-preview --source 25 src/UnkillableChild.java
 */
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.FailedException;

public class UnkillableChild {

    public static void main(String[] args) throws InterruptedException {
        long t0 = System.nanoTime();
        try (var scope = StructuredTaskScope.<String>open()) {
            scope.fork(UnkillableChild::resilientFetch);
            scope.fork(() -> { Thread.sleep(50); throw new RuntimeException("sibling failed"); });
            scope.join();
        } catch (FailedException e) {
            System.out.printf("join() finally threw \"%s\" after %d ms.%n",
                    e.getCause().getMessage(), (System.nanoTime() - t0) / 1_000_000);
        }
        System.out.println("The failure happened at 50 ms. Why were we held for half a second?");
    }

    /** "Never give up" — retries through ANY interruption. This is the bug. */
    static String resilientFetch() {
        for (int attempt = 1; attempt <= 4; attempt++) {
            try {
                Thread.sleep(500);                       // pretend attempt
                return "data";
            } catch (InterruptedException e) {
                System.out.println("   resilientFetch: interrupted on attempt " + attempt + " — retrying anyway!");
            }
        }
        return "gave up";
    }
}
