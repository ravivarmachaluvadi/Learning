import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Experiment 03 — the cache stampede (dogpile), and the ten lines that stop it.
 *
 * Proves the claim from README §11.4:
 *   1. When one popular entry expires, EVERY concurrent request misses at the
 *      same instant and all of them hit the origin together — the cache's whole
 *      purpose inverted, on a timer you set yourself.
 *   2. Single-flight (one refill, everyone else waits for it) collapses that
 *      spike to exactly one origin request, with no extra staleness.
 *
 * Run:  java experiments/Exp03Stampede.java
 */
public class Exp03Stampede {

    static final int CONCURRENT_READERS = 30;
    static final long ORIGIN_WORK_MILLIS = 300;    // a real DB query + render
    static final long TTL_MILLIS = 500;

    /** The value the origin serves — stand-in for the rendered menu JSON. */
    record Menu(int biryani) { }

    /** What a reader got back, and how it got it. */
    record Answer(Menu value, String outcome) { }

    /**
     * Counts not just total hits but the WORST simultaneous load it saw —
     * that concurrency number is what actually takes an origin down.
     */
    static final class Origin {
        private int hits;
        private int inFlight;
        private int peakInFlight;

        Menu fetchMenu() {
            synchronized (this) {
                hits++;
                inFlight++;
                peakInFlight = Math.max(peakInFlight, inFlight);
            }
            sleep(ORIGIN_WORK_MILLIS);              // the expensive part — held OUTSIDE the lock
            synchronized (this) {
                inFlight--;
            }
            return new Menu(250);
        }

        synchronized int hits() { return hits; }

        synchronized int peakInFlight() { return peakInFlight; }
    }

    interface Cache {
        Answer get();
    }

    interface CacheFactory {
        Cache create(Origin origin, long ttlMillis);
    }

    /**
     * Textbook cache-aside: look, miss, fetch, store. Correct — and it
     * stampedes, because nothing coordinates the concurrent missers.
     */
    static final class NaiveCache implements Cache {
        private final Origin origin;
        private final long ttlMillis;
        // volatile only so the 30 readers SEE each other's writes. Visibility is
        // not coordination — and the missing coordination is this cache's bug.
        private volatile Menu value;
        private volatile long storedAt;

        NaiveCache(Origin origin, long ttlMillis) {
            this.origin = origin;
            this.ttlMillis = ttlMillis;
        }

        public Answer get() {
            if (value != null && System.currentTimeMillis() - storedAt < ttlMillis) {
                return new Answer(value, "HIT");
            }
            Menu fetched = origin.fetchMenu();      // <-- every misser arrives here
            value = fetched;
            storedAt = System.currentTimeMillis();
            return new Answer(fetched, "MISS");
        }
    }

    /**
     * Same cache plus one rule: only ONE thread may refill a given key; the
     * others wait for that refill and reuse its result.
     */
    static final class SingleFlightCache implements Cache {
        private final Origin origin;
        private final long ttlMillis;
        private volatile Menu value;
        private volatile long storedAt;
        // ReentrantLock rather than `synchronized`: it is the lock virtual threads
        // are meant to block on, and the readers below are all virtual threads.
        private final ReentrantLock refillLock = new ReentrantLock();

        SingleFlightCache(Origin origin, long ttlMillis) {
            this.origin = origin;
            this.ttlMillis = ttlMillis;
        }

        public Answer get() {
            if (value != null && System.currentTimeMillis() - storedAt < ttlMillis) {
                return new Answer(value, "HIT");
            }
            // ---- SINGLE FLIGHT: the queue forms here, not at the origin. ----
            refillLock.lock();
            try {
                // Re-check inside the lock: while we queued, the winner may have
                // already refilled the entry — then we are a HIT after all.
                if (value != null && System.currentTimeMillis() - storedAt < ttlMillis) {
                    return new Answer(value, "HIT (waited for the refill)");
                }
                Menu fetched = origin.fetchMenu();  // exactly one thread reaches this
                value = fetched;
                storedAt = System.currentTimeMillis();
                return new Answer(fetched, "MISS (I did the refill)");
            } finally {
                refillLock.unlock();
            }
        }
    }

    /** One reader's experience: how it was served, and how long it waited. */
    record ReaderResult(int id, String outcome, double ms) { }

    static int[] run(CacheFactory factory, String label) throws Exception {
        Origin origin = new Origin();
        Cache cache = factory.create(origin, TTL_MILLIS);
        List<ReaderResult> results = Collections.synchronizedList(new ArrayList<>());

        cache.get();                                 // warm it up: one legitimate miss
        int warmHits = origin.hits();
        sleep(600);                                  // …and now let the entry expire

        long t0 = System.nanoTime();
        // One virtual thread per reader: 30 of them cost about as much as 30
        // objects, so "30 requests arrive at the same instant" is literally true
        // here rather than approximated. try-with-resources close() joins them all.
        try (ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < CONCURRENT_READERS; i++) {
                final int id = i;
                readers.submit(() -> {
                    long start = System.nanoTime();
                    Answer answer = cache.get();
                    results.add(new ReaderResult(id, answer.outcome(),
                            (System.nanoTime() - start) / 1e6));
                });
            }
        }
        double wall = (System.nanoTime() - t0) / 1e6;

        long misses = results.stream().filter(r -> r.outcome().startsWith("MISS")).count();
        double slowest = results.stream().mapToDouble(ReaderResult::ms).max().orElse(0);
        System.out.println(label);
        System.out.printf("  origin requests from the %d readers : %d%n",
                CONCURRENT_READERS, origin.hits() - warmHits);
        System.out.printf("  worst simultaneous load the origin saw   : %d%n", origin.peakInFlight());
        System.out.printf("  readers that went to the origin          : %d%n", misses);
        System.out.printf("  slowest reader                           : %6.0f ms%n", slowest);
        System.out.printf("  wall clock for all %d readers          : %6.0f ms%n%n",
                CONCURRENT_READERS, wall);
        return new int[] { origin.hits() - warmHits, origin.peakInFlight() };
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.printf("%d readers ask for the same menu at the same moment,%n",
                CONCURRENT_READERS);
        System.out.println("one millisecond after the cached copy expired.\n");

        int[] naive = run(NaiveCache::new, "WITHOUT single-flight (plain cache-aside)");
        int[] singleFlight = run(SingleFlightCache::new, "WITH single-flight");

        System.out.println("WHAT YOU JUST SAW");
        System.out.println("-".repeat(72));
        System.out.printf("The naive cache turned one expiry into %d identical origin%n", naive[0]);
        System.out.printf("requests, %d of them running at once — a load spike shaped%n", naive[1]);
        System.out.println("like your TTL, arriving on schedule forever. Nothing was wrong with");
        System.out.println("any single line of that cache: the bug is that N missers were allowed");
        System.out.println("to miss independently.");
        System.out.printf("%nSingle-flight made it %d origin request. The other readers%n",
                singleFlight[0]);
        System.out.println("queued on a lock in YOUR process (cheap) instead of on the origin's");
        System.out.println("database connections (expensive), and got the same fresh answer.");
        System.out.println("""

                Two production siblings of this fix, from README §11.4:
                  • stale-while-revalidate — don't even make them wait: serve the
                    slightly-stale copy instantly, refresh in the background.
                  • TTL jitter — so a thousand DIFFERENT keys don't all expire on
                    the same second and stampede together.""");
    }
}
