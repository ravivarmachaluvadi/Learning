import java.util.*;

/**
 * RGA ("replicated growable array") — a shared piece of TEXT as a CRDT.
 *
 * You already met this shape in the google-docs-system-design lab, where it was
 * the alternative to operational transformation. Here is why it belongs in this
 * lab: it is the same trick as the OR-Set, applied to order.
 *
 * The problem with text is that "insert at position 3" is a claim about a
 * document that has since changed. RGA never says a position. Every character
 * gets a dot, and an insert says "this character goes immediately after THAT
 * character" — naming a specific, immortal identity. Two concurrent inserts
 * after the same character are ordered by their dots (bigger dot first), which
 * is a rule both replicas can apply without talking. Deletes are tombstones,
 * so a delete can safely arrive before the character it deletes... as long as
 * the character eventually arrives too.
 *
 * Merge is union of characters + union of tombstones: laws hold for free.
 *
 * One catch worth remembering, because it bit this lab during construction:
 * union-merge makes RGA CONVERGE no matter what dots you use, but it only puts
 * the characters in the order a human expected if the dot counters are Lamport
 * counters — every new dot must be numbered higher than every dot the author
 * has seen. Use a plain per-replica 1,2,3... and all replicas still agree, they
 * just agree on "HATSC" instead of "CHATS". Convergence and intention
 * preservation are two different properties, and only one of them is free.
 * Call nextCounter() to get a correctly-numbered dot.
 */
public final class Rga implements Replicated<Rga> {

    public static final Dot ROOT = new Dot("^", 0);

    public record Cell(char ch, Dot after) {}

    private final SortedMap<Dot, Cell> cells;
    private final SortedSet<Dot> deleted;

    public Rga() { this(new TreeMap<>(), new TreeSet<>()); }
    private Rga(SortedMap<Dot, Cell> cells, SortedSet<Dot> deleted) {
        this.cells = cells; this.deleted = deleted;
    }

    /** Insert `ch` immediately after the character identified by `after` (ROOT = at the start). */
    public Rga insertAfter(Dot after, char ch, Dot id) {
        TreeMap<Dot, Cell> next = new TreeMap<>(cells);
        next.put(id, new Cell(ch, after));
        return new Rga(next, new TreeSet<>(deleted));
    }

    public Rga insertAt(int visibleIndex, char ch, Dot id) {
        List<Dot> visible = visibleDots();
        Dot after = visibleIndex == 0 ? ROOT : visible.get(visibleIndex - 1);
        return insertAfter(after, ch, id);
    }

    public Rga deleteAt(int visibleIndex) {
        return delete(visibleDots().get(visibleIndex));
    }

    public Rga delete(Dot id) {
        TreeSet<Dot> next = new TreeSet<>(deleted);
        next.add(id);   // INVARIANT: a delete is a tombstone on an IDENTITY, never on a position
        return new Rga(new TreeMap<>(cells), next);
    }

    @Override public Rga merge(Rga other) {
        TreeMap<Dot, Cell> nextCells = new TreeMap<>(cells);
        nextCells.putAll(other.cells);              // union — same dot always means the same cell
        TreeSet<Dot> nextDeleted = new TreeSet<>(deleted);
        nextDeleted.addAll(other.deleted);          // union
        return new Rga(nextCells, nextDeleted);
    }

    public String value() {
        StringBuilder sb = new StringBuilder();
        for (Dot d : walk()) if (!deleted.contains(d)) sb.append(cells.get(d).ch());
        return sb.toString();
    }

    public List<Dot> visibleDots() {
        List<Dot> out = new ArrayList<>();
        for (Dot d : walk()) if (!deleted.contains(d)) out.add(d);
        return out;
    }

    /** Depth-first over the "inserted after" tree; siblings newest-dot-first. */
    private List<Dot> walk() {
        Map<Dot, List<Dot>> children = new HashMap<>();
        for (var e : cells.entrySet())
            children.computeIfAbsent(e.getValue().after(), k -> new ArrayList<>()).add(e.getKey());
        children.values().forEach(l -> l.sort(Comparator.reverseOrder()));

        List<Dot> out = new ArrayList<>();
        Deque<Dot> stack = new ArrayDeque<>();
        List<Dot> roots = children.getOrDefault(ROOT, List.of());
        for (int i = roots.size() - 1; i >= 0; i--) stack.push(roots.get(i));
        while (!stack.isEmpty()) {
            Dot d = stack.pop();
            out.add(d);
            List<Dot> kids = children.getOrDefault(d, List.of());
            for (int i = kids.size() - 1; i >= 0; i--) stack.push(kids.get(i));
        }
        return out;
    }

    /** A Lamport counter: strictly higher than every dot this replica has seen. */
    public long nextCounter() {
        long max = 0;
        for (Dot d : cells.keySet()) max = Math.max(max, d.counter());
        for (Dot d : deleted) max = Math.max(max, d.counter());
        return max + 1;
    }

    /** visible characters vs cells actually stored — the tombstone tax, measured. */
    public int metadataCells() { return cells.size(); }
    public int visibleLength() { return value().length(); }

    @Override public boolean equals(Object o) {
        return o instanceof Rga r && cells.equals(r.cells) && deleted.equals(r.deleted);
    }
    @Override public int hashCode() { return Objects.hash(cells, deleted); }
    @Override public String toString() { return "\"" + value() + "\" (" + cells.size() + " cells)"; }
}
