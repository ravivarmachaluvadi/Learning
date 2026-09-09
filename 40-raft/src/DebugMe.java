/**
 * EXERCISE (ladder rung 6). This file contains THREE subtly wrong rules --
 * one per method. Each one compiles, each one looks reasonable, and each one
 * breaks something different.
 *
 *   java src/DebugMeTest.java
 *
 * For each failure, answer three questions before you fix it:
 *   1. which line is wrong?
 *   2. does it cost SAFETY (something wrong happens) or LIVENESS (nothing happens)?
 *   3. which named Raft property does it break?
 *
 * Answers in SOLUTIONS.md.
 */
class DebugMe {

    static boolean candidateLogIsUpToDate(int candLastIndex, int candLastTerm,
                                          int myLastIndex, int myLastTerm) {
        if (candLastTerm != myLastTerm) return candLastTerm > myLastTerm;
        return candLastIndex > myLastIndex;
    }

    static boolean shouldGrantVote(int myTerm, int myVotedFor,
                                   int candTerm, int candId, boolean candLogUpToDate) {
        if (candTerm < myTerm) return false;
        if (myVotedFor != -1) return false;
        return candLogUpToDate;
    }

    static int highestCommittableIndex(int[] matchIndex, int[] logTerms,
                                       int currentTerm, int commitIndex) {
        int best = commitIndex;
        for (int n = logTerms.length - 1; n > commitIndex; n--) {
            if (logTerms[n] != currentTerm) continue;
            int count = 0;
            for (int m : matchIndex) if (m >= n) count++;
            if (count >= matchIndex.length / 2) { best = n; break; }
        }
        return best;
    }
}
