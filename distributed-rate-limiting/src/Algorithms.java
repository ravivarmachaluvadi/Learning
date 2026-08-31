import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * All six counting algorithms, side by side in one file ON PURPOSE.
 *
 * The point of this lab is not that any one of them is clever. It is that they
 * are six different answers to the SAME question — "has this key used up its
 * quota?" — and each one trades accuracy against memory against smoothness.
 * Reading them in one file makes the trade visible: look at how many numbers
 * each one stores per key (`stateSizeFor`), and at how it decides.
 */
public final class Algorithms {
    private Algorithms() {}

    /**
     * Floating-point slack, and it is NOT a cosmetic detail — it is a bug this
     * lab's own test suite caught.  `retry_after_is_honest_for_every_limiter`
     * failed until this existed: the limiter said "retry in 200000 us", the
     * client retried at exactly that microsecond, and got 0.9999999996 tokens
     * instead of 1.0 because `elapsed * rate` does not land exactly on integers
     * in binary floating point.  In production that is a client obeying your
     * Retry-After header, being refused anyway, and retrying again — a retry
     * storm YOU caused.  See README stage 11, failure F7.
     */
    static final double EPS = 1e-9;

    // ---------------------------------------------------------------------
    // 1. FIXED WINDOW — the one everybody writes first, and the one with the
    //    famous bug. "How many since the top of the minute?"
    // ---------------------------------------------------------------------
    public static final class FixedWindow implements Limiter {
        private final int limit;
        private final long windowMicros;
        private final Map<String, long[]> state = new HashMap<>(); // [windowId, used]

        public FixedWindow(int limit, long windowMicros) {
            this.limit = limit; this.windowMicros = windowMicros;
        }

        @Override public Decision allow(String key, int cost, long now) {
            long windowId = Math.floorDiv(now, windowMicros);
            long[] s = state.computeIfAbsent(key, k -> new long[]{windowId, 0});
            // THE WHOLE ALGORITHM: when the clock ticks over into a new window,
            // the counter is thrown away. That single line is also the bug —
            // it means a client's history vanishes at a moment it can predict.
            if (s[0] != windowId) { s[0] = windowId; s[1] = 0; }
            if (s[1] + cost <= limit) {
                s[1] += cost;
                return Decision.allow(limit - s[1]);
            }
            long resetAt = (windowId + 1) * windowMicros;
            return Decision.deny(resetAt - now, limit - s[1]);
        }

        @Override public String name() { return "fixed-window"; }
        @Override public long stateSizeFor(String key) { return 2; } // windowId + count
    }

    // ---------------------------------------------------------------------
    // 2. SLIDING WINDOW LOG — perfectly correct, and that is the problem.
    //    Remember every request time; count the ones inside the last window.
    // ---------------------------------------------------------------------
    public static final class SlidingLog implements Limiter {
        private final int limit;
        private final long windowMicros;
        private final Map<String, Deque<Long>> state = new HashMap<>();

        public SlidingLog(int limit, long windowMicros) {
            this.limit = limit; this.windowMicros = windowMicros;
        }

        @Override public Decision allow(String key, int cost, long now) {
            Deque<Long> log = state.computeIfAbsent(key, k -> new ArrayDeque<>());
            long cutoff = now - windowMicros;
            // INVARIANT: after this loop, the log contains exactly the requests
            // that happened inside the trailing window. Nothing is approximated.
            while (!log.isEmpty() && log.peekFirst() <= cutoff) log.pollFirst();

            if (log.size() + cost <= limit) {
                for (int i = 0; i < cost; i++) log.addLast(now);
                return Decision.allow(limit - log.size());
            }
            // The oldest request must age out before there is room.
            long oldest = log.peekFirst();
            return Decision.deny(oldest + windowMicros - now, limit - log.size());
        }

        @Override public String name() { return "sliding-log"; }
        @Override public long stateSizeFor(String key) {
            Deque<Long> log = state.get(key);
            return log == null ? 0 : log.size(); // one timestamp PER REQUEST — this is the bill
        }
    }

    // ---------------------------------------------------------------------
    // 3. SLIDING WINDOW COUNTER — the engineering compromise. Keep two
    //    counters and pretend the previous window's traffic was evenly spread.
    // ---------------------------------------------------------------------
    public static final class SlidingCounter implements Limiter {
        private final int limit;
        private final long windowMicros;
        private final Map<String, long[]> state = new HashMap<>(); // [windowId, curr, prev]

        public SlidingCounter(int limit, long windowMicros) {
            this.limit = limit; this.windowMicros = windowMicros;
        }

