import java.util.*;

/**
 * The keyspace: one big hash table from key -> typed value, plus the two things
 * that make it a *bounded* store rather than a leak: expiry and eviction.
 *
 * The single most important design fact in this file: the value is TYPED. It is
 * not an opaque blob. Because the store knows that "visits" holds a number and
 * "queue" holds a list, the server can offer INCR and LPUSH as single operations.
 * That is what lets a caller change shared state without ever holding a lock --
 * the whole reason Redis exists rather than "memcached with more commands".
 */
final class Store {

    enum Type { STRING, LIST, HASH, SET, ZSET }

    /** One value in the keyspace, plus the bookkeeping eviction needs. */
    static final class Value {
        final Type type;
        final Object data;
        long lastAccessMs;   // for LRU: when was this value last read or written?
        int  freq;           // for LFU: roughly how often is it used?

        Value(Type t, Object d, long now) { type = t; data = d; lastAccessMs = now; freq = 1; }

        @SuppressWarnings("unchecked") String str()               { return (String) data; }
        @SuppressWarnings("unchecked") Deque<String> list()       { return (Deque<String>) data; }
        @SuppressWarnings("unchecked") Map<String,String> hash()  { return (Map<String,String>) data; }
        @SuppressWarnings("unchecked") Set<String> set()          { return (Set<String>) data; }
        @SuppressWarnings("unchecked") Map<String,Double> zset()  { return (Map<String,Double>) data; }
    }

    // The two tables. Real Redis has exactly this pair too: `dict` and `expires`.
    final Map<String, Value> dict    = new HashMap<>();
    final Map<String, Long>  expires = new HashMap<>();   // key -> absolute deadline in ms

    // Configuration knobs, named after the real redis.conf directives.
    long   maxMemory       = 0;             // 0 = unlimited
    String maxMemoryPolicy = "noeviction";
    int    maxMemorySamples = 5;            // real Redis default

    // Counters that INFO reports and the experiments measure.
    long hits, misses, expiredKeys, evictedKeys, commandsProcessed;

    private final Random rnd = new Random(42);
    private long clockOffsetMs = 0;         // lets experiments fast-forward time

    long now() { return System.currentTimeMillis() + clockOffsetMs; }
    void advanceClock(long ms) { clockOffsetMs += ms; }

    // ------------------------------------------------------------------
    // Lookup -- and the first surprising thing about expiry
    // ------------------------------------------------------------------

    /**
     * INVARIANT (lazy expiry): a key past its deadline must never be VISIBLE, but it
     * may still be RESIDENT. Redis does not delete a key the moment it expires --
     * nothing is watching the clock per key. It notices on the next touch, here.
     *
     * That is why "the TTL passed" and "the memory came back" are two different
     * events, and why a Redis instance can sit above maxmemory holding nothing but
     * dead keys. activeExpireCycle() below is the other half of the answer.
     */
    Value lookup(String key) {
        Value v = dict.get(key);
        if (v == null) { misses++; return null; }
        Long deadline = expires.get(key);
        if (deadline != null && now() >= deadline) {
            dict.remove(key); expires.remove(key); expiredKeys++; misses++;
            return null;
        }
        v.lastAccessMs = now();
        if (v.freq < 255) v.freq++;
        hits++;
        return v;
    }

    /** Same as lookup but does not move the key's LRU clock -- used by TTL, TYPE, etc. */
    boolean existsNoTouch(String key) {
        Value v = dict.get(key);
        if (v == null) return false;
        Long d = expires.get(key);
        if (d != null && now() >= d) { dict.remove(key); expires.remove(key); expiredKeys++; return false; }
        return true;
    }

    void put(String key, Value v)      { dict.put(key, v); }
    void removeExpiry(String key)      { expires.remove(key); }
    void setExpiryAt(String k, long t) { expires.put(k, t); }
    Long expiryOf(String key)          { return expires.get(key); }

    boolean delete(String key) {
        expires.remove(key);
        return dict.remove(key) != null;
    }

    void flush() { dict.clear(); expires.clear(); }

    /** Keys currently resident AND not yet past their deadline. */
    List<String> liveKeys() {
        List<String> out = new ArrayList<>();
        for (String k : new ArrayList<>(dict.keySet())) if (existsNoTouch(k)) out.add(k);
        return out;
    }


    // ------------------------------------------------------------------
    // Versions -- what makes WATCH possible
    // ------------------------------------------------------------------

    /**
     * `dirty` counts real mutations. The server propagates a command to the AOF and to
     * replicas only when this number moved, so a SET NX that lost the race is never
     * replayed as though it had won.
     *
     * `versions` gives each key a counter that ticks on every real change. WATCH
     * records the counter; EXEC compares it. That is the whole of Redis's optimistic
     * concurrency -- the expected-version idea from the event-sourcing lab, per key.
     */
    long dirty;
    private final Map<String, Long> versions = new HashMap<>();

    void bump(String key) {
        dirty++;
        versions.merge(key, 1L, Long::sum);
    }

    Long versionOf(String key) { return versions.getOrDefault(key, 0L); }

    // ------------------------------------------------------------------
    // Active expiry -- the half that reclaims memory nobody is asking for
    // ------------------------------------------------------------------

