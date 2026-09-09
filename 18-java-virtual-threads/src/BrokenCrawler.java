/**
 * DEBUG EXERCISE — this crawler was "upgraded to virtual threads" but is
 * still slow. 40 page-fetches of ~100 ms each should take ~100 ms total
 * (they can all wait simultaneously)... yet it takes far longer.
 *
 * Find the bug. (Answer in SOLUTIONS.md — resist peeking.)
 *
 * Run:  java src/BrokenCrawler.java
 */
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BrokenCrawler {

    public static void main(String[] args) throws Exception {
        System.out.println("Crawling 40 pages, ~100 ms each, 'on virtual threads'...");
        long t0 = System.nanoTime();

        // We use virtual threads now — see, there's the factory!
        try (ExecutorService ex = Executors.newFixedThreadPool(8, Thread.ofVirtual().factory())) {
            for (int page = 1; page <= 40; page++) {
                int p = page;
                ex.submit(() -> fetchPage(p));
            }
        }

        System.out.printf("Done in %,d ms  (expected ~100 ms — why so slow?)%n",
                (System.nanoTime() - t0) / 1_000_000);
    }

    static void fetchPage(int page) {
        try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
