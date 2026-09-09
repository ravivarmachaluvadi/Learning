import java.util.*;

/** The strategies the ring is competing against. Each answers the same one question. */
final class Placements {

    // ==================================================================== modulo

    /**
     * What everybody writes first, and it is not stupid -- it is perfectly balanced,
     * needs no memory, and is one instruction.
     *
     * ITS ENTIRE PROBLEM IS THE `% n`. That n is a GLOBAL property of the cluster, so
     * every key's answer is a function of how many machines exist. Change the count
     * and you have changed the answer for nearly every key at once. Experiment 1
     * measures "nearly".
     */
    static final class Modulo implements Placement {
        private final List<String> nodes = new ArrayList<>();
        @Override public String name() { return "modulo"; }
        @Override public int size() { return nodes.size(); }
        @Override public List<String> nodes() { return new ArrayList<>(nodes); }
        @Override public void addNode(String n) { nodes.add(n); Collections.sort(nodes); }
        @Override public void removeNode(String n) { nodes.remove(n); }
        @Override public String owner(String key) {
            if (nodes.isEmpty()) return null;
            int i = (int) Math.floorMod(Hashing.hash64(key), nodes.size());
            return nodes.get(i);   // <- the whole bug lives in `nodes.size()`
        }
        @Override public List<String> owners(String key, int r) {
            List<String> out = new ArrayList<>();
            if (nodes.isEmpty()) return out;
            int i = (int) Math.floorMod(Hashing.hash64(key), nodes.size());
            for (int k = 0; k < Math.min(r, nodes.size()); k++) out.add(nodes.get((i + k) % nodes.size()));
            return out;
        }
    }

    // =============================================================== rendezvous

    /**
     * Rendezvous hashing, a.k.a. highest random weight (Thaler & Ravishankar, 1996 --
     * one year BEFORE the consistent hashing paper, and far less famous).
     *
     * The idea is almost embarrassingly simple: for a given key, score every machine
     * by hash(key + machine), and give the key to the highest scorer.
     *
     * WHY THIS IS WORTH KNOWING: it needs no ring, no virtual nodes, no sorted array,
     * and no tuning -- and its balance is better than a ring's, because there are no
     * arcs to be unequal. When a machine leaves, only the keys it won move, and they
     * spread across ALL survivors instead of piling onto one successor. Its cost is
     * that a lookup is O(machines) rather than O(log positions), which is why the ring
     * won for large clusters and rendezvous is excellent for small ones.
     */
    static final class Rendezvous implements Placement {
        private final List<String> nodes = new ArrayList<>();
        @Override public String name() { return "rendezvous"; }
        @Override public int size() { return nodes.size(); }
        @Override public List<String> nodes() { return new ArrayList<>(nodes); }
        @Override public void addNode(String n) { if (!nodes.contains(n)) nodes.add(n); }
        @Override public void removeNode(String n) { nodes.remove(n); }
        @Override public String owner(String key) {
            String best = null; long bestScore = Long.MIN_VALUE;
            for (String n : nodes) {
                long s = Hashing.hash64(key + "@" + n);       // the "random weight"
                if (s > bestScore || (s == bestScore && n.compareTo(best) > 0)) { bestScore = s; best = n; }
            }
            return best;
        }
        /** Replication falls out for free: take the top r scorers instead of the top 1. */
        @Override public List<String> owners(String key, int r) {
            List<String> sorted = new ArrayList<>(nodes);
            sorted.sort((a, b) -> Long.compare(Hashing.hash64(key + "@" + b), Hashing.hash64(key + "@" + a)));
            return sorted.subList(0, Math.min(r, sorted.size()));
        }
    }

    // ================================================================ jump hash

