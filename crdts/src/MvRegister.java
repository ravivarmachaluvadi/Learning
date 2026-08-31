import java.util.*;

/**
 * MV-Register ("multi-value register") — the honest alternative to LWW.
 *
 * When two writes are genuinely concurrent, LWW picks one and destroys the
 * other. An MV-Register refuses to pick: it keeps BOTH and hands the pair to
 * whoever reads it. Those two survivors are exactly the "siblings" you saw in
 * the quorums lab; Riak and Dynamo work this way.
 *
 * Mechanism: each candidate carries the version vector it was written against.
 * A write joins every vector it can see, bumps its own entry, and replaces all
 * candidates. Merge takes the union and then drops every candidate that some
 * other candidate strictly dominates — a dominated candidate is not a rival
 * opinion, it is an ancestor, and its author has already been overruled by
 * someone who saw it.
 */
public final class MvRegister<V extends Comparable<V>> implements Replicated<MvRegister<V>> {

    public record Candidate<V>(VersionVector clock, V value) {}

    private final List<Candidate<V>> candidates;

    public MvRegister() { this(List.of()); }
    private MvRegister(List<Candidate<V>> candidates) { this.candidates = List.copyOf(candidates); }

    public MvRegister<V> assign(V value, String replica) {
        VersionVector clock = VersionVector.empty();
        for (Candidate<V> c : candidates) clock = clock.join(c.clock());
        // INVARIANT: a write must dominate everything it saw — that is how it
        // earns the right to replace those candidates instead of joining them.
        return new MvRegister<>(List.of(new Candidate<>(clock.bump(replica), value)));
    }

    @Override public MvRegister<V> merge(MvRegister<V> other) {
        List<Candidate<V>> pool = new ArrayList<>(candidates);
        for (Candidate<V> c : other.candidates)
            if (pool.stream().noneMatch(p -> p.clock().equals(c.clock()) && p.value().equals(c.value())))
                pool.add(c);

        List<Candidate<V>> survivors = new ArrayList<>();
        for (Candidate<V> c : pool) {
            boolean beaten = pool.stream().anyMatch(o -> c.clock().strictlyDominatedBy(o.clock()));
            if (!beaten) survivors.add(c);
        }
        survivors.sort(Comparator.comparing((Candidate<V> c) -> c.value()).thenComparing(Candidate::clock));
        return new MvRegister<>(survivors);
    }

    /** More than one entry means "we could not decide — you decide". */
    public List<V> values() { return candidates.stream().map(Candidate::value).toList(); }
    public boolean isConflicted() { return candidates.size() > 1; }
    public int metadataCells() { return candidates.stream().mapToInt(c -> c.clock().entries().size()).sum(); }

    @Override public boolean equals(Object o) { return o instanceof MvRegister<?> r && candidates.equals(r.candidates); }
    @Override public int hashCode() { return candidates.hashCode(); }
    @Override public String toString() { return values().toString(); }
}
