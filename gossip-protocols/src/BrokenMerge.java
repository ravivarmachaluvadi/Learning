/**
 * FOR EXERCISE 6 (debug). One planted bug, in the one method that matters.
 *
 * `java src/Tests.java broken` routes every merge through here instead of through
 * PeerState.mergeFrom. Look at which tests fail and work out what is wrong before
 * reading SOLUTIONS.md -- the bug is a single wrong comparison, and it is the kind
 * that reads perfectly sensibly out loud.
 */
final class BrokenMerge {

    static boolean mergeFrom(PeerState mine, PeerState in, long round) {
        // "Whoever has the newer heartbeat has the newer information."
        if (in.heartbeat > mine.heartbeat) {
            mine.heartbeat = in.heartbeat;
            mine.status = in.status;
            mine.incarnation = Math.max(mine.incarnation, in.incarnation);
            mine.lastChangedRound = round;
            return true;
        }
        if (in.incarnation > mine.incarnation) {
            mine.incarnation = in.incarnation;
            mine.status = in.status;
            mine.lastChangedRound = round;
            return true;
        }
        return false;
    }

    private BrokenMerge() {}
}