    /**
     * Google's jump consistent hash (Lamping & Veach, 2014). Zero memory, no ring,
     * about 20 lines, and near-perfect balance.
     *
     * THE CATCH, and it decides where you can use it: it maps a key to a bucket NUMBER
     * in [0, n). Buckets are positions in an ordered list, so you can only add or
     * remove at the END. You cannot remove machine number 3 out of ten and keep the
     * rest stable -- there is no way to express "that one is gone".
     *
     * So it is superb for "shard this dataset into n parts and occasionally grow n",
     * and unusable for a cluster where any machine can die. Experiment 6 shows both
     * halves of that.
     */
    static final class Jump implements Placement {
        private final List<String> nodes = new ArrayList<>();
        @Override public String name() { return "jump hash"; }
        @Override public int size() { return nodes.size(); }
        @Override public List<String> nodes() { return new ArrayList<>(nodes); }
        @Override public void addNode(String n) { if (!nodes.contains(n)) nodes.add(n); }
        @Override public void removeNode(String n) {
            if (!nodes.isEmpty() && nodes.get(nodes.size() - 1).equals(n)) { nodes.remove(nodes.size() - 1); return; }
            throw new UnsupportedOperationException(
                "jump hash can only remove the LAST bucket; asked to remove " + n
                + " from " + nodes + " -- this is the algorithm's defining limitation, not a bug here");
        }
        @Override public String owner(String key) {
            return nodes.isEmpty() ? null : nodes.get(jump(Hashing.hash64(key), nodes.size()));
        }
        @Override public List<String> owners(String key, int r) {
            List<String> out = new ArrayList<>();
            if (nodes.isEmpty()) return out;
            int i = jump(Hashing.hash64(key), nodes.size());
            for (int k = 0; k < Math.min(r, nodes.size()); k++) out.add(nodes.get((i + k) % nodes.size()));
            return out;
        }
        /** The canonical algorithm, verbatim in shape from the paper. */
        static int jump(long key, int buckets) {
            long k = key, b = -1, j = 0;
            while (j < buckets) {
                b = j;
                k = k * 2862933555777941757L + 1;
                j = (long) ((b + 1) * ((double) (1L << 31) / (double) ((k >>> 33) + 1)));
            }
            return (int) b;
        }
    }

    // ============================================================ bounded loads

    /**
     * Consistent hashing with bounded loads (Mirrokni, Thorup & Zadimoghaddam, 2016;
     * shipped in HAProxy and used by Vimeo).
     *
     * It exists because virtual nodes fix the AVERAGE imbalance and leave the TAIL: a
     * ring can still hand one machine noticeably more than its share, and if the items
     * are live requests rather than stored keys, that machine is the one that falls
     * over.
     *
     * The fix: give every machine a hard capacity of ceil(c * total / n) for some
     * c slightly above 1, and when a key's rightful owner is full, keep walking
     * clockwise to the first machine that is not. Nobody can exceed the cap, ever.
     *
     * THE PRICE, and it is a real one: this is STATEFUL. The answer for a key now
     * depends on how many other keys have already been placed, so two clients with
     * different traffic reach different answers, and it cannot be used for stored data
     * whose location must be recomputable from the key alone. It is a load balancer's
     * tool, not a database's.
     */
    static final class BoundedLoad implements Placement {
        private final Ring ring;
        private final double c;
        private final Map<String, Integer> load = new HashMap<>();
        private int total;
        int displaced;                       // keys that did NOT get their first choice

        BoundedLoad(int vnodes, double c) { this.ring = new Ring(vnodes); this.c = c; }

        @Override public String name() { return String.format("bounded load (c=%.2f)", c); }
        @Override public int size() { return ring.size(); }
        @Override public List<String> nodes() { return ring.nodes(); }
        @Override public void addNode(String n) { ring.addNode(n); load.putIfAbsent(n, 0); }
        @Override public void removeNode(String n) { ring.removeNode(n); load.remove(n); }

        void reset() { load.replaceAll((k, v) -> 0); total = 0; displaced = 0; }

        @Override public String owner(String key) {
            if (ring.size() == 0) return null;
            total++;
            int cap = (int) Math.ceil(c * total / (double) ring.size());
            List<String> walk = ring.owners(key, ring.size());   // clockwise, distinct
            for (int i = 0; i < walk.size(); i++) {
                String n = walk.get(i);
                if (load.getOrDefault(n, 0) < cap) {
                    if (i > 0) displaced++;
                    load.merge(n, 1, Integer::sum);
                    return n;
                }
            }
            String n = walk.get(0);                              // everyone full: overflow to first
            load.merge(n, 1, Integer::sum);
            return n;
        }
        @Override public List<String> owners(String key, int r) { return ring.owners(key, r); }

        Map<String, Integer> loads() { return load; }
    }

    private Placements() {}
}