    /**
     * Faithful to real Redis's activeExpireCycle: sample 20 random keys that have a
     * deadline, delete the dead ones, and if MORE THAN A QUARTER of the sample was
     * dead, immediately go round again -- on the theory that a lot must be dead.
     * Bounded rounds so it can never monopolise the one thread.
     *
     * This is PROBABILISTIC, not exhaustive. It is why expired-but-resident keys are
     * a normal steady state, not a bug.
     */
    int activeExpireCycle(int maxRounds) {
        int removed = 0;
        for (int round = 0; round < maxRounds; round++) {
            if (expires.isEmpty()) break;
            List<String> keys = new ArrayList<>(expires.keySet());
            int sample = Math.min(20, keys.size());
            int deadInSample = 0;
            long t = now();
            for (int i = 0; i < sample; i++) {
                String k = keys.get(rnd.nextInt(keys.size()));
                Long d = expires.get(k);
                if (d != null && t >= d) {
                    dict.remove(k); expires.remove(k);
                    expiredKeys++; removed++; deadInSample++;
                }
            }
            if (deadInSample * 4 <= sample) break;   // <=25% dead: stop, it isn't worth it
        }
        return removed;
    }

    // ------------------------------------------------------------------
    // Memory accounting and eviction
    // ------------------------------------------------------------------

    /**
     * SIMPLIFICATION (flagged): real Redis asks the allocator (jemalloc) how many
     * bytes it actually handed out. We estimate. The estimate is consistent enough
     * for the eviction experiments to mean something, but do not compare these
     * numbers to a real `INFO memory`.
     */
    long usedMemory() {
        long total = 0;
        for (Map.Entry<String, Value> e : dict.entrySet()) {
            total += 64 + e.getKey().length() * 2L;    // key + per-entry overhead
            total += sizeOf(e.getValue());
        }
        total += expires.size() * 48L;
        return total;
    }

    private long sizeOf(Value v) {
        switch (v.type) {
            case STRING: return 16 + v.str().length() * 2L;
            case LIST:   { long s = 16; for (String x : v.list()) s += 32 + x.length() * 2L; return s; }
            case SET:    { long s = 16; for (String x : v.set())  s += 40 + x.length() * 2L; return s; }
            case HASH:   { long s = 16; for (Map.Entry<String,String> x : v.hash().entrySet())
                                s += 48 + (x.getKey().length() + x.getValue().length()) * 2L; return s; }
            case ZSET:   { long s = 16; for (String x : v.zset().keySet()) s += 56 + x.length() * 2L; return s; }
        }
        return 16;
    }

    /**
     * INVARIANT (capacity): after this returns, usedMemory() <= maxMemory -- unless the
     * policy is noeviction, in which case the caller must reject the write instead.
     *
     * THE KEY DESIGN CHOICE, and it is a deliberate one: Redis does NOT keep an exact
     * LRU order. An exact LRU list costs two pointers per key -- real memory spent on
     * bookkeeping instead of data. So Redis SAMPLES: pick `maxmemory-samples` keys at
     * random, evict the worst one, repeat. With the default 5 samples this lands very
     * close to true LRU while costing nothing per key. Experiment 5 measures the gap.
     */
    int evictIfNeeded() {
        if (maxMemory <= 0 || maxMemoryPolicy.equals("noeviction")) return 0;
        int evicted = 0;
        int guard = 0;
        while (usedMemory() > maxMemory && !dict.isEmpty() && guard++ < 100000) {
            String victim = pickVictim();
            if (victim == null) break;
            dict.remove(victim); expires.remove(victim);
            evictedKeys++; evicted++;
        }
        return evicted;
    }

    private String pickVictim() {
        boolean volatileOnly = maxMemoryPolicy.startsWith("volatile");
        List<String> pool = volatileOnly ? new ArrayList<>(expires.keySet())
                                         : new ArrayList<>(dict.keySet());
        if (pool.isEmpty()) return null;

        if (maxMemoryPolicy.endsWith("random")) return pool.get(rnd.nextInt(pool.size()));
        // Not a real Redis policy -- it exists only so Experiment 5 can measure what the
        // sampling shortcut actually costs you in hit ratio.
        if (maxMemoryPolicy.equals("allkeys-exactlru")) return exactLruVictim();

        String best = null;
        double bestScore = Double.MAX_VALUE;
        int samples = Math.min(maxMemorySamples, pool.size());
        for (int i = 0; i < samples; i++) {
            String k = pool.get(rnd.nextInt(pool.size()));
            Value v = dict.get(k);
            if (v == null) continue;
            double score;
            if (maxMemoryPolicy.endsWith("lru"))      score = v.lastAccessMs;   // oldest touch loses
            else if (maxMemoryPolicy.endsWith("lfu")) score = v.freq;           // least used loses
            else if (maxMemoryPolicy.endsWith("ttl")) { Long d = expires.get(k); score = d == null ? Double.MAX_VALUE : d; }
            else score = v.lastAccessMs;
            if (score < bestScore) { bestScore = score; best = k; }
        }
        return best != null ? best : pool.get(rnd.nextInt(pool.size()));
    }

    /** Exact LRU over the whole keyspace -- NOT what Redis does. Experiment 5 compares them. */
    String exactLruVictim() {
        String best = null; long oldest = Long.MAX_VALUE;
        for (Map.Entry<String, Value> e : dict.entrySet())
            if (e.getValue().lastAccessMs < oldest) { oldest = e.getValue().lastAccessMs; best = e.getKey(); }
        return best;
    }
}
