import java.util.*;

/**
 * 2P-Set ("two-phase set") — the first thing everybody invents when they need
 * remove: keep a grow-only set of adds and a grow-only set of removes, and
 * show adds minus removes. Both halves only grow, so it converges.
 *
 * It converges onto the wrong answer. Once "milk" is in the removes pile it is
 * dead forever: adding it again puts it back in the adds pile, where it is
 * still cancelled by the removes pile. You cannot re-add.
 *
 * That failure is what forces the OR-Set: the removes pile must name the
 * SPECIFIC add it kills, not the value.
 */
public final class TwoPSet<E extends Comparable<E>> implements Replicated<TwoPSet<E>> {

    private final GSet<E> added;
    private final GSet<E> removed;   // "tombstones", by value

    public TwoPSet() { this(new GSet<>(), new GSet<>()); }
    private TwoPSet(GSet<E> added, GSet<E> removed) { this.added = added; this.removed = removed; }

    public TwoPSet<E> add(E e)    { return new TwoPSet<>(added.add(e), removed); }
    public TwoPSet<E> remove(E e) { return new TwoPSet<>(added, removed.add(e)); }

    @Override public TwoPSet<E> merge(TwoPSet<E> other) {
        return new TwoPSet<>(added.merge(other.added), removed.merge(other.removed));
    }

    public Set<E> value() {
        TreeSet<E> live = new TreeSet<>(added.value());
        live.removeAll(removed.value());
        return live;
    }

    public int metadataCells() { return added.metadataCells() + removed.metadataCells(); }

    @Override public boolean equals(Object o) {
        return o instanceof TwoPSet<?> t && added.equals(t.added) && removed.equals(t.removed);
    }
    @Override public int hashCode() { return Objects.hash(added, removed); }
    @Override public String toString() { return value() + " (dead: " + removed + ")"; }
}
