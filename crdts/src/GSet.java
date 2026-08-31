import java.util.*;

/**
 * G-Set ("grow-only set") — the simplest CRDT there is. Add things; merge is
 * set union. Union is commutative, associative and idempotent for free, so
 * this converges with no extra machinery at all.
 *
 * Its whole problem is the one thing it cannot do: remove.
 */
public final class GSet<E extends Comparable<E>> implements Replicated<GSet<E>> {

    private final SortedSet<E> items;

    public GSet() { this.items = Collections.unmodifiableSortedSet(new TreeSet<>()); }
    private GSet(SortedSet<E> items) { this.items = Collections.unmodifiableSortedSet(items); }

    public GSet<E> add(E e) {
        TreeSet<E> next = new TreeSet<>(items);
        next.add(e);
        return new GSet<>(next);
    }

    @Override public GSet<E> merge(GSet<E> other) {
        TreeSet<E> next = new TreeSet<>(items);
        next.addAll(other.items);   // INVARIANT: union — the three laws hold trivially.
        return new GSet<>(next);
    }

    public Set<E> value() { return items; }
    public boolean contains(E e) { return items.contains(e); }
    public int metadataCells() { return items.size(); }

    @Override public boolean equals(Object o) { return o instanceof GSet<?> g && items.equals(g.items); }
    @Override public int hashCode() { return items.hashCode(); }
    @Override public String toString() { return items.toString(); }
}
