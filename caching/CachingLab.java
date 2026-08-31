import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/**
 * CachingLab — a zero-dependency simulation of a cache in front of a slow
 * database. Run with:
 *
 *   java CachingLab.java test        — correctness checks for the cache implementations
 *   java CachingLab.java hit-rate    — why caching works: hit ratio vs cache size, and the latency math
 *   java CachingLab.java eviction    — FIFO vs LRU vs LFU vs Random, on a skewed workload and under a scan
 *   java CachingLab.java stampede    — 200 threads hit one expired hot key; with and without single-flight
 *   java CachingLab.java stale-race  — the delete-then-repopulate race; with and without version checking
 *
 * Everything here maps to a section of README.md; the load-bearing lines
 * carry comments naming the invariant they enforce.
 */
public class CachingLab {

    // ──────────────────────────────────────────────────────────────
    // The cache contract: a BOUNDED map. Bounded is the whole point —
    // an unbounded "cache" is just a memory leak with good intentions.
    // ──────────────────────────────────────────────────────────────
    interface Cache {
        Integer get(int key);
        void put(int key, int value);
        void remove(int key);
        int size();
        String name();
    }

    /** FIFO: evict whatever has been IN the cache longest, ignoring use. */
    static class FifoCache implements Cache {
        final int capacity;
        final LinkedHashMap<Integer, Integer> map = new LinkedHashMap<>(); // insertion order
        FifoCache(int capacity) { this.capacity = capacity; }
        public Integer get(int key) { return map.get(key); } // a hit does NOT refresh position — FIFO's defining flaw
        public void put(int key, int value) {
            map.remove(key);
            map.put(key, value);
            if (map.size() > capacity) {                      // invariant: size never exceeds capacity
                int oldest = map.keySet().iterator().next();
                map.remove(oldest);
            }
        }
        public void remove(int key) { map.remove(key); }
        public int size() { return map.size(); }
        public String name() { return "FIFO"; }
    }

    /**
     * LRU, hand-rolled: hash map for O(1) lookup + doubly linked list for
     * O(1) recency order. This pairing is THE classic cache data structure —
     * neither works alone (map has no order; list has no fast lookup).
     */
    static class LruCache implements Cache {
        static class Node { int key, value; Node prev, next; Node(int k, int v) { key = k; value = v; } }
        final int capacity;
        final Map<Integer, Node> map = new HashMap<>();
        final Node head = new Node(-1, -1), tail = new Node(-1, -1); // sentinels: head side = most recent
        LruCache(int capacity) { this.capacity = capacity; head.next = tail; tail.prev = head; }
        private void unlink(Node n) { n.prev.next = n.next; n.next.prev = n.prev; }
        private void linkAtHead(Node n) { n.next = head.next; n.prev = head; head.next.prev = n; head.next = n; }
        public Integer get(int key) {
            Node n = map.get(key);
            if (n == null) return null;
            unlink(n); linkAtHead(n);   // THE LRU move: every hit promotes to most-recent.
            return n.value;             // This one line is what makes LRU beat FIFO on skewed workloads.
        }
        public void put(int key, int value) {
            Node n = map.get(key);
            if (n != null) { n.value = value; unlink(n); linkAtHead(n); return; }
            if (map.size() == capacity) {
                Node victim = tail.prev;         // least-recently-used lives at the tail
                unlink(victim); map.remove(victim.key);
            }
            n = new Node(key, value); map.put(key, n); linkAtHead(n);
        }
        public void remove(int key) { Node n = map.remove(key); if (n != null) unlink(n); }
        public int size() { return map.size(); }
        public String name() { return "LRU"; }
    }

    /**
     * LFU (simple form): evict the entry used the FEWEST times. Resists scan
     * pollution (a scan touches everything once — frequency 1 loses to the
     * genuinely hot) but carries its own disease: old frequency is forever,
     * so yesterday's hot key squats and new keys can't establish themselves.
     */
    static class LfuCache implements Cache {
        final int capacity;
        final Map<Integer, Integer> values = new HashMap<>();
        final Map<Integer, Integer> freq = new HashMap<>();
        long clock = 0; final Map<Integer, Long> lastUse = new HashMap<>(); // tie-break: LRU among equal frequencies
        LfuCache(int capacity) { this.capacity = capacity; }
        public Integer get(int key) {
            Integer v = values.get(key);
            if (v != null) { freq.merge(key, 1, Integer::sum); lastUse.put(key, clock++); }
            return v;
        }
        public void put(int key, int value) {
            if (!values.containsKey(key) && values.size() == capacity) {
                int victim = -1; int bestF = Integer.MAX_VALUE; long bestT = Long.MAX_VALUE;
                for (var e : freq.entrySet()) {
                    if (!values.containsKey(e.getKey())) continue;
                    long t = lastUse.getOrDefault(e.getKey(), 0L);
                    if (e.getValue() < bestF || (e.getValue() == bestF && t < bestT)) {
                        bestF = e.getValue(); bestT = t; victim = e.getKey();
                    }
                }
                values.remove(victim);
            }
            values.put(key, value);
            freq.merge(key, 1, Integer::sum);
            lastUse.put(key, clock++);
        }
        public void remove(int key) { values.remove(key); }
        public int size() { return values.size(); }
        public String name() { return "LFU"; }
    }

