import java.util.*;

/**
 * A version vector: "how much of each replica's history have I seen".
 * Identical in shape to a G-Counter — and that is not a coincidence, it is the
 * same lattice. You used this in the quorums-and-replication lab to detect
 * siblings; here it is the causal context that tells a multi-value register
 * which of its candidates are stale and which are genuinely concurrent.
 */
public record VersionVector(SortedMap<String, Long> entries) implements Comparable<VersionVector> {

    public static VersionVector empty() { return new VersionVector(new TreeMap<>()); }

    public VersionVector bump(String replica) {
        TreeMap<String, Long> next = new TreeMap<>(entries);
        next.merge(replica, 1L, Long::sum);
        return new VersionVector(next);
    }

    public VersionVector join(VersionVector other) {
        TreeMap<String, Long> next = new TreeMap<>(entries);
        other.entries.forEach((k, v) -> next.merge(k, v, Math::max));
        return new VersionVector(next);
    }

    /** true when every entry of this is <= other's — i.e. this happened before (or equals) other */
    public boolean dominatedBy(VersionVector other) {
        for (var e : entries.entrySet())
            if (e.getValue() > other.entries.getOrDefault(e.getKey(), 0L)) return false;
        return true;
    }

    public boolean strictlyDominatedBy(VersionVector other) {
        return dominatedBy(other) && !equals(other);
    }

    @Override public int compareTo(VersionVector o) { return entries.toString().compareTo(o.entries.toString()); }
    @Override public String toString() { return entries.toString(); }
}
