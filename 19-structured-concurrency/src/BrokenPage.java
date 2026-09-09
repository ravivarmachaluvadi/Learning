/**
 * DEBUG EXERCISE D1 — "I parallelized it with structured concurrency,
 * but it's no faster than before."
 *
 * Five widgets, ~100 ms each. Structured concurrency is right there in the
 * code — scope, fork, join, the works. It compiles, runs, throws nothing...
 * and takes ~500 ms instead of ~100 ms. Find the structural bug.
 *
 * Run:  java --enable-preview --source 25 src/BrokenPage.java
 */
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.StructuredTaskScope;

public class BrokenPage {

    public static void main(String[] args) throws Exception {
        long t0 = System.nanoTime();
        List<String> widgets = new ArrayList<>();

        for (String name : List.of("news", "weather", "stocks", "mail", "calendar")) {
            try (var scope = StructuredTaskScope.<String>open()) {
                var widget = scope.fork(() -> renderWidget(name));
                scope.join();
                widgets.add(widget.get());
            }
        }

        System.out.println("Rendered: " + widgets);
        System.out.printf("Took %d ms  (expected ~100 ms — why so slow?)%n",
                (System.nanoTime() - t0) / 1_000_000);
    }

    static String renderWidget(String name) throws InterruptedException {
        Thread.sleep(100);                       // each widget calls a backend
        return name;
    }
}