    /** Random eviction: the humble baseline that is surprisingly hard to embarrass. */
    static class RandomCache implements Cache {
        final int capacity; final Map<Integer, Integer> map = new HashMap<>();
        final List<Integer> keys = new ArrayList<>(); final Random rnd;
        RandomCache(int capacity, long seed) { this.capacity = capacity; rnd = new Random(seed); }
        public Integer get(int key) { return map.get(key); }
        public void put(int key, int value) {
            if (!map.containsKey(key) && map.size() == capacity) {
                int idx = rnd.nextInt(keys.size());
                int victim = keys.get(idx);
                keys.set(idx, keys.get(keys.size() - 1)); keys.remove(keys.size() - 1);
                map.remove(victim);
            }
            if (!map.containsKey(key)) keys.add(key);
            map.put(key, value);
        }
        public void remove(int key) { if (map.remove(key) != null) { keys.remove((Integer) key); } }
        public int size() { return map.size(); }
        public String name() { return "Random"; }
    }

    // ──────────────────────────────────────────────────────────────
    // A simulated database: the source of truth. It counts every call —
    // protecting this counter from load is half of why caches exist.
    // ──────────────────────────────────────────────────────────────
    static class SlowDatabase {
        final AtomicLong calls = new AtomicLong();
        final Map<Integer, Integer> rows = new ConcurrentHashMap<>();
        final long latencyMillis; // 0 = cost accounted, not slept (keeps big experiments fast)
        SlowDatabase(long latencyMillis) { this.latencyMillis = latencyMillis; }
        int read(int key) {
            calls.incrementAndGet();
            if (latencyMillis > 0) { try { Thread.sleep(latencyMillis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
            return rows.getOrDefault(key, key * 1000); // default deterministic content
        }
        void write(int key, int value) { rows.put(key, value); }
    }

    /** Zipf-ish skewed key generator: a few keys are hot, most are cold — the reason caching works at all. */
    static class SkewedKeys {
        final int n; final double[] cdf; final Random rnd;
        SkewedKeys(int n, double skew, long seed) {
            this.n = n; rnd = new Random(seed);
            double[] w = new double[n]; double sum = 0;
            for (int i = 0; i < n; i++) { w[i] = 1.0 / Math.pow(i + 1, skew); sum += w[i]; }
            cdf = new double[n]; double acc = 0;
            for (int i = 0; i < n; i++) { acc += w[i] / sum; cdf[i] = acc; }
        }
        int next() {
            double u = rnd.nextDouble();
            int lo = 0, hi = n - 1;
            while (lo < hi) { int mid = (lo + hi) / 2; if (cdf[mid] < u) lo = mid + 1; else hi = mid; }
            return lo;
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Scenario: test — executable documentation of cache behavior
    // ══════════════════════════════════════════════════════════════
    static int passed = 0, failed = 0;
    static void check(String name, boolean ok) {
        if (ok) passed++; else failed++;
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + name);
    }

    static void runTests() {
        System.out.println("Cache correctness checks");

        Cache lru = new LruCache(3);
        lru.put(1, 10); lru.put(2, 20); lru.put(3, 30);
        check("lru_capacity_bound_holds", lru.size() == 3);
        lru.put(4, 40); // evicts 1 (least recently used)
        check("lru_evicts_least_recently_used", lru.get(1) == null && lru.get(2) != null);
        lru.get(2);      // touch 2 → most recent; order now [2,4,3]
        lru.put(5, 50);  // must evict 3, NOT 2 — a hit refreshed 2's recency
        check("lru_hit_refreshes_recency", lru.get(2) != null && lru.get(3) == null);

        Cache fifo = new FifoCache(3);
        fifo.put(1, 10); fifo.put(2, 20); fifo.put(3, 30);
        fifo.get(1); fifo.get(1); fifo.get(1); // heavy use of 1 — FIFO does not care
        fifo.put(4, 40);
        check("fifo_evicts_hot_key_anyway", fifo.get(1) == null);

        Cache lfu = new LfuCache(3);
        lfu.put(1, 10); lfu.put(2, 20); lfu.put(3, 30);
        lfu.get(1); lfu.get(1); lfu.get(2);   // freq: 1→3, 2→2, 3→1
        lfu.put(4, 40);                        // evicts 3 (lowest frequency)
        check("lfu_evicts_least_frequently_used", lfu.get(3) == null && lfu.get(1) != null);

        Cache r = new RandomCache(3, 42);
        r.put(1, 10); r.put(2, 20); r.put(3, 30); r.put(4, 40);
        check("random_capacity_bound_holds", r.size() == 3);

        lru.remove(2);
        check("remove_deletes_entry (this is invalidation)", lru.get(2) == null);

        System.out.printf("%n%d passed, %d failed%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    // ══════════════════════════════════════════════════════════════
    // Scenario: hit-rate — hit ratio vs cache size, and the latency math
    // ══════════════════════════════════════════════════════════════
    static void runHitRate() {
        int keys = 10_000, requests = 200_000;
        double dbMs = 5.0, cacheMs = 0.05;
        System.out.printf("Workload: %,d requests over %,d keys, skewed (zipf s=1.0) — a few keys are hot, most are cold.%n", requests, keys);
        System.out.printf("Cost model: DB read = %.1f ms, cache read = %.2f ms.%n%n", dbMs, cacheMs);
        System.out.printf("%-12s %-10s %-14s %-16s %s%n", "cache size", "hit ratio", "avg latency", "DB calls", "(cache covers what % of keyspace)");
        for (int size : new int[]{100, 500, 1000, 2000, 5000}) {
            SlowDatabase db = new SlowDatabase(0);
            Cache cache = new LruCache(size);
            SkewedKeys gen = new SkewedKeys(keys, 1.0, 7);
            long hits = 0;
            for (int i = 0; i < requests; i++) {
                int k = gen.next();
                Integer v = cache.get(k);
                if (v != null) hits++;
                else cache.put(k, db.read(k));   // cache-aside: on miss, read DB, fill cache
            }
            double h = hits / (double) requests;
            double avg = h * cacheMs + (1 - h) * dbMs;   // the one formula that runs this whole field
            System.out.printf("%-12d %-10s %-14s %-16s (%.0f%%)%n",
                size, String.format("%.1f%%", h * 100), String.format("%.3f ms", avg),
                String.format("%,d", db.calls.get()), size * 100.0 / keys);
        }
        System.out.println("\nRead the last column against the first: 10% of the keyspace captures the vast");
        System.out.println("majority of traffic — that skew is WHY caching works. And note the diminishing");
        System.out.println("returns: each doubling of memory buys a smaller hit-ratio gain than the last.");
    }

    // ══════════════════════════════════════════════════════════════
    // Scenario: eviction — policy shootout, without and with a scan
    // ══════════════════════════════════════════════════════════════
    /**
     * Returns the hit ratio for USER requests only. The scan's own requests are
     * excluded from the measurement on purpose: they always miss (each scanned row
     * is read once), so counting them would drop the ratio for a purely definitional
     * reason. What we want to see is the damage the scan does to the user traffic —
     * that is what scan pollution means.
     */
    static double measure(Cache cache, int keys, int requests, boolean withScan, long seed) {
        SkewedKeys gen = new SkewedKeys(keys, 1.0, seed);
        long userHits = 0, userRequests = 0;
        int scanKey = keys; // scan reads keys OUTSIDE the hot set: ids keys..2*keys
        for (int i = 0; i < requests; i++) {
            int k = gen.next();
            userRequests++;
            if (cache.get(k) != null) userHits++; else cache.put(k, k);
            if (withScan && i % 4 == 0) {          // every 4th request, one step of a rolling full-table scan
                int s = scanKey++; if (scanKey >= 2 * keys) scanKey = keys;
                if (cache.get(s) == null) cache.put(s, s);  // not counted: measuring damage TO users, not the scan
            }
        }
        return userHits / (double) userRequests;
    }

    /**
     * A workload whose popularity SHIFTS halfway through: keys 0..999 are hot for the
     * first half, then a different set (5000..5999) becomes hot. This is the falsifying
     * test for LFU: frequency counted over all history is a great predictor only while
     * the future resembles the past.
     */
    static double measureShifting(Cache cache, int requests, long seed) {
        Random rnd = new Random(seed);
        long hits = 0;
        for (int i = 0; i < requests; i++) {
            boolean secondHalf = i >= requests / 2;
            int base = secondHalf ? 5000 : 0;
            // 90% of traffic goes to the current 1000-key hot set, 10% scattered cold
            int k = rnd.nextDouble() < 0.9 ? base + rnd.nextInt(1000) : rnd.nextInt(10_000);
            if (cache.get(k) != null) hits++; else cache.put(k, k);
        }
        return hits / (double) requests;
    }

    static void runEviction() {
        int keys = 10_000, requests = 200_000, size = 1000;
        System.out.printf("Cache size %,d. Three workloads, same cache, four policies.%n%n", size);
        System.out.println("  A. skewed        — a few keys hot, most cold, popularity never changes");
        System.out.println("  B. + scan        — A, plus a rolling report reading cold rows once each");
        System.out.println("  C. shifting      — the hot set MOVES halfway through (a menu changes, a new city launches)");
        System.out.println("     (B and C measure hit ratio on user requests only, so the numbers stay comparable.)\n");
        System.out.printf("%-10s %-14s %-14s %-14s%n", "policy", "A: skewed", "B: + scan", "C: shifting");
        for (String policy : new String[]{"FIFO", "LRU", "LFU", "Random"}) {
            double clean = measure(makeCache(policy, size), keys, requests, false, 7);
            double scanned = measure(makeCache(policy, size), keys, requests, true, 7);
            double shifting = measureShifting(makeCache(policy, size), requests, 7);
            System.out.printf("%-10s %-14s %-14s %-14s%n", policy,
                String.format("%.1f%%", clean * 100),
                String.format("%.1f%%", scanned * 100),
                String.format("%.1f%%", shifting * 100));
        }
        System.out.println("\nWhat the three columns prove, in order:");
        System.out.println("A: LRU beats FIFO and Random — refreshing recency on every hit is worth ~4 points.");
        System.out.println("   LFU leads here because popularity NEVER changes, so counting history predicts");
        System.out.println("   the future perfectly. Hold that thought until column C.");
        System.out.println("B: the scan costs LRU and FIFO ~5 points of user hit ratio — scanned-once keys look");
        System.out.println("   'most recent' and evict genuinely hot entries. LFU is untouched: a frequency-1");
        System.out.println("   stranger cannot displace a proven resident. That is scan resistance.");
        System.out.println("C: now the hot set MOVES — and LFU's virtue becomes its disease. Old keys carry huge");
        System.out.println("   accumulated counts, so the new hot set cannot get established: LFU falls behind the");
        System.out.println("   recency policies it beat in A. This is cache squatting, and it is why real caches");
        System.out.println("   (Caffeine's W-TinyLFU) combine frequency with recency AND age out old counts.");
    }

    static Cache makeCache(String policy, int size) {
        return switch (policy) {
            case "FIFO" -> new FifoCache(size);
            case "LRU" -> new LruCache(size);
            case "LFU" -> new LfuCache(size);
            default -> new RandomCache(size, 42);
        };
    }

    // ══════════════════════════════════════════════════════════════
    // Scenario: stampede — one expired hot key, 200 concurrent readers
    // ══════════════════════════════════════════════════════════════
    static void runStampede() throws Exception {
        int threads = 200;
        System.out.printf("The hot key ('today's menu') just expired. %d requests arrive in the same instant.%n", threads);
        System.out.println("Each DB read takes 50 ms. Watch the DB call counter.\n");

        for (boolean singleFlight : new boolean[]{false, true}) {
            SlowDatabase db = new SlowDatabase(50);
            ConcurrentHashMap<Integer, Integer> cache = new ConcurrentHashMap<>(); // empty: the key just expired
            ConcurrentHashMap<Integer, CompletableFuture<Integer>> inFlight = new ConcurrentHashMap<>();
            long t0 = System.nanoTime();
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int i = 0; i < threads; i++) {
                    executor.submit(() -> {
                        Integer v = cache.get(1);
                        if (v != null) return v;
                        if (!singleFlight) {
                            int loaded = db.read(1);          // everyone who missed goes to the DB — the stampede
                            cache.put(1, loaded);
                            return loaded;
                        }
                        // single-flight: the FIRST misser creates the load; everyone else awaits the same future.
                        CompletableFuture<Integer> mine = new CompletableFuture<>();
                        CompletableFuture<Integer> existing = inFlight.putIfAbsent(1, mine);
                        if (existing != null) return existing.join();   // join the flight already in the air
                        try {
                            int loaded = db.read(1);                    // exactly one DB call per key per expiry
                            cache.put(1, loaded);
                            mine.complete(loaded);
                            return loaded;
                        } finally { inFlight.remove(1); }
                    });
                }
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            System.out.printf("%-28s DB calls: %-6d wall time: %d ms%n",
                singleFlight ? "WITH single-flight lock:" : "WITHOUT protection:", db.calls.get(), ms);
        }
        System.out.println("\nWithout protection the DB absorbed the entire burst for one key — multiply by every");
        System.out.println("hot key expiring at lunch peak and this is how caches take databases down. Single-flight");
        System.out.println("(per-key deduplication of loads) turns N concurrent misses into 1 DB call.");
    }

    // ══════════════════════════════════════════════════════════════
    // Scenario: stale-race — delete-then-repopulate, and the version fix
    // ══════════════════════════════════════════════════════════════
    static void runStaleRace() throws Exception {
        System.out.println("The race: a reader fetches the OLD value from the DB, then a writer updates the DB");
        System.out.println("and invalidates the cache, then the slow reader finishes and fills the cache with the");
        System.out.println("value it fetched before the update. The invalidation ran perfectly — and the cache is");
        System.out.println("now wrong until the next write (or forever, without a TTL).\n");

        for (boolean versionCheck : new boolean[]{false, true}) {
            SlowDatabase db = new SlowDatabase(0);
            db.write(1, 100); // price of dish #1 is ₹100
            Map<Integer, Integer> cache = new ConcurrentHashMap<>();
            AtomicLong dbVersion = new AtomicLong(1);          // stands in for a real DB's row version / lease token
            Map<Integer, Long> fillVersion = new ConcurrentHashMap<>();

            CountDownLatch readerFetched = new CountDownLatch(1);
            CountDownLatch writerDone = new CountDownLatch(1);

            Thread reader = Thread.ofVirtual().start(() -> {
                long seen = dbVersion.get();                    // reader remembers WHICH truth it read
                int value = db.read(1);                         // reads ₹100 — currently correct
                readerFetched.countDown();
                try { writerDone.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                // reader was slow (GC pause, network) — the world changed while it held the old value
                if (!versionCheck) {
                    cache.put(1, value);                        // naive fill: installs outdated truth AFTER the invalidation
                } else {
                    if (dbVersion.get() == seen) {              // the fix: fill only if truth hasn't moved since the read
                        cache.put(1, value);
                        fillVersion.put(1, seen);
                    } // else: discard the fill; next reader will fetch fresh
                }
            });

            Thread writer = Thread.ofVirtual().start(() -> {
                try { readerFetched.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                db.write(1, 120);                               // price is now ₹120
                dbVersion.incrementAndGet();
                cache.remove(1);                                // textbook invalidate-on-write — and still not enough
                writerDone.countDown();
            });

            reader.join(); writer.join();
            Integer cached = cache.get(1);
            int truth = db.read(1);
            System.out.printf("%-24s cache says: %-8s DB truth: %d   → %s%n",
                versionCheck ? "WITH version check:" : "NAIVE fill:",
                cached == null ? "(empty)" : "₹" + cached, truth,
                (cached != null && cached != truth) ? "STALE — cache and truth disagree" : "consistent");
        }
        System.out.println("\nThe naive run shows the trap: every step was 'correct', the interleaving was not.");
        System.out.println("Real fixes are versions of the same idea — prove the truth didn't move between your");
        System.out.println("read and your fill: memcached leases (Facebook's fix), compare-and-set fills, or");
        System.out.println("accepting the race and bounding the damage with a TTL.");
    }

    public static void main(String[] args) throws Exception {
        String scenario = args.length > 0 ? args[0] : "test";
        System.out.println("═".repeat(78));
        switch (scenario) {
            case "test" -> runTests();
            case "hit-rate" -> runHitRate();
            case "eviction" -> runEviction();
            case "stampede" -> runStampede();
            case "stale-race" -> runStaleRace();
            default -> System.out.println("Unknown scenario. Use: test | hit-rate | eviction | stampede | stale-race");
        }
        System.out.println("═".repeat(78));
    }
}
