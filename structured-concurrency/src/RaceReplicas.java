/**
 * DEMO 3 — A different policy: first success wins, losers are cancelled.
 *
 * Ask two replicas of the same service; take whichever answers first and
 * cancel the other. This is the `anySuccessfulResultOrThrow` joiner —
 * "hedged requests" in three lines.
 *
 * Run:  java --enable-preview --source 25 src/RaceReplicas.java
 */
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.FailedException;
import java.util.concurrent.StructuredTaskScope.Joiner;

public class RaceReplicas {

    public static void main(String[] args) throws InterruptedException {
        long t0 = System.nanoTime();
        try (var scope = StructuredTaskScope.open(Joiner.<String>anySuccessfulResultOrThrow())) {
            scope.fork(() -> replica("replica-A (slow today)", 300));
            scope.fork(() -> replica("replica-B", 40));
            String winner = scope.join();                        // the FIRST successful result
            System.out.printf("Winner: %s  (%d ms — the 300 ms replica was cancelled)%n",
                    winner, (System.nanoTime() - t0) / 1_000_000);
        }

        System.out.println();
        System.out.println("And if EVERY replica fails, join() throws with the last failure as cause:");
        try (var scope = StructuredTaskScope.open(Joiner.<String>anySuccessfulResultOrThrow())) {
            scope.fork(() -> { throw new RuntimeException("A: connection refused"); });
            scope.fork(() -> { throw new RuntimeException("B: connection refused"); });
            scope.join();
        } catch (FailedException e) {
            System.out.println("FailedException, cause: " + e.getCause().getMessage());
        }
    }

    static String replica(String name, int ms) throws InterruptedException {
        Thread.sleep(ms);
        return "answer from " + name;
    }
}
