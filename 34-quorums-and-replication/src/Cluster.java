import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A leaderless replicated store -- the Dynamo / Cassandra / Riak shape.
 *
 * There is no leader and no agreement protocol. A client picks any node as the
 * COORDINATOR for its request; that node forwards to the replicas that own the
 * key, waits for a configured number of replies, and answers. Everything this
 * lab is about lives in the two methods `put` and `get` below.
 */
class Cluster {

    // ---- configuration the experiments turn into knobs ------------------

    int N = 3;                       // copies of each key
    int W = 2;                       // acks required before a write is called a success
    int R = 2;                       // replies required before a read is answered
    boolean lastWriteWins = false;   // false = keep concurrent values as siblings
    boolean sloppyQuorum = false;    // during trouble, accept acks from ANY node
    boolean readRepair = true;       // push the winner back to replicas that were behind
    boolean hintedHandoff = false;   // hand held-back values home when the node returns

    final List<Node> nodes = new ArrayList<>();
    long now = 0;                    // simulated wall clock, milliseconds

    /** Nodes on opposite sides cannot talk. Empty = healthy network. */
    private final Set<String> sideA = new HashSet<>(), sideB = new HashSet<>();

    // ---- observable counters --------------------------------------------
    long writesOk = 0, writesFailed = 0, readsOk = 0, readsFailed = 0;
    long readRepairs = 0, hintsStored = 0, hintsDelivered = 0, siblingReads = 0;
    long partialWrites = 0;          // "failed" writes that nevertheless landed somewhere

    Cluster(int nodeCount) { for (int i = 0; i < nodeCount; i++) nodes.add(new Node("n" + i)); }

    Node node(String id) { return nodes.stream().filter(n -> n.id.equals(id)).findFirst().orElseThrow(); }

    // ---- placement -------------------------------------------------------

    /**
     * Which N nodes own this key? Hash the key onto the ring of nodes and take
     * the next N going clockwise -- the placement rule from the
     * consistent-hashing lab, in its simplest form.
     */
    List<Node> homeReplicas(String key) {
        int start = Math.floorMod(key.hashCode(), nodes.size());
        List<Node> out = new ArrayList<>();
        for (int i = 0; i < N; i++) out.add(nodes.get((start + i) % nodes.size()));
        return out;
    }

    // ---- faults ----------------------------------------------------------

    void kill(String id) { node(id).alive = false; }
    void revive(String id) { node(id).alive = true; }
    void makeSlow(String id, boolean s) { node(id).slow = s; }
    void skewClock(String id, long ms) { node(id).clockSkew = ms; }
    void partition(Set<String> a, Set<String> b) { sideA.clear(); sideB.clear(); sideA.addAll(a); sideB.addAll(b); }
    void heal() { sideA.clear(); sideB.clear(); }

    private boolean cut(String from, String to) {
        if (sideA.isEmpty() || sideB.isEmpty()) return false;
        return (sideA.contains(from) && sideB.contains(to)) || (sideB.contains(from) && sideA.contains(to));
    }

    /** Can the coordinator get a reply from this replica *in time*? */
    private boolean respondsInTime(Node coord, Node n) {
        return n.alive && !n.slow && !cut(coord.id, n.id);
    }
    /** Will the write physically land, even if the reply is too late to count? */
    private boolean applyReaches(Node coord, Node n) {
        return n.alive && !cut(coord.id, n.id);
    }

    // ---- the write path --------------------------------------------------

    record WriteResult(boolean ok, int acks, VersionVector vv, List<String> ackedBy) {}

    /**
     * Send to every replica; declare success once W have replied.
     *
     * Read the two loops carefully -- the gap between them is the source of
     * half the surprises in this lab. A replica that is merely SLOW still
     * stores the value; it just does not reply in time to be counted. So a
     * write that the client is told FAILED can still be sitting on a replica,
     * and nothing ever rolls it back.
     */
    WriteResult put(String coordId, String clientId, String key, String value, VersionVector context) {
        Node coord = node(coordId);
        VersionVector vv = context.incrementedBy(clientId);
        List<Node> replicas = homeReplicas(key);
        // The write timestamp is stamped ONCE, by the coordinator's clock --
        // which is exactly why a single machine with a wrong clock can poison
        // last-write-wins for everybody. (`clockskew` experiment.)
        long ts = now + coord.clockSkew;

        int acks = 0;
        List<String> ackedBy = new ArrayList<>();
        for (Node r : replicas) {
            if (!applyReaches(coord, r)) continue;
            r.write(key, new Val(value, vv, ts, clientId), lastWriteWins);
            if (respondsInTime(coord, r)) { acks++; ackedBy.add(r.id); }
        }

        // Not enough of the key's own replicas answered. Two choices, and they
        // are the whole availability-vs-guarantee trade:
        if (acks < W && sloppyQuorum) {
            for (Node other : nodes) {
                if (acks >= W) break;
                if (replicas.contains(other) || !respondsInTime(coord, other)) continue;
                // A "sloppy" ack: stored by a node that does NOT own this key,
                // purely so the write can be called a success. A strict reader
                // will never look here.
                Node homeMissing = replicas.stream().filter(r -> !respondsInTime(coord, r)).findFirst().orElse(replicas.get(0));
                other.hints.add(new Object[]{homeMissing.id, key, new Val(value, vv, ts, clientId)});
                hintsStored++;
                acks++; ackedBy.add(other.id + "(hint)");
            }
        }

        if (Broken.COUNT_ANY_ACK && acks < W) {          // planted bug
            for (Node other : nodes) {
                if (acks >= W) break;
                if (replicas.contains(other) || !respondsInTime(coord, other)) continue;
                acks++; ackedBy.add(other.id + "(counted, stores nothing)");
            }
        }
        boolean ok = acks >= W;
        if (ok) writesOk++;
        else { writesFailed++; if (!ackedBy.isEmpty() || anyApplied(coord, replicas)) partialWrites++; }
        return new WriteResult(ok, acks, vv, ackedBy);
    }

