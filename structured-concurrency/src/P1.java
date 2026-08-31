/** Exercise P1 — predict both numbers before running! (see EXERCISES.md)
 *  Run:  java --enable-preview --source 25 src/P1.java */
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Joiner;

public class P1 {
    public static void main(String[] args) throws Exception {
        long t0 = System.nanoTime();
        try (var scope = StructuredTaskScope.<String>open()) {
            scope.fork(() -> { Thread.sleep(100); return "cache"; });
            scope.fork(() -> { Thread.sleep(200); return "db"; });
            scope.fork(() -> { Thread.sleep(300); return "archive"; });
            scope.join();
        }
        System.out.println("default joiner : " + (System.nanoTime() - t0) / 1_000_000 + " ms");

        t0 = System.nanoTime();
        try (var scope = StructuredTaskScope.open(Joiner.<String>anySuccessfulResultOrThrow())) {
            scope.fork(() -> { Thread.sleep(100); return "cache"; });
            scope.fork(() -> { Thread.sleep(200); return "db"; });
            scope.fork(() -> { Thread.sleep(300); return "archive"; });
            System.out.println("anySuccessful joiner returned: " + scope.join());
        }
        System.out.println("anySuccessful  : " + (System.nanoTime() - t0) / 1_000_000 + " ms");
    }
}
