/**
 * The one interface every rate limiter in this lab implements.
 *
 * Two design choices here are worth noticing before you read any algorithm:
 *
 *  1. TIME IS AN ARGUMENT, NOT A GLOBAL.  Every call takes `nowMicros`.
 *     A limiter that calls System.nanoTime() internally cannot be tested:
 *     you cannot ask it "what happens 59 seconds from now?" without waiting
 *     59 seconds.  Passing time in makes every experiment in this lab
 *     deterministic and instant.  Real implementations do the same thing —
 *     Redis rate limiters read the clock ONCE on the server and pass it
 *     into the script, so all nodes agree on "now".
 *
 *  2. THE KEY IS AN ARGUMENT.  A limiter is not "the limit on this service".
 *     It is a limit per *identity*: merchant_42, api_key_abc, IP 1.2.3.4.
 *     Choosing that key is most of the design work (README stage 10.6).
 */
public interface Limiter {

    /** What the limiter decided, and everything the caller needs to answer the client. */
    record Decision(boolean allowed, long retryAfterMicros, double remaining) {
        static Decision allow(double remaining) { return new Decision(true, 0, remaining); }
        static Decision deny(long retryAfterMicros, double remaining) {
            return new Decision(false, Math.max(0, retryAfterMicros), remaining);
        }
        @Override public String toString() {
            return allowed ? String.format("ALLOW (remaining %.2f)", remaining)
                           : String.format("DENY  (retry after %.3f ms)", retryAfterMicros / 1000.0);
        }
    }

    /**
     * @param cost how much quota this request consumes. 1 for a normal call;
     *             a bulk report might cost 20.  Cost-weighting is what stops
     *             "1000 requests/min" from meaning something wildly different
     *             for a cheap endpoint and an expensive one.
     */
    Decision allow(String key, int cost, long nowMicros);

    default Decision allow(String key, long nowMicros) { return allow(key, 1, nowMicros); }

    String name();

    /** How many numbers this limiter stores per key. The memory bill of the algorithm. */
    long stateSizeFor(String key);

    // Time helpers. Everything in this lab is in MICROseconds (long) so that
    // sub-millisecond refill rates stay exact in integer arithmetic.
    long MICROS_PER_MS = 1_000L;
    long MICROS_PER_SEC = 1_000_000L;
    long MICROS_PER_MIN = 60 * MICROS_PER_SEC;
}
