import java.util.*;

/**
 * One member of the cluster. It knows three things and nothing else:
 *
 *   1. its own name, heartbeat and incarnation number
 *   2. a membership list -- what it believes about every node it has heard of
 *   3. one shared fact (a config value) with a version number, standing in for
 *      "whatever the cluster needs everybody to know"
 *
 * There is NO coordinator, NO leader and NO node with a complete picture. Every
 * method here is something a node can do using only what it holds locally.
 */
final class Node {

    /**
     * Exercise 6 only: routes every merge through the planted-bug version.
     * A global switch is not how you would write this for real -- it exists so
     * `java src/Tests.java broken` can swap the rule under the whole cluster.
     */
    static boolean brokenMerge = false;

    final String id;
    boolean running = true;              // simulation only: is the process up?

    long heartbeat = 0;                  // I bump this every round I am alive
    long incarnation = 1;                // I bump this only to refute my own death

    /** What I believe about everyone, including myself. */
    final Map<String, PeerState> view = new LinkedHashMap<>();

    /** The shared fact being spread. Merged by "highest version wins". */
    String configValue = null;
    long configVersion = 0;

    /**
     * Rumour-mongering bookkeeping: how many times I have been told the current
     * config version by someone who clearly already had it. Used by the stop rule
     * in §10 v4 -- and the experiment that shows why the stop rule alone is unsafe.
     */
    int redundantReceipts = 0;
    boolean stillSpreading = false;

    /**
     * Which evidence of life this node accepts (the two real designs, §10 v5):
     *
     *   false -- Cassandra-style. A rising heartbeat counts no matter who carried
     *            it to me. Cheap, and second-hand news keeps a peer alive in my
     *            list even when I personally cannot reach it.
     *   true  -- SWIM-style. Only a message I received DIRECTLY from a peer proves
     *            that peer is reachable BY ME. Catches broken links that
     *            heartbeat-propagation hides -- and needs indirect probes so it
     *            does not fire on every one of them.
     */
    boolean directEvidenceOnly = false;

    /** Events this node produced this round, for the narration. */
    final List<String> events = new ArrayList<>();

    Node(String id) {
        this.id = id;
        view.put(id, new PeerState(1, 0, Status.ALIVE, 0));
    }

    // ------------------------------------------------------------------ local

    /** Start of a round: prove I am alive by moving my own number forward. */
    void tick(long round) {
        if (!running) return;
        heartbeat++;
        PeerState me = view.get(id);
        me.heartbeat = heartbeat;
        me.incarnation = incarnation;
        me.status = Status.ALIVE;
        me.lastChangedRound = round;
    }

    /** The payload I would send right now: my whole membership list. */
    Map<String, PeerState> digest() {
        Map<String, PeerState> out = new LinkedHashMap<>();
        for (Map.Entry<String, PeerState> e : view.entrySet()) out.put(e.getKey(), e.getValue().copy());
        return out;
    }

    /**
     * Take in someone else's view and fold it into mine.
     *
     * Note what is NOT here: no ordering, no acknowledgement, no idea whether this
     * sender has spoken to me before. The merge rule is doing all the work, and it
     * works because it is commutative and monotonic (see PeerState.mergeFrom).
     *
     * @return the number of entries that told me something new
     */
    int merge(Map<String, PeerState> incoming, long round) {
        int changed = 0;
        // Under SWIM-style detection, third-party news may update WHAT I believe,
        // but it may not restart my stopwatch on a peer -- only hearing from that
        // peer myself does that. Cluster.deliver() is what restarts it.
        Map<String, Long> stopwatches = new HashMap<>();
        if (directEvidenceOnly)
            for (Map.Entry<String, PeerState> e : view.entrySet())
                stopwatches.put(e.getKey(), e.getValue().lastChangedRound);
        for (Map.Entry<String, PeerState> e : incoming.entrySet()) {
            String peer = e.getKey();
            PeerState in = e.getValue();

            if (peer.equals(id)) {
                // SOMEBODY IS TALKING ABOUT ME. If they think I am suspect or dead
                // at my current incarnation, I am the only node in the universe that
                // can prove otherwise -- so I raise my incarnation and say ALIVE.
                if (in.status != Status.ALIVE && in.incarnation >= incarnation) {
                    incarnation = in.incarnation + 1;
                    PeerState me = view.get(id);
                    me.incarnation = incarnation;
                    me.status = Status.ALIVE;
                    me.heartbeat = ++heartbeat;
                    me.lastChangedRound = round;
                    events.add(id + " was reported " + in.status + " and REFUTED it with incarnation "
                            + incarnation);
                    changed++;
                }
                continue;
            }
            PeerState mine = view.get(peer);
            if (mine == null) {
                view.put(peer, in.copy());
                events.add(id + " learned that " + peer + " exists");
                changed++;
            } else if (brokenMerge ? BrokenMerge.mergeFrom(mine, in, round)
                                   : mine.mergeFrom(in, round)) {
                changed++;
            }
        }
        if (directEvidenceOnly)
            for (Map.Entry<String, Long> e : stopwatches.entrySet()) {
                PeerState p = view.get(e.getKey());
                if (p != null && !e.getKey().equals(id)) p.lastChangedRound = e.getValue();
            }
        return changed;
    }

