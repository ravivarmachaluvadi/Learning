import java.util.*;

/**
 * EXERCISE 5 — implement the merge yourself.
 *
 * This is an OR-Set with the merge rule removed. Everything else works: adding
 * mints a dot, removing tears up the dots this replica has observed. Only
 * merge() is missing.
 *
 *   java src/YourMerge.java     runs five checks against your implementation
 *
 * You are done when it prints 5/5. Start by getting the three laws to pass —
 * they are checks 1-3 and they are the whole specification.
 */
public final class YourMerge implements Replicated<YourMerge> {

    private final SortedMap<String, SortedSet<Dot>> live;
    private final SortedSet<Dot> torn;

    public YourMerge() { this(new TreeMap<>(), new TreeSet<>()); }
    private YourMerge(SortedMap<String, SortedSet<Dot>> live, SortedSet<Dot> torn) {
        this.live = live; this.torn = torn;
    }

    public YourMerge add(String item, Dot dot) {
        TreeMap<String, SortedSet<Dot>> next = copy(live);
        next.computeIfAbsent(item, k -> new TreeSet<>()).add(dot);
        return new YourMerge(next, new TreeSet<>(torn));
    }

    public YourMerge remove(String item) {
        TreeMap<String, SortedSet<Dot>> next = copy(live);
        TreeSet<Dot> nextTorn = new TreeSet<>(torn);
        SortedSet<Dot> observed = next.remove(item);
        if (observed != null) nextTorn.addAll(observed);
        return new YourMerge(next, nextTorn);
    }

    @Override public YourMerge merge(YourMerge other) {
        // ================== YOUR CODE HERE ==================
        // Return a NEW YourMerge combining this and other. Two piles to think
        // about: the torn receipts, and the live ones. Order matters between
        // those two steps — work out which way round, and why.
        //
        // Delete the next line and write the real thing.
        return this;
        // ====================================================
    }

    public Set<String> value() { return live.keySet(); }

    private static TreeMap<String, SortedSet<Dot>> copy(SortedMap<String, SortedSet<Dot>> m) {
        TreeMap<String, SortedSet<Dot>> c = new TreeMap<>();
        m.forEach((k, v) -> c.put(k, new TreeSet<>(v)));
        return c;
    }
    @Override public boolean equals(Object o) {
        return o instanceof YourMerge y && live.equals(y.live) && torn.equals(y.torn);
    }
    @Override public int hashCode() { return Objects.hash(live, torn); }
    @Override public String toString() { return live.keySet() + " torn=" + torn; }

    // ------------------------------------------------------------- self-check

    public static void main(String[] args) {
        int ok = 0;

        YourMerge a = new YourMerge().add("milk", new Dot("a", 1));
        YourMerge b = a.add("eggs", new Dot("b", 1)).remove("milk");
        YourMerge c = a.add("milk", new Dot("a", 2));
        List<YourMerge> s = List.of(new YourMerge(), a, b, c);

        boolean comm = true, assoc = true, idem = true;
        for (YourMerge x : s) for (YourMerge y : s) {
            if (!x.merge(y).equals(y.merge(x))) comm = false;
            if (!x.merge(x).equals(x)) idem = false;
            for (YourMerge z : s)
                if (!x.merge(y).merge(z).equals(x.merge(y.merge(z)))) assoc = false;
        }
        ok += report(1, "commutative:  a.merge(b) == b.merge(a)", comm);
        ok += report(2, "associative:  grouping does not matter", assoc);
        ok += report(3, "idempotent:   a.merge(a) == a", idem);

        boolean removeSticks = !a.merge(b).value().contains("milk");
        ok += report(4, "a remove of an observed add is respected", removeSticks);

        boolean addWins = b.merge(c).value().contains("milk") && c.merge(b).value().contains("milk");
        ok += report(5, "a concurrent re-add survives a remove (add wins)", addWins);

        System.out.printf("%n%d / 5%s%n", ok, ok == 5
            ? "  — correct. Compare yours with OrSet.java."
            : "  — keep going. Check 1 failing usually means the two piles are combined in the wrong order.");
    }

    static int report(int n, String what, boolean pass) {
        System.out.printf("  %d. %-52s %s%n", n, what, pass ? "PASS" : "fail");
        return pass ? 1 : 0;
    }
}
