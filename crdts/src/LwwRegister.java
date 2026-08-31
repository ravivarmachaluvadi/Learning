import java.util.Objects;

/**
 * LWW-Register ("last-writer-wins register") — one slot holding one value, the
 * newest write wins. You met this in the quorums-and-replication lab as the
 * conflict rule that silently discarded 1,000 of 1,000 concurrent updates.
 *
 * It IS a legitimate CRDT: merge = "keep the entry with the bigger
 * (timestamp, replicaId)", which is commutative, associative and idempotent.
 * The tie-break on replicaId is not decoration — without it, two writes with
 * the same millisecond timestamp merge differently depending on direction and
 * the replicas diverge forever.
 *
 * So it converges. It just converges by throwing one of the two writes in the
 * bin, and your wall clock decides which. That is the entire argument for
 * every other type in this lab.
 */
public final class LwwRegister<V> implements Replicated<LwwRegister<V>> {

    private final V value;
    private final long timestamp;
    private final String replica;   // tie-break when timestamps collide

    public LwwRegister() { this(null, Long.MIN_VALUE, ""); }
    private LwwRegister(V value, long timestamp, String replica) {
        this.value = value; this.timestamp = timestamp; this.replica = replica;
    }

    public LwwRegister<V> assign(V newValue, long timestamp, String replica) {
        return new LwwRegister<>(newValue, timestamp, replica);
    }

    @Override public LwwRegister<V> merge(LwwRegister<V> other) {
        return wins(other, this) ? other : this;
    }

    /** total order on (timestamp, replica) — total, so merge can never be ambiguous */
    private static boolean wins(LwwRegister<?> a, LwwRegister<?> b) {
        if (a.timestamp != b.timestamp) return a.timestamp > b.timestamp;
        return a.replica.compareTo(b.replica) > 0;
    }

    public V value() { return value; }
    public long timestamp() { return timestamp; }
    public int metadataCells() { return 2; }   // one timestamp, one replica id

    @Override public boolean equals(Object o) {
        return o instanceof LwwRegister<?> r
            && Objects.equals(value, r.value) && timestamp == r.timestamp && replica.equals(r.replica);
    }
    @Override public int hashCode() { return Objects.hash(value, timestamp, replica); }
    @Override public String toString() { return value + "@" + timestamp + "/" + replica; }
}