        @Override public Decision allow(String key, int cost, long now) {
            long windowId = Math.floorDiv(now, windowMicros);
            long[] s = state.computeIfAbsent(key, k -> new long[]{windowId, 0, 0});
            if (s[0] != windowId) {
                s[2] = (windowId == s[0] + 1) ? s[1] : 0; // only the immediately previous window counts
                s[1] = 0;
                s[0] = windowId;
            }
            long elapsed = now - windowId * windowMicros;
            // THE APPROXIMATION: assume the previous window's requests were
            // spread evenly, so the fraction still "inside" the sliding window
            // shrinks linearly as we move through the current window.
            double weight = 1.0 - (double) elapsed / windowMicros;
            double estimate = s[2] * weight + s[1];

            if (estimate + cost <= limit + EPS) {
                s[1] += cost;
                return Decision.allow(limit - (estimate + cost));
            }
            return Decision.deny(retryAfter(s, now, windowId, cost), limit - estimate);
        }

        /**
         * Getting THIS right is harder than the allow() path, and the first
         * version was wrong — see README stage 11, failure F7. The naive answer
         * ("wait for the window to flip") is a lie, because at the flip the
         * CURRENT count becomes the previous count and is weighted at 1.0, so
         * the estimate can be exactly as high as it was a microsecond earlier.
         * There are two phases to consider, in order.
         */
        private long retryAfter(long[] s, long now, long windowId, int cost) {
            if (cost > limit) return windowMicros;      // can never fit, whatever we say
            long windowStart = windowId * windowMicros;
            long windowEnd = windowStart + windowMicros;
            long prev = s[2], curr = s[1];

            // PHASE 1 — still inside this window: the previous window's
            // contribution shrinks as time passes. Is there a moment it is small
            // enough? Only if the current window alone already leaves room.
            double roomForPrev = limit - curr - cost;
            if (prev > 0 && roomForPrev >= 0) {
                double neededWeight = roomForPrev / prev;
                if (neededWeight < 1.0) {
                    long elapsedNeeded = (long) Math.ceil((1.0 - neededWeight) * windowMicros);
                    return Math.max(1, windowStart + elapsedNeeded - now);
                }
                return 1;                                // unreachable from the deny branch
            }

            // PHASE 2 — the window flips, `curr` becomes the new previous count,
            // and now IT has to age out before there is room.
            if (curr + cost <= limit || curr <= 0) return Math.max(1, windowEnd - now);
            double neededWeight2 = (limit - cost) / (double) curr;
            if (neededWeight2 < 0) neededWeight2 = 0;
            long elapsedNeeded2 = (long) Math.ceil((1.0 - neededWeight2) * windowMicros);
            return Math.max(1, windowEnd + elapsedNeeded2 - now);
        }

        @Override public String name() { return "sliding-counter"; }
        @Override public long stateSizeFor(String key) { return 3; } // windowId + curr + prev
    }

    // ---------------------------------------------------------------------
    // 4. TOKEN BUCKET — the one most production systems actually use.
    //    A bucket of tokens refilled by a steady drip; a request spends one.
    // ---------------------------------------------------------------------
    public static final class TokenBucket implements Limiter {
        private final double capacity;        // burst: how much saved-up credit you may spend at once
        private final double tokensPerMicro;  // steady rate
        private final Map<String, double[]> state = new HashMap<>(); // [tokens, lastRefillMicros]

        public TokenBucket(double capacity, double tokensPerMicro) {
            this.capacity = capacity; this.tokensPerMicro = tokensPerMicro;
        }

        /** Convenience: `limit` requests per `windowMicros`, allowed to burst up to `burst`. */
        public static TokenBucket perWindow(int limit, long windowMicros, double burst) {
            return new TokenBucket(burst, (double) limit / windowMicros);
        }

        @Override public Decision allow(String key, int cost, long now) {
            double[] s = state.computeIfAbsent(key, k -> new double[]{capacity, now});
            // LAZY REFILL — no background timer anywhere. We compute how many
            // tokens *would* have dripped in since we last looked. This is the
            // trick that makes token buckets cheap enough for millions of keys.
            double elapsed = now - s[1];
            s[0] = Math.min(capacity, s[0] + elapsed * tokensPerMicro);
            s[1] = now;

            if (s[0] >= cost - EPS) {
                s[0] = Math.max(0, s[0] - cost);
                return Decision.allow(s[0]);
            }
            long wait = (long) Math.ceil((cost - s[0]) / tokensPerMicro);
            return Decision.deny(wait, s[0]);
        }

