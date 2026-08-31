import java.util.*;

/**
 * G-Counter ("grow-only counter") — a number that several machines can add to
 * at the same time, offline, with no coordination, and never lose a count.
 *
 * The idea: do not store one number. Store a SCOREBOARD with one row per
 * replica. A replica may only ever raise its OWN row. Merging two scoreboards
 * means taking the larger of each row. The visible value is the sum of rows.
 *
 *   phone-a: {a=3}         phone-b: {b=2}
 *   merge   -> {a=3, b=2}  -> value 5
 *
 * Why per-row max and not per-row sum: max is idempotent (max(3,3)=3), so the
 * same scoreboard can arrive ten times and change nothing. Sum is not
 * (3+3=6), so a duplicated message would invent counts that never happened.
 * Experiments.java measures exactly that inflation.
 */
public final class GCounter implements Replicated<GCounter> {

    private final SortedMap<String, Long> rows;

    public GCounter() { this.rows = Collections.unmodifiableSortedMap(new TreeMap<>()); }

    private GCounter(SortedMap<String, Long> rows) {
        this.rows = Collections.unmodifiableSortedMap(rows);
    }

    /** A replica raises its own row only. Raising someone else's row is the bug in Broken.java. */
    public GCounter increment(String replica, long by) {
        if (by < 0) throw new IllegalArgumentException(
            "a G-Counter can only grow — decrement needs a PN-Counter");
        TreeMap<String, Long> next = new TreeMap<>(rows);
        next.merge(replica, by, Long::sum);
        return new GCounter(next);
    }

    public GCounter increment(String replica) { return increment(replica, 1); }

    @Override public GCounter merge(GCounter other) {
        TreeMap<String, Long> next = new TreeMap<>(rows);
        // INVARIANT: per-row MAX. Commutative, associative and idempotent,
        // which is what makes this whole type immune to the network.
        other.rows.forEach((replica, count) -> next.merge(replica, count, Math::max));
        return new GCounter(next);
    }

    public long value() {
        long total = 0;
        for (long v : rows.values()) total += v;
        return total;
    }

    public Map<String, Long> rows() { return rows; }

    /** How many numbers we are storing to represent one number. */
    public int metadataCells() { return rows.size(); }

    @Override public boolean equals(Object o) { return o instanceof GCounter g && rows.equals(g.rows); }
    @Override public int hashCode() { return rows.hashCode(); }
    @Override public String toString() { return rows + "=" + value(); }
}
