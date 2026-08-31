import java.util.*;

/**
 * The world the nodes live in: a synchronous, in-memory network you can break.
 *
 * SIMPLIFICATION, stated up front: a round here is a lock-step tick in which every
 * node gossips once and every message that is not dropped arrives before the round
 * ends. Real gossip is asynchronous and messages take a variable time. What the
 * round model preserves exactly -- and it is the part that matters -- is the SHAPE
 * of the spread: how many nodes know a fact after k exchanges, and how that number
 * grows with fanout and cluster size.
 */
final class Cluster {

    enum Mode {
        /** I tell you what I know. One message. */
        PUSH,
        /** I ask what you know. Request + reply. */
        PULL,
        /** We swap. Request + reply, both sides learn. */
        PUSH_PULL
    }

    final List<Node> nodes = new ArrayList<>();
    final Map<String, Node> byId = new LinkedHashMap<>();
    private final Random rng;

    // ---- knobs. Every one of them is a real production dial. ----
    Mode mode = Mode.PUSH_PULL;
    int fanout = 2;                    // peers contacted per node per round
    double dropRate = 0.0;             // fraction of messages lost
    int suspectAfter = 3;              // rounds of silence before suspicion
    int deadAfter = 3;                 // further rounds before declaring dead
    int indirectProbes = 0;            // SWIM-style: ask k others before suspecting
    boolean rumorMongering = false;    // stop spreading a fact once it feels old
    int stopAfterRedundant = 3;
    int maxEntriesPerMessage = 0;      // 0 = unlimited
    /** true = SWIM-style (only direct contact proves reachability). See Node. */
    boolean directEvidenceOnly = false;
    /**
     * Whether the membership list rides along on every message.
     *
     * The experiments that measure how one FACT spreads switch this off, so the
     * numbers are about dissemination alone and not about the size of the cluster's
     * bookkeeping. Real systems always have it on -- that is the anti-entropy layer
     * of §10 v4.
     */
    boolean gossipMembership = true;

    /** Network faults. */
    final Map<String, Integer> partitionOf = new HashMap<>();      // node -> side (0 = default)
    final Set<String> blockedOneWay = new HashSet<>();             // "from>to" links that are broken

    // ---- counters ----
    long messagesSent, messagesDropped, entriesTransferred, configMessagesSent;
    long round = 0;
    final List<String> log = new ArrayList<>();
    boolean verbose = false;

    Cluster(int n, long seed) { this(n, seed, true); }

    /**
     * @param withMembership false builds a cluster for measuring how ONE FACT
     *        spreads: nodes know who their peers are but exchange no membership
     *        list. Keeps the experiments about dissemination, and keeps a
     *        5,000-node run from allocating 25 million bookkeeping rows.
     */
    Cluster(int n, long seed, boolean withMembership) {
        this.rng = new Random(seed);
        this.gossipMembership = withMembership;
        for (int i = 1; i <= n; i++) {
            Node node = new Node("n" + i);
            nodes.add(node);
            byId.put(node.id, node);
        }
        // Everyone starts knowing everyone: this lab is about keeping a membership
        // list current, not about bootstrapping one. (Real clusters start from a
        // few seed nodes and discover the rest by gossiping -- see PRODUCTION.)
        if (withMembership)
            for (Node a : nodes)
                for (Node b : nodes)
                    if (!a.id.equals(b.id)) a.view.put(b.id, new PeerState(1, 0, Status.ALIVE, 0));
    }

    // ------------------------------------------------------------- the network

    /** Can a message physically get from a to b right now? */
    boolean canDeliver(String from, String to) {
        Node f = byId.get(from), t = byId.get(to);
        if (!f.running || !t.running) return false;
        if (partitionOf.getOrDefault(from, 0) != partitionOf.getOrDefault(to, 0)) return false;
        if (blockedOneWay.contains(from + ">" + to)) return false;
        return true;
    }

    private boolean send(String from, String to) {
        messagesSent++;
        if (!canDeliver(from, to) || rng.nextDouble() < dropRate) { messagesDropped++; return false; }
        return true;
    }

    /**
     * A frozen copy of what a node knew at the START of the round.
     *
     * This is not a detail -- it is what makes a "round" mean anything. A node
     * gossips what it knew when the round began, not what it learned two
     * milliseconds ago from a neighbour in the same round. Without the freeze, one
     * fact could relay across the whole cluster inside a single tick and the
     * measured convergence would be an artefact of the simulator.
     */
    private record Snap(String id, Map<String, PeerState> view, String cfg, long cfgVersion,
                        long heartbeat, boolean spreading) {}

