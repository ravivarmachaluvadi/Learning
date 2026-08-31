/**
 * One row of a node's membership list: everything node A believes about node B.
 *
 * Three numbers, and each answers a different question:
 *
 *   heartbeat    "how fresh is my evidence that B is running?"  B bumps it every
 *                round; it only ever goes up, and it is compared by MAX, never by
 *                wall-clock time -- so no clock anywhere needs to agree.
 *
 *   incarnation  "which version of B is this belief about?"  Only B may raise it,
 *                and B raises it to REFUTE a rumour of its own death. This is the
 *                one number a node may say about itself that others must accept.
 *
 *   lastChanged  local-only bookkeeping: the round in which this node last saw
 *                B's heartbeat go up. Never sent anywhere -- it is the observer's
 *                stopwatch, not a fact about B.
 */
final class PeerState {
    long incarnation;
    long heartbeat;
    Status status;
    long lastChangedRound;

    PeerState(long incarnation, long heartbeat, Status status, long round) {
        this.incarnation = incarnation;
        this.heartbeat = heartbeat;
        this.status = status;
        this.lastChangedRound = round;
    }

    PeerState copy() { return new PeerState(incarnation, heartbeat, status, lastChangedRound); }

    /**
     * THE MERGE RULE -- the single most important method in this lab.
     *
     * It must be:
     *   - deterministic  : same two inputs, same result, on every node
     *   - commutative    : merge(a,b) == merge(b,a), because gossip has no order
     *   - idempotent     : merging the same news twice changes nothing
     *   - monotonic      : the result is never "older" than either input
     *
     * Those four properties are why nodes that receive the same facts in totally
     * different orders, some twice, some never, still end up identical. Break any
     * one of them and the cluster can oscillate forever.
     *
     * @return true if this state actually changed (i.e. the incoming news was new)
     */
    boolean mergeFrom(PeerState in, long round) {
        // The whole rule in one sentence: keep whichever of the two states is
        // LARGER when you compare (incarnation, then status, then heartbeat) in
        // that order -- exactly how you compare version numbers like 2.7.1.
        //
        // Comparing in that fixed order is what buys the four properties above.
        // Mixing components (say, "take the higher heartbeat but the worse status")
        // looks harmless and destroys commutativity: two nodes given the same news
        // in different orders end up in different states, for ever.
        int cmp = Long.compare(in.incarnation, this.incarnation);
        if (cmp == 0) cmp = Integer.compare(in.status.ordinal(), this.status.ordinal());
        if (cmp == 0) cmp = Long.compare(in.heartbeat, this.heartbeat);
        if (cmp <= 0) return false;                      // mine is already >= theirs

        this.incarnation = in.incarnation;
        this.status = in.status;
        this.heartbeat = in.heartbeat;
        this.lastChangedRound = round;                   // the observer's stopwatch restarts
        return true;
    }

    @Override public String toString() {
        return status + "(i" + incarnation + ",hb" + heartbeat + ")";
    }
}
