import java.util.List;

/**
 * The four messages Raft actually sends. Every message carries the sender's
 * term, because term comparison is the single rule that orders all of Raft:
 * a bigger term always wins and always demotes the receiver to follower.
 */
interface Msg {
    int term();

    /** Candidate -> everyone: "vote for me in this term". */
    record RequestVote(int term, int candidateId, int lastLogIndex, int lastLogTerm) implements Msg {}

    /** Voter -> candidate. */
    record VoteReply(int term, boolean granted, int voterId) implements Msg {}

    /**
     * Leader -> follower. Also the heartbeat: with an empty `entries` list this
     * is exactly the message that stops followers from starting elections.
     */
    record AppendEntries(int term, int leaderId, int prevLogIndex, int prevLogTerm,
                         List<Entry> entries, int leaderCommit) implements Msg {
        public AppendEntries { entries = List.copyOf(entries); }
    }

    /** Follower -> leader. `matchIndex` = highest index the follower now agrees on. */
    record AppendReply(int term, boolean success, int matchIndex, int followerId) implements Msg {}
}