    private boolean anyApplied(Node coord, List<Node> replicas) {
        return replicas.stream().anyMatch(r -> applyReaches(coord, r));
    }

    // ---- the read path ---------------------------------------------------

    record ReadResult(boolean ok, List<Val> values, int responses, List<String> from) {}

    /**
     * Ask every replica; answer once R have replied; return the newest of what
     * came back -- or ALL of them if they are concurrent and cannot be ordered.
     */
    ReadResult get(String coordId, String key) {
        Node coord = node(coordId);
        List<Node> replicas = homeReplicas(key);

        List<Val> gathered = new ArrayList<>();
        List<Node> responders = new ArrayList<>();
        int need = Broken.NO_OVERLAP_CHECK ? 1 : R;        // planted bug ignores R
        for (Node r : replicas) {
            if (responders.size() >= need) break;           // stop at R: we do not wait for the rest
            if (!respondsInTime(coord, r)) continue;
            responders.add(r);
            gathered.addAll(r.read(key));
        }
        if (responders.size() < need) { readsFailed++; return new ReadResult(false, List.of(), responders.size(), List.of()); }

        readsOk++;
        List<Val> winners = resolve(gathered);
        if (winners.size() > 1) siblingReads++;

        if (readRepair && !winners.isEmpty()) {
            for (Node r : responders) {
                List<Val> theirs = resolve(new ArrayList<>(r.read(key)));
                if (!sameValues(theirs, winners)) {
                    for (Val w : winners) r.write(key, w, lastWriteWins);
                    readRepairs++;                          // this replica was behind; it is not any more
                }
            }
        }
        return new ReadResult(true, winners, responders.size(), responders.stream().map(n -> n.id).toList());
    }

    /** Keep only the values nothing else supersedes. More than one left = a real conflict. */
    List<Val> resolve(List<Val> all) {
        if (all.isEmpty()) return List.of();
        if (lastWriteWins || Broken.LWW_ALWAYS) {          // planted bug forces the guess
            Val best = all.get(0);
            for (Val v : all) if (v.ts() > best.ts()) best = v;
            return List.of(best);
        }
        List<Val> out = new ArrayList<>();
        for (Val v : all) {
            boolean dominated = all.stream().anyMatch(o -> o != v && v.vv().compare(o.vv()) == VersionVector.Rel.BEFORE);
            boolean dup = out.stream().anyMatch(o -> o.vv().compare(v.vv()) == VersionVector.Rel.EQUAL && o.value().equals(v.value()));
            if (!dominated && !dup) out.add(v);
        }
        return out;
    }

    private boolean sameValues(List<Val> a, List<Val> b) {
        if (a.size() != b.size()) return false;
        var av = new HashSet<>(a.stream().map(Val::value).toList());
        var bv = new HashSet<>(b.stream().map(Val::value).toList());
        return av.equals(bv);
    }

    // ---- background repair ----------------------------------------------

    /** Deliver values that were parked on the wrong node while its owner was away. */
    void deliverHints() {
        if (!hintedHandoff) return;
        for (Node holder : nodes) {
            if (!holder.alive) continue;
            var it = holder.hints.iterator();
            while (it.hasNext()) {
                Object[] h = it.next();
                Node home = node((String) h[0]);
                if (home.alive && !cut(holder.id, home.id)) {
                    home.write((String) h[1], (Val) h[2], lastWriteWins);
                    hintsDelivered++;
                    it.remove();
                }
            }
        }
    }

    /**
     * Compare replicas to each other and fix differences, with no client
     * involved. This is what actually bounds staleness for keys nobody reads --
     * read repair only helps keys that are read.
     */
    void antiEntropy(String key) {
        List<Node> replicas = homeReplicas(key).stream().filter(n -> n.alive).toList();
        List<Val> all = new ArrayList<>();
        for (Node r : replicas) all.addAll(r.read(key));
        List<Val> winners = resolve(all);
        for (Node r : replicas) for (Val w : winners) r.write(key, w, lastWriteWins);
    }

    Map<String, String> snapshot(String key) {
        Map<String, String> m = new LinkedHashMap<>();
        for (Node n : homeReplicas(key))
            m.put(n.id + (n.alive ? "" : " (down)"),
                  n.read(key).isEmpty() ? "—" : n.read(key).stream().map(Val::toString).toList().toString());
        return m;
    }
}
