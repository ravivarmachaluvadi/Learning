import java.util.*;

/**
 * OR-Set ("observed-remove set") — the set that actually works.
 *
 * The fix for the 2P-Set is one sentence: an add is not the value, it is a
 * unique EVENT. Adding "milk" mints a fresh dot such as phone-a#3 and stores
 * the pair ("milk", phone-a#3). Removing "milk" does not blacklist the word
 * "milk" — it kills exactly the dots for "milk" that this replica has actually
 * SEEN. Hence "observed-remove".
 *
 * Two consequences fall straight out, and both are the point of the design:
 *
 *  - Re-adding works. The second add mints phone-a#9, which no existing
 *    tombstone names, so it survives.
 *  - Concurrent add-vs-remove: ADD WINS. Ben's remove on a different machine
 *    only names the dots Ben had seen, and Amira's brand-new dot was not one
 *    of them, so it is untouched. That is a deliberate, documented bias, not
 *    an accident — see the doc's CORRECTNESS section.
 */
public final class OrSet<E extends Comparable<E>> implements Replicated<OrSet<E>> {

    private final SortedMap<E, SortedSet<Dot>> live;   // value -> the adds still alive
    private final SortedSet<Dot> killed;               // tombstones, by DOT not by value

    public OrSet() { this(new TreeMap<>(), new TreeSet<>()); }

    private OrSet(SortedMap<E, SortedSet<Dot>> live, SortedSet<Dot> killed) {
        this.live = live; this.killed = killed;
    }

    /** The caller supplies the dot so the test harness stays deterministic. */
    public OrSet<E> add(E e, Dot dot) {
        // INVARIANT: live and killed must never overlap, or merge (which
        // subtracts killed from live) stops being idempotent. Re-presenting a
        // dot that has already been torn up is a replay of a dead event, so it
        // is ignored rather than resurrected. A randomised law check in
        // Tests.java found this: without the guard, 995 of 50,000 random
        // triples broke a.merge(a) == a.
        if (killed.contains(dot)) return this;
        TreeMap<E, SortedSet<Dot>> nextLive = deepCopy(live);
        nextLive.computeIfAbsent(e, k -> new TreeSet<>()).add(dot);
        return new OrSet<>(nextLive, new TreeSet<>(killed));
    }

    /** Kills only the dots this replica can currently see. Everything else survives. */
    public OrSet<E> remove(E e) {
        TreeMap<E, SortedSet<Dot>> nextLive = deepCopy(live);
        TreeSet<Dot> nextKilled = new TreeSet<>(killed);
        SortedSet<Dot> observed = nextLive.remove(e);
        if (observed != null) nextKilled.addAll(observed);   // INVARIANT: observed-remove
        return new OrSet<>(nextLive, nextKilled);
    }

    @Override public OrSet<E> merge(OrSet<E> other) {
        TreeSet<Dot> nextKilled = new TreeSet<>(killed);
        nextKilled.addAll(other.killed);                     // tombstones: union

        TreeMap<E, SortedSet<Dot>> nextLive = deepCopy(live);
        other.live.forEach((e, dots) ->
            nextLive.computeIfAbsent(e, k -> new TreeSet<>()).addAll(dots));   // adds: union

        // then subtract every tombstoned dot, and drop values left with no dots
        nextLive.values().forEach(dots -> dots.removeAll(nextKilled));
        nextLive.values().removeIf(Set::isEmpty);

        return new OrSet<>(nextLive, nextKilled);
    }

    public Set<E> value() { return Collections.unmodifiableSet(live.keySet()); }
    public boolean contains(E e) { return live.containsKey(e); }
    public SortedSet<Dot> dotsFor(E e) { return live.getOrDefault(e, new TreeSet<>()); }
    public int tombstoneCount() { return killed.size(); }

    /** Every dot we store, alive or dead — the real memory cost of the set. */
    public int metadataCells() {
        int n = killed.size();
        for (SortedSet<Dot> d : live.values()) n += d.size();
        return n;
    }

    private static <E> TreeMap<E, SortedSet<Dot>> deepCopy(SortedMap<E, SortedSet<Dot>> m) {
        TreeMap<E, SortedSet<Dot>> copy = new TreeMap<>();
        m.forEach((k, v) -> copy.put(k, new TreeSet<>(v)));
        return copy;
    }

    @Override public boolean equals(Object o) {
        return o instanceof OrSet<?> s && live.equals(s.live) && killed.equals(s.killed);
    }
    @Override public int hashCode() { return Objects.hash(live, killed); }
    @Override public String toString() { return live + " tombstones=" + killed.size(); }
}