    /** Merge a shared fact. Same shape as membership: highest version wins. */
    boolean mergeConfig(String value, long version) {
        if (version > configVersion) {
            configValue = value;
            configVersion = version;
            redundantReceipts = 0;
            stillSpreading = true;
            events.add(id + " learned config v" + version + " = " + value);
            return true;
        }
        if (version == configVersion && version > 0) redundantReceipts++;   // "I already knew that"
        return false;
    }

    // -------------------------------------------------------------- detection

    /**
     * Failure detection, done with nothing but local arithmetic: if a peer's
     * heartbeat has not moved for `suspectAfter` rounds, I suspect it; after
     * `deadAfter`, I declare it dead.
     *
     * The thing to notice is what this CANNOT distinguish: a crashed node, a
     * paused node, and a node whose messages to me are being dropped all look
     * exactly the same from here. That is not a flaw in the code -- it is the
     * fundamental limit, and everything in §10 v5-v6 exists because of it.
     */
    void detect(long round, int suspectAfter, int deadAfter) {
        if (!running) return;
        for (Map.Entry<String, PeerState> e : view.entrySet()) {
            if (e.getKey().equals(id)) continue;
            PeerState p = e.getValue();
            long silence = round - p.lastChangedRound;
            if (p.status == Status.ALIVE && silence > suspectAfter) {
                p.status = Status.SUSPECT;
                p.lastChangedRound = round;
                events.add(id + " has not heard from " + e.getKey() + " for " + silence
                        + " rounds -> SUSPECT");
            } else if (p.status == Status.SUSPECT && silence > deadAfter) {
                p.status = Status.DEAD;
                p.lastChangedRound = round;
                events.add(id + " declares " + e.getKey() + " DEAD");
            }
        }
    }

    /** Cached peer ids, so choosing a random partner does not cost O(cluster). */
    private String[] peerArray;
    private int peerArrayViewSize = -1;

    /**
     * Pick up to k random partners. Uniformly random choice is not a stylistic
     * preference -- it is the assumption behind every convergence number in this
     * lab. Gossip to your neighbours instead and the spread becomes a slow walk
     * across the topology rather than a doubling.
     */
    List<String> randomTargets(Random rng, int k) {
        if (peerArrayViewSize != view.size()) {
            List<String> ids = new ArrayList<>(view.keySet());
            ids.remove(id);
            peerArray = ids.toArray(new String[0]);
            peerArrayViewSize = view.size();
        }
        if (peerArray.length == 0) return List.of();
        List<String> out = new ArrayList<>(k);
        int attempts = 0;
        while (out.size() < k && attempts < k * 8) {
            attempts++;
            String cand = peerArray[rng.nextInt(peerArray.length)];
            PeerState st = view.get(cand);
            if (st == null || st.status == Status.DEAD || out.contains(cand)) continue;
            out.add(cand);
        }
        return out;
    }

    /** Peers I would consider gossiping to: known, not me, not written off. */
    List<String> gossipTargets() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, PeerState> e : view.entrySet())
            if (!e.getKey().equals(id) && e.getValue().status != Status.DEAD) out.add(e.getKey());
        return out;
    }

    Status believesAbout(String peer) {
        PeerState p = view.get(peer);
        return p == null ? null : p.status;
    }

    @Override public String toString() { return id + (running ? "" : "(down)"); }
}