        @Override public String name() { return "token-bucket"; }
        @Override public long stateSizeFor(String key) { return 2; } // tokens + lastRefill
    }

    // ---------------------------------------------------------------------
    // 5. LEAKY BUCKET (as a meter) — water pours in, drains at a constant
    //    rate, overflow is rejected. Test `leaky_bucket_is_token_bucket_inverted`
    //    proves this makes the SAME decisions as #4. Same machine, mirrored.
    // ---------------------------------------------------------------------
    public static final class LeakyBucketMeter implements Limiter {
        private final double capacity;
        private final double drainPerMicro;
        private final Map<String, double[]> state = new HashMap<>(); // [level, lastDrainMicros]

        public LeakyBucketMeter(double capacity, double drainPerMicro) {
            this.capacity = capacity; this.drainPerMicro = drainPerMicro;
        }

        @Override public Decision allow(String key, int cost, long now) {
            double[] s = state.computeIfAbsent(key, k -> new double[]{0, now});
            double elapsed = now - s[1];
            s[0] = Math.max(0, s[0] - elapsed * drainPerMicro);
            s[1] = now;
            if (s[0] + cost <= capacity + EPS) {
                s[0] = Math.min(capacity, s[0] + cost);
                return Decision.allow(capacity - s[0]);
            }
            long wait = (long) Math.ceil((s[0] + cost - capacity) / drainPerMicro);
            return Decision.deny(wait, capacity - s[0]);
        }

        @Override public String name() { return "leaky-bucket-meter"; }
        @Override public long stateSizeFor(String key) { return 2; }
    }

    // ---------------------------------------------------------------------
    // 6. GCRA — the same behaviour as a token bucket, stored in ONE number.
    //    Instead of "how many tokens are left", remember "the earliest time
    //    the next request is theoretically due". Redis's CL.THROTTLE and
    //    telecom traffic policers use exactly this.
    // ---------------------------------------------------------------------
    public static final class Gcra implements Limiter {
        private final double emissionMicros;   // one request "costs" this much time
        private final double toleranceMicros;  // how far ahead of schedule you may run = burst
        private final Map<String, Double> tat = new HashMap<>(); // theoretical arrival time

        /** Match a token bucket of `burst` capacity refilling at limit/windowMicros. */
        public Gcra(int limit, long windowMicros, double burst) {
            this.emissionMicros = (double) windowMicros / limit;
            this.toleranceMicros = emissionMicros * burst;
        }

        @Override public Decision allow(String key, int cost, long now) {
            double stored = tat.getOrDefault(key, (double) now);
            double t = Math.max(stored, now);          // you cannot be "ahead" of an idle period
            double newTat = t + emissionMicros * cost; // spending time instead of tokens
            double allowAt = newTat - toleranceMicros;
            if (now < allowAt - EPS) {
                return Decision.deny((long) Math.ceil(allowAt - now),
                                     Math.max(0, (now - (t - toleranceMicros)) / emissionMicros));
            }
            tat.put(key, newTat);
            return Decision.allow(Math.max(0, (now - (newTat - toleranceMicros)) / emissionMicros));
        }

        @Override public String name() { return "gcra"; }
        @Override public long stateSizeFor(String key) { return 1; } // ONE number. That is the whole point.
    }

    // ---------------------------------------------------------------------
    // 7. LEAKY BUCKET AS A QUEUE — the odd one out. The five above are
    //    POLICERS: they answer yes/no immediately. This one is a SHAPER: it
    //    accepts the request and decides *when* it will be released, so the
    //    downstream sees a perfectly even stream. Used in Experiment 3.
    // ---------------------------------------------------------------------
    public static final class LeakyBucketQueue {
        private final int queueCapacity;
        private final long intervalMicros; // one release every interval
        private final Deque<Long> queue = new ArrayDeque<>();
        private long nextRelease = Long.MIN_VALUE;

        public LeakyBucketQueue(int queueCapacity, long intervalMicros) {
            this.queueCapacity = queueCapacity; this.intervalMicros = intervalMicros;
        }

        /** @return the time this request will actually be forwarded, or -1 if the queue is full. */
        public long offer(long now) {
            while (!queue.isEmpty() && queue.peekFirst() <= now) queue.pollFirst();
            if (queue.size() >= queueCapacity) return -1;
            long release = Math.max(now, nextRelease);
            nextRelease = release + intervalMicros;
            queue.addLast(release);
            return release;
        }

        public String name() { return "leaky-bucket-queue"; }
    }
}
