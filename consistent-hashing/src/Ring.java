import java.util.*;

/**
 * Consistent hashing: the ring.
 *
 * THE ONE IDEA, and it is worth stating before any code:
 *
 *     Modulo asks "which of my N buckets?", so N is in the formula and every key's
 *     answer changes when N changes. The ring asks "which machine is NEXT, going
 *     clockwise from where this key landed?" -- and the number of machines appears
 *     NOWHERE in that question. Remove a machine and only the keys that were walking
 *     to *it* notice; everybody else's walk is unchanged.
 *
 * Keys and machines are hashed into the SAME 64-bit space, which is treated as a
 * circle (2^63-1 wraps round to -2^63). A key belongs to the first machine at or
 * after its own position, going clockwise.
 *
 * Each machine appears on the ring many times, at unrelated positions -- see
 * `vnodesPerNode` and the comment on rebuild() for why one position per machine is
 * not enough.
 */
final class Ring implements Placement {

    // ---- planted bugs, for EXERCISES.md rung 6. All false in normal operation. ----
    // Each is a mistake that looks entirely reasonable in review.
    static boolean BUG_POSITIONS_FROM_INDEX = false;  // place machines by their position in the list, not their name
    static boolean BUG_REPLICAS_NOT_DISTINCT = false; // let the replica walk revisit the same machine
    static boolean BUG_NO_WRAP = false;               // stop at the end of the array instead of coming round

    private final int vnodesPerNode;
    private final boolean goodKeyHash;

    /** node name -> how many times its share should be, relative to 1. */
    private final Map<String, Integer> weights = new LinkedHashMap<>();

    // The ring itself: two parallel arrays kept sorted by position. Production
    // libraries use exactly this rather than a TreeMap -- a sorted primitive array
    // binary-searches with far better cache behaviour than a tree of boxed objects.
    private long[] positions = new long[0];
    private String[] ownerOf = new String[0];

    Ring(int vnodesPerNode) { this(vnodesPerNode, true); }
    /**
     * `goodKeyHash = false` keeps the machine positions spread properly across the
     * whole circle and hashes only the KEYS badly -- which is the realistic version of
     * this mistake. Your ring library hashes machine names correctly; you hand it keys
     * hashed with something of your own. Experiment 6 measures the result.
     */
    Ring(int vnodesPerNode, boolean goodKeyHash) {
        this.vnodesPerNode = vnodesPerNode;
        this.goodKeyHash = goodKeyHash;
    }

    @Override public String name() { return "ring(vnodes=" + vnodesPerNode + (goodKeyHash ? "" : ", 32-bit key hash") + ")"; }
    @Override public int size() { return weights.size(); }
    @Override public List<String> nodes() { return new ArrayList<>(weights.keySet()); }

    @Override public void addNode(String node) { weights.put(node, 1); rebuild(); }
    @Override public void removeNode(String node) { weights.remove(node); rebuild(); }

    /**
     * A machine with `weight` w gets w times as many positions, so it attracts about
     * w times as many keys. This is how you run mixed hardware -- a box with 4x the
     * memory takes 4x the keys. See F6 and Experiment 8.
     */
    void addNode(String node, int weight) { weights.put(node, Math.max(1, weight)); rebuild(); }

