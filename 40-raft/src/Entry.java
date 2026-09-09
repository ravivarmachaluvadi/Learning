/**
 * One command in the replicated log.
 *
 * `term` is the term of the leader that FIRST created this entry. It never
 * changes as the entry is copied around the cluster, which is what makes the
 * Log Matching property checkable with a single comparison.
 */
record Entry(int term, String cmd) {
    @Override public String toString() { return cmd + "@t" + term; }
}
