import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * "Who has written to this key, and how many times each?"
 *
 * A plain counter cannot answer "did these two writes happen one after the
 * other, or at the same time without either knowing about the other?" -- and
 * that question is the whole difference between safely overwriting a value and
 * silently destroying somebody's work.
 *
 * A version vector answers it by keeping ONE counter PER WRITER. Ana writing
 * twice gives {ana:2}. If Ben then reads that and writes, he produces
 * {ana:2, ben:1} -- strictly newer, because it contains everything Ana had.
 * But if Ben writes without having seen Ana's second write, he produces
 * {ana:1, ben:1}, and neither vector contains the other. That is the shape of
 * a genuine conflict, and it is DETECTABLE rather than guessed at.
 */
record VersionVector(Map<String, Integer> counters) {

    enum Rel { EQUAL, BEFORE, AFTER, CONCURRENT }

    static VersionVector empty() { return new VersionVector(Map.of()); }

    /** A write by `writer` on top of what it had read. */
    VersionVector incrementedBy(String writer) {
        Map<String, Integer> m = new HashMap<>(counters);
        m.merge(writer, 1, Integer::sum);
        return new VersionVector(Map.copyOf(m));
    }

    /** What a client knows after reading several replicas: the pointwise maximum. */
    VersionVector merge(VersionVector other) {
        Map<String, Integer> m = new HashMap<>(counters);
        other.counters().forEach((k, v) -> m.merge(k, v, Math::max));
        return new VersionVector(Map.copyOf(m));
    }

    /**
     * The comparison that makes conflict detection possible.
     * CONCURRENT means neither write saw the other -- no ordering exists, and
     * any system that picks a winner here is GUESSING.
     */
    Rel compare(VersionVector other) {
        boolean allLE = true, allGE = true;
        for (String k : union(other)) {
            int a = counters.getOrDefault(k, 0), b = other.counters().getOrDefault(k, 0);
            if (a > b) allLE = false;
            if (a < b) allGE = false;
        }
        if (allLE && allGE) return Rel.EQUAL;
        if (allLE) return Rel.BEFORE;
        if (allGE) return Rel.AFTER;
        return Rel.CONCURRENT;
    }

    private java.util.Set<String> union(VersionVector other) {
        var s = new java.util.HashSet<>(counters.keySet());
        s.addAll(other.counters().keySet());
        return s;
    }

    @Override public String toString() { return new TreeMap<>(counters).toString(); }
}
