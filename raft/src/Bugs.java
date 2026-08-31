/**
 * Deliberately breakable rules. Each flag switches OFF one Raft rule so you can
 * watch the guarantee it was protecting die. Used by Experiments and by the
 * debugging exercises.
 */
class Bugs {
    /** OFF the rule "a server votes at most once per term". Breaks Election Safety. */
    boolean voteTwice = false;

    /** OFF the rule "only vote for a candidate whose log is at least as up to date as yours". Breaks Leader Completeness. */
    boolean noUpToDateCheck = false;

    /** OFF the prevLogIndex/prevLogTerm consistency check. Breaks Log Matching. */
    boolean noLogMatchCheck = false;

    /** OFF the rule "a leader only commits entries from its OWN term". This is the Figure 8 bug. */
    boolean commitAnyTerm = false;

    /** OFF the randomisation of election timeouts. Breaks liveness, not safety. */
    boolean fixedTimeout = false;

    boolean any() { return voteTwice || noUpToDateCheck || noLogMatchCheck || commitAnyTerm || fixedTimeout; }
}