    /**
     * Scatter every machine's positions around the circle.
     *
     * INVARIANT (why virtual nodes exist at all): with ONE position per machine, four
     * machines are four random points on a circle, and four random points do not cut
     * a circle into four equal arcs -- one of them routinely owns half of it. Placing
     * each machine at many unrelated positions averages those arcs out. Experiment 2
     * measures the imbalance against the number of positions.
     *
     * The vnode's position is the hash of "<node>#<i>". That naming is arbitrary but
     * it must be IDENTICAL in every client, or two clients build different rings from
     * the same machine list and disagree about ownership -- see F8.
     */
    private void rebuild() {
        List<long[]> pts = new ArrayList<>();
        List<String> names = new ArrayList<>();
        int nodeIndex = 0;
        for (Map.Entry<String, Integer> e : weights.entrySet()) {
            int n = vnodesPerNode * e.getValue();
            for (int i = 0; i < n; i++) {
                // The name is load-bearing: a machine's positions must depend on that
                // machine ALONE. Derive them from anything that changes when the cluster
                // changes -- an index, a count, an ordinal -- and the modulo disease is
                // back, wearing a ring costume.
                long h = BUG_POSITIONS_FROM_INDEX
                        ? Hashing.hash64(nodeIndex + "#" + i)        // <- planted bug 1
                        : Hashing.hash64(e.getKey() + "#" + i);
                pts.add(new long[]{h, names.size()});
                names.add(e.getKey());
            }
            nodeIndex++;
        }
        pts.sort((a, b) -> Long.compare(a[0], b[0]));
        positions = new long[pts.size()];
        ownerOf = new String[pts.size()];
        for (int i = 0; i < pts.size(); i++) {
            positions[i] = pts.get(i)[0];
            ownerOf[i] = names.get((int) pts.get(i)[1]);
        }
    }

    /**
     * THE LOAD-BEARING METHOD OF THE WHOLE LAB.
     *
     * Hash the key to a position, then take the first machine at or after it. Note
     * what is absent: no division, no modulo, and no reference to how many machines
     * exist. That absence IS consistent hashing.
     */
    @Override public String owner(String key) {
        if (positions.length == 0) return null;
        return ownerOf[successorIndex(hash(key))];
    }

    /**
     * Replication: keep walking clockwise, collecting DISTINCT machines.
     *
     * INVARIANT (and it is easy to get wrong): the walk must skip further positions
     * belonging to a machine already chosen. Without the distinctness check, all r
     * "copies" of a key can land on the SAME machine -- which reads as replicated and
     * is not, and the day that machine dies you discover it. BrokenRing bug 2 removes
     * this check.
     */
    @Override public List<String> owners(String key, int r) {
        if (r <= 0) return new ArrayList<>();
        List<String> out = new ArrayList<>(r);
        if (positions.length == 0) return out;
        int i = successorIndex(hash(key));
        for (int step = 0; step < positions.length && out.size() < r; step++) {
            String n = ownerOf[(i + step) % positions.length];
            if (BUG_REPLICAS_NOT_DISTINCT || !out.contains(n)) out.add(n);   // <- planted bug 2
        }
        return out;
    }

    /** First index whose position >= h, wrapping to 0 -- this wrap is what closes the ring. */
    private int successorIndex(long h) {
        int lo = 0, hi = positions.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (positions[mid] < h) lo = mid + 1; else hi = mid;
        }
        if (BUG_NO_WRAP) return Math.min(lo, positions.length - 1);   // <- planted bug 3
        return lo == positions.length ? 0 : lo;
    }

    private long hash(String key) { return goodKeyHash ? Hashing.hash64(key) : Hashing.badHash64(key); }

    // ---- inspection, used by the tests, the experiments and the drawing ----

    int ringPoints() { return positions.length; }

    /** Every position on the ring, in clockwise order, as {position, ownerName}. */
    List<Object[]> layout() {
        List<Object[]> out = new ArrayList<>();
        for (int i = 0; i < positions.length; i++) out.add(new Object[]{positions[i], ownerOf[i]});
        return out;
    }

    /**
     * What fraction of the circle each machine owns, purely from arc lengths -- no
     * keys involved. This is the theoretical share; Experiment 2 checks that real keys
     * follow it.
     */
    Map<String, Double> arcShare() {
        Map<String, Double> share = new LinkedHashMap<>();
        for (String n : weights.keySet()) share.put(n, 0.0);
        if (positions.length == 0) return share;
        double total = Math.pow(2, 64);
        for (int i = 0; i < positions.length; i++) {
            long start = positions[(i - 1 + positions.length) % positions.length];
            long end = positions[i];
            double arc = (i == 0) ? (double) end - (double) start + total : (double) end - (double) start;
            share.merge(ownerOf[i], arc / total, Double::sum);
        }
        return share;
    }
}