    private static final Map<String, PeerState> NO_MEMBERSHIP = Map.of();

    private Snap snap(Node n) {
        return new Snap(n.id, gossipMembership ? n.digest() : NO_MEMBERSHIP,
                n.configValue, n.configVersion, n.heartbeat, !rumorMongering || n.stillSpreading);
    }

    /**
     * What actually goes on the wire. With maxEntriesPerMessage set, only the
     * freshest news travels -- which is how real implementations keep a UDP packet
     * under the MTU no matter how big the cluster gets.
     */
    private Map<String, PeerState> payload(Snap s) {
        Map<String, PeerState> full = s.view();
        if (maxEntriesPerMessage <= 0 || full.size() <= maxEntriesPerMessage) {
            entriesTransferred += full.size();
            return full;
        }
        List<Map.Entry<String, PeerState>> es = new ArrayList<>(full.entrySet());
        es.sort((a, b) -> Long.compare(b.getValue().lastChangedRound, a.getValue().lastChangedRound));
        Map<String, PeerState> out = new LinkedHashMap<>();
        for (int i = 0; i < maxEntriesPerMessage; i++) out.put(es.get(i).getKey(), es.get(i).getValue());
        entriesTransferred += out.size();
        return out;
    }

    private void deliver(Snap from, Node to) {
        to.directEvidenceOnly = directEvidenceOnly;
        to.merge(payload(from), round);
        // Direct contact: whatever the merge decided, I have now heard from `from`
        // myself -- the only evidence SWIM-style detection accepts.
        PeerState senderEntry = to.view.get(from.id());
        if (senderEntry != null && senderEntry.status != Status.DEAD) {
            senderEntry.heartbeat = Math.max(senderEntry.heartbeat, from.heartbeat());
            senderEntry.lastChangedRound = round;
        }
        // The shared fact rides along on the same message -- "piggybacking".
        if (from.cfgVersion() > 0 && from.spreading()) {
            configMessagesSent++;                    // this message actually carried the news
            to.mergeConfig(from.cfg(), from.cfgVersion());
        }
    }

    // ---------------------------------------------------------------- a round

    void runRound() {
        round++;
        for (Node n : nodes) { n.events.clear(); n.directEvidenceOnly = directEvidenceOnly; n.tick(round); }

        // Anyone who has heard the news back too often gives up on it -- decided
        // before anybody sends, so the round is not order-dependent.
        for (Node n : nodes) {
            if (!n.running) continue;
            if (rumorMongering && n.stillSpreading && n.redundantReceipts >= stopAfterRedundant) {
                n.stillSpreading = false;
                n.events.add(n.id + " stopped spreading config v" + n.configVersion
                        + " (heard it back " + n.redundantReceipts + " times)");
            }
        }
        Map<String, Snap> frozen = new LinkedHashMap<>();
        for (Node n : nodes) if (n.running) frozen.put(n.id, snap(n));

        List<Node> order = new ArrayList<>(nodes);
        Collections.shuffle(order, rng);
        for (Node n : order) {
            if (!n.running) continue;
            Snap mine = frozen.get(n.id);
            for (String targetId : pickPeers(n)) {
                Node t = byId.get(targetId);
                Snap theirs = frozen.get(targetId);
                switch (mode) {
                    case PUSH -> { if (send(n.id, targetId)) deliver(mine, t); }
                    case PULL -> {
                        if (send(n.id, targetId) && send(targetId, n.id) && theirs != null) deliver(theirs, n);
                    }
                    case PUSH_PULL -> {
                        if (send(n.id, targetId)) {
                            deliver(mine, t);
                            if (send(targetId, n.id) && theirs != null) deliver(theirs, n);
                        }
                    }
                }
            }
        }
        runDetection();
        if (verbose) for (Node n : nodes) for (String e : n.events) log.add("r" + round + "  " + e);
    }


    /** Uniformly random peer choice -- the property that makes the maths work. */
    private List<String> pickPeers(Node n) {
        if (gossipMembership) return n.randomTargets(rng, fanout);
        // No membership list is being exchanged, so peers come from the roster.
        List<String> out = new ArrayList<>(fanout);
        for (int i = 0; i < fanout * 8 && out.size() < fanout; i++) {
            Node cand = nodes.get(rng.nextInt(nodes.size()));
            if (cand == n || !cand.running || out.contains(cand.id)) continue;
            out.add(cand.id);
        }
        return out;
    }

