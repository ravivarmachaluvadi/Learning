/**
 * EXERCISE (ladder rung 5). Three methods, three of Raft's rules. Replace each
 * TODO with the real rule and make ImplExerciseTest pass:
 *
 *     java src/ImplExerciseTest.java
 *
 * These are the exact rules RaftNode uses; the working versions are in
 * RaftNode.candidateUpToDate, RaftNode.onRequestVote and RaftNode.advanceCommit
 * if you get stuck -- but try first, the tests tell you which rule you missed.
 */
class ImplExercise {

    /**
     * 1. THE UP-TO-DATE TEST (paper section 5.4.1).
     * A voter compares the candidate's last log entry with its own. Return true
     * if the CANDIDATE's log is at least as up to date as the voter's.
     *
     * The comparison is on the last entry only: later TERM wins; if the terms are
     * equal, the longer log wins.
     */
    static boolean candidateLogIsUpToDate(int candLastIndex, int candLastTerm,
                                          int myLastIndex, int myLastTerm) {
        return true;   // TODO: this currently says yes to everyone -- fix it
    }

    /**
     * 2. THE VOTING RULE.
     *  - never vote in a term older than your own
     *  - at most ONE vote per term (voting again for the same candidate is fine,
     *    because the request may simply be a retry of one you already answered)
     *  - never vote for a candidate whose log is behind yours
     *
     * `myVotedFor` is -1 when this server has not voted in `myTerm` yet.
     * Assume `myTerm == candTerm` (the caller already stepped down if the
     * candidate's term was higher).
     */
    static boolean shouldGrantVote(int myTerm, int myVotedFor,
                                   int candTerm, int candId, boolean candLogUpToDate) {
        return true;   // TODO
    }

    /**
     * 3. THE COMMIT RULE (paper section 5.3 + 5.4.2).
     * Return the highest index the leader may mark committed, or `commitIndex`
     * unchanged if nothing new qualifies.
     *
     *  - an index qualifies when a MAJORITY of servers have it (matchIndex >= N)
     *  - and only if the entry at that index was created in the LEADER'S OWN term
     *  - never go backwards
     *
     * matchIndex[i] = highest index server i is known to have.
     * logTerms[i]   = term of the leader's log entry at index i (logTerms[0] = 0).
     */
    static int highestCommittableIndex(int[] matchIndex, int[] logTerms,
                                       int currentTerm, int commitIndex) {
        return commitIndex;   // TODO
    }
}
