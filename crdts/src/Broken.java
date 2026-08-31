import java.util.*;

/**
 * Three plausible, wrong CRDTs — for exercise 6 in EXERCISES.md.
 *
 * Each one breaks exactly ONE of the three laws, and each one is a mistake
 * that has shipped in real systems. Run `java src/Tests.java broken` to see
 * which test catches which.
 */
public final class Broken {

    /**
     * BUG 1 — merge adds the rows instead of taking the max.
     * Breaks: IDEMPOTENCE. Looks perfect until a message is delivered twice.
     */
    public static final class SummingCounter implements Replicated<SummingCounter> {
        private final SortedMap<String, Long> rows;
        public SummingCounter() { this(new TreeMap<>()); }
        private SummingCounter(SortedMap<String, Long> rows) { this.rows = rows; }
        public SummingCounter increment(String r, long by) {
            TreeMap<String, Long> n = new TreeMap<>(rows); n.merge(r, by, Long::sum); return new SummingCounter(n);
        }
        @Override public SummingCounter merge(SummingCounter o) {
            TreeMap<String, Long> n = new TreeMap<>(rows);
            o.rows.forEach((k, v) -> n.merge(k, v, Long::sum));   // <-- BUG: should be Math::max
            return new SummingCounter(n);
        }
        public long value() { long t = 0; for (long v : rows.values()) t += v; return t; }
        @Override public boolean equals(Object o) { return o instanceof SummingCounter c && rows.equals(c.rows); }
        @Override public int hashCode() { return rows.hashCode(); }
        @Override public String toString() { return rows + "=" + value(); }
    }

    /**
     * BUG 2 — merge unions the live sets but only subtracts the tombstones it
     * already had, not the ones that just arrived.
     * Breaks: COMMUTATIVITY. Whether a deleted item stays deleted depends on
     * which direction the last message travelled.
     */
    public static final class LeakyOrSet implements Replicated<LeakyOrSet> {
        private final SortedMap<String, SortedSet<Dot>> live;
        private final SortedSet<Dot> killed;
        public LeakyOrSet() { this(new TreeMap<>(), new TreeSet<>()); }
        private LeakyOrSet(SortedMap<String, SortedSet<Dot>> l, SortedSet<Dot> k) { live = l; killed = k; }
        public LeakyOrSet add(String e, Dot d) {
            TreeMap<String, SortedSet<Dot>> n = copy(live);
            n.computeIfAbsent(e, k -> new TreeSet<>()).add(d);
            return new LeakyOrSet(n, new TreeSet<>(killed));
        }
        public LeakyOrSet remove(String e) {
            TreeMap<String, SortedSet<Dot>> n = copy(live);
            TreeSet<Dot> k = new TreeSet<>(killed);
            SortedSet<Dot> seen = n.remove(e);
            if (seen != null) k.addAll(seen);
            return new LeakyOrSet(n, k);
        }
        @Override public LeakyOrSet merge(LeakyOrSet o) {
            TreeMap<String, SortedSet<Dot>> n = copy(live);
            o.live.forEach((e, dots) -> {
                TreeSet<Dot> incoming = new TreeSet<>(dots);
                incoming.removeAll(killed);                // <-- BUG: uses only MY tombstones...
                if (!incoming.isEmpty()) n.computeIfAbsent(e, k -> new TreeSet<>()).addAll(incoming);
            });
            TreeSet<Dot> k = new TreeSet<>(killed);
            k.addAll(o.killed);                            // ...and only learns theirs afterwards
            return new LeakyOrSet(n, k);
        }
        public Set<String> value() { return live.keySet(); }
        private static TreeMap<String, SortedSet<Dot>> copy(SortedMap<String, SortedSet<Dot>> m) {
            TreeMap<String, SortedSet<Dot>> c = new TreeMap<>();
            m.forEach((k, v) -> c.put(k, new TreeSet<>(v))); return c;
        }
        @Override public boolean equals(Object o) { return o instanceof LeakyOrSet s && live.equals(s.live) && killed.equals(s.killed); }
        @Override public int hashCode() { return Objects.hash(live, killed); }
        @Override public String toString() { return live.keySet().toString(); }
    }

    /**
     * BUG 3 — last-writer-wins with no tie-break on replica id: equal
     * timestamps resolve to "whatever I already had".
     * Breaks: COMMUTATIVITY, but only when two clocks read the same millisecond
     * — which on a busy service is constantly.
     */
    public static final class TielessLww implements Replicated<TielessLww> {
        private final String value; private final long ts;
        public TielessLww() { this(null, Long.MIN_VALUE); }
        private TielessLww(String v, long t) { value = v; ts = t; }
        public TielessLww assign(String v, long t) { return new TielessLww(v, t); }
        @Override public TielessLww merge(TielessLww o) {
            return o.ts > ts ? o : this;                   // <-- BUG: ties silently keep `this`
        }
        public String value() { return value; }
        @Override public boolean equals(Object o) { return o instanceof TielessLww l && Objects.equals(value, l.value) && ts == l.ts; }
        @Override public int hashCode() { return Objects.hash(value, ts); }
        @Override public String toString() { return value + "@" + ts; }
    }
}