    /**
     * Detection, with the SWIM refinement bolted on top: before I suspect you, I
     * ask a few other nodes to try you on my behalf. If any of them can reach you,
     * the problem was the path between us, not you.
     */
    private void runDetection() {
        for (Node n : nodes) {
            if (!n.running) continue;
            if (indirectProbes > 0) {
                for (Map.Entry<String, PeerState> e : n.view.entrySet()) {
                    if (e.getKey().equals(n.id)) continue;
                    PeerState p = e.getValue();
                    if (p.status != Status.ALIVE || round - p.lastChangedRound <= suspectAfter) continue;
                    if (indirectlyReachable(n, e.getKey())) {
                        Node target = byId.get(e.getKey());
                        p.heartbeat = Math.max(p.heartbeat, target.heartbeat);
                        p.lastChangedRound = round;                    // evidence of life, second-hand
                        n.events.add(n.id + " could not reach " + e.getKey()
                                + " directly, but a helper could -- not suspecting it");
                    }
                }
            }
            n.detect(round, suspectAfter, deadAfter);
        }
    }

    /** Ask up to `indirectProbes` random peers to probe `target` on n's behalf. */
    private boolean indirectlyReachable(Node n, String target) {
        List<String> helpers = new ArrayList<>(n.gossipTargets());
        helpers.remove(target);
        Collections.shuffle(helpers, rng);
        for (int i = 0; i < Math.min(indirectProbes, helpers.size()); i++) {
            String h = helpers.get(i);
            messagesSent += 2;                                   // ask the helper, hear back
            if (canDeliver(n.id, h) && canDeliver(h, target)
                    && canDeliver(target, h) && canDeliver(h, n.id)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------ observation

    /** Seed a fact at one node. Everything after this is the fact travelling. */
    void publishConfig(String startNode, String value, long version) {
        Node n = byId.get(startNode);
        n.configValue = value;
        n.configVersion = version;
        n.stillSpreading = true;
        n.redundantReceipts = 0;
    }

    long nodesWith(long version) {
        return nodes.stream().filter(n -> n.running && n.configVersion >= version).count();
    }

    long runningCount() { return nodes.stream().filter(n -> n.running).count(); }

    /** How many running nodes hold belief `s` about `peer`. The convergence yardstick. */
    long believing(String peer, Status s) {
        return nodes.stream().filter(n -> n.running && n.believesAbout(peer) == s).count();
    }

    /**
     * The disagreement measure the consistent-hashing lab needed: the fraction of
     * running nodes whose membership list differs from the majority view.
     */
    double disagreementFraction() {
        Map<String, Map<String, Status>> views = new LinkedHashMap<>();
        for (Node n : nodes) {
            if (!n.running) continue;
            Map<String, Status> v = new LinkedHashMap<>();
            for (Map.Entry<String, PeerState> e : n.view.entrySet()) v.put(e.getKey(), e.getValue().status);
            views.put(n.id, v);
        }
        if (views.isEmpty()) return 0;
        Map<Map<String, Status>, Integer> counts = new HashMap<>();
        for (Map<String, Status> v : views.values()) counts.merge(v, 1, Integer::sum);
        int majority = counts.values().stream().max(Integer::compare).orElse(0);
        return 1.0 - (double) majority / views.size();
    }

    /** Of every belief every living node holds about every other, what share says DEAD? */
    double deadBeliefFraction() {
        long dead = 0, total = 0;
        for (Node n : nodes) {
            if (!n.running) continue;
            for (Map.Entry<String, PeerState> e : n.view.entrySet()) {
                if (e.getKey().equals(n.id)) continue;
                total++;
                if (e.getValue().status == Status.DEAD) dead++;
            }
        }
        return total == 0 ? 0 : (double) dead / total;
    }

    void kill(String id) { byId.get(id).running = false; }
    void revive(String id) { byId.get(id).running = true; }
    void partition(List<String> sideB) { for (String id : sideB) partitionOf.put(id, 1); }
    void heal() { partitionOf.clear(); blockedOneWay.clear(); }
    void blockOneWay(String from, String to) { blockedOneWay.add(from + ">" + to); }
}
