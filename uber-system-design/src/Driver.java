import java.util.concurrent.atomic.AtomicReference;

/**
 * A driver, and the one line that carries the whole lab:
 *
 *     AtomicReference<DriverState> state
 *
 * A driver is not a row you read and later write -- a driver is a SINGLE-OWNER
 * RESOURCE that exactly one dispatch attempt may claim at a time. Everything in
 * Dispatch.java rests on being able to say "move this driver from AVAILABLE to
 * OFFERED, and tell me whether I was the one who did it".
 */
final class Driver {
    final String id;
    volatile Loc loc;
    /** Wall-clock of the last location ping. A driver who stopped pinging is a ghost. */
    volatile long lastPingMs;
    /** How much slower than crow-flies this driver's roads are. 1.0 = teleport. */
    final double roadFactor;

    /**
     * INVARIANT MACHINERY. Every state change goes through compareAndSet, so
     * "two threads both saw AVAILABLE" can never become "two threads both
     * proceeded". Exactly one CAS wins; the loser is told it lost.
     */
    final AtomicReference<DriverState> state = new AtomicReference<>(DriverState.OFFLINE);

    /** Set only by the thread that won the CAS into OFFERED. */
    volatile Offer offer;

    Driver(String id, Loc loc, double roadFactor) {
        this.id = id;
        this.loc = loc;
        this.roadFactor = roadFactor;
    }

    @Override public String toString() { return id + "@" + loc + "/" + state.get(); }
}
