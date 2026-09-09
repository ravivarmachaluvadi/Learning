import java.util.*;

/**
 * PN-Counter — a counter that can go up AND down.
 *
 * You cannot let a replica lower its own row in a G-Counter: merge takes the
 * max, so the lower number is simply thrown away and the decrement vanishes.
 * (Experiments.java runs that failure.)
 *
 * The fix is embarrassingly simple: keep TWO grow-only counters. P counts
 * everything ever added, N counts everything ever subtracted, and the visible
 * value is P - N. Both halves still only ever grow, so both halves still merge
 * by max, so the whole thing still obeys the three laws.
 *
 *   +5 then -2  ->  P={a=5}, N={a=2}  -> value 3
 */
public final class PNCounter implements Replicated<PNCounter> {

    private final GCounter plus;
    private final GCounter minus;

    public PNCounter() { this(new GCounter(), new GCounter()); }
    private PNCounter(GCounter plus, GCounter minus) { this.plus = plus; this.minus = minus; }

    public PNCounter increment(String replica, long by) {
        return new PNCounter(plus.increment(replica, by), minus);
    }

    /** A decrement is not a smaller number — it is a bigger number in the other pile. */
    public PNCounter decrement(String replica, long by) {
        return new PNCounter(plus, minus.increment(replica, by));
    }

    @Override public PNCounter merge(PNCounter other) {
        return new PNCounter(plus.merge(other.plus), minus.merge(other.minus));
    }

    public long value() { return plus.value() - minus.value(); }

    public GCounter plusHalf() { return plus; }
    public GCounter minusHalf() { return minus; }
    public int metadataCells() { return plus.metadataCells() + minus.metadataCells(); }

    @Override public boolean equals(Object o) {
        return o instanceof PNCounter p && plus.equals(p.plus) && minus.equals(p.minus);
    }
    @Override public int hashCode() { return Objects.hash(plus, minus); }
    @Override public String toString() { return "P" + plus.rows() + " N" + minus.rows() + "=" + value(); }
}
