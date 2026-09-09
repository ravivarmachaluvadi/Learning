/**
 * A globally unique name for one write, minted with no coordination:
 * which replica accepted it, and how many writes that replica had accepted
 * before. You met this exact record in the crdts lab.
 */
public record Dot(int replica, long seq) implements Comparable<Dot> {
    @Override public int compareTo(Dot o) {
        int c = Long.compare(seq, o.seq);
        return c != 0 ? c : Integer.compare(replica, o.replica);
    }
    @Override public String toString() { return "R" + replica + "#" + seq; }
}
